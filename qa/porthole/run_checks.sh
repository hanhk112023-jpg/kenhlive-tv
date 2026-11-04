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

echo; echo "=== Mở KenhLive ==="
porthole launch com.kenhlive.tv || adb shell monkey -p com.kenhlive.tv -c android.intent.category.LAUNCHER 1
sleep 12
shot 01-hub.png
foc 01-focused.json

echo; echo "=== Vào hub Phim (focus-on ô 'Phim') ==="
if porthole focus-on "Phim" --select; then ok OPEN_PHIM "focus-on Phim + select OK"; else
  porthole dump-ui > "$OUT/hub-ui.json" 2>/dev/null || true
  ok OPEN_PHIM "FAIL — không focus-on được ô Phim (xem hub-ui.json)"
fi
if porthole wait-for "DANH MỤC"; then ok PHIM_LOADED "thấy hàng DANH MỤC"; else ok PHIM_LOADED "FAIL — không thấy DANH MỤC"; fi
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
echo "checks done"
