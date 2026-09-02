# QA Click-Through — KênhLive

Tool tự động bấm qua mọi nút/tính năng trong app trên Android TV emulator để phát hiện:
- Nút bấm **không phản hồi** (không mở màn hình nào)
- **Crash** (FATAL EXCEPTION trong logcat)

## Chạy

1. Push lên main → **Actions → "QA Clicks" → Run workflow**.
2. Workflow dựng emulator TV x86 API30 (qua proxy VN), cài APK, chạy `qa/qa_click.py`:
   - `uiautomator dump` → tìm mọi node `clickable=true`
   - tap từng node (tránh trùng tọa độ), chờ 2.5s, chụp ảnh, bấm BACK quay lại
   - sau mỗi tap: đếm `FATAL EXCEPTION` mới trong logcat → ghi crash
   - nếu uiautomator không có: fallback **D-pad sweep**
3. Tải artifact `qa-clickthrough` gồm:
   - `qa_report.json` — kết quả từng tap (tested / crash)
   - `qa_crash.log` — logcat crash
   - `qa_shots/*.png` — ảnh mỗi màn sau khi bấm

## Output

`qa_report.json`:
```json
{
  "pkg": "com.kenhlive.tv",
  "tests": [{"screen":1,"text":"<label>","xy":[x,y],"crash":false}],
  "crashes": [{"screen":1,"text":"<label>","xy":[x,y],"sig":"<FATAL stacktrace>"}]
}
```

## Lưu ý

- Emulator chỉ chạy trong CI (host không có adb) → workflow phải được trigger thủ công.
- `uiautomator dump` trên image TV có thể bị chặn → script tự fallback D-pad.
- Ảnh chỉ là bằng chứng hành vi; crash là nguồn tin cậy nhất (lấy từ logcat).
