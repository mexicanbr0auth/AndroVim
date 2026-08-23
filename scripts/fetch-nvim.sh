#!/usr/bin/env bash
# Stage prebuilt Neovim binaries + runtime into the Android project.
# Requires: python3, curl (via urllib), patchelf.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSETS="$ROOT/app/src/main/assets"
JNI="$ROOT/app/src/main/jniLibs"
ABIS="${ABIS:-arm64-v8a armeabi-v7a x86_64}"

command -v python3 >/dev/null || { echo "python3 is required"; exit 1; }
command -v patchelf >/dev/null || { echo "patchelf is required (apt install patchelf)"; exit 1; }

rm -rf "$JNI" "$ASSETS/runtime" "$ASSETS/terminfo" "$ASSETS/lua"
mkdir -p "$JNI" "$ASSETS"

first=1
for abi in $ABIS; do
  extra=""
  [ "$first" = 1 ] && extra="--write-assets"
  python3 "$ROOT/scripts/termux_fetch.py" \
    --arch "$abi" \
    --jni "$JNI/$abi" \
    --assets "$ASSETS" \
    $extra
  first=0
done

touch "$JNI/.gitkeep" "$ASSETS/.gitkeep"
echo
echo "Staged ABIs: $ABIS"
du -sh "$JNI" "$ASSETS/runtime" 2>/dev/null || true
