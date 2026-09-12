#!/bin/bash
# D-pad Interaction Test (v6): quay screenrecord ROM ATV trong khi bắn D-pad.
# TV v6 dùng navigation RAIL trái → đổi tab bằng `am start --ei tab N` (chắc chắn hơn
# bắn phím mò), D-pad test tập trung vào grid/card/dialog/player/multiview.
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
tab()   { adb shell am start -n com.kenhlive.tv/.MainActivity --ei tab $1 >/dev/null 2>&1; sleep 6; mark "TAB $1"; }

adb shell am start -W -n com.kenhlive.tv/.MainActivity >/dev/null 2>&1
mark "BOOT"; sleep 24

adb shell rm -f /sdcard/rec.mp4
adb shell screenrecord --bit-rate 8000000 --time-limit 240 /sdcard/rec.mp4 &
RECPID=$!; sleep 2

# ===== HOME: rail -> content -> hero -> card -> hàng =====
go  "RIGHT: rail->content" 22 3
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

# ===== TAB LICH TRINH (deep-link tab 1) =====
tab 1
go  "DOWN danh sach"       20 2
go  "DOWN"                 20 2
go  "DOWN"                 20 2
go  "UP"                   19 2

# ===== TAB TIM KIEM (deep-link tab 2) =====
tab 2
go  "DOWN qua chip"        20 2
go  "RIGHT chip 2"         22 2
adb shell input text "real" >/dev/null 2>&1; sleep 9; mark "TYPE real"
go  "DOWN qua ket qua"     20 2
go  "DOWN"                 20 2
go  "BACK"                 4  3

# ===== TAB CAI DAT (deep-link tab 3) =====
tab 3
go  "DOWN row 1"           20 2
go  "DOWN row 2"           20 2
go  "OK: mo dialog"        23 3
go  "DOWN trong dialog"    20 2
go  "BACK dong dialog"     4  2

# ===== MULTIVIEW =====
adb shell am start -n com.kenhlive.tv/.MainActivity --es open mv --ei mv_layout 4 >/dev/null 2>&1
sleep 26; mark "MULTIVIEW 4o"
go  "MV: di o2"            20 3
go  "MV: di o3"            22 3
go  "MV: ve o1"            19 3
go  "BACK khoi MV"         4  4

# ===== UX: BACK o top-level -> dialog xac nhan thoat =====
adb shell am start -n com.kenhlive.tv/.MainActivity >/dev/null 2>&1; sleep 8
adb shell input keyevent 4; sleep 2; mark "BACK top-level -> DIALOG?"
adb exec-out screencap -p > $OUT/back_dialog_a$API.png
adb shell input keyevent 4; sleep 2; mark "BACK dismiss dialog"

sleep 3
adb shell screenrecord --stop >/dev/null 2>&1; wait $RECPID 2>/dev/null || true; sleep 2
adb pull /sdcard/rec.mp4 $OUT/rec.mp4 >/dev/null 2>&1 || true
adb logcat -d -s AndroidRuntime:E | tail -30 > $OUT/crash_a$API.txt || true
cat $TL
