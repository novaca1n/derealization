#!/bin/bash
# A/B на 6 повторах запуска+закрытия и жеста: ab6.sh '<sed>' метка  → пропуски лаунчера
D=$(dirname "$(readlink -f "$0")")
ORIG=$(adb shell settings get global ntweaker_anim | tr -d '\r')
adb shell "settings put global ntweaker_anim '$(echo "$ORIG" | sed "$1")'"; sleep 1
a() { adb shell "$@"; }
s_open() { for i in 1 2 3 4 5 6; do a input tap 905 1225; sleep 1.3; a input keyevent HOME; sleep 1.3; done; }
s_gest() { for i in 1 2 3 4 5 6; do a input tap 905 1225; sleep 1.3; a input swipe 540 2385 540 1300 120; sleep 1.3; done; }
export -f a s_open s_gest
for s in open gest; do a input keyevent HOME; sleep 1
    printf "%-6s" $s; "$D/ft.sh" "ab6_$2_$s" 17 s_$s 2>&1 | grep -E "SearchLauncher" | awk '{d+=$1; f+=$4; l+=$9} END {printf "%d/%d (%.2f %%) задержка %.1f\n", d, f, 100*d/(f+d), l/NR}'
done
adb shell "settings put global ntweaker_anim '$ORIG'"
