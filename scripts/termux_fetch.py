#!/usr/bin/env python3
"""AndroVim bundler: fetches a prebuilt Neovim (+ full dependency closure)
from the Termux package repository, normalizes the ELF files with patchelf so
they can be packaged inside an APK's jniLibs/, and stages the Neovim runtime
files as Android assets.

Usage:
    termux_fetch.py --arch arm64-v8a --jni app/src/main/jniLibs/arm64-v8a \
        --assets app/src/main/assets [--write-assets]
"""

import argparse
import hashlib
import io
import os
import shutil
import subprocess
import sys
import tarfile
import urllib.request

REPO = os.environ.get(
    "ANDROVIM_TERMUX_REPO", "https://packages.termux.dev/apt/termux-main"
).rstrip("/")

ARCH_MAP = {
    "arm64-v8a": "aarch64",
    "armeabi-v7a": "arm",
    "x86_64": "x86_64",
    "x86": "i686",
}

DEFAULT_PARSERS = (
    "c,lua,vim,vimdoc,query,bash,json,yaml,toml,markdown,markdown_inline,"
    "python,rust,go,javascript,typescript,tsx,html,css"
)

USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AndroVim-bundler/1.0"


def die(msg):
    print(f"error: {msg}", file=sys.stderr)
    sys.exit(1)


def fetch(url, timeout=120):
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read()


def parse_packages(index_text):
    packages = {}
    stanza = {}
    for line in index_text.splitlines() + [""]:
        if line.strip() == "":
            if stanza.get("Package"):
                packages[stanza["Package"]] = stanza
            stanza = {}
            continue
        if ":" in line:
            key, _, value = line.partition(":")
            stanza[key.strip()] = value.strip()
    return packages


def dep_names(depends_field):
    """Yield package names from a Depends field, dropping version constraints."""
    if not depends_field:
        return
    for group in depends_field.split(","):
        group = group.strip()
        if not group:
            continue
        # Alternatives ("a | b"): pick the first alternative that exists later;
        # here we just take the first name and let resolution report failures.
        first = group.split("|")[0].strip()
        name = first.split("(")[0].strip()
        if name:
            yield name


def resolve_closure(packages, roots):
    resolved, order, queue = set(), [], list(roots)
    while queue:
        name = queue.pop(0)
        if name in resolved:
            continue
        pkg = packages.get(name)
        if pkg is None:
            print(f"warning: package '{name}' not found in index", file=sys.stderr)
            continue
        resolved.add(name)
        order.append(pkg)
        queue.extend(n for n in dep_names(pkg.get("Depends")) if n not in resolved)
    return order


class ArReader:
    """Minimal 'ar' archive reader good enough for .deb data members."""

    def __init__(self, blob):
        if blob[:8] != b"!<arch>\n":
            raise ValueError("not an ar archive")
        self.blob, self.offset = blob, 8

    def next_member(self):
        b = self.blob
        if self.offset >= len(b):
            return None
        header = b[self.offset : self.offset + 60]
        if len(header) < 60:
            return None
        name = header[0:16].decode("ascii", "replace").strip()
        size = int(header[48:58].decode("ascii").strip())
        self.offset += 60
        data = b[self.offset : self.offset + size]
        self.offset += size + (size % 2)
        return name, data


def extract_deb(deb_path, dest):
    with open(deb_path, "rb") as fh:
        ar = ArReader(fh.read())
    data_member = None
    while True:
        entry = ar.next_member()
        if entry is None:
            break
        name, payload = entry
        if name.startswith("data.tar"):
            data_member = (name, payload)
    if data_member is None:
        raise ValueError("no data.tar.* member found")
    name, payload = data_member
    if name.endswith(".zst") or name.endswith(".zst ") or b"\x28\xb5\x2f\xfd" == payload[:4]:
        proc = subprocess.run(["tar", "--zstd", "-xf", "-", "-C", dest], input=payload)
        if proc.returncode != 0:
            raise RuntimeError("tar --zstd failed")
        return
    with tarfile.open(fileobj=io.BytesIO(payload)) as tf:
        members = tf.getmembers()
        # Pass 1: regular files. Pass 2: links (so targets exist).
        for m in members:
            if m.isfile():
                safe_extract_file(tf, m, dest)
        for m in members:
            if m.issym():
                target_path = os.path.join(dest, m.name)
                os.makedirs(os.path.dirname(target_path), exist_ok=True)
                if os.path.lexists(target_path):
                    os.remove(target_path)
                os.symlink(m.linkname, target_path)
            elif m.islnk():
                src = os.path.join(dest, m.linkname)
                dst = os.path.join(dest, m.name)
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                if os.path.lexists(dst):
                    os.remove(dst)
                shutil.copy2(src, dst)


