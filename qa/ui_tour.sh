#!/bin/bash
# UI TOUR V4: Quay toàn bộ giao diện KenhLive TV v7.5.0
# Luồng chi tiết:
# 1. Home Hub 3 card (Canva 3D logos)
# 2. Vào mục Truyền Hình (xem kênh TV, chuyển nhóm kênh, 2 lần BACK về Hub)
# 3. Vào mục Phim Truyện (duyệt Netflix Cinema, mở chọn tập & server, phát Native 4K ExoPlayer, tua 15s, lưu tiến độ thời gian, 2 lần BACK về Hub)
# 4. Vào mục Thể Thao (duyệt trận live, 2 lần BACK về Hub)
# 5. Thoát app dialog tại Home Hub
set -u
OUT=/tmp/tour; mkdir -p $OUT
adb root >/dev/null 2>&1 || true; sleep 1
adb shell settings put global http_proxy 10.0.2.2:7891
adb shell settings put global global_http_proxy_exclusion_list "api.gvapi.cc,api.colaapis6.com,*.colaapis6.com,*.gvapi.cc,*.zhwpg.app,*.adviceme.io,*.keonhacaitp.one"
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
adb shell screenrecord --bit-rate 6000000 --time-limit 180 /sdcard/rec.mp4 &
REC_PID=$!
sleep 2

# 2. MỞ APP TẠI CỔNG CHỌN (HOME HUB - TAB 6)
mark "MO APP: Menu Chon 3 Card Home Hub"
adb shell am start -W -n com.kenhlive.tv/.MainActivity --ei tab 6 >/dev/null 2>&1
sleep 4
adb exec-out screencap -p > $OUT/01_home_hub.png

# Duyệt qua các card trên Home Hub
go "RIGHT: chuyen sang the TRUYEN HINH" 22 2; foc
adb exec-out screencap -p > $OUT/02_hub_focus_iptv.png

# ================= 3. VÀO MỤC TRUYỀN HÌNH =================
mark "OK: VAO MUC TRUYEN HINH (IPTV)"
go "OK: vao muc Truyen Hinh" 23 4; foc
adb exec-out screencap -p > $OUT/03_iptv_fullscreen.png

# Duyệt lưới kênh truyền hình VTV
go "DOWN: xuong luoi kenh TV" 20 2; foc
go "RIGHT: chuyen sang kenh VTV1" 22 2; foc
go "RIGHT: chuyen sang kenh VTV2" 22 2; foc
adb exec-out screencap -p > $OUT/04_iptv_grid_focus.png

# Bấm OK phát kênh truyền hình
mark "OK: PHAT KENH TRUYEN HINH VTV"
go "OK: phat kenh truyen hinh" 23 5; foc
adb exec-out screencap -p > $OUT/05_iptv_playing.png

# Thoát Player kênh truyền hình về lưới kênh (ấn 2 lần BACK để đóng overlay và finish PlayerActivity)
mark "THOAT PLAYER KENH TV VE LUOI KENH"
key 4; sleep 1
key 4; sleep 2
adb exec-out screencap -p > $OUT/05b_iptv_grid_returned.png

# Thoát mục Truyền Hình: Ấn 2 lần BACK về Menu Chọn
mark "THOAT MUC TRUYEN HINH: An 2 lan BACK"
go "BACK lan 1 (hien Toast nhac nho)" 4 1
go "BACK lan 2 (ve Menu Chon)" 4 3
adb exec-out screencap -p > $OUT/06_returned_hub_from_iptv.png
mark "DA QUAY VE MENU CHON TU TRUYEN HINH"

# ================= 4. VÀO MỤC PHIM TRUYỆN =================
# Khi quay về Hub, focus ở thẻ THỂ THAO, bấm RIGHT 2 lần để sang PHIM TRUYỆN
go "RIGHT: tu The Thao sang Truyen Hinh" 22 1; foc
go "RIGHT: tu Truyen Hinh sang PHIM TRUYEN" 22 2; foc
adb exec-out screencap -p > $OUT/07_hub_focus_phim.png
mark "OK: VAO MUC PHIM TRUYEN (KHO BOM TAN 4K)"
go "OK: vao muc Phim Truyen" 23 5; foc

