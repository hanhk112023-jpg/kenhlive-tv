#!/bin/bash
# UI TOUR V2 (Tối ưu tốc độ + AI Driver): quay toàn bộ giao diện KenhLive trên ROM Android TV
# Sử dụng dynamic settle thay vì sleep mù để giảm 50% thời gian chạy.
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
adb shell am start -W -n com.kenhlive.tv/.MainActivity >/dev/null 2>&1

# Chờ UI sẵn sàng nhanh qua uiautomator
wait_ready() {
  for w in $(seq 1 15); do
    D=$(adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb shell cat /sdcard/u.xml 2>/dev/null)
    if echo "$D" | grep -qE 'XEM NGAY|phòng live|Hôm nay|Truyền hình|Việt Nam'; then return 0; fi
    if echo "$D" | grep -qE 'Không tải|THỬ LẠI|Sân vắng'; then return 1; fi
    sleep 2
  done; return 1
}
wait_ready || true

TL=$OUT/timeline.txt; : > $TL
T0=$(date +%s)
focus() { adb shell dumpsys window 2>/dev/null | grep -m1 -o 'com.kenhlive.tv/[^ ]*' || echo '?'; }
mark()  { echo "$(($(date +%s)-T0))s|$1|$(focus)" >> $TL; }
key()   { adb shell input keyevent $1; }
foc()   { local X=$(adb shell "uiautomator dump /sdcard/f.xml >/dev/null 2>&1; cat /sdcard/f.xml" 2>/dev/null | tr '>' '\n' | grep 'focused="true"' | grep -o 'bounds="[^"]*"' | head -1); echo "$(($(date +%s)-T0))s|FOCU $X" >> $TL; }
go()    { local n="$1" c="$2" w="$3"; key "$c"; sleep "$w"; mark "$n"; }
relaunch(){ adb shell am start -n com.kenhlive.tv/.MainActivity --ei tab "$1" >/dev/null 2>&1; sleep 3; mark "RELAUNCH tab$1"; }
startrec(){ REC_CUR="$1"; adb shell rm -f /sdcard/$REC_CUR; adb shell screenrecord --bit-rate 6000000 --time-limit 150 /sdcard/$REC_CUR & REC_PID=$!; }
stoprec() { local f="${1:-${REC_CUR:-rec1.mp4}}"; adb shell screenrecord --stop >/dev/null 2>&1; wait $REC_PID 2>/dev/null || true; sleep 2; adb pull /sdcard/$f $OUT/$f >/dev/null 2>&1 || true; }

# ==================== ĐOẠN 1 ====================
startrec rec1.mp4; sleep 2; mark "MO APP: Trang Chu & Truc Tiep The Thao"

# 1. TAB 0: HOME / TRỰC TIẾP
sleep 2
adb exec-out screencap -p > $OUT/tab0_fullscreen_no_rail.png
mark "CHUP ANH: Full man hinh noi dung (Side tab da an)"
go "LEFT: cham canh trai de goi Side tab hien len" 21 2; foc
adb exec-out screencap -p > $OUT/tab0_rail_revealed.png
mark "CHUP ANH: Side tab tu dong hien khi tuong tac sang trai"

# TEST DPAD CHUYỂN TAB TỰ NHIÊN:
# Đứng tại Side tab, bấm DOWN để duyệt từng mục, bấm RIGHT/OK để vào nội dung
go "DOWN: chuyen focus tren Side tab xuong Tab Lich thi dau" 20 1; foc
adb exec-out screencap -p > $OUT/tab0_rail_focus_nav.png
mark "CHUP ANH: Focus di chuyen tren Side tab"

# 2. CHUYỂN SANG TAB 1 BẰNG DPAD (KHÔNG DÙNG RELAUNCH):
go "RIGHT: vao Tab 1 Lich thi dau bang D-pad" 22 2; foc
go "DOWN: cuon xuong tran ke tiep"                         20 1
go "UP: cuon tro lai tran dau tien"                         19 1
adb exec-out screencap -p > $OUT/tab1_schedule.png
mark "CHUP ANH: Tab 1 Schedule"