def safe_extract_file(tf, member, dest):
    target = os.path.join(dest, member.name)
    if os.path.sep in member.name:
        real_dest = os.path.realpath(dest)
        real_target = os.path.realpath(target)
        if not real_target.startswith(real_dest + os.path.sep):
            print(f"warning: skipping unsafe path {member.name}", file=sys.stderr)
            return
    os.makedirs(os.path.dirname(target), exist_ok=True)
    if os.path.lexists(target):
        os.remove(target)
    extracted = tf.extractfile(member)
    if extracted is None:
        return
    with extracted as src, open(target, "wb") as out:
        shutil.copyfileobj(src, out)


def is_elf(path):
    try:
        with open(path, "rb") as fh:
            return fh.read(4) == b"\x7fELF"
    except OSError:
        return False


def canonical_name(basename):
    stem = basename.split(".so")[0]
    if not stem.startswith("lib"):
        stem = "lib" + stem
    # Keep APK/zip-friendly characters only
    stem = stem.replace("+", "plus")
    return stem + ".so"


def parser_lang(fn):
    """Return the treesitter language id for a parser lib, or None."""
    for prefix in ("libtree-sitter-", "libtree_sitter_"):
        if fn.startswith(prefix):
            rest = fn[len(prefix):].split(".so")[0]
            return rest.replace("-", "_")
    return None


def patchelf(*args, optional=False):
    proc = subprocess.run(["patchelf", *args], capture_output=True, text=True)
    if proc.returncode != 0 and not optional:
        raise RuntimeError(f"patchelf {' '.join(args)} failed:\n{proc.stderr}")
    return proc


