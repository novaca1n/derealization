#!/bin/bash
# Puts tweakstore.apk into derealization's assets — the patcher installs it while patching.
#   ./tools/bundle-tweakstore.sh            — build ../tweakstore (release) if it's next to this repo,
#                                             otherwise download the latest release from GitHub
#   ./tools/bundle-tweakstore.sh FILE.apk   — use this APK
set -e
cd "$(dirname "$0")/.."
OUT=app/src/main/assets/tweakstore.apk
if [ -n "$1" ]; then
  cp "$1" "$OUT"
elif [ -x ../tweakstore/build.sh ]; then
  ../tweakstore/build.sh assembleRelease
  cp ../tweakstore/app/build/outputs/apk/release/app-release.apk "$OUT"
else
  curl -fL -o "$OUT" https://github.com/novaca1n/tweakstore/releases/latest/download/tweakstore.apk
fi
echo "bundled: $OUT ($(stat -c %s "$OUT") bytes)"
