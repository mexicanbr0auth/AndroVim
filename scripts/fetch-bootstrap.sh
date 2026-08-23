#!/usr/bin/env bash
# Stage an apt/dpkg bootstrap (termux binaries) into assets as a single tar.gz.
# Resolved dependency closure lets the app ship a working `apt` out of the box.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSETS="$ROOT/app/src/main/assets"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

REPO="${REPO:-https://packages.termux.dev/apt/termux-main}"
ARCH="${BOOTSTRAP_ARCH:-aarch64}"
SEEDS=(apt dpkg termux-keyring ca-certificates busybox python nodejs-lts git)

command -v python3 >/dev/null || { echo "python3 required"; exit 1; }
command -v zstd >/dev/null || { echo "zstd required"; exit 1; }

IDX="$STAGE/Packages"
curl -fsSL "$REPO/dists/stable/main/binary-$ARCH/Packages" -o "$IDX"

FILES=$(python3 - "$IDX" "${SEEDS[@]}" <<'PY'
import sys

index = {}
name = None
stanza = {}
def flush():
    global name, stanza
    if name:
        index[name] = stanza
    stanza = {}
for line in open(sys.argv[1], encoding="utf-8", errors="replace"):
    line = line.rstrip("\n")
    if not line.strip():
        flush(); continue
    if line[0] in " \t" and stanza:
        k = next(iter(stanza)); stanza[k] += "\n" + line.strip(); continue
    if ":" in line:
        k, v = line.split(":", 1); stanza[k.strip()] = v.strip()
        name = stanza.get("Package")
flush()

seen, queue = set(), list(sys.argv[2:])
while queue:
    p = queue.pop(0)
    if p in seen or p not in index: continue
    seen.add(p)
    deps = [d.strip().split(" ")[0].split("|")[0]
            for d in index[p].get("Depends", "").split(",") if d.strip()]
    queue.extend(deps)

for p in sorted(seen):
    print(index[p]["Filename"] + "|" + p + "|" + index[p].get("Version", "?"))
PY
)

[ -n "$FILES" ] || { echo "no packages resolved"; exit 1; }
echo "== bootstrap packages =="; echo "$FILES"

DL="$STAGE/debs"; mkdir -p "$DL" "$STAGE/root"
mkdir -p "$STAGE/root/data/data/com.termux/files/usr/etc"
MANIFEST="$STAGE/root/data/data/com.termux/files/usr/etc/androvim-bootstrap.json"

JSON="{"
first=1
while IFS='|' read -r fn pname ver; do
  [ -n "$fn" ] || continue
  base=$(basename "$fn")
  curl -fsSL --retry 3 "$REPO/$fn" -o "$DL/$base"
  # unwrap the ar archive member data.tar.*
  python3 - "$DL/$base" "$STAGE" <<'PY'
import sys
data = open(sys.argv[1], "rb").read()
assert data[:8] == b"!<arch>\n", "not a deb"
off = 8
while off < len(data):
    hdr = data[off:off+60]; off += 60
    mname = hdr[0:16].decode().strip()
    msize = int(hdr[48:58].decode().strip())
    member = data[off:off+msize]; off += msize + (msize % 2)
    if mname.startswith("data.tar"):
        open(f"{sys.argv[2]}/data.tar.bin", "wb").write(member)
        sys.exit(0)
sys.exit("data.tar not found")
PY
  tar -xf "$STAGE/data.tar.bin" -C "$STAGE/root" 2>/dev/null || \
    tar -I zstd -xf "$STAGE/data.tar.bin" -C "$STAGE/root"
  rm -f "$STAGE/data.tar.bin"
  [ $first -eq 0 ] && JSON="$JSON,"
  JSON="$JSON\"$pname\":\"$ver\""
  first=0
done < <(printf '%s\n' "$FILES")
JSON="$JSON}"
echo "$JSON" > "$MANIFEST"
echo "== embedded manifest =="; cat "$MANIFEST"

# flatten termux prefix layout: data/data/com.termux/files/usr/<rest> -> <rest>
SRC="$STAGE/root/data/data/com.termux/files/usr"
[ -d "$SRC" ] || SRC="$STAGE/root/usr"
[ -d "$SRC" ] || { echo "unexpected tar layout"; find "$STAGE/root" -maxdepth 4 | head; exit 1; }

rm -rf "$ASSETS/aptdist.tar.gz" "$ASSETS/aptdist.ver"
tar -czf "$ASSETS/aptdist.tar.gz" -C "$SRC" .
date -u +"%Y%m%d%H%M%S-seeds-${SEEDS[*]}" | tr ' ' '+' > "$ASSETS/aptdist.ver"

# sanity: the tar must contain the essential binaries
# (capture listing first: grep -q would SIGPIPE tar under set -o pipefail)
LISTING="$STAGE/listing.txt"
tar -tzf "$ASSETS/aptdist.tar.gz" > "$LISTING"
for want in 'bin/apt' 'bin/dpkg' 'bin/busybox' 'bin/python3' 'bin/node' 'bin/git'; do
  grep -qE "^\.?/?$want\$" "$LISTING" || {
    echo "FATAL: $want missing from aptdist.tar.gz"; exit 1;
  }
done

# Ship as a fake native lib: jniLibs are NEVER filtered out of the APK,
# unlike arbitrary asset extensions which aapt2 may drop.
JNILIB="$ROOT/app/src/main/jniLibs/arm64-v8a/libaptdist.so"
mkdir -p "$(dirname "$JNILIB")"
cp "$ASSETS/aptdist.tar.gz" "$JNILIB"

echo
echo "bootstrap staged:"
du -sh "$ASSETS/aptdist.tar.gz" "$JNILIB"
