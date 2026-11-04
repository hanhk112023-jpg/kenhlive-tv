#!/usr/bin/env bash
# Porthole deterministic checks for KenhLive TV — KHÔNG dùng AI:
# điều hướng bằng D-pad/focus của Porthole (github.com/giolaq/porthole),
# khẳng định bằng chữ trên UI (wait-for) + node đang focus (focused -q) + screenshot.
# Chạy bên trong android-emulator-runner sau khi APK đã cài và proxy VN đã set.
set -u
OUT="${1:-qa/porthole-out}"
mkdir -p "$OUT"
exec > >(tee -a "$OUT/report.txt") 2>&1
echo "Porthole checks — $(date -u +%FT%TZ)"
ok() { echo "RESULT $1: $2"; }
shot() { porthole screenshot -o "$OUT/$1" || echo "screenshot $1 failed"; }
foc() { porthole focused -q > "$OUT/$1" 2>/dev/null || echo "focused $1 failed"; }

echo; echo "=== Attach Porthole vào emulator đang chạy ==="
AVD=$(adb emu avd name 2>/dev/null | head -1 | tr -d '\r')
echo "AVD=$AVD"
porthole start "$AVD" --detach -q || porthole start --detach -q || echo "porthole start failed"
sleep 5
porthole list || true

echo; echo "=== Chẩn đoán mạng: phimapi qua proxy VN và trực tiếp (phía host runner) ==="
curl -sS -m 25 -x http://127.0.0.1:7891 -o /dev/null -w "phimapi VIA PROXY: HTTP %{http_code}\n" "https://phimapi.com/v1/api/danh-sach/phim-moi-cap-nhat?page=1&limit=1" || echo "phimapi VIA PROXY: CURL FAIL"
curl -sS -m 25 -o /dev/null -w "phimapi DIRECT: HTTP %{http_code}\n" "https://phimapi.com/v1/api/danh-sach/phim-moi-cap-nhat?page=1&limit=1" || echo "phimapi DIRECT: CURL FAIL"

echo; echo "=== Bắt đầu quay video toàn bộ quá trình test (adb screenrecord) ==="
# porthole record đã hỏng 2 lượt liên tiếp (run 7+8: record.log rỗng, không có file video)
# vì nó bám vào luồng H.264 của Porthole — stream chưa sẵn sàng là chết lặng không một dòng lỗi.
# Quay thẳng bằng screenrecord của chính emulator: độc lập, xác định. Trần 175s của Android
# vừa đủ kịch bản (~90-120s sau khi mở app); bắt đầu ngay trước khi mở app.
adb shell rm -f /sdcard/test-run.mp4
adb shell screenrecord --size 1280x720 --bit-rate 4000000 --time-limit 175 /sdcard/test-run.mp4 > "$OUT/record.log" 2>&1 &
REC_PID=$!
echo "screenrecord host-pid=$REC_PID (file trên emulator: /sdcard/test-run.mp4)"
sleep 2

echo; echo "=== Mở KenhLive ==="
porthole launch com.kenhlive.tv || adb shell monkey -p com.kenhlive.tv -c android.intent.category.LAUNCHER 1
sleep 12
# Khi mạng chạy, app có thể hiện CẢ hộp cập nhật (trên cùng) lẫn hộp THÔNG BÁO Telegram bên dưới.
# Đóng hộp cập nhật trước bằng nút ĐỂ SAU, rồi mới đóng hộp Telegram.
if porthole wait-for "Có bản mới"; then
  porthole focus-on "ĐỂ SAU" --select && ok UPDATE_POPUP "đã bấm ĐỂ SAU hộp cập nhật" || ok UPDATE_POPUP "FAIL — không bấm được ĐỂ SAU"
  sleep 2
else
  ok UPDATE_POPUP "không có hộp cập nhật"
