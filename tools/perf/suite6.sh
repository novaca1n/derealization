#!/bin/bash
# Набор сценариев плавности: по трассе FrameTimeline на каждую анимацию. suite.sh [метка]
# Перед запуском: телефон разблокирован, на столе. Жесты — только по пустым местам и в безопасных точках.
D=$(dirname "$(readlink -f "$0")"); T=${1:-run}
a() { adb shell "$@"; }
home() { a input keyevent HOME; sleep 1; }
s_open()   { for i in 1 2 3 4 5 6; do a input tap 905 1225; sleep 1.3; a input keyevent HOME; sleep 1.3; done; }
s_gest()   { for i in 1 2 3 4 5 6; do a input tap 905 1225; sleep 1.3; a input swipe 540 2385 540 1300 120; sleep 1.3; done; }
s_folder() { for i in 1 2 3 4 5 6; do a input tap 440 1010; sleep 1.0; a input keyevent BACK; sleep 1.0; done; }
s_shade()  { for i in 1 2 3 4 5 6; do a input swipe 300 5 300 1400 260; sleep 0.9; a input swipe 540 1400 540 200 220; sleep 0.9; done; }
s_drawer() { for i in 1 2 3 4 5 6; do a input swipe 540 1900 540 700 220; sleep 1.0; a input keyevent HOME; sleep 1.0; done; }

export -f a home s_open s_gest s_folder s_shade s_drawer
for s in open:17 gest:17 folder:13 shade:12 drawer:13; do
    n=${s%%:*}; sec=${s##*:}
    home
    echo "== $n"
    "$D/ft.sh" "${T}_$n" "$sec" s_$n 2>&1 | grep -E "ВСЕГО|SearchLauncher|NotificationShade|VolumeDialog" | head -5
done
