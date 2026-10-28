#!/bin/bash
# короткий сценарий плавности: стол → меню → стол → шторка → стол → приложение → стол (×2)
adb shell input keyevent HOME; sleep 1
for i in 1 2; do
  adb shell input swipe 540 1900 540 700 220; sleep 1.0; adb shell input keyevent HOME; sleep 1.0
  adb shell input swipe 300 5 300 1400 260; sleep 1.0; adb shell input swipe 540 1400 540 200 220; sleep 1.0
  adb shell input tap 905 1225; sleep 1.6; adb shell input keyevent HOME; sleep 1.4
done
