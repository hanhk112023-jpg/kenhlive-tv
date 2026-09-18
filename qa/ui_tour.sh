#!/bin/bash
# UI TOUR: quay toan bo giao dien KenhLive tren ROM ATV (proxy VN -> du lieu live that).
# Test toan bo cac Tab (Live, Lich thi dau, Tim kiem, Cai dat) + Player + MultiView (2 & 4 o) + Dialog thoat.
set -u
OUT=/tmp/tour; mkdir -p $OUT
adb root >/dev/null 2>&1 || true; sleep 1
adb shell settings put global http_proxy 10.0.2.2:7891
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
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
foc()   { local X=$(adb shell "uiautomator dump /sdcard/f.xml >/dev/null 2>&1; cat /sdcard/f.xml" 2>/dev/null | tr '>' '\n' | grep 'focused="true"' | grep -o 'bounds="[^"]*"' | head -1); echo "$(($(date +%s)-T0))s|FOCU $X" >> $TL; }
go()    { local n="$1" c="$2" w="$3"; key "$c"; sleep "$w"; mark "$n"; }
relaunch(){ adb shell am start -n com.kenhlive.tv/.MainActivity --ei tab "$1" >/dev/null 2>&1; sleep 8; mark "RELAUNCH tab$1"; }
startrec(){ REC_CUR="$1"; adb shell rm -f /sdcard/$REC_CUR; adb shell screenrecord --bit-rate 6000000 --time-limit 170 /sdcard/$REC_CUR & REC_PID=$!; }
stoprec() { local f="${1:-${REC_CUR:-rec1.mp4}}"; adb shell screenrecord --stop >/dev/null 2>&1; wait $REC_PID 2>/dev/null || true; sleep 3; adb pull /sdcard/$f $OUT/$f >/dev/null 2>&1 || true; }

# ==================== DOAN 1 ====================
startrec rec1.mp4; sleep 2; mark "MO APP: Trang Chu & Truc Tiep The Thao"

# 1. TAB 0: HOME / TRỰC TIẾP
go "RIGHT: chip giai"      22 2
go "LEFT: ve TAT CA"       21 2
go "DOWN: hero"            20 3; foc
go "DOWN: card LIVE 1"     20 3; foc
go "RIGHT: card 2"         22 2
go "RIGHT: card 3"         22 2
go "LEFT: ve card 1"       21 2
go "DOWN: danh sach BLV"   20 2
go "RIGHT: BLV Top 2"      22 2
go "LEFT: ve BLV Top 1"    21 2
adb exec-out screencap -p > $OUT/tab0_home.png
mark "CHUP ANH: Tab 0 Home"

# 2. TAB 1: LỊCH THI ĐẤU
relaunch 1
go "DOWN: danh sach lich"  20 2
go "DOWN: tran ke tiep"    20 2
go "UP"                    19 2
adb exec-out screencap -p > $OUT/tab1_schedule.png
mark "CHUP ANH: Tab 1 Schedule"

# 3. TAB 2: TÌM KIẾM
relaunch 2
adb shell input text "u23" >/dev/null 2>&1; sleep 6; mark "TYPE 'u23' -> ket qua tim kiem"
go "BACK dong ban phim"    4 2
go "DOWN qua ket qua"      20 2
adb exec-out screencap -p > $OUT/tab2_search.png
mark "CHUP ANH: Tab 2 Search"

# 4. TAB 3: CÀI ĐẶT
relaunch 3
go "DOWN muc"              20 2
go "DOWN"                  20 2
go "UP"                    19 2
adb exec-out screencap -p > $OUT/tab3_settings.png
mark "CHUP ANH: Tab 3 Settings"

# 5. VÀO PHÒNG LIVE -> PLAYER EXOPLAYER
relaunch 0
go "DOWN toi card LIVE"    20 3
go "OK: danh sach phong"   23 3
go "OK: vao phong"         23 10; mark "PLAYER dang phat ExoPlayer"
adb exec-out screencap -p > $OUT/player.png
mark "CHUP ANH: Player"
SPLIT=$(($(date +%s)-T0)); echo "$SPLIT" > $OUT/split_at.txt
stoprec

