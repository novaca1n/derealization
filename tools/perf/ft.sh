#!/bin/bash
# Трасса FrameTimeline (Perfetto) на время сценария и разбор опозданий кадров по окнам.
# ft.sh <имя> <секунды> <команда сценария…>   → tp/<имя>.pftrace + таблица
D=$(dirname "$(readlink -f "$0")")
NAME=$1; SEC=$2; shift 2
CFG="buffers { size_kb: 32768 fill_policy: RING_BUFFER }
data_sources { config { name: \"android.surfaceflinger.frametimeline\" } }
duration_ms: $((SEC * 1000))"
adb shell "rm -f /data/misc/perfetto-traces/ft.pftrace"
echo "$CFG" | adb shell "perfetto --txt -c - -o /data/misc/perfetto-traces/ft.pftrace" >/dev/null 2>&1 &
sleep 1.2
"$@"
wait
adb pull /data/misc/perfetto-traces/ft.pftrace "$D/tp/$NAME.pftrace" >/dev/null 2>&1 || adb shell "su -c 'cat /data/misc/perfetto-traces/ft.pftrace'" > "$D/tp/$NAME.pftrace"
"$D/tp/venv/bin/python" "$D/ft.py" "$D/tp/$NAME.pftrace"