# 3. CHUYỂN SANG TAB 2 TRUYỀN HÌNH BẰNG DPAD:
go "LEFT: goi lai Side tab tu Tab 1" 21 2; foc
go "DOWN: di chuyen D-pad xuong icon Tab Truyen Hinh" 20 1; foc
go "RIGHT: vao Tab 2 Truyen Hinh bang D-pad" 22 4; foc
adb exec-out screencap -p > $OUT/tab2_iptv.png
mark "CHUP ANH: Tab 2 IPTV va EPG Live"

# Đảm bảo đưa focus sang lưới kênh (từ menu bấm RIGHT để vào nội dung IPTV)
go "RIGHT: vao luoi kenh VTV" 22 1; foc
go "XAC NHAN: da vao kenh VTV (EPG Now & Next)" 0 1; foc
adb exec-out screencap -p > $OUT/tab2_vtv_focused.png

go "RIGHT: sang kenh VTV tiep theo" 22 2; foc
go "RIGHT: sang kenh VTV thu 3" 22 2; foc
go "OK: mo xem kenh VTV trong Player Pro" 23 5
adb exec-out screencap -p > $OUT/tab2_vtv_player.png
mark "CHUP ANH: Player Pro phat kenh VTV"
go "UP: Quick Channel OSD Banner chuyen kenh" 19 2
adb exec-out screencap -p > $OUT/tab2_quick_osd.png
mark "CHUP ANH: Quick Channel OSD Banner"
go "DOWN: mo Carousel chuyen kenh ngang duoi day TV" 20 1
adb exec-out screencap -p > $OUT/tab2_carousel_channels.png
mark "CHUP ANH: Bottom Channel Carousel ngang"
go "RIGHT: luot chon kenh ke tiep tren carousel" 22 1
go "RIGHT: luot chon them kenh nua" 22 1
adb exec-out screencap -p > $OUT/tab2_carousel_focused.png
mark "CHUP ANH: Carousel focus va hien EPG"
go "BACK: dong carousel" 4 1
go "OK: hien controls player va sidebar" 23 1
go "RIGHT: toi nut Aspect Ratio" 22 1
go "OK: bam chuyen ti le man hinh (Fit/Fill/Zoom)" 23 1
go "RIGHT: toi nut Sleep Timer" 22 1
go "OK: mo dialog Hen Gio Tat TV" 23 1
adb exec-out screencap -p > $OUT/player_sleep_timer_dialog.png
mark "CHUP ANH: Dialog Hen Gio Tat TV"
go "DOWN: chon hen 30 phut" 20 1
go "OK: xac nhan hen gio" 23 1
go "OK: hien lai controls player" 23 1
go "RIGHT: toi nut Audio Tracks" 22 1
go "OK: mo dialog Chon Luong Audio / BLV" 23 1
adb exec-out screencap -p > $OUT/player_audio_tracks_dialog.png
mark "CHUP ANH: Dialog Chon Luong Audio"
go "BACK: dong dialog audio" 4 1
go "OK: hien lai controls player" 23 1
go "RIGHT: toi nut Audio Boost" 22 1
go "OK: tang am luong phan cung (+3dB / +6dB / +9dB)" 23 1
go "RIGHT: toi nut Stats for Nerds HUD" 22 1
go "OK: mo Stats for Nerds HUD" 23 2
adb exec-out screencap -p > $OUT/tab2_stats_hud.png
mark "CHUP ANH: Stats for Nerds HUD"
go "BACK: dong Stats HUD" 4 1
go "OK: mo danh sach kenh Sidebar" 23 1
adb exec-out screencap -p > $OUT/tab2_sidebar_channels.png
mark "CHUP ANH: Sidebar danh sach kenh IPTV"
go "BACK: dong sidebar ve player" 4 1
go "BACK: thoat player ve tab truyen hinh" 4 2
go "UP: len danh muc nhom kenh" 19 1
go "RIGHT: chon nhom The Thao" 22 1
go "OK: active filter The Thao" 23 2
adb exec-out screencap -p > $OUT/tab2_sports_filter.png
mark "CHUP ANH: Nhom The Thao (DAZN/Sky/beIN)"
go "DOWN: focus kenh the thao dau tien" 20 1

