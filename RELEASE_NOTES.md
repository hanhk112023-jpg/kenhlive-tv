# 🚀 KENHLIVE TV v7.4.9 (Build 125) — TÍCH HỢP TỈ SỐ TRỰC TIẾP REAL-TIME CHO 3 ĐÀI THỂ THAO & CÔNG NGHỆ DIRECT NO_PROXY SIÊU TỐC

### ⚽ 1. Tỉ Số Thời Gian Thực (Real-Time Live Scores) Đồng Bộ 3 Đài Thể Thao
- **Tích hợp API chính thức của 3 đài lớn:**
  - **ColaTV (`api.gvapi.cc/api/matches`):** Nạp toàn bộ hơn 85 trận đấu trực tiếp và sắp diễn ra, bóc tách mảng `homeScore` - `awayScore`.
  - **Gà Vàng 33 (`gavangtv-api.adviceme.io/api/v1/matches`):** Bóc tách mảng `homeScores` - `awayScores` cho các trận đang diễn ra (`matchStatus = "live"`).
  - **Giờ Vàng TV (`live-api.keonhacaitp.one/storage/livestream/live.json`):** Bóc tách đối tượng `score.fulltime.home` - `score.fulltime.away` và cả tỉ số các môn bóng rổ (EuroCup).
- **Hiển thị tỉ số trực quan nổi bật trên thẻ TV:**
  - Tỉ số hiển thị ngay tại ô điểm số trung tâm của thẻ trận (ví dụ: `0 : 1`, `1 : 2`, `22 : 18`) thay vì chữ `VS` mặc định.
  - Phân tích và chuẩn hóa tên giải đấu (`Cúp C1 Châu Âu`, `UEFA Youth League`, `Cúp Nga`, `JPN D2`...).
  - Đầy đủ huy hiệu trạng thái đỏ rực `• LIVE`, tên bình luận viên độc quyền (`[ColaTV] BLV POC`, `[Gà Vàng 33] Gà Ô Long`, `[Giờ Vàng] BLV MASON`...).

---

### ⚡ 2. Động Cơ Kết Nối Direct NO_PROXY — Tốc Độ Tải Tức Thì (< 200ms)
- **Cơ chế Direct Connection Engine (`Http.getDirectWithRetry`):**
  - Tự động bỏ qua proxy hệ thống (`Proxy.NO_PROXY`) đối với các API quốc tế của ColaTV, Gà Vàng 33 và Giờ Vàng TV.
  - Khắc phục triệt để lỗi nghẽn mạng, `connection closed` hoặc timeout do proxy trung gian.
- **Phân giải stream On-Demand:**
  - Danh sách hàng chục trận đấu được nạp và dựng giao diện tức thì chỉ trong ~200ms.
  - Luồng phát HLS m3u8 chất lượng cao (CDN `live05.zhwpg.app`, VCDN Cloud) được phân giải mượt mà khi người dùng bấm chọn xem trận đấu.
- **Cơ chế Fallback thông minh:** Tự động chuyển đổi sang nguồn phụ và M3U feed nếu một endpoint gặp sự cố, đảm bảo người dùng luôn xem được trận đấu.

---

### 📦 Tải & Cài Đặt:
- Tải trực tiếp file `KenhLive-v7.4.9.apk` bên dưới.
- Bản Universal APK gộp chung cả 32-bit (`armeabi-v7a`) và 64-bit (`arm64-v8a`), tương thích 100% mọi dòng Android TV Box & Smart TV, cài đè giữ nguyên toàn bộ dữ liệu phim đã lưu và lịch sử xem.