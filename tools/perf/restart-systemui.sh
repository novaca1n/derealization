#!/bin/bash
# перезапуск SystemUI (телефон заблокируется)
adb shell su -c 'kill $(pidof com.android.systemui)'