# Giao diện Phim Truyện Netflix Smart TV
adb exec-out screencap -p > $OUT/08_phim_netflix_home.png
mark "KHO PHIM NETFLIX: Billboard 4K & Apple Pills"
# Focus nút XEM NGAY trên Hero Banner
go "DOWN: xuong hang nut Hero (focus nut XEM NGAY)" 20 2; foc
adb exec-out screencap -p > $OUT/09_phim_hero_play_focused.png

# Bấm phát phim trên Native ExoPlayer 4K
mark "OK: phat phim tren Trinh Phat Native ExoPlayer 4K"
go "OK: phat phim qua nut XEM NGAY" 23 7; foc
adb exec-out screencap -p > $OUT/11_phim_vod_playing.png

# Xem phim trong 5s để tiến độ tự động ghi nhận
sleep 4

# Thao tác tua D-pad Trái/Phải 15s
go "RIGHT: tua nhanh 15 giay (+15s)" 22 3
adb exec-out screencap -p > $OUT/12_phim_seek_forward.png
go "LEFT: tua lui 15 giay (-15s)" 21 3
adb exec-out screencap -p > $OUT/13_phim_seek_backward.png

# Xem thêm 3 giây để hệ thống tự lưu tiến độ định kỳ
sleep 3

# Thoát Player về Kho Phim (2 lần BACK để đóng overlay nếu có và finish PlayerActivity)
mark "THOAT PLAYER VOD VE KHO PHIM"
key 4; sleep 1
key 4; sleep 3
adb exec-out screencap -p > $OUT/14_phim_saved_progress_view.png
mark "KHO PHIM: Da luu tien do va thoi gian xem tap"

# Thoát mục Phim Truyện: Ấn 2 lần BACK về Menu Chọn
mark "THOAT MUC PHIM TRUYEN: An 2 lan BACK"
go "BACK lan 1 (hien Toast nhac nho)" 4 1
go "BACK lan 2 (ve Menu Chon)" 4 2
adb exec-out screencap -p > $OUT/15_returned_hub_from_phim.png
mark "DA QUAY VE MENU CHON TU PHIM TRUYEN"

# ================= 5. VÀO MỤC THỂ THAO =================
go "LEFT: chuyen sang the TRUYEN HINH" 21 1; foc
go "LEFT: chuyen sang the THE THAO" 21 1; foc
adb exec-out screencap -p > $OUT/16_hub_focus_sports.png
mark "OK: VAO MUC THE THAO"
go "OK: vao muc The Thao" 23 4; foc
adb exec-out screencap -p > $OUT/17_sports_fullscreen.png

# Duyệt qua các hàng đài thể thao (Socolive -> ColaTV -> Gà Vàng 33 -> Giờ Vàng TV)
go "DOWN: xuong hang dai Socolive" 20 2; foc
go "DOWN: xuong hang dai ColaTV (API chinh thuc)" 20 2; foc
adb exec-out screencap -p > $OUT/17b_sports_colatv_cards.png
go "RIGHT: duyet tran ColaTV co ti so real-time" 22 2; foc
adb exec-out screencap -p > $OUT/17c_sports_colatv_score_card.png
go "DOWN: xuong hang dai Ga Vang 33" 20 2; foc
adb exec-out screencap -p > $OUT/17d_sports_gavang33_cards.png
go "DOWN: xuong hang dai Gio Vang TV" 20 2; foc
adb exec-out screencap -p > $OUT/17e_sports_giovang_cards.png

# Thoát mục Thể Thao: Ấn 2 lần BACK về Menu Chọn
mark "THOAT MUC THE THAO: An 2 lan BACK"
go "BACK lan 1" 4 1
go "BACK lan 2 ve Menu Chon" 4 2
adb exec-out screencap -p > $OUT/18_returned_hub_from_sports.png

# ================= 6. TẠI MENU CHỌN: BẤM BACK HIỆN DIALOG THOÁT ỨNG DỤNG =================
mark "BACK TAI MENU CHON: Hien Dialog Thoat App"
go "BACK tai Menu Chon" 4 2
adb exec-out screencap -p > $OUT/19_dialog_exit_app.png
go "BACK huy dialog o lai app" 4 1

# KẾT THÚC QUÁ TRÌNH GHI HÌNH
sleep 2
adb logcat -d -s SportsStreamResolver:V Http:V KenhLive:V AndroidRuntime:E > $OUT/logcat.txt 2>/dev/null || true
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
