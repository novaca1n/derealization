#!/bin/bash
# пропущенные композитором кадры (GPU) за один прогон scenario.sh
D=$(dirname "$0")
sf() { adb shell dumpsys SurfaceFlinger | grep -E "GPU missed frame count" | awk '{print $NF}' | tr -d '\r'; }
a=$(sf); "$D/scenario.sh"; b=$(sf); echo $((b-a))
