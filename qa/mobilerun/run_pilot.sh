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
sed "s/__MODEL__/${AI_MODEL}/g" qa/mobilerun/config.yaml > qa/mobilerun/config.runtime.yaml

# Install + enable the Mobilerun Portal on the emulator (downloads the Portal APK).
echo "== mobilerun setup (Portal) =="
mobilerun setup > "$OUT/setup.log" 2>&1 || mobilerun setup >> "$OUT/setup.log" 2>&1 || true
mobilerun ping >> "$OUT/setup.log" 2>&1 || true
tail -5 "$OUT/setup.log" || true

SUMMARY="$OUT/summary.md"
{
  echo "# Mobilerun pilot — KenhLive regression scenarios"
  echo ""
  echo "- Model: ${AI_MODEL:-unknown}"
  echo "- Date (UTC): $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo ""
} > "$SUMMARY"

for f in qa/mobilerun/scenarios/*.txt; do
  name=$(basename "$f" .txt)
  echo "== Scenario: $name =="
  timeout 1200 mobilerun run "$(cat "$f")" \
    -c qa/mobilerun/config.runtime.yaml \
    --vision --reasoning --steps 45 \
    --save-trajectory action \
    > "$OUT/$name.log" 2>&1
  code=$?
  adb exec-out screencap -p > "$OUT/$name-final.png" 2>/dev/null || true
  if [ "$code" -eq 0 ]; then
    echo "- ✅ $name — agent reported success (log: $name.log)" >> "$SUMMARY"
  else
    echo "- ❌ $name — exit code $code (log: $name.log)" >> "$SUMMARY"
  fi
  echo "   exit=$code"
done

# Best effort: collect saved trajectories next to the logs.
cp -r trajectories "$OUT/" 2>/dev/null || true

echo "" >> "$SUMMARY"
echo "Chi tiết từng kịch bản nằm trong file log tương ứng và ảnh chụp cuối mỗi kịch bản." >> "$SUMMARY"
cat "$SUMMARY"
exit 0
