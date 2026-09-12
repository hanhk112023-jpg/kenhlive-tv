#!/bin/bash
# D-pad Interaction Test: quay screenrecord ROM ATV trong khi bắn tung ding D-pad,
# log timeline (giay + phim + focus dang tai) de ghep caption video sau.
set -u
API=${1:-34}
OUT=/tmp/dpad; mkdir -p $OUT
adb root >/dev/null 2>&1 || true; sleep 1
adb shell settings put global http_proxy 10.0.2.2:7891
adb shell wm size 1920x1080
adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null 2>&1
adb logcat -c
adb shell am force-stop com.kenhlive.tv; sleep 2

T0=$(date +%s); TL=$OUT/timeline.txt; : > $TL
focus() { adb shell dumpsys window 2>/dev/null | grep -m1 -o 'com.kenhlive.tv/[^ ]*' || echo '?'; }
mark()  { echo "$(($(date +%s)-T0))s|$1|$(focus)" >> $TL; }
key()   { adb shell input keyevent $1; }
go()    { local name="$1" code="$2" wait="$3"; key "$code"; sleep "$wait"; mark "$name"; }

adb shell am start -W -n com.kenhlive.tv/.MainActivity >/dev/null 2>&1
mark "BOOT"; sleep 24

adb shell screenrecord --bit-rate 8000000 --time-limit 175 --bugreport $OUT/rec.mp4 &
RECPID=$!; sleep 2

# ===== HOME: di chuan hero -> card -> hang -> qua lai =====
go  "DOWN: hero->card"     20 3
go  "RIGHT: card2"         22 2
go  "RIGHT: card3"         22 2
go  "LEFT:  ve card1"      21 2
go  "DOWN: hang 2"         20 2
go  "UP:   hang 1"         19 2
go  "UP:   hero"           19 2
go  "DOWN: ve card"        20 2

# ===== vao PLAYER bang OK, roi BACK ra =====
go  "OK: mo phong"         23 7
go  "OK: chon phong"       23 9
mark "PLAYER mo"
go  "BACK: ra player"      4  4
# BACK co the thoat app -> kiem tra va khoi dong lai tab
FG=$(focus); case "$FG" in *kenhlive*) : ;; *) adb shell am start -n com.kenhlive.tv/.MainActivity >/dev/null 2>&1; sleep 12; key 20; sleep 2; mark "RE-OPEN+DOWN";; esac

# ===== TAB LICH TRINH =====
go  "RIGHT -> LICH TRINH"  22 6
go  "DOWN danh sach"       20 2
go  "DOWN"                 20 2
go  "DOWN"                 20 2
go  "UP"                   19 2

# ===== TAB TIM KIEM =====
go  "RIGHT -> TIM KIEM"    22 6
adb shell input text "real" >/dev/null 2>&1; sleep 9; mark "TYPE real"
go  "DOWN qua ket qua"     20 2
go  "DOWN"                 20 2
go  "BACK"                 4  3

# ===== ve HOME, LONGPRESS -> MULTIVIEW =====
go  "LEFT -> TRUC TIEP"    21 6
key 21; sleep 2; key 20; sleep 2
adb shell input keyevent --longpress 23 >/dev/null 2>&1 || adb shell input keyevent --duration-key 1200 23 >/dev/null 2>&1
sleep 22; mark "LONGPRESS -> MULTIVIEW"
go  "MV: di o2"            20 3
go  "MV: di o3"            22 3
go  "MV: ve o1"            19 3
go  "BACK khoi MV"         4  4

sleep 2
adb shell screenrecord --stop >/dev/null 2>&1; wait $RECPID 2>/dev/null || true; sleep 2
adb pull /sdcard/rec.mp4 $OUT/rec.mp4 >/dev/null 2>&1 || true
adb logcat -d -s AndroidRuntime:E | tail -20 > $OUT/crash_a$API.txt
echo "===== TIMELINE ====="; cat $TL
SZ=$(stat -c%s $OUT/rec.mp4 2>/dev/null || echo 0)
echo "KET-QUA rec=$SZ bytes"
[ "$SZ" -gt 100000 ] || { echo "::error::khong co video"; exit 1; }
