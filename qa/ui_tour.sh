#!/bin/bash
# UI TOUR V3: Quay toàn bộ giao diện KenhLive TV v7.4.7
# Trọng tâm: Vào trực tiếp Phim Truyện từ Home Hub, duyệt Netflix UI, tua 15s, thoát 2 lần BACK về Home Hub, vào Truyền Hình & Thể Thao.
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
adb shell am force-stop com.kenhlive.tv; sleep 1

TL=$OUT/timeline.txt; : > $TL
T0=$(date +%s)
focus() { adb shell dumpsys window 2>/dev/null | grep -m1 -o 'com.kenhlive.tv/[^ ]*' || echo '?'; }
mark()  { echo "$(($(date +%s)-T0))s|$1|$(focus)" >> $TL; }
key()   { adb shell input keyevent $1; }
foc()   { local X=$(adb shell "uiautomator dump /sdcard/f.xml >/dev/null 2>&1; cat /sdcard/f.xml" 2>/dev/null | tr '>' '\n' | grep 'focused="true"' | grep -o 'bounds="[^"]*"' | head -1); echo "$(($(date +%s)-T0))s|FOCU $X" >> $TL; }
go()    { local n="$1" c="$2" w="$3"; key "$c"; sleep "$w"; mark "$n"; }

# 1. KHỞI CHẠY QUAY VIDEO DUY NHẤT (Single-pass continuous 1080p recording)
adb shell rm -f /sdcard/rec.mp4
adb shell screenrecord --bit-rate 6000000 --time-limit 170 /sdcard/rec.mp4 &
REC_PID=$!
sleep 2

# 2. MỞ APP TẠI CỔNG CHỌN (HOME HUB - TAB 6)
mark "MO APP: Menu Chon 3 Card Home Hub"
adb shell am start -W -n com.kenhlive.tv/.MainActivity --ei tab 6 >/dev/null 2>&1
sleep 4
adb exec-out screencap -p > $OUT/01_home_hub.png

# Đóng thông báo nếu có
go "BACK: dong thong bao" 4 1
adb exec-out screencap -p > $OUT/02_home_hub_clean.png

# 3. CHUYỂN TIÊU ĐIỂM VÀ BẤM OK VÀO THẲNG MỤC PHIM TRUYỆN
go "RIGHT: chuyen sang the Truyen Hinh" 22 1; foc
go "RIGHT: chuyen sang the PHIM TRUYEN" 22 1; foc
adb exec-out screencap -p > $OUT/03_hub_focus_phim.png
mark "OK: VAO MUC PHIM TRUYEN (KHO BOM TAN 4K)"
go "OK: vao muc Phim Truyen" 23 4; foc

# Giao diện Phim Truyện Netflix Smart TV
adb exec-out screencap -p > $OUT/04_phim_netflix_home.png
mark "KHO PHIM NETFLIX: Billboard 4K & Apple Pills"
go "DOWN: xuong hang poster phim" 20 2; foc
go "RIGHT: duyet poster phim 2" 22 1; foc
go "RIGHT: duyet poster phim 3" 22 1; foc
adb exec-out screencap -p > $OUT/05_phim_posters.png

# Bấm mở thông tin & danh sách tập phim
mark "OK: mo danh sach tap va server phim"
go "OK: mo thong tin tap va server" 23 3
adb exec-out screencap -p > $OUT/06_phim_episodes_dialog.png

# Bấm phát phim trên Native ExoPlayer
mark "OK: phat tap 1 tren Trinh Phat Native ExoPlayer 4K"
go "OK: phat phim" 23 4
adb exec-out screencap -p > $OUT/07_phim_vod_playing.png

# Thao tác tua D-pad Trái/Phải 15s
go "RIGHT: tua nhanh 15 giay (+15s)" 22 2
adb exec-out screencap -p > $OUT/08_phim_seek_forward.png
go "LEFT: tua lui 15 giay (-15s)" 21 2
adb exec-out screencap -p > $OUT/09_phim_seek_backward.png

# Thoát Player về Kho Phim
go "BACK: thoat player ve Kho Phim" 4 2

# 4. TEST THOÁT MỤC PHIM TRUYỆN: ẤN 2 LẦN BACK VỀ MENU CHỌN
mark "THOAT MUC PHIM TRUYEN: An 2 lan BACK"
go "BACK lan 1 (hien Toast nhac nho)" 4 1
go "BACK lan 2 (ve Menu Chon)" 4 2
adb exec-out screencap -p > $OUT/10_returned_hub_from_phim.png
mark "DA QUAY VE MENU CHON TU PHIM TRUYEN"

