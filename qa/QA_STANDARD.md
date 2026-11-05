# Chuẩn QA — KenhLive TV

Bộ test trên GitHub Actions, chạy sau mỗi thay đổi UI/logic quan trọng.
Nguyên tắc: **mỗi lần test xong phải nâng cấp QA theo đúng những gì lần test
vừa rồi phát hiện** — không để cùng một lỗ hổng đo kiểm tra lặp lại.

## 4 tầng test

| Tầng | Workflow | Việc làm | Kết quả |
|---|---|---|---|
| Build | `build.yml` (tự chạy khi push main) | Compile + đóng APK | `BUILD SUCCESSFUL` + artifact APK |
| Deterministic | `porthole-test.yml` | Porthole lái D-pad/focus, khẳng định màn hình cụ thể | `porthole-report` |
| Full QA | `qa-suite.yml` | QA suite tổng hợp trên emulator | `qa-report` |
| Video | `ui-tour.yml` | Quay 720p toàn bộ luồng UI + timeline | `ui-tour-video`: `rec.mp4` + ảnh từng bước |

## Chuẩn kết luận QA (thay Pilot từ 2026-11-05)

Pilot (Mobilerun) đã bỏ — chỉ dùng QA-Suite + Porthole + UI-Tour. QA-Suite là AI
agent tự chủ trong `qa/kenhlive_qa/` (missions + vision judge + đo jank/RAM +
quay video toàn trình), báo cáo chấm /100 trong `qa_report.json`:

1. **Kết quả đọc từ điểm + findings trong `qa_report.json`** (`qa/ci_report.py`),
   KHÔNG chỉ tin workflow xanh — workflow xanh chỉ nghĩa là script chạy xong.
2. **Fail-fast foreground**: QA/tour phải kiểm tra app còn foreground trước khi
   bấm tiếp. App rớt ra launcher mà script vẫn bấm mù sẽ quay ra màn hình
   launcher/YouTube vô nghĩa (đã xảy ra ở UI-Tour 2026-11-05).
3. **Launcher test riêng**: `:testlauncher` là HOME duy nhất trên emulator
   (chỉ mở KenhLive, BACK không thoát) + disable YouTube — QA-Suite và UI-Tour
   đều cài nó trước khi chạy.
4. **Phân biệt lỗi app vs lỗi đo kiểm tra**: trước khi kết luận app hỏng, kiểm
   tra tiêu chí đo còn khớp thiết kế hiện tại không (vd nút "Thoát" từng bị bỏ
   có chủ đích). Tiêu chí cũ → sửa mission/kịch bản, không sửa app.

## Mission mới nhất trong QA-Suite

- `MISSION_PLAYER_SETTINGS_DIALOG` (thêm 2026-11-05): dialog cài đặt thống nhất
  4 nhóm Hình ảnh/Âm thanh/Tỉ lệ/Bước tua, dòng "Đang chọn:", dấu ✓, chọn tỉ lệ
  áp dụng ngay, focus xuống nút Đóng, BACK đóng dialog không thoát player.

## Thêm/sửa kịch bản khi nào

- Đổi giao diện/chức năng mới (vd dialog cài đặt) → thêm kịch bản tương ứng
  trước khi coi thay đổi là "xong"
- Sửa thiết kế có chủ đích → sửa tiêu chí kịch bản trong cùng commit
- Lần test nào phát hiện lỗ hổng đo kiểm tra → ghi vào đây và vá trong cùng đợt
