#!/bin/bash
# UI TOUR: quay toan bo giao dien KenhLive tren ROM ATV 34 (co proxy VN -> du lieu live that),
# timeline.txt = `giay|nhan` cho ghep caption ffmpeg o buoc dung video.
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

adb shell rm -f /sdcard/rec.mp4
adb shell screenrecord --bit-rate 6000000 --time-limit 300 /sdcard/rec.mp4 &
RECPID=$!; sleep 2; mark "MO APP: tab Bong da (Sport Zone, full-man)"

# ===== 1. HOME BONG DA: chip -> hero -> rail -> danh sach =====
go "RIGHT: chip giai thu 2" 22 2
go "RIGHT: chip giai thu 3" 22 2
go "LEFT: ve chip TAT CA"   21 2
go "DOWN: xuong HERO"       20 3
go "DOWN: xuong card LIVE 1" 20 3
go "RIGHT: card 2"          22 2
go "RIGHT: card 3"          22 2
go "LEFT:  ve card 1"       21 2
go "DOWN: danh sach Hom nay" 20 2
go "DOWN"                   20 2
go "DOWN"                   20 2
go "UP"                     19 2

# ===== 2. RAIL OVERLAY kieu FPT: LEFT mo -> di menu -> chon tab -> TU DONG AN =====
go "LEFT sat mép: MO RAIL"  21 3
go "DOWN: muc Lịch thi đấu" 20 2
go "OK: chon Lịch (rail tu an)" 23 4
go "DOWN danh sach lich"    20 2
go "DOWN"                   20 2
go "UP"                     19 2

# ===== 3. TIM KIEM: mo rail -> chon -> go text =====
go "LEFT: MO RAIL"          21 3
go "DOWN: Lich"             20 2
go "DOWN: Tim kiem"         20 2
go "OK: chon Tìm kiếm"      23 4
adb shell input text "real" >/dev/null 2>&1; sleep 9; mark "TYPE: 'real' -> ket qua"
go "BACK dong ban phim"     4 2
go "DOWN qua ket qua"       20 2
go "UP ve o tim"            19 2

# ===== 4. CAI DAT qua hook (on dinh) =====
relaunch 3; mark "TAB Cai dat"
go "DOWN danh muc"          20 2
go "DOWN"                   20 2
go "UP"                     19 2

# ===== 5. PHONG LIVE: hook ve Bong da -> card -> dialog phong -> player =====
relaunch 0
go "DOWN toi card LIVE"     20 3
go "OK: mo danh sach phong" 23 3
go "OK: vao phong 1"        23 12; mark "PLAYER dang chay"
go "BACK ra khoi player"    4  5

# ===== 6. MULTIVIEW: longpress OK tren card =====
FG=$(focus); case "$FG" in *kenhlive*) : ;; *) relaunch 0; key 20; sleep 2;; esac
go "DOWN ve card"           20 3
adb shell input keyevent --longpress 23 >/dev/null 2>&1 || adb shell input keyevent --duration-key 1200 23 >/dev/null 2>&1
sleep 25; mark "MULTIVIEW 2 o dang chay"
go "DOWN: chuyen o 2"       20 3
go "UP: ve o 1"             19 3
go "BACK khoi Multiview"    4  4

# ===== 7. DIALOG XAC NHAN THOAT =====
go "BACK top-level"         4  3
adb exec-out screencap -p > $OUT/back_dialog.png
mark "DIALOG 'Thoat KenhLive?' hien"
go "BACK: huy dialog (o lai)" 4 3

sleep 2
adb shell screenrecord --stop >/dev/null 2>&1; wait $RECPID 2>/dev/null || true; sleep 2
adb pull /sdcard/rec.mp4 $OUT/rec.mp4 >/dev/null 2>&1 || true
adb exec-out screencap -p > $OUT/end.png
adb logcat -d -s AndroidRuntime:E | tail -40 > $OUT/crash.txt
adb logcat -d -b events | grep -E 'am_(crash|anr|proc_died)' | grep -i kenhlive > $OUT/events.txt || true
echo "===== TIMELINE ====="; cat $TL
echo "KET-QUA rec=$(stat -c%s $OUT/rec.mp4 2>/dev/null || echo 0) crash=$(wc -l < $OUT/crash.txt) appcrash=$(wc -l < $OUT/events.txt)"
[ "$(stat -c%s $OUT/rec.mp4 2>/dev/null || echo 0)" -gt 100000 ] || { echo "::error::khong co video"; exit 1; }