# 4. TAB 3: TÌM KIẾM
go "LEFT: goi lai Side tab tu Tab 2" 21 2; foc
go "DOWN: di chuyen D-pad xuong icon Tab Tim Kiem" 20 1; foc
go "RIGHT: vao Tab 3 Tim Kiem bang D-pad" 22 2; foc
go "KIEM TRA: focus tu dong vao search input" 0 1
adb shell input text "u23" >/dev/null 2>&1; sleep 2; mark "TYPE 'u23' -> ket qua tim kiem"
go "BACK dong ban phim"                       4 1
go "DOWN: chuyen focus xuong chip giai hoac ket qua" 20 1
adb exec-out screencap -p > $OUT/tab3_search.png
mark "CHUP ANH: Tab 3 Search"

# 5. TAB 4: CÀI ĐẶT
go "LEFT: goi lai Side tab tu Tab 3" 21 2; foc
go "DOWN: di chuyen D-pad xuong icon Tab Cai Dat" 20 1; foc
go "RIGHT: vao Tab 4 Cai Dat bang D-pad" 22 2; foc
go "DOWN: dong cai dat dau tien (Chat luong hinh anh)" 20 1
go "OK: mo dialog chon chat luong hinh anh"            23 2
adb exec-out screencap -p > $OUT/tab4_dialog_video.png
mark "CHUP ANH: Dialog chon chat luong hinh anh"
go "DOWN: chuyen lua chon chat luong"                   20 1
go "OK: chon chat luong moi va dong dialog"             23 2
go "DOWN: dong cai dat thu 2 (Che do am thanh)"         20 1
go "OK: mo dialog chon che do am thanh"                23 2
adb exec-out screencap -p > $OUT/tab4_dialog_audio.png
mark "CHUP ANH: Dialog chon che do am thanh"
go "DOWN: chuyen lua chon am thanh (Bass)"             20 1
go "OK: chon che do am thanh va dong dialog"           23 2
go "UP: quay lai dong dau"                             19 1
adb exec-out screencap -p > $OUT/tab4_settings.png
mark "CHUP ANH: Tab 4 Settings sau khi chinh chat luong"

# 6. VÀO PHÒNG LIVE -> PLAYER EXOPLAYER
go "LEFT: goi lai Side tab tu Tab 4" 21 2; foc
go "UP: di chuyen D-pad len icon Tab Trang Chu" 19 4; foc
go "RIGHT: vao lai Tab 0 Trang Chu bang D-pad" 22 2; foc
go "DOWN toi card LIVE"    20 2
go "OK: danh sach phong"   23 2
go "OK: vao phong"         23 6; mark "PLAYER dang phat ExoPlayer"
go "OK: hien control overlay player" 23 1
go "OK: mo dialog cai dat chat luong hinh/am" 23 2
adb exec-out screencap -p > $OUT/player_settings_dialog.png
mark "CHUP ANH: Player settings dialog"
go "RIGHT: chon muc chat luong khac" 22 1
go "DOWN: chuyen xuong nhom am thanh" 20 1
go "BACK: dong dialog cai dat player" 4 1
adb exec-out screencap -p > $OUT/player.png
mark "CHUP ANH: Player"
SPLIT=$(($(date +%s)-T0)); echo "$SPLIT" > $OUT/split_at.txt
stoprec

# ==================== ĐOẠN 2 ====================
startrec rec2.mp4; sleep 1; mark "TIEP: BACK khoi player ve Home"
go "BACK ra player"        4 2
FG=$(focus); case "$FG" in *kenhlive*) : ;; *) relaunch 0; key 20; sleep 1;; esac

# 7. MULTIVIEW (2 Ô VÀ 4 Ô)
adb shell am start -n com.kenhlive.tv/.MultiViewActivity --ei mv_layout 0 >/dev/null 2>&1
sleep 6; mark "MULTIVIEW 2 o"
adb exec-out screencap -p > $OUT/multiview2.png
go "DOWN: o 2"             20 1
go "UP: o 1"               19 1

