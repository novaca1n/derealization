#!/bin/bash
# A/B: один параметр ntweaker_anim меняется, сценарии open/gest/folder прогоняются. ab.sh '<json-подмена sed>' метка
D=$(dirname "$(readlink -f "$0")")
ORIG=$(adb shell settings get global ntweaker_anim | tr -d '\r')
NEW=$(echo "$ORIG" | sed "$1")
adb shell "settings put global ntweaker_anim '$NEW'"; sleep 1
a() { adb shell "$@"; }
s_open()   { for i in 1 2 3; do a input tap 905 1225; sleep 1.3; a input keyevent HOME; sleep 1.3; done; }
s_gest()   { for i in 1 2 3; do a input tap 905 1225; sleep 1.3; a input swipe 540 2385 540 1300 120; sleep 1.3; done; }
s_folder() { for i in 1 2 3; do a input tap 440 1010; sleep 1.0; a input keyevent BACK; sleep 1.0; done; }
export -f a s_open s_gest s_folder
for s in open:9 gest:9 folder:7; do n=${s%%:*}; a input keyevent HOME; sleep 1
    printf "%-7s" "$n"; "$D/ft.sh" "ab_$2_$n" "${s##*:}" s_$n 2>&1 | grep -m1 "SearchLauncher" | cut -c1-70; done
adb shell "settings put global ntweaker_anim '$ORIG'"