# ==================== DOAN 2 ====================
startrec rec2.mp4; sleep 1; mark "TIEP: BACK khoi player ve Home"
go "BACK ra player"        4 4
FG=$(focus); case "$FG" in *kenhlive*) : ;; *) relaunch 0; key 20; sleep 2;; esac

# 6. MULTIVIEW (2 Ô VÀ 4 Ô)
adb shell am start -n com.kenhlive.tv/.MultiViewActivity --ei mv_layout 0 >/dev/null 2>&1
sleep 15; mark "MULTIVIEW 2 o"
adb exec-out screencap -p > $OUT/multiview2.png
go "DOWN: o 2"             20 3
go "UP: o 1"               19 3

adb shell am start -n com.kenhlive.tv/.MultiViewActivity --ei mv_layout 1 >/dev/null 2>&1
sleep 15; mark "MULTIVIEW 4 o"
adb exec-out screencap -p > $OUT/multiview4.png
go "BACK khoi MV"          4 3

# 7. DIALOG THOAT APP
relaunch 0
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

# Ghep 2 doan + burn phu de timeline vao rec.mp4
if [ "$S1" -gt 100000 ] && [ "$S2" -gt 100000 ]; then
  python3 - <<'PY'
import os
OUT='/tmp/tour'
seg=[]; last=-99
if os.path.exists(OUT+'/timeline.txt'):
    for L in open(OUT+'/timeline.txt',encoding='utf-8',errors='replace'):
        p=L.strip().split('|')
        if len(p)<2: continue
        try: t=int(p[0].rstrip('s'))
        except: continue
        if t<2 or t-last<7: continue
        last=t; seg.append((t,p[1]))
def esc(x): return x.replace('{','(').replace('}',')')
def fmt(s):
    m,ss=divmod(s,60); return f"0:{m:02d}:{ss:02d}"
lines=["[Script Info]","PlayResX: 1920","PlayResY: 1080","ScriptType: v4.00+","","[V4+ Styles]","Format: Name, Fontname, Fontsize, PrimaryColour, Outline, Shadow, Alignment, MarginV","Style: Cap,Noto Sans,46,&H00FFFFFF,2,1,1,60","","[Events]","Format: Marked, Start, End, Text"]
for idx,(t,lab) in enumerate(seg):
    end=seg[idx+1][0] if idx+1<len(seg) else t+10
    lines.append(f"Dialogue: 0,{fmt(t)},{fmt(end)},{esc(lab)}")
open(OUT+'/caps.ass','w',encoding='utf-8').write("\n".join(lines))
PY
  printf "file '%s'\nfile '%s'\n" "$OUT/rec1.mp4" "$OUT/rec2.mp4" > $OUT/list.txt
  ffmpeg -y -f concat -safe 0 -i $OUT/list.txt -vf "ass=$OUT/caps.ass" -c:v libx264 -preset veryfast -crf 27 $OUT/rec.mp4 >/dev/null 2>&1 \
    || ffmpeg -y -f concat -safe 0 -i $OUT/list.txt -c copy $OUT/rec.mp4 >/dev/null 2>&1 \
    || cp "$OUT/rec1.mp4" "$OUT/rec.mp4"
fi

if [ ! -s "$OUT/rec.mp4" ]; then
  if [ -s "$OUT/rec1.mp4" ]; then
    cp "$OUT/rec1.mp4" "$OUT/rec.mp4"
  fi
fi

SZ=$(stat -c%s $OUT/rec.mp4 2>/dev/null || echo 0)
echo "KET-QUA rec1=$S1 rec2=$S2 rec=$SZ split=$SPLIT crash=$(wc -l < $OUT/crash.txt) appcrash=$(wc -l < $OUT/events.txt)"
[ "$SZ" -gt 100000 ] || { echo "::error::thieu video"; exit 1; }
