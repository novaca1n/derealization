#!/bin/bash
# Подробная трасса (atrace gfx/view/hwui + планировщик + FrameTimeline) на время сценария. deep.sh <имя> <сек> <команда>
D=$(dirname "$(readlink -f "$0")"); NAME=$1; SEC=$2; shift 2
CFG="buffers { size_kb: 131072 fill_policy: RING_BUFFER }
data_sources { config { name: \"android.surfaceflinger.frametimeline\" } }
data_sources { config { name: \"linux.process_stats\" } }
data_sources { config { name: \"linux.ftrace\" ftrace_config {
  ftrace_events: \"sched/sched_switch\" ftrace_events: \"power/cpu_frequency\"
  atrace_categories: \"gfx\" atrace_categories: \"view\" atrace_categories: \"input\" atrace_categories: \"wm\"
  atrace_apps: \"com.nothing.launcher\" atrace_apps: \"com.android.systemui\" } } }
duration_ms: $((SEC * 1000))"
adb shell "rm -f /data/misc/perfetto-traces/deep.pftrace"
echo "$CFG" | adb shell "perfetto --txt -c - -o /data/misc/perfetto-traces/deep.pftrace" >/dev/null 2>&1 &
sleep 1.5
"$@"
wait
adb pull /data/misc/perfetto-traces/deep.pftrace "$D/tp/$NAME.pftrace" >/dev/null 2>&1 || adb shell "su -c 'cat /data/misc/perfetto-traces/deep.pftrace'" > "$D/tp/$NAME.pftrace"
ls -la "$D/tp/$NAME.pftrace" | awk '{print $5}'
