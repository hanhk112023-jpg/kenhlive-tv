# KenhLive Grand AI QA Suite (Unified v4.0)

Hệ thống kiểm thử tự trị toàn diện dành cho Android TV (10-foot UI), tích hợp các kỹ thuật SOTA mới nhất:

---

## 🌟 Kiến trúc cốt lõi

1. **State Transition Graph & TV D-Pad Focus Oracle (`qa/kenhlive_qa/grand_engine.py`):**
   - Đọc trực tiếp cấu trúc cây Accessibility / UI Hierarchy XML trong thời gian thực.
   - Giám sát vị trí Focus D-pad, phát hiện:
     - `FOCUS TRAP`: Kẹt nút điều hướng không thể thoát hoặc không di chuyển.
     - `FOCUS LOSS`: Mất dấu focus sau khi tương tác hoặc đóng Dialog / Player.
     - `DEAD SCREEN`: Màn hình đen xì hoặc không hiển thị nội dung do lỗi mạng hoặc treo tiến trình.

2. **AI Autonomous Exploration & Stress Hunter:**
   - Điều khiển ngẫu nhiên có phản xạ (Weighted Reflex + Jev System One / Ling-VL):
   - Tự động khám phá các ngóc ngách, menu, danh sách kênh/lịch thi đấu để tìm kiếm lỗi biên và rò rỉ bộ nhớ (Memory Leak).

3. **Bộ Probes chuyên sâu cho KenhLive TV (`qa/kenhlive_qa/run_qa.py`):**
   - **Cold Start:** Đo lường thời gian khởi động, kiểm tra blank screen và crash baseline.
   - **MultiView Verification:** Kiểm tra cơ chế chống tràn ô, viền trắng focus giữa 2 luồng phát (`--es open mv`).
   - **Player & PIP:** Kiểm tra luồng phát ExoPlayer và Picture-in-Picture (`--es open player`, `--es open pip`).
   - **IPTV Leanback Grid:** Kiểm tra lưới 5 cột, Hero Preview EPG và chuyển kênh trực tiếp.
   - **Auto-Refresh:** Chờ 3.5 phút đo độ lệch pixel kiểm tra tự động cập nhật danh sách trận.
   - **D-Pad Mash & Mép hàng:** Mash 32 lần 4 hướng và test trượt mép trái mở rail panel.

4. **Báo cáo trực quan:**
   - Xuất file HTML Dashboard `qa_report.html` và JSON `qa_report.json` kèm ảnh hiện trường.
   - Tự động hiển thị tóm tắt và danh sách lỗi lên GitHub Actions Step Summary qua `ci_report.py`.

---

## 🚀 Kích hoạt trên GitHub Actions

Vào **Actions** -> Chọn workflow **QA-Suite** -> Bấm **Run workflow**.
Sau khi hoàn tất, tải artifact `qa-report` để xem báo cáo HTML và toàn bộ ảnh chụp hiện trường.
