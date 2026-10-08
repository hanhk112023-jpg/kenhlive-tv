# KenhLive 100% Autonomous AI QA Suite (Unified Brain v5.0)

Hệ thống kiểm thử tự trị 100% điều khiển bởi AI Agent dành riêng cho Android TV (10-foot UI):

---

## 🌟 Kiến trúc 100% Autonomous AI QA Agent

1. **Bộ não AI đa nguồn (Multi-Brain Cognitive Engine):**
   - **Antigravity Gateway:** Sử dụng model `gemini-3.8-flash-high` với năng lực Vision + Reasoning sâu để trực tiếp nhìn nhận màn hình và ra quyết định.
   - **Kilo Gateway:** Hỗ trợ model `inclusionai/ling-3.0-flash-vl:free` / `kilo-auto/free`.
   - **Local Autonomous Reflex Brain:** Fallback tự động khi offline, sử dụng cây quyết định phản xạ thông minh để CI không bao giờ treo.

2. **Cơ chế Tri Giác Toàn Diện (Multimodal Perception):**
   - **Vision Probes:** Chụp ảnh màn hình 1080p, phát hiện viền sáng Focus D-pad (`#FF6500` Cam hoặc Trắng/Cyan) với độ dày và độ tương phản cao.
   - **Video Frame Motion Detector:** Lấy mẫu 3 khung hình liên tiếp để đo đạc toán học xem ExoPlayer có đang render luồng video trực tiếp hay bị đứng hình / màn hình đen.
   - **UI Hierarchy Stream:** Bóc tách cây UI XML, định vị thẻ đang focus và các view lân cận.
   - **Logcat Realtime Triage:** Bắt ngay lập tức các sự kiện Crash FATAL, ANR, hoặc lỗi đứt mạng ExoPlayer.

3. **Hệ thống Nhiệm vụ Tự Động (AI Mission System):**
   - **Mission 1 (Cold Start):** Đo tốc độ mở app, splash screen và nạp trang chủ.
   - **Mission 2 (Thể Thao Đa Nguồn):** Duyệt bộ lọc theo đài thể thao (ColaTV, Gà Vàng, Khán Đài, Socolive), kiểm tra badge thương hiệu.
   - **Mission 3 (Video Player & Server Picker):** Kiểm tra chuyển động video, mở menu đổi nguồn phát (Server Picker dialog), xác thực failover.
   - **Mission 4 (IPTV TV360 & Lưới 5 Cột):** Điều hướng lưới Leanback 5 cột, Hero Preview EPG, giải mã HLS TV360.
   - **Mission 5 (Kho Phim NguonC):** Duyệt Phim Lẻ, Phim Bộ, Hoạt Hình Anime, mở phát player StreamC.
   - **Mission 6 (Lịch & Tìm Kiếm):** Tìm kiếm không dấu, kiểm tra chuỗi focus từ ô tìm kiếm sang danh sách kết quả.
   - **Mission 7 (D-pad Chaos Stress):** Nhồi phím liên tục 4 hướng, kiểm tra mép viền và chống mất focus.

4. **Báo cáo AI & Khắc phục lỗi:**
   - Xuất file HTML Dashboard `qa_report.html` và JSON `qa_report.json` kèm ảnh hiện trường.
   - Ghi lại toàn bộ dòng suy nghĩ (Thought Stream), hành động và đề xuất code Kotlin tương ứng.
