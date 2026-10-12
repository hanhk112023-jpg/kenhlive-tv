# 🚀 KENHLIVE TV v7.4.4 (Build 120) — SỬA TRIỆT ĐỂ LỆCH TIÊU ĐỀ & ỔN ĐỊNH 4 NHÀ ĐÀI THỂ THAO

### ⚽ 1. Khắc Phục Triệt Để Lỗi Lệch Tiêu Đề Trận Đấu & Phòng Live Gà Vàng 33
- **Căn nguyên**: Gà Vàng 33 API trả về danh sách có cả trận đang diễn ra (`matchStatus == "live"`) và các trận tương lai đêm nay / ngày mai (`scheduled`). BLV Gà Vàng dùng link stream cá nhân cố định và được gán cho nhiều trận. Trước đây app đưa cả các trận chưa đá vào, khi bấm vào xem thì BLV đang phát trận live hiện tại, dẫn đến tiêu đề và hình ảnh trên sân không khớp nhau.
- **Khắc phục**:
  - Lọc nghiêm ngặt: Chỉ lấy các trận **`matchStatus == "live"`** vào danh mục phát trực tiếp.
  - Mỗi phòng phát đúng link stream của BLV trận đó (`zktsva.app` `.m3u8`), lọc bỏ hoàn toàn các domain lỗi SSL.
  - Khớp 100% giữa tiêu đề trận đấu, logo 2 đội và nội dung video trên sân.

---

### 🥤 2. Khắc Phục Hoàn Toàn Đài ColaTV
- **Căn nguyên**: Trước đây app tạo phòng giả cho ColaTV từ danh sách Socolive rồi bốc ngẫu nhiên luồng stream, đồng thời nguồn M3U chứa cả tên miền chết `miekgo.app` (timeout) và định dạng `.flv`.
- **Khắc phục**:
  - Tải trực tiếp danh sách trận đấu thực tế của ColaTV từ M3U, chỉ lấy các trận có chấm xanh **`🟢` (Đang phát trực tiếp)**.
  - Chỉ nhận luồng HLS `.m3u8` từ máy chủ sống `zhwpg.app`, loại bỏ hoàn toàn tên miền chết `miekgo.app` và `.flv`.
  - Trích xuất tự động tên 2 đội bóng, logo giải đấu và tên BLV Cola (`BLV 7UP`, `BLV FANTA`, `BLV ROCKSTAR`, `BLV SODA`, `BLV PEPSI`).

---

### ⏰ 3. Tối Ưu Hóa & Ổn Định Đài Giờ Vàng TV
- Tải sẵn luồng phát trực tiếp HLS `.m3u8` từ hạ tầng VCDN Cloud (`vcdn.cloud`) và tên BLV chuẩn (`BLV Chồn`, `BLV Trĩ`, `BLV Tôm`...) ngay khi nạp danh sách trận.
- Bấm vào là phát tức thì không độ trễ, không còn tình trạng báo "Luồng phát chưa sẵn sàng".

---

### 🛡️ 4. Sửa Lỗi Tự Động Đổi Trận Khi Mạng Chập Chờn Trong Trình Phát TV
- Loại bỏ cơ chế failover chéo nhà đài bốc ngẫu nhiên luồng của trận khác khi gặp lỗi mạng.
- Khi mạng lag hoặc buffering, trình phát sẽ tự động kết nối lại chính xác luồng trực tiếp của trận đấu đang xem.

---

### 📦 Tải & Cài Đặt:
- Tải trực tiếp file `KenhLive-v7.4.4.apk` bên dưới.
- Tương thích tất cả dòng Android TV, Google TV, Android TV Box (Android 5.0 trở lên, chip 32-bit & 64-bit).
