#!/bin/bash
# Claude 기록 앱 — 빌드 · (무선) 설치 · 실행
#   ./dev.sh build | install | run | log | devices
# JAVA_HOME·ANDROID_HOME 이 이미 잡혀 있으면 그대로 쓴다.
set -e
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
P="$(cd "$(dirname "$0")" && pwd)"
APP=history
PKG=kr.joonlab.cchistory
APK="$P/$APP/build/outputs/apk/debug/$APP-debug.apk"

# 같은 폰이 무선 adb 두 포트로 잡힐 수 있다 — :5555 우선, FOLD_SERIAL 로 덮어쓰기.
pick_device() {
  if [ -n "$FOLD_SERIAL" ]; then echo "$FOLD_SERIAL"; return; fi
  local all; all=$(adb devices | awk '$2=="device"{print $1}')
  echo "$all" | grep ':5555$' | head -1 | grep . || echo "$all" | head -1
}
S=""
need_device() {
  S="$(pick_device)"
  [ -n "$S" ] || { echo "연결된 기기가 없습니다 — adb 로 폰을 먼저 연결하세요"; exit 1; }
  echo "기기: $S"
}
case "${1:-run}" in
  build)   "$P/gradlew" -p "$P" ":$APP:assembleDebug" ;;
  install) need_device; adb -s "$S" install -r "$APK" ;;
  run)     need_device
           "$P/gradlew" -p "$P" ":$APP:assembleDebug"
           adb -s "$S" install -r "$APK"
           adb -s "$S" shell am start -n $PKG/.MainActivity ;;
  log)     need_device; adb -s "$S" logcat --pid="$(adb -s "$S" shell pidof $PKG)" ;;
  devices) adb devices -l ;;
  *) echo "사용: ./dev.sh [build|install|run|log|devices]" ;;
esac