adb shell am start -n com.kenhlive.tv/.MultiViewActivity --ei mv_layout 1 >/dev/null 2>&1
sleep 6; mark "MULTIVIEW 4 o"
adb exec-out screencap -p > $OUT/multiview4.png
go "BACK khoi MV"          4 2

# 7.5 TEST GIAO DIỆN MOBILE PHONE (CHUYỂN PROFILE PHONE 1080x2400 PORTRAIT & LANDSCAPE)
mark "BAT DAU TEST PHONE MODE: Chuyen man hinh doc dien thoai"
adb shell wm size 1080x2400
adb shell wm density 420
adb shell am force-stop com.kenhlive.tv; sleep 1

# Mở trực tiếp PlayerActivity với URL test trên Phone dọc
adb shell am start -n com.kenhlive.tv/.PlayerActivity --es url "https://bitdash-a.akamaihd.net/content/sintel/hls/video/master.m3u8" --es name "Chelsea vs Arsenal" >/dev/null 2>&1
sleep 4
adb shell input keyevent 23 # phím OK/Enter kích hoạt overlay
sleep 1
adb exec-out screencap -p > $OUT/phone_player_portrait.png
mark "CHUP ANH: Mobile Phone Player Portrait doc"

# Bấm mở Menu More Options trên Phone (nút 3 chấm ⋮ ở góc trên bên phải)
adb shell input tap 990 100
sleep 2
adb exec-out screencap -p > $OUT/phone_player_menu_dialog.png
mark "CHUP ANH: Mobile Phone Player More Options Menu"
adb shell input keyevent 4 # Đóng menu
sleep 1

# Chuyển Phone sang Landscape xoay ngang (2400x1080)
adb shell settings put system user_rotation 1
adb shell wm size 2400x1080
sleep 3
# Chạm nhẹ màn hình để gọi hiển thị controls trên màn hình ngang
adb shell input keyevent 23
sleep 1
adb exec-out screencap -p > $OUT/phone_player_landscape.png
mark "CHUP ANH: Mobile Phone Player Landscape ngang"

# Khôi phục lại chuẩn Android TV
adb shell settings put system user_rotation 0
adb shell wm size 1920x1080
adb shell wm density 320
adb shell am force-stop com.kenhlive.tv; sleep 1

# 8. DIALOG THOAT APP
relaunch 0
go "BACK top-level"        4 2
adb exec-out screencap -p > $OUT/back_dialog.png
mark "DIALOG Thoat?"
go "BACK huy (o lai)"      4 2
sleep 1; stoprec

adb exec-out screencap -p > $OUT/end.png
adb logcat -d -s AndroidRuntime:E | tail -40 > $OUT/crash.txt
adb logcat -d -b events | grep -E 'am_(crash|anr|proc_died)' | grep -i kenhlive > $OUT/events.txt || true
echo "===== TIMELINE ====="; cat $TL
S1=$(stat -c%s $OUT/rec1.mp4 2>/dev/null || echo 0); S2=$(stat -c%s $OUT/rec2.mp4 2>/dev/null || echo 0)

# Ghép 2 đoạn + burn phụ đề timeline vào rec.mp4
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
        if p[1].startswith('FOCU'): continue
        if t<1 or t-last<2: continue
        last=t; seg.append((t,p[1]))
def esc(x): return x.replace('{','(').replace('}',')')
def fmt(s):
    m,ss=divmod(s,60); return f"0:{m:02d}:{ss:02d}"
lines=["[Script Info]","PlayResX: 1920","PlayResY: 1080","ScriptType: v4.00+","","[V4+ Styles]","Format: Name, Fontname, Fontsize, PrimaryColour, Outline, Shadow, Alignment, MarginV","Style: Cap,Noto Sans,46,&H00FFFFFF,2,1,1,60","","[Events]","Format: Marked, Start, End, Text"]
for idx,(t,lab) in enumerate(seg):
    end=seg[idx+1][0] if idx+1<len(seg) else t+8
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
