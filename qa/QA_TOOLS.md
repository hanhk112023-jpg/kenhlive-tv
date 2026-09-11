# QA Agent Toolset (`qa/kenhlive_qa/qa_tools.py`)

Bộ tool chạy **local, miễn phí** để AI vision judge tự đo đạc thay vì đoán 1-shot
(tinh thần benchmark V*/HR-Bench: model cầm Python crop/zoom/measure).

| Tool | Việc | Ví dụ dùng |
|---|---|---|
| `zoom` | crop (x,y,w,h) + phóng `scale`× (LANCZOS), trả ảnh mới cho model | soi viền, chữ nhỏ |
| `scan_color` | tìm hàng/cột chứa chủ yếu 1 màu → **định vị** đường kẻ | "viền đỏ nằm ở y=540" |
| `measure` | đo **độ dày px** dải màu cắt qua 1 hàng/cột | "viền = 4px" |
| `pixel` | đọc RGB 1 điểm | kiểm tra màu accent #FF3B30 |
| `diff` | % khác nhau 2 screenshot | "bấm DOWN mà màn không đổi?" |
| `logcat` | dòng log khớp regex (cần adb) | lỗi ẩn ExoPlayer |
| `ui_dump` | uiautomator: class/bounds/text/focus (cần adb) | đọc đúng text thay vì OCR |

## Cách chạy trong judge
`ai_judge.judge_screen_agent(label, png_path, jpeg)`:
1. System = rubric + **"bắt buộc scan_color→measure trước khi kết luận viền/chữ"**
2. Model (Step 3.7 Flash → nemotron rotate, Kilo free) gọi tool qua `tool_calls`
3. Mỗi kết quả tool (text + ảnh zoom nếu có) đưa lại cho model, tối đa 5 lượt
4. Hết lượt → **ép** trả mảng JSON; kết quả qua `_coerce` (model trả key tiếng Việt vẫn nhận)
5. Fail toàn bộ → `judge_screen()` 1-shot cũ (crop+zoom tự động) là lưới an toàn

Env: `QA_AGENT=0` tắt agent; `KILO_MODEL=...` pin model; `QA_TOOLS_OUT` thư mục ảnh zoom.

## Đã verify
- Tools đo đúng ground-truth PIL (viền thiết kế 4-5px → model báo "4px")
- Agent loop end-to-end với ảnh QA thật: Step đo viền đúng nhưng hay lang thang
  → nemotron kết luận nhanh hơn trong bài rubric đầy đủ
- `run_qa.py`: mỗi màn hình lưu PNG **full-res** (`shots/raw_*.png`) cho tool đo
  (JPEG gửi model vẫn thumbnail 860px) — trên CI thì `logcat`/`ui_dump` có adb thật.
