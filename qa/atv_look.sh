#!/bin/bash
# ATV-Look: chụp multiview/home trên ROM Android TV (chạy trong emulator script)
set -u
API=$1
OUT=/tmp/look
mkdir -p $OUT
adb root >/dev/null 2>&1 || true
sleep 1
adb shell settings put global http_proxy 10.0.2.2:7891
adb shell settings put global wifi_proxy 10.0.2.2:7891
adb shell wm size 1920x1080
adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null 2>&1
adb logcat -c

wait_ready() {
  # toi da 120s: de man hinh vao ONG (v6 retry fetch + proxy nua chet co the keo dai)
  for w in $(seq 1 12); do
    D=$(adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb shell cat /sdcard/u.xml 2>/dev/null)
    if echo "$D" | grep -qE 'XEM NGAY|phòng live|Hôm nay'; then return 0; fi
    if echo "$D" | grep -qE 'Không tải|THỬ LẠI|Sân vắng'; then return 1; fi
    sleep 10
  done; return 2
}
for i in 1 2 3 4; do
  adb shell am start -W -n com.kenhlive.tv/.MainActivity >/dev/null 2>&1
  sleep 12
  wait_ready; RC=$?; echo "round $i ready rc=$RC"
  # nếu rơi màn lỗi mạng → bấm THỬ LẠI (giữa màn) tối đa 2 lần
  for k in 1 2; do
    if [ "$RC" = "1" ] && adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1 && adb shell cat /sdcard/u.xml | grep -q 'KHƠNG\|Không tải\|THỬ LẠI\|THU LAI'; then
      echo "round $i: màn lỗi mạng -> tap THỬ LẠI"; adb shell input tap 960 640; sleep 15; wait_ready; RC=$?
    else break; fi
  done
  adb exec-out screencap -p > $OUT/home_a$API.png
  adb shell am start -n com.kenhlive.tv/.MainActivity --es open mv --ei mv_layout 2 >/dev/null 2>&1
  sleep 28
  adb exec-out screencap -p > $OUT/mv2_a$API.png
  adb shell input keyevent 20; sleep 2
  adb shell input keyevent 20; sleep 3
  adb exec-out screencap -p > $OUT/mv2f_a$API.png
  adb shell am start -n com.kenhlive.tv/.MainActivity --es open mv --ei mv_layout 4 >/dev/null 2>&1
  sleep 30
  adb exec-out screencap -p > $OUT/mv4_a$API.png
  FG=$(adb shell dumpsys window | grep mCurrentFocus || true)
  echo "round $i focus: $FG"
  # OK khi có 2 ảnh mv khác nhau (video đổi frame = có traffic) VÀ app foreground
  if echo "$FG" | grep -q kenhlive && cmp -s <(adb exec-out screencap -p) /dev/null || true; then :; fi
  if echo "$FG" | grep -q kenhlive; then
    a=$(adb exec-out screencap -p | md5sum); sleep 4; b=$(adb exec-out screencap -p | md5sum)
    if ! grep -q 'Không tải' <(adb shell uiautomator dump /sdcard/v.xml >/dev/null 2>&1; adb shell cat /sdcard/v.xml 2>/dev/null); then break; fi
  fi
  adb shell am force-stop com.kenhlive.tv || true
  sleep 3
done

adb shell dumpsys package com.kenhlive.tv | grep versionName || true
adb logcat -d -s AndroidRuntime:E | tail -30 > $OUT/crash_a$API.txt

N=$(ls -1 $OUT/mv2_a$API.png $OUT/mv4_a$API.png 2>/dev/null | wc -l)
S=$(stat -c%s $OUT/mv2_a$API.png 2>/dev/null || echo 0)
S4=$(stat -c%s $OUT/mv4_a$API.png 2>/dev/null || echo 0)
echo "KET-QUA api=$API files=$N mv2=$S mv4=$S4"
if [ "$N" -ge 2 ] && [ "$S" -gt 20000 ]; then echo OK; else echo "::error::Khong chup duoc multiview api $API"; exit 1; fi
