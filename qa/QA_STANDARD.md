# Chuẩn QA — KenhLive TV

Bộ test trên GitHub Actions, chạy sau mỗi thay đổi UI/logic quan trọng.
Nguyên tắc: **mỗi lần test xong phải nâng cấp QA theo đúng những gì lần test
vừa rồi phát hiện** — không để cùng một lỗ hổng đo kiểm tra lặp lại.

## 4 tầng test

| Tầng | Workflow | Việc làm | Kết quả |
|---|---|---|---|
| Build | `build.yml` (tự chạy khi push main) | Compile + đóng APK | `BUILD SUCCESSFUL` + artifact APK |
| Pilot AI | `mobilerun-pilot.yml` | Mobilerun (AI nhìn màn hình, bấm D-pad) chạy kịch bản `qa/mobilerun/scenarios/*.txt` trên emulator Android TV | `mobilerun-report`: log + ảnh cuối + trajectory từng kịch bản |
| Deterministic | `porthole-test.yml` | Porthole lái D-pad/focus, khẳng định màn hình cụ thể | `porthole-report` |
| Full QA | `qa-suite.yml` | QA suite tổng hợp trên emulator | `qa-report` |
| Video | `ui-tour.yml` | Quay 720p toàn bộ luồng UI + timeline | `ui-tour-video`: `rec.mp4` + ảnh từng bước |

## Chuẩn kết luận Pilot (sửa sau pilot 2026-11-05)

1. **Kết quả đọc từ kết luận PASS/FAIL của agent trong log** (`TEST RESULT: PASS/FAIL`,
   `Goal achieved:`, `Result:`), KHÔNG chỉ từ exit code — mobilerun exit 0 ngay cả
   khi agent kết luận FAIL (đã xảy ra ở kịch bản 04). Summary ghi:
   - `✅ PASS` — kết luận PASS
   - `✅ PASS sau khi quét lại (attempt 2)` — lần 1 FAIL/lỗi model, lần 2 PASS
   - `❌ FAIL xác nhận sau N lần` — vẫn FAIL sau khi quét lại
   - `⚠️ KHÔNG CÓ KẾT LUẬN RÕ RÀNG` — log không có verdict, cần quét lại thủ công
2. **Quét lại tự động 1 lần**: kịch bản FAIL hoặc lỗi hạ tầng/model (vd model trả
   rỗng) thì `run_pilot.sh` chạy lại kịch bản đó 1 lần rồi mới chốt. Log lần 1
   giữ lại ở `*.attempt1.log`.
3. **Phân biệt lỗi app vs lỗi test**: trước khi kết luận app hỏng, đọc log xem
   tiêu chí kịch bản còn khớp thiết kế hiện tại không (vd kịch bản 04 từng đòi
   nút "Thoát" đã bị bỏ có chủ đích). Tiêu chí cũ → sửa kịch bản, không sửa app.
4. **Fail-fast**: tour/QA phải kiểm tra app còn foreground trước khi bấm tiếp.
   App rớt ra launcher mà script vẫn bấm mù sẽ quay ra màn hình launcher/YouTube
   vô nghĩa (đã xảy ra ở UI-Tour 2026-11-05). `ui_tour.sh` nay dừng ngay kèm
   logcat khi `am start` xong mà app không foreground.

## Kịch bản hiện có (`qa/mobilerun/scenarios/`)

| File | Kiểm tra |
|---|---|
| 01-posters | Poster thật, không ô trống |
| 02-phim-bo-grid | Lưới phim bộ, cuộn, badge |
| 03-episode-chips | Chip tập đủ chữ, đều kích thước, focus viền cam |
| 04-player-bar | Thanh điều khiển VOD 7 nút, không bị hint che (không còn nút Thoát — thoát bằng BACK) |
| 05-seek-stable | Vị trí phát tiến, không giật ngược |
| 06-player-settings-dialog | Dialog cài đặt thống nhất 4 nhóm, dòng "Đang chọn:", dấu ✓, chọn tỉ lệ áp dụng ngay |

## Thêm/sửa kịch bản khi nào

- Đổi giao diện/chức năng mới (vd dialog cài đặt) → thêm kịch bản tương ứng
  trước khi coi thay đổi là "xong"
- Sửa thiết kế có chủ đích → sửa tiêu chí kịch bản trong cùng commit
- Lần test nào phát hiện lỗ hổng đo kiểm tra → ghi vào đây và vá trong cùng đợt
