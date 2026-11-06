#!/usr/bin/env bash
# Porthole Live — đưa KenhLive ra WEB để điều khiển bằng trình duyệt, KHÔNG dùng AI.
#
# Chạy bên trong reactivecircus/android-emulator-runner (emulator đã boot, adb sẵn sàng).
# Việc làm:
#   1. Cấu hình emulator TV (root, proxy VN, 1920x1080)
#   2. Cài APK KenhLive
#   3. porthole start  -> phát màn hình emulator ra HTTP cổng 3200
#   4. cloudflared quick tunnel -> URL công khai https://xxx.trycloudflare.com
#   5. In URL ra log (Hermes đọc log để gửi link), giữ phiên sống N phút
#
# Người dùng chỉ cần mở link trên điện thoại/máy tính: thấy màn hình TV, bấm D-pad
# (lên/xuống/trái/phải/OK/Back/Home) bằng chuột hoặc bàn phím ngay trên web.
set -uo pipefail

OUT=/tmp/porthole-live
mkdir -p "$OUT"
PORT=3200
DURATION_MIN="${DURATION_MIN:-90}"
log() { echo "[$(date -u +%H:%M:%S)] $*" | tee -a "$OUT/live.log"; }

log "=== Porthole Live bắt đầu, giữ phiên ${DURATION_MIN} phút ==="

# ---------- 1. Cấu hình emulator ----------
log "--- Cấu hình emulator ---"
adb root >/dev/null 2>&1 || true
sleep 2
adb wait-for-device
if [ -n "${PROXY_PORT:-}" ]; then
  adb shell settings put global http_proxy "10.0.2.2:${PROXY_PORT}" || true
  log "proxy set 10.0.2.2:${PROXY_PORT}"
fi
adb shell wm size 1920x1080 || true
adb shell wm density 320 || true
adb shell settings put system accelerometer_rotation 0 || true
adb shell settings put system user_rotation 0 || true
adb shell settings put global window_animation_scale 1.0 || true
adb shell settings put global transition_animation_scale 1.0 || true
adb shell settings put global animator_duration_scale 1.0 || true

# ---------- 2. Cài APK ----------
log "--- Cài APK ---"
APK=$(ls /tmp/app-debug.apk 2>/dev/null | head -1)
if [ -z "$APK" ]; then
  log "::error::Không thấy /tmp/app-debug.apk"
  exit 1
fi
adb install -r -g "$APK" 2>&1 | tee -a "$OUT/live.log" || adb install -r "$APK" 2>&1 | tee -a "$OUT/live.log"
adb logcat -c || true
log "APK đã cài: $APK"

# ---------- 3. Bật Porthole ----------
log "--- Bật Porthole (cổng $PORT) ---"
AVD=$(adb emu avd name 2>/dev/null | head -1 | tr -d '\r')
log "AVD=$AVD"

# --host mặc định 127.0.0.1 (localhost): cloudflared chạy cùng máy nên gọi được,
# và Porthole không sinh token nên không cần ghép ?token= vào link công khai.
porthole start "$AVD" --port "$PORT" --detach -q --no-preview \
  > "$OUT/start.json" 2> "$OUT/start.err" || \
porthole start --port "$PORT" --detach -q --no-preview \
  > "$OUT/start.json" 2>> "$OUT/start.err"

cat "$OUT/start.json" 2>/dev/null | tee -a "$OUT/live.log"
cat "$OUT/start.err" 2>/dev/null | tee -a "$OUT/live.log" || true

TOKEN=$(python3 - << 'PY'
import json,re,sys
raw=open('/tmp/porthole-live/start.json').read()
try:
    d=json.loads(raw)
    u=d.get('url','')
except Exception:
    u=''
if not u:
    m=re.search(r'token=([A-Za-z0-9_\-]+)', raw)
    u=''
    print(m.group(1) if m else '')
else:
    m=re.search(r'token=([A-Za-z0-9_\-]+)', u)
    print(m.group(1) if m else '')
PY
)
log "TOKEN=${TOKEN:-<không có>}"

# Chờ HTTP server sống
for i in $(seq 1 30); do
  if curl -sS -m 3 -o /dev/null "http://127.0.0.1:$PORT/" 2>/dev/null; then
    log "Porthole HTTP OK sau ${i} lần thử"
    break
  fi
  sleep 2
