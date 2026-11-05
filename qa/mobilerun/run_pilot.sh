#!/usr/bin/env bash
# Mobilerun pilot runner — executed INSIDE the android-emulator-runner step on the
# GitHub host: the TV emulator is up, the KenhLive debug APK is installed,
# `mobilerun` is on PATH, and env AI_MODEL + OPENAI_API_KEY are set.
# Each scenario in qa/mobilerun/scenarios/*.txt is one natural-language regression
# task; mobilerun exits 0/1 per task, which we record in qa_out_mobilerun/summary.md.
# This script always exits 0 — the pilot is informational; the summary is the result.
set -u

OUT=qa_out_mobilerun
mkdir -p "$OUT"

echo "== Mobilerun pilot =="
echo "Model: ${AI_MODEL:-<unset>}"
mobilerun --help >/dev/null 2>&1 || { echo "mobilerun CLI not available"; echo "SETUP FAIL" > "$OUT/summary.md"; exit 0; }

# Inject the Preflight-picked model into a runtime copy of the config.
# NOTE: the model id contains slashes (e.g. ag/gemini-3.8-flash-high) — the sed
# delimiter must be |, not / (with / the first pilot run produced an empty
# runtime config and every scenario died with "missing _version").
sed "s|__MODEL__|${AI_MODEL}|g" qa/mobilerun/config.yaml > qa/mobilerun/config.runtime.yaml
grep -m1 "model:" qa/mobilerun/config.runtime.yaml || true

# Install + enable the Mobilerun Portal on the emulator (downloads the Portal APK).
# The first attempt timed out on adb while pushing the ~51MB Portal APK to a
# freshly booted emulator, so: wait for boot completion, then retry up to 3x.
echo "== mobilerun setup (Portal) =="
adb wait-for-device
for i in $(seq 1 30); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 2
done
# Play Protect on the GMS TV emulator BLOCKS the Portal install with a modal
# "Blocked by Play Protect — This app may be harmful" dialog (seen in the
# run-2 final screenshots; the dialog also made setup look like an adb
# timeout). Disable package verification for adb installs BEFORE the first
# Portal attempt, and dismiss any leftover dialog.
adb shell settings put global package_verifier_enable 0
adb shell settings put global verifier_verify_adb_installs 0
adb shell input keyevent 66
adb shell input keyevent 4
SETUP_OK=0
for attempt in 1 2 3; do
  echo "--- setup attempt $attempt ---" >> "$OUT/setup.log"
  if mobilerun setup >> "$OUT/setup.log" 2>&1; then SETUP_OK=1; break; fi
  sleep 20
done
mobilerun ping >> "$OUT/setup.log" 2>&1 || true
tail -5 "$OUT/setup.log" || true
if [ "$SETUP_OK" != "1" ]; then
  {
    echo "# Mobilerun pilot — SETUP FAILED"
    echo ""
    echo "Portal không cài được lên emulator sau 3 lần thử — các kịch bản không chạy. Xem setup.log."
  } > "$OUT/summary.md"
  cat "$OUT/summary.md"
  exit 0
fi

SUMMARY="$OUT/summary.md"
{
  echo "# Mobilerun pilot — KenhLive regression scenarios"
  echo ""
  echo "- Model: ${AI_MODEL:-unknown}"
  echo "- Date (UTC): $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo ""
} > "$SUMMARY"

