#!/bin/bash
# Build derealization: ./build.sh [gradle task, default assembleDebug]
# Toolchain (JDK 21, Android SDK 37, Gradle 9.8.1) is expected in $ANDROID_DEV (default /mnt/build/android-dev);
# set JAVA_HOME / ANDROID_HOME yourself to use another one.
D=${ANDROID_DEV:-/mnt/build/android-dev}
[ -d "$D" ] && export JAVA_HOME=${JAVA_HOME:-$D/jdk21} ANDROID_HOME=${ANDROID_HOME:-$D/sdk} GRADLE_USER_HOME=${GRADLE_USER_HOME:-$D/gradle-home}
# optional local SOCKS proxy for Gradle downloads
if ss -ltn 2>/dev/null | grep -q '127.0.0.1:10808'; then
  export JAVA_TOOL_OPTIONS="-DsocksProxyHost=127.0.0.1 -DsocksProxyPort=10808"
fi
cd "$(dirname "$0")" && exec ./gradlew --console=plain "${@:-assembleDebug}"
