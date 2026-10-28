#!/bin/bash
# phone screenshot → downscaled jpg in $1 (default /tmp/shot.jpg); the png is not kept
OUT=${1:-/tmp/shot.jpg}
mkdir -p "$(dirname "$OUT")"
adb exec-out screencap -p | python3 -c "
import sys,io; from PIL import Image
im=Image.open(io.BytesIO(sys.stdin.buffer.read())).convert('RGB'); im.thumbnail((540,1200)); im.save('$OUT',quality=70)"