# --- Network isolation probe (added after pilot #4: posters still blank in CI
# even with the v0.1.2 image-loader fix, while the same URLs return 200 from
# outside). Question to answer: can the EMULATOR's own network stack load a
# phimimg.com poster at all (browser test), or is egress to that host broken
# in this environment (=> environment problem, not an app problem)?
{
  echo "== ping phimimg.com =="
  adb shell ping -c 2 phimimg.com || true
  echo "== ping phimapi.com =="
  adb shell ping -c 2 phimapi.com || true
} > "$OUT/probe.log" 2>&1
POSTER_URL=$(curl -s --max-time 25 "https://phimapi.com/v1/api/danh-sach/phim-moi-cap-nhat?page=1&limit=1" | python3 -c "
import json,sys
try:
    it = json.load(sys.stdin)['data']['items'][0]
    p = it.get('poster_url','')
    print(p if p.startswith('http') else 'https://phimimg.com/' + p.lstrip('/'))
except Exception:
    print('https://phimimg.com/uploads/movies/20261001/lego-one-piece-poster.webp')
" 2>/dev/null)
echo "probe poster URL: $POSTER_URL" >> "$OUT/probe.log"
adb shell am start -a android.intent.action.VIEW -d "$POSTER_URL" >> "$OUT/probe.log" 2>&1 || true
sleep 8
adb exec-out screencap -p > "$OUT/probe_browser.png" 2>/dev/null || true
adb shell input keyevent 3 || true
sleep 2

# Optional scenario filter: ONLY=01 runs only scenarios whose name starts with it.
for f in qa/mobilerun/scenarios/*.txt; do
  base0=$(basename "$f")
  if [ -n "${ONLY:-}" ] && [ "${base0#"$ONLY"}" = "$base0" ]; then
    echo "== skip $base0 (ONLY=$ONLY) =="
    continue
  fi
  name=$(basename "$f" .txt)
  echo "== Scenario: $name =="
  # QA standard (xem qa/QA_STANDARD.md): ket qua phai doc tu ket luan PASS/FAIL
  # cua agent trong log, KHONG chi tu exit code cua mobilerun (exit 0 ke ca khi
  # agent ket luan FAIL). Scenario nao FAIL hoac loi ha tang -> chay lai 1 lan
  # (attempt 2) roi moi chot ket qua; ghi ro RETRIED trong summary.
  attempt=1
  verdict="UNKNOWN"
  code=0
  while [ $attempt -le 2 ]; do
    timeout 1200 mobilerun run "$(cat "$f")" \
      -c qa/mobilerun/config.runtime.yaml \
      --vision --reasoning --steps 45 \
      --save-trajectory action \
      > "$OUT/$name.log" 2>&1
    code=$?
    adb exec-out screencap -p > "$OUT/$name-final.png" 2>/dev/null || true
    if grep -qaE "TEST RESULT: FAIL|Result: FAIL|^FAIL$" "$OUT/$name.log"; then
      verdict="FAIL"
    elif grep -qaE "TEST RESULT: PASS|Goal achieved: PASS|Goal achieved: TEST RESULT: PASS" "$OUT/$name.log"; then
      verdict="PASS"
    elif [ $code -eq 0 ]; then
      verdict="UNKNOWN(exit0)"
    else
      verdict="UNKNOWN(exit$code)"
    fi
    echo "   attempt $attempt: exit=$code verdict=$verdict"
    # PASS thi dung; FAIL/UNKNOWN thi quet lai 1 lan
    [ "$verdict" = "PASS" ] && break
    [ $attempt -eq 2 ] && break
    echo "   -> quet lai scenario $name (attempt 2)..."
    attempt=2
    cp "$OUT/$name.log" "$OUT/$name.attempt1.log"
  done
  if [ "$verdict" = "PASS" ]; then
    if [ $attempt -eq 2 ]; then
      echo "- ✅ $name — PASS sau khi quet lai (attempt 2, log: $name.log, attempt1: $name.attempt1.log)" >> "$SUMMARY"
    else
      echo "- ✅ $name — PASS (log: $name.log)" >> "$SUMMARY"
    fi
  elif [ "$verdict" = "FAIL" ]; then
    echo "- ❌ $name — FAIL xac nhan sau ${attempt} lan chay (log: $name.log)" >> "$SUMMARY"
  else
    echo "- ⚠️ $name — KHONG CO KET LUAN RO RANG ($verdict, exit=$code, log: $name.log) — can quet lai thu cong" >> "$SUMMARY"
  fi
done

# Best effort: collect saved trajectories next to the logs.
cp -r trajectories "$OUT/" 2>/dev/null || true

echo "" >> "$SUMMARY"
echo "Chi tiết từng kịch bản nằm trong file log tương ứng và ảnh chụp cuối mỗi kịch bản." >> "$SUMMARY"
cat "$SUMMARY"
exit 0
