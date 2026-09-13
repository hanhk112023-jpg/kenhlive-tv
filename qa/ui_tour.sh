#!/bin/bash
# UI TOUR: quay toan bo giao dien KenhLive tren ROM ATV 34 (proxy VN -> du lieu live that).
# screenrecord mo-i ~180s -> CHIA 2 DOAN cat chu dich tai "PLAYER xong"; timeline.txt
# ghi gio tuyen tinh T0 (tinh tu dau doan 1) + TEN_DOAN, buoc dung video local phat lai offset.
set -u
OUT=/tmp/tour; mkdir -p $OUT
adb root >/dev/null 2>&1 || true; sleep 1
adb shell settings put global http_proxy 10.0.2.2:7891
adb shell wm size 1920x1080
adb shell wm density 320
adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null 2>&1
adb logcat -c
adb shell am force-stop com.kenhlive.tv; sleep 2
adb shell am start -W -n com.kenhlive.tv/.MainActivity >/dev/null 2>&1

wait_ready() {
  for w in $(seq 1 12); do
    D=$(adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb shell cat /sdcard/u.xml 2>/dev/null)
    if echo "$D" | grep -qE 'XEM NGAY|phòng live|Hôm nay'; then return 0; fi
    if echo "$D" | grep -qE 'Không tải|THỬ LẠI|Sân vắng'; then return 1; fi
    sleep 10
  done; return 1
}
wait_ready || { adb shell input tap 960 640; sleep 12; wait_ready; }

TL=$OUT/timeline.txt; : > $TL
T0=$(date +%s)
focus() { adb shell dumpsys window 2>/dev/null | grep -m1 -o 'com.kenhlive.tv/[^ ]*' || echo '?'; }
mark()  { echo "$(($(date +%s)-T0))s|$1|$(focus)" >> $TL; }
key()   { adb shell input keyevent $1; }
go()    { local n="$1" c="$2" w="$3"; key "$c"; sleep "$w"; mark "$n"; }
relaunch(){ adb shell am start -n com.kenhlive.tv/.MainActivity --ei tab "$1" >/dev/null 2>&1; sleep 14; mark "RELAUNCH tab$1"; }
startrec(){ adb shell rm -f /sdcard/$1; adb shell screenrecord --bit-rate 6000000 --time-limit 200 /sdcard/$1 & REC_PID=$!; }
stoprec() { adb shell screenrecord --stop >/dev/null 2>&1; wait $REC_PID 2>/dev/null || true; sleep 3; adb pull /sdcard/$1 $OUT/$1 >/dev/null 2>&1 || true; }

# ==================== DOAN 1 ====================
startrec rec1.mp4; sleep 2; mark "MO APP: Bong da (Sport Zone full-man)"
# 1. HOME: chip -> hero -> rail -> danh sach
go "RIGHT: chip giai"      22 2
go "LEFT: ve TAT CA"       21 2
go "DOWN: hero"            20 3
go "DOWN: card LIVE 1"     20 3
go "RIGHT: card 2"         22 2
go "RIGHT: card 3"         22 2
go "LEFT: ve card 1"       21 2
go "DOWN: danh sach"       20 2
go "DOWN"                  20 2
go "UP"                    19 2
# 2. RAIL FPT: LEFT mo -> chon Lịch -> TU DONG AN
go "LEFT mép: MO RAIL"     21 3
go "DOWN: Lịch thi đấu"    20 2
go "OK: chon (rail tu an)" 23 4
go "DOWN danh sach lich"   20 2
go "DOWN"                  20 2
go "UP"                    19 2
# 3. TIM KIEM
go "LEFT: MO RAIL"         21 3
go "DOWN: Lich"            20 2
go "DOWN: Tim kiem"        20 2
go "OK: chon Tìm kiếm"     23 4
adb shell input text "real" >/dev/null 2>&1; sleep 9; mark "TYPE 'real' -> ket qua"
go "BACK dong ban phim"    4 2
go "DOWN qua ket qua"      20 2
# 4. CAI DAT
relaunch 3; mark "TAB Cai dat"
go "DOWN muc"              20 2
go "DOWN"                  20 2
go "UP"                    19 2
# 5. vao PHONG LIVE -> PLAYER (cuoi doan 1)
relaunch 0
go "DOWN toi card LIVE"    20 3
go "OK: danh sach phong"   23 3
go "OK: vao phong"         23 10; mark "PLAYER dang phat"
SPLIT=$(($(date +%s)-T0)); echo "$SPLIT" > $OUT/split_at.txt
stoprec
# ==================== DOAN 2 ====================
startrec rec2.mp4; sleep 1; mark "TIEP: BACK khoi player"
go "BACK ra player"        4 5
FG=$(focus); case "$FG" in *kenhlive*) : ;; *) relaunch 0; key 20; sleep 2;; esac
# 6. MULTIVIEW
go "DOWN ve card"          20 3
adb shell input keyevent --longpress 23 >/dev/null 2>&1 || adb shell input keyevent --duration-key 1200 23 >/dev/null 2>&1
sleep 24; mark "MULTIVIEW 2 o"
go "DOWN: o 2"             20 3
go "UP: o 1"               19 3
go "BACK khoi MV"          4 4
# 7. DIALOG THOAT
go "BACK top-level"        4 3
adb exec-out screencap -p > $OUT/back_dialog.png
mark "DIALOG Thoat?"
go "BACK huy (o lai)"      4 3
sleep 2; stoprec

adb exec-out screencap -p > $OUT/end.png
adb logcat -d -s AndroidRuntime:E | tail -40 > $OUT/crash.txt
adb logcat -d -b events | grep -E 'am_(crash|anr|proc_died)' | grep -i kenhlive > $OUT/events.txt || true
echo "===== TIMELINE ====="; cat $TL
S1=$(stat -c%s $OUT/rec1.mp4 2>/dev/null || echo 0); S2=$(stat -c%s $OUT/rec2.mp4 2>/dev/null || echo 0)
echo "KET-QUA rec1=$S1 rec2=$S2 split=$SPLIT crash=$(wc -l < $OUT/crash.txt) appcrash=$(wc -l < $OUT/events.txt)"
{ [ "$S1" -gt 100000 ] && [ "$S2" -gt 100000 ]; } || { echo "::error::thieu video"; exit 1; }