# 5. CHUYỂN SANG VÀ VÀO MỤC TRUYỀN HÌNH
go "LEFT: chuyen sang the TRUYEN HINH" 21 1; foc
mark "OK: VAO MUC TRUYEN HINH"
go "OK: vao muc Truyen Hinh" 23 4; foc
adb exec-out screencap -p > $OUT/11_iptv_fullscreen.png

# Duyệt lưới kênh truyền hình
go "DOWN: xuong luoi kenh TV" 20 1; foc
go "RIGHT: kenh VTV1" 22 1; foc
go "RIGHT: kenh VTV2" 22 1; foc
go "RIGHT: kenh VTV3" 22 1; foc
adb exec-out screencap -p > $OUT/12_iptv_grid_focus.png

# Test thoát mục Truyền Hình: Ấn 2 lần BACK về Menu Chọn
mark "THOAT MUC TRUYEN HINH: An 2 lan BACK"
go "BACK lan 1" 4 1
go "BACK lan 2 ve Menu Chon" 4 2
adb exec-out screencap -p > $OUT/13_returned_hub_from_iptv.png

# 6. CHUYỂN SANG VÀ VÀO MỤC THỂ THAO
go "LEFT: chuyen sang the THE THAO" 21 1; foc
mark "OK: VAO MUC THE THAO"
go "OK: vao muc The Thao" 23 3; foc
adb exec-out screencap -p > $OUT/14_sports_fullscreen.png

# Duyệt qua các hàng đài thể thao
go "DOWN: xuong danh sach tran dau" 20 1; foc
go "RIGHT: duyet tran dang live" 22 1; foc

# Test thoát mục Thể Thao: Ấn 2 lần BACK về Menu Chọn
mark "THOAT MUC THE THAO: An 2 lan BACK"
go "BACK lan 1" 4 1
go "BACK lan 2 ve Menu Chon" 4 2
adb exec-out screencap -p > $OUT/15_returned_hub_from_sports.png

# 7. TẠI MENU CHỌN: BẤM BACK HIỆN DIALOG THOÁT ỨNG DỤNG
mark "BACK TAI MENU CHON: Hien Dialog Thoat App"
go "BACK tai Menu Chon" 4 2
adb exec-out screencap -p > $OUT/16_dialog_exit_app.png
go "BACK huy dialog o lai app" 4 1

# KẾT THÚC QUÁ TRÌNH GHI HÌNH
sleep 2
adb shell screenrecord --stop >/dev/null 2>&1
wait $REC_PID 2>/dev/null || true
sleep 2
adb pull /sdcard/rec.mp4 $OUT/rec.mp4 >/dev/null 2>&1 || true

# Burn phụ đề timeline vào video
if [ -s "$OUT/rec.mp4" ] && [ -s "$TL" ]; then
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
        if p[1].startswith('FOCU'): continue
        if t<1 or t-last<2: continue
        last=t; seg.append((t,p[1]))
def esc(x): return x.replace('{','(').replace('}',')')
def fmt(s):
    m,ss=divmod(s,60); return f"0:{m:02d}:{ss:02d}"
lines=["[Script Info]","PlayResX: 1920","PlayResY: 1080","ScriptType: v4.00+","","[V4+ Styles]","Format: Name, Fontname, Fontsize, PrimaryColour, Outline, Shadow, Alignment, MarginV","Style: Cap,Noto Sans,44,&H00FFFFFF,2,1,1,55","","[Events]","Format: Marked, Start, End, Text"]
for idx,(t,lab) in enumerate(seg):
    end=seg[idx+1][0] if idx+1<len(seg) else t+6
    lines.append(f"Dialogue: 0,{fmt(t)},{fmt(end)},{esc(lab)}")
open(OUT+'/caps.ass','w',encoding='utf-8').write("\n".join(lines))
PY
  ffmpeg -y -i $OUT/rec.mp4 -vf "ass=$OUT/caps.ass" -c:v libx264 -preset veryfast -crf 27 $OUT/rec_sub.mp4 >/dev/null 2>&1 \
    && mv $OUT/rec_sub.mp4 $OUT/rec.mp4 || true
fi

SZ=$(stat -c%s $OUT/rec.mp4 2>/dev/null || echo 0)
echo "KET-QUA rec.mp4=$SZ bytes"
[ "$SZ" -gt 100000 ] || { echo "::error::thieu video"; exit 1; }
