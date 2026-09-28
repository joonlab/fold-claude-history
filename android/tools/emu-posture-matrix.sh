#!/bin/bash
# 에뮬레이터(7.6" Fold) 자세 매트릭스 — 상태를 만든 뒤 자세만 바꿔 가며 캡처한다. 에뮬레이터 전용(실기기 입력 주입 금지).
export PATH="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools:$PATH"
E=emulator-5554; OUT=$1; mkdir -p "$OUT"
a() { adb -s $E "$@"; }
rot() { a shell settings put system accelerometer_rotation 0; a shell settings put system user_rotation $1; }
hinge() { a emu sensor set hinge-angle0 $1 >/dev/null; }
shot() { sleep 3; a exec-out screencap -p > "$OUT/$1.png"; echo "$1: $(a logcat -d -s cchistory:I | grep posture= | tail -1 | sed 's/.*cchistory: //')"; }

a shell wm size reset; rot 0; hinge 180
a shell am force-stop kr.joonlab.cchistory; a logcat -c
a shell am start -n kr.joonlab.cchistory/.MainActivity >/dev/null; sleep 6
# 상태 만들기: 목록 검색 «M4»(아무 검색어나 바꿔 써도 된다) → 첫 세션 → 대화를 위로 조금
a shell input tap 414 331; sleep 1; a shell input text M4; sleep 1; a shell input keyevent 111; sleep 1
a shell input tap 414 731; sleep 4
a shell input swipe 1300 1000 1300 1900 300; sleep 1; a shell input swipe 1300 1000 1300 1900 300; sleep 2
shot 1-port
rot 1; shot 2-land
rot 0; hinge 90; shot 3-book
rot 1; shot 4-table
hinge 180; rot 0; a shell wm size 884x2208; shot 5-cover
a shell wm size reset; shot 6-port-again
rot 0