fi
# Lần mở đầu tiên app hiện hộp THÔNG BÁO Telegram — đóng trước khi điều hướng.
if porthole wait-for "THÔNG BÁO"; then
  porthole focus-on "ĐÃ HIỂU / ĐÓNG" --select && ok WELCOME_POPUP "đã đóng hộp thông báo đầu phiên" || ok WELCOME_POPUP "FAIL — không đóng được hộp thông báo"
  sleep 2
else
  ok WELCOME_POPUP "không có hộp thông báo (đã từng đóng)"
fi
shot 01-hub.png
foc 01-focused.json

echo; echo "=== Vào hub Phim (focus-on ô 'PHIM TRUYỆN') ==="
if porthole focus-on "PHIM TRUYỆN" --select; then ok OPEN_PHIM "focus-on PHIM TRUYỆN + select OK"; else
  porthole dump-ui > "$OUT/hub-ui.json" 2>/dev/null || true
  ok OPEN_PHIM "FAIL — không focus-on được ô PHIM TRUYỆN (xem hub-ui.json)"
fi
if porthole wait-for "Tất Cả"; then ok PHIM_LOADED "thấy pill Tất Cả (hub phim đã tải)"; else
  # Có thể rơi vào màn lỗi tải (nút THỬ LẠI đang focus) — bấm thử lại một lần rồi chờ lại.
  echo "Chưa thấy pill Tất Cả — thử bấm THỬ LẠI (OK) rồi chờ lại 12s"
  porthole key 23 || true
  sleep 12
  if porthole wait-for "Tất Cả"; then ok PHIM_LOADED "thấy pill Tất Cả sau THỬ LẠI"; else ok PHIM_LOADED "FAIL — không thấy pill Tất Cả kể cả sau THỬ LẠI"; fi
fi
sleep 6
shot 02-phim-hub.png

echo; echo "=== Pill danh mục: focus 'Tất Cả' rồi sang phải ==="
porthole focus-on "Tất Cả" && ok PILL_FOCUS "focus-on Tất Cả OK" || ok PILL_FOCUS "FAIL"
foc 03-pill.json
shot 03-pill.png
porthole remote dpad_right || porthole key 22
sleep 1
foc 04-pill-next.json
shot 04-pill-next.png

echo; echo "=== Xuống thẻ phim đầu tiên ==="
porthole remote dpad_down || porthole key 20
sleep 1
foc 05-card.json
shot 05-card.png

echo; echo "=== Mở hộp thoại phim, test D-pad trong dialog, BACK, kiểm tra focus còn sống ==="
porthole key 23
sleep 5
shot 06-dialog.png
foc 06-focused.json
# Test D-pad thật trong dialog (sửa focus 2026-11-04): sang phải, xuống hàng cuối,
# xuống nữa phải sang nút Đóng, lên lại lưới tập. Các bước này không làm fail run
# nếu focus khác dự kiến — chỉ ghi screenshot + focused để soi tay sau.
porthole remote dpad_right || porthole key 22
sleep 1
shot 06b-dialog-right.png
foc 06b-focused.json
porthole remote dpad_down || porthole key 20
sleep 1
shot 06c-dialog-down.png
foc 06c-focused.json
porthole remote dpad_down || porthole key 20
sleep 1
shot 06d-dialog-close.png
foc 06d-focused.json
porthole remote dpad_up || porthole key 19
sleep 1
shot 06e-dialog-up.png
foc 06e-focused.json
porthole key 4
sleep 2
foc 07-after-back.json
shot 07-after-back.png
# Sau khi đóng dialog, focus phải còn sống và di chuyển được sang thẻ bên cạnh
# (đây chính là lỗi báo cáo 01:43-02:27: thẻ thu nhỏ, không sang được thẻ khác).
porthole remote dpad_right || porthole key 22
sleep 1
shot 07b-card-right.png
foc 07b-focused.json
porthole remote dpad_left || porthole key 21
sleep 1
shot 07c-card-left.png
foc 07c-focused.json

