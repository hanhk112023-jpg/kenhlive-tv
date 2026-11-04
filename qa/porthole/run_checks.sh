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

echo; echo "=== Bắt đầu quay video toàn bộ quá trình test ==="
# QUAN TRỌNG: record ghi file theo đường dẫn TUYỆT ĐỐI phía server (cwd của porthole server
# là thư mục gốc repo, không phải cwd của script này) — phải canonical hoá trước khi truyền.
REC_PATH="$(cd "$(dirname "$OUT")" && pwd)/$(basename "$OUT")/test-run.mp4"
# Trần 420s đủ cho toàn bộ kịch bản (~3 phút); dừng sớm bằng SIGINT ở cuối.
porthole record "$REC_PATH" --duration 420s > "$OUT/record.log" 2>&1 &
REC_PID=$!
echo "recording pid=$REC_PID path=$REC_PATH"
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

echo; echo "=== Mở hộp thoại phim, BACK, kiểm tra focus còn sống ==="
porthole key 23
sleep 5
shot 06-dialog.png
foc 06-focused.json
porthole key 4
sleep 2
foc 07-after-back.json
shot 07-after-back.png

echo; echo "=== Kiểm tra crash ==="
if adb shell pidof com.kenhlive.tv > /dev/null 2>&1; then ok APP_ALIVE "tiến trình đang chạy"; else ok APP_ALIVE "FAIL — tiến trình đã chết"; fi
adb logcat -d -t 600 2>/dev/null | grep -iE "FATAL EXCEPTION|AndroidRuntime.*FATAL" | head -5 > "$OUT/crashes.txt" || true
adb logcat -d 2>/dev/null | grep -iE "phimapi|kkphim|UnknownHost|SSLHandshake|ConnectException|SocketTimeout|Unable to resolve host" | head -40 > "$OUT/netlog.txt" || true

echo; echo "=== Dừng quay video ==="
kill -INT "$REC_PID" 2>/dev/null || true
wait "$REC_PID" 2>/dev/null || true
sleep 2
ls -la "$OUT/test-run.mp4" 2>/dev/null || echo "KHÔNG có file video — xem record.log"
echo "checks done"
