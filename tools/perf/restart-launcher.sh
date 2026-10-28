#!/bin/bash
# перезапуск лаунчера Nothing (отдельным файлом: pkill -f совпал бы со своей командой)
adb shell su -c 'kill $(pidof com.nothing.launcher)'