echo; echo "=== Dump UI o trang thai phim hub ==="
porthole dump-ui > "$OUT/phim-ui.json" 2>/dev/null || echo "dump phim-ui failed"

echo; echo "=== BACK ve hub chinh, roi vao hub The Thao ==="
# Phim hub can BACK kep trong cua so ngan: BACK 1 hien toast "Nhan BACK lan nua", BACK 2 moi ve hub chinh.
porthole key 4
sleep 1
porthole key 4
sleep 3
shot 08-hub-back.png
foc 08-focused.json
if porthole focus-on "THỂ THAO" --select; then ok OPEN_SPORTS "focus-on THE THAO + select OK"; else ok OPEN_SPORTS "FAIL - khong focus-on duoc THE THAO"; fi
sleep 8
shot 09-sports.png
foc 09-focused.json
porthole dump-ui > "$OUT/sports-ui.json" 2>/dev/null || echo "dump sports-ui failed"
porthole key 4
sleep 3
shot 10-hub-final.png
foc 10-focused.json

echo; echo "=== Mở trình phát live từ hub Thể Thao, test thanh trên gọn mới ==="
# Lúc này vẫn ở hub Thể Thao, focus ở nút [OK] Xem ngay (như ảnh 10 các run
# trước). Bấm OK để mở PlayerActivity. Các bước sau không làm fail run nếu
# luồng live chưa tải kịp — chỉ ghi screenshot + focused + dump-ui để soi tay.
# Lưu ý: video screenrecord giới hạn 175s nên phần trình phát này có thể nằm
# ngoài video, nhưng screenshot/focused/dump-ui vẫn là bằng chứng chính.
porthole key 23
sleep 12
shot 11-player.png
foc 11-player-focused.json
porthole dump-ui > "$OUT/player-ui.json" 2>/dev/null || echo "dump player-ui failed"
# Overlay tự ẩn sau ~3.5s nên bấm OK lần nữa để hiện lại thanh trên, focus
# lúc này phải về nút Tùy chọn phát (sau sửa: nút Chất lượng đã ẩn khỏi thanh).
porthole key 23
sleep 2
shot 11b-player-overlay.png
foc 11b-player-focused.json
# Bấm OK trên nút Tùy chọn phát để mở menu gọn mới.
porthole key 23
sleep 2
shot 11c-player-menu.png
foc 11c-player-focused.json
# BACK: đóng menu, ẩn overlay, thoát player về hub Thể Thao (BACK thừa ở hub
# chỉ hiện toast, không thoát app, nên 3 lần BACK là an toàn).
porthole key 4
sleep 1
porthole key 4
sleep 1
porthole key 4
sleep 3
shot 12-player-back.png
foc 12-player-focused.json

echo; echo "=== Kiểm tra crash ==="
if adb shell pidof com.kenhlive.tv > /dev/null 2>&1; then ok APP_ALIVE "tiến trình đang chạy"; else ok APP_ALIVE "FAIL — tiến trình đã chết"; fi
adb logcat -d -t 600 2>/dev/null | grep -iE "FATAL EXCEPTION|AndroidRuntime.*FATAL" | head -5 > "$OUT/crashes.txt" || true
adb logcat -d 2>/dev/null | grep -iE "phimapi|kkphim|UnknownHost|SSLHandshake|ConnectException|SocketTimeout|Unable to resolve host" | head -40 > "$OUT/netlog.txt" || true

echo; echo "=== Dừng quay video ==="
adb shell 'kill -2 $(pidof screenrecord)' 2>/dev/null || true
sleep 3
kill "$REC_PID" 2>/dev/null || true
adb pull /sdcard/test-run.mp4 "$OUT/test-run.mp4" >> "$OUT/record.log" 2>&1 || true
if [ -s "$OUT/test-run.mp4" ]; then
  ls -la "$OUT/test-run.mp4"
else
  echo "KHÔNG có file video — xem record.log"
fi
echo "checks done"
