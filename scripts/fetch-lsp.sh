#!/usr/bin/env bash
# Embed language servers (pyright, vscode html/css/json, typescript) into the
# existing bootstrap tarball and generate $PREFIX/bin shims for them.
# Run AFTER scripts/fetch-bootstrap.sh. Requires node/npm.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS="$ROOT/app/src/main/assets"
TAR="$ASSETS/aptdist.tar.gz"
JNILIB="$ROOT/app/src/main/jniLibs/arm64-v8a/libaptdist.so"

command -v npm >/dev/null || { echo "npm not found"; exit 1; }
[ -f "$TAR" ] || { echo "run scripts/fetch-bootstrap.sh first"; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/root"
tar -xzf "$TAR" -C "$WORK/root"

LSP="$WORK/root/lib/androvim-lsp"
mkdir -p "$LSP"

install_suite() { # <suite-name> <npm-pkg>...
  local suite="$1"; shift
  echo "== npm: $suite ($*) =="
  mkdir -p "$WORK/npm/$suite" "$LSP/$suite"
  (
    cd "$WORK/npm/$suite"
    npm init -y >/dev/null 2>&1 || true
    npm install --omit=dev --no-audit --no-fund --loglevel=error "$@"
  )
  cp -r "$WORK/npm/$suite/node_modules" "$LSP/$suite/"
}

install_suite pyright pyright
install_suite vscode vscode-langservers-extracted
install_suite tsls typescript-language-server typescript

# generate one shim per bin entry of the top-level packages, pointing into the
# embedded tree via $PREFIX so it works wherever the app extracts it
python3 - "$WORK/root" <<'PY'
import json, os, sys

root = sys.argv[1]
lsp = os.path.join(root, "lib", "androvim-lsp")
bindir = os.path.join(root, "bin")
os.makedirs(bindir, exist_ok=True)

top = {
    "pyright": ["pyright"],
    "vscode": ["vscode-langservers-extracted"],
    "tsls": ["typescript-language-server", "typescript"],
}
versions = {}
for suite, pkgs in top.items():
    for p in pkgs:
        base = os.path.join(lsp, suite, "node_modules", p)
        meta = json.load(open(os.path.join(base, "package.json")))
        versions[p] = meta.get("version", "?")
        bins = meta.get("bin", {})
        if isinstance(bins, str):
            bins = {p: bins}
        for name, rel in bins.items():
            target = f"$PREFIX/lib/androvim-lsp/{suite}/node_modules/{p}/{rel}"
            shim = os.path.join(bindir, name)
            with open(shim, "w") as f:
                f.write("#!/bin/sh\nexec node \"%s\" \"$@\"\n" % target)
            os.chmod(shim, 0o755)
            print("shim:", name)

mf = os.path.join(root, "etc", "androvim-bootstrap.json")
data = {}
if os.path.exists(mf):
    data = json.load(open(mf))
data.update(versions)
json.dump(data, open(mf, "w"), sort_keys=True)
print("manifest updated:", ", ".join(f"{k}={v}" for k, v in versions.items()))
PY

tar -czf "$TAR" -C "$WORK/root" .
cp "$TAR" "$JNILIB"
date -u +"%Y%m%d%H%M%S-seeds-py-node-git+lsp" | tr ' ' '+' > "$ASSETS/aptdist.ver"

LISTING="$WORK/listing.txt"
tar -tzf "$TAR" > "$LISTING"
for want in bin/pyright-langserver bin/vscode-html-language-server \
            bin/vscode-css-language-server bin/vscode-json-language-server \
            bin/typescript-language-server; do
  grep -qE "^\.?/?$want\$" "$LISTING" || { echo "FATAL: $want missing"; exit 1; }
done

echo
echo "bootstrap + lsp staged:"
du -sh "$ASSETS/aptdist.tar.gz" "$JNILIB"