def copy_tree_resolving(src, dst):
    """Copy a tree into an Android assets dir. APK assets cannot represent
    symlinks, so linked directories are followed and dangling links dropped."""
    skipped = 0
    for dirpath, _dirnames, filenames in os.walk(src, followlinks=True):
        rel = os.path.relpath(dirpath, src)
        out_dir = dst if rel == "." else os.path.join(dst, rel)
        os.makedirs(out_dir, exist_ok=True)
        for fn in filenames:
            s = os.path.join(dirpath, fn)
            if os.path.islink(s) and not os.path.exists(s):
                skipped += 1
                continue
            shutil.copy2(s, os.path.join(out_dir, fn))
    if skipped:
        print(f"note: dropped {skipped} dangling symlinks while copying {src}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--arch", required=True, choices=sorted(ARCH_MAP))
    ap.add_argument("--jni", required=True, help="output dir for this ABI's libs")
    ap.add_argument("--assets", help="Android assets root dir")
    ap.add_argument("--write-assets", action="store_true")
    ap.add_argument("--roots", default="neovim")
    ap.add_argument("--cache", default=os.path.expanduser("~/.cache/androvim/debs"))
    args = ap.parse_args()

    arch = ARCH_MAP[args.arch]
    os.makedirs(args.jni, exist_ok=True)
    os.makedirs(args.cache, exist_ok=True)
    stage = os.path.join(args.cache, f"stage-{args.arch}")
    shutil.rmtree(stage, ignore_errors=True)
    os.makedirs(stage)

    index_url = f"{REPO}/dists/stable/main/binary-{arch}/Packages"
    print(f"[{args.arch}] fetching package index: {index_url}")
    packages = parse_packages(fetch(index_url).decode("utf-8"))

    roots = [r.strip() for r in args.roots.split(",") if r.strip()]
    closure = resolve_closure(packages, roots)
    print(f"[{args.arch}] resolving {len(closure)} packages: "
          + ", ".join(p["Package"] for p in closure))

    for pkg in closure:
        url = f"{REPO}/{pkg['Filename']}"
        sha = pkg.get("SHA256")
        local = os.path.join(args.cache, os.path.basename(pkg["Filename"]))
        if not (os.path.exists(local) and os.path.getsize(local) == int(pkg["Size"])):
            print(f"[{args.arch}] downloading {pkg['Package']} "
                  f"{pkg['Version']} ({int(pkg['Size']) // 1024} KiB)")
            blob = fetch(url)
            if sha and hashlib.sha256(blob).hexdigest() != sha:
                die(f"sha256 mismatch for {pkg['Filename']}")
            with open(local, "wb") as fh:
                fh.write(blob)
        extract_deb(local, stage)

    # Termux packages unpack into data/data/com.termux/files/usr — hoist the
    # prefix up so the rest of the script can assume a plain ./usr layout.
    termux_prefix = os.path.join(stage, "data/data/com.termux/files/usr")
    if not os.path.isdir(os.path.join(stage, "usr")) and os.path.isdir(termux_prefix):
        shutil.move(termux_prefix, os.path.join(stage, "usr"))

    parsers_allow = set(
        p for p in os.environ.get("ANDROVIM_PARSERS", DEFAULT_PARSERS).split(",") if p
    )

    # Map every shared library (and every symlink alias) to its canonical,
    # APK-friendly name: lib*.so
    lib_root = os.path.join(stage, "usr/lib")
    real_files, aliases = {}, {}
    for dirpath, _dirnames, filenames in os.walk(lib_root):
        for fn in filenames:
            full = os.path.join(dirpath, fn)
            if ".so" not in fn:
                continue
            if os.path.islink(full):
                resolved = os.path.normpath(
                    os.path.join(dirpath, os.readlink(full))
                )
                if os.path.exists(resolved):
                    aliases.setdefault(fn, canonical_name(os.path.basename(resolved)))
            elif is_elf(full):
                real_files[fn] = full

    mapping = dict(aliases)
    for fn in real_files:
        mapping.setdefault(fn, canonical_name(fn))

    def keep(fn):
        lang = parser_lang(fn)
        if lang is None:
            return True  # regular shared library (incl. libtree-sitter core)
        return lang in parsers_allow

    total = 0
    seen = {}
    for fn, path in sorted(real_files.items()):
        if not keep(fn):
            continue
        canon = mapping[fn]
        if canon in seen and seen[canon] != os.path.realpath(path):
            die(f"canonical name collision: {canon} for {path} and {seen[canon]}")
        seen[canon] = os.path.realpath(path)
        needed_out = patchelf("--print-needed", path)
        for old in needed_out.stdout.split():
            new = mapping.get(old)
            if new and new != old:
                patchelf("--replace-needed", old, new, path)
        patchelf("--set-soname", canon, path)
        patchelf("--set-rpath", "$ORIGIN", path, optional=True)
        shutil.copy2(path, os.path.join(args.jni, canon))
        os.chmod(os.path.join(args.jni, canon), 0o755)
        total += 1

    for candidate in (
        os.path.join(stage, "usr/libexec/nvim/nvim"),
        os.path.join(stage, "usr/bin/nvim"),
    ):
        if os.path.exists(candidate) and is_elf(candidate):
            nvim_src = candidate
            break
    else:
        die(f"nvim binary missing in staging area")
    needed_out = patchelf("--print-needed", nvim_src)
    for old in needed_out.stdout.split():
        new = mapping.get(old)
        if new and new != old:
            patchelf("--replace-needed", old, new, nvim_src)
    patchelf("--set-rpath", "$ORIGIN", nvim_src, optional=True)
    shutil.copy2(nvim_src, os.path.join(args.jni, "libnvim.so"))
    os.chmod(os.path.join(args.jni, "libnvim.so"), 0o755)

    nvim_pkg = next(p for p in closure if p["Package"] == "neovim")

    if args.write_assets and args.assets:
        runtime_src = os.path.join(stage, "usr/share/nvim/runtime")
        if not os.path.isdir(runtime_src):
            die("runtime files missing in staging area")
        assets_runtime = os.path.join(args.assets, "runtime")
        shutil.rmtree(assets_runtime, ignore_errors=True)
        copy_tree_resolving(runtime_src, assets_runtime)
        with open(os.path.join(assets_runtime, ".androvim-version"), "w") as fh:
            fh.write(f"{nvim_pkg['Version']}\n")
        terminfo_src = os.path.join(stage, "usr/share/terminfo")
        if os.path.isdir(terminfo_src):
            shutil.rmtree(os.path.join(args.assets, "terminfo"), ignore_errors=True)
            copy_tree_resolving(terminfo_src, os.path.join(args.assets, "terminfo"))

    size_kb = sum(
        os.path.getsize(os.path.join(args.jni, f)) for f in os.listdir(args.jni)
    ) // 1024
    print(f"[{args.arch}] staged {total} libs + libnvim.so -> {args.jni} "
          f"({size_kb} KiB); nvim {nvim_pkg['Version']}")


if __name__ == "__main__":
    main()