done
porthole list 2>&1 | tee -a "$OUT/live.log" || true

# ---------- 4. Mở app ----------
log "--- Mở KenhLive ---"
porthole launch com.kenhlive.tv 2>&1 | tee -a "$OUT/live.log" || \
  adb shell monkey -p com.kenhlive.tv -c android.intent.category.LAUNCHER 1 2>&1 | tee -a "$OUT/live.log" || true
sleep 10
porthole screenshot -o "$OUT/01-launched.png" >/dev/null 2>&1 && log "đã chụp 01-launched.png"

# ---------- 5. Cloudflare tunnel ----------
log "--- Mở đường hầm công khai ---"
nohup /tmp/cloudflared tunnel --url "http://127.0.0.1:$PORT" --no-autoupdate \
  > "$OUT/cloudflared.log" 2>&1 &
CF_PID=$!
log "cloudflared pid=$CF_PID"

PUBLIC_URL=""
for i in $(seq 1 45); do
  PUBLIC_URL=$(grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' "$OUT/cloudflared.log" 2>/dev/null | head -1)
  [ -n "$PUBLIC_URL" ] && break
  sleep 2
done

if [ -z "$PUBLIC_URL" ]; then
  log "::warning::Không lấy được URL cloudflared — xem cloudflared.log"
  tail -20 "$OUT/cloudflared.log" | tee -a "$OUT/live.log" || true
  exit 1
fi

LIVE_URL="$PUBLIC_URL"

echo "$LIVE_URL" > "$OUT/URL.txt"
echo "================================================================"  | tee -a "$OUT/live.log"
echo "  LINK ĐIỀU KHIỂN TRỰC TIẾP: $LIVE_URL"                            | tee -a "$OUT/live.log"
echo "================================================================"  | tee -a "$OUT/live.log"
log "Link còn sống trong ${DURATION_MIN} phút kể từ bây giờ."

# Ghi vào Job Summary để mở xem ngay trên GitHub, không cần lội log
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  {
    echo "## 🖥 Porthole Live — điều khiển app qua web"
    echo ""
    echo "**Link:** $LIVE_URL"
    echo ""
    echo "Bắt đầu $(TZ=Asia/Ho_Chi_Minh date '+%H:%M:%S %d/%m/%Y') (giờ VN) — sống ${DURATION_MIN} phút."
    echo ""
    echo "Trên web: bấm ⬆⬇⬅➡ OK Back Home (hoặc phím mũi tên / Enter / Esc trên bàn phím)."
  } >> "$GITHUB_STEP_SUMMARY"
fi

# Kiểm chứng tunnel thật sự phục vụ được (không chỉ có URL)
for i in $(seq 1 15); do
  CODE=$(curl -sS -m 10 -o /dev/null -w '%{http_code}' "$LIVE_URL" 2>/dev/null || echo 000)
  log "kiểm tra tunnel lần $i: HTTP $CODE"
  [ "$CODE" = "200" ] && break
  sleep 4
done

# ---------- 6. Giữ phiên sống ----------
log "--- Giữ phiên (heartbeat mỗi 60s) ---"
END=$(( $(date +%s) + DURATION_MIN * 60 ))
N=0
while [ "$(date +%s)" -lt "$END" ]; do
  sleep 60
  N=$((N+1))
  if ! kill -0 "$CF_PID" 2>/dev/null; then
    log "::warning::cloudflared đã chết — dừng phiên"
    break
  fi
  # Ảnh chụp định kỳ để biết phiên còn sống và lưu bằng chứng
  if [ $((N % 5)) -eq 0 ]; then
    porthole screenshot -o "$OUT/hb-$N.png" >/dev/null 2>&1 || true
    log "heartbeat #$N — $(date -u +%H:%M:%S) — chụp hb-$N.png"
  fi
done

log "=== Kết thúc phiên (sau $N phút) ==="
porthole screenshot -o "$OUT/99-final.png" >/dev/null 2>&1 || true
kill "$CF_PID" 2>/dev/null || true
porthole kill -q 2>/dev/null || true
