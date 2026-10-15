# 🚀 KENHLIVE TV v7.5.1 (Build 127) — TÍCH HỢP ĐỒNG BỘ LOGO ĐỘI BÓNG ĐA NGUỒN & HẠ TẦNG TẢI ẢNH TRỰC TIẾP

### 🛡️ 1. Đồng Bộ Logo Đội Bóng Thời Gian Thực (TeamLogoRegistry)
- **Đồng bộ tự động từ 4 nhà đài:**
  - Tự động bóc tách và đăng ký logo câu lạc bộ từ API chính thức của ColaTV (`img.gvapi.cc`), Gà Vàng 33, Giờ Vàng TV và lịch thi đấu Socolive (`sta.vnres.co`).
  - Tự động gắn logo đội nhà & đội khách vào các trận đấu Socolive vốn bị khuyết hình ảnh từ luồng phát gốc.
- **Kho Logo 4K Siêu Nét Từ ESPN CDN:**
  - Tích hợp sẵn cơ sở dữ liệu logo 500x500 PNG trong suốt sắc nét cho hơn 40 câu lạc bộ hàng đầu thế giới (Man City, MU, Liverpool, Arsenal, Chelsea, Real Madrid, Barca, Bayern, Dortmund, PSG, Juventus, Inter Miami, v.v.).
  - Tự động nhận diện tên đội bóng (kể cả các biến thể như `Man City U19`, `Real Madrid C`) để hiển thị logo chính thức.
- **Huy Hiệu Khiên Thể Thao Cao Cấp (Shield Crest Placeholder):**
  - Khi trận đấu của giải phong trào hoặc chưa tải kịp ảnh, thẻ hiển thị biểu tượng khiên thể thao sang trọng với viền bạc tinh tế `#4A5568` trên nền gradient obsidian, thay thế hoàn toàn biểu tượng bóng tròn đen cũ.

---

### ⚡ 2. Nâng Cấp Động Cơ Tải Ảnh Trực Tiếp (Coil NO_PROXY & Browser Headers)
- Cấu hình OkHttpClient độc quyền cho bộ tải ảnh Coil với chế độ `Proxy.NO_PROXY` trực tiếp:
  - Loại bỏ hoàn toàn lỗi **403 Forbidden** từ Cloudflare khi nạp logo từ các CDN quốc tế (`img.gvapi.cc`, `sta.vnres.co`, `a.espncdn.com`).
  - Gắn kèm User-Agent trình duyệt và Referer chính quy để tải ảnh mượt mà 100% trên mọi dòng Smart TV & Android Box.

---

### 📦 Tải & Cài Đặt:
- Tải trực tiếp file `KenhLive-v7.5.1.apk` bên dưới.
- Bản Universal APK gộp chung cả 32-bit (`armeabi-v7a`) và 64-bit (`arm64-v8a`), tương thích mọi dòng Android TV Box & Smart TV, cài đè giữ nguyên toàn bộ dữ liệu phim đã lưu và lịch sử xem.