# 🚀 KENHLIVE TV v7.5.0 (Build 126) — THIẾT KẾ THẺ TRẬN ĐẤU MỚI TOÀN DIỆN & AUTO-REFRESH TỈ SỐ NGẦM 60S

### 🎨 1. Thiết Kế Lại Thẻ Trận Đấu Thể Thao Đẹp Mắt, Đẳng Cấp 4K Smart TV
- **Hộp Tỉ Số Sân Vận Động (Stadium Scoreboard Pill):**
  - Khi trận đang live có tỉ số: Hộp tỉ số chuyển sang phong cách Dark Neon thể thao với nền gradient cam đen sâu thẳm (`#2A170A` -> `#140A04`), viền cam neon 1.5dp, chữ số màu **Vàng Neon (#FFD700)** nổi bật rõ nét.
  - Khi trận sắp diễn ra: Hộp VS xám đá tinh tế sang trọng.
  - Kích thước mở rộng (56dp x 32dp), chữ số 14.5sp bold Oswald, đọc rõ ràng từ khoảng cách 3-5 mét trên màn hình TV lớn.
- **Huy Hiệu Nhà Đài Độc Quyền (Branded Source Tag):**
  - Bổ sung huy hiệu phân loại đài phát sóng ngay cạnh tên giải đấu:
    - 🔴 **COLATV:** Tag Đỏ Rose (`#E11D48`)
    - 🟡 **GÀ VÀNG:** Tag Hổ Phách (`#D97706`)
    - 🔵 **GIỜ VÀNG:** Tag Xanh Royal (`#2563EB`)
    - 🟢 **SOCOLIVE:** Tag Xanh Ngọc Emerald (`#059669`)
  - Giúp người dùng nhận biết ngay luồng phát và đài bình luận yêu thích.
- **Bố Cục Cân Đối Đối Xứng 100%:**
  - Tăng kích thước logo 2 đội bóng lên 38dp x 38dp.
  - Tên 2 đội hiển thị màu trắng tinh `#FFFFFF` font bán đậm `sans-serif-semibold`, luôn cân đối 2 bên bảng điểm.
- **Nền Thẻ Obsidian Glass:** Thẻ trận chuyển sang nền kính đen nhám (`#161A22` -> `#0B0D12`) viền hairline thanh thoát, tạo chiều sâu 3D khi lướt remote.

---

### 🔄 2. Tự Động Quét Ngầm Tỉ Số & Trận Mới Mỗi 60 Giây (Silent Auto-Refresh)
- Vòng lặp quét ngầm tự động kích hoạt khi người dùng mở mục Thể Thao (`LiveFragment`).
- Tự động lấy dữ liệu tỉ số và trận đấu mới nhất từ cả 3 đài (ColaTV, Gà Vàng 33, Giờ Vàng TV) song song không gián đoạn.
- Khi có bàn thắng hoặc có trận mới bắt đầu:
  - Tự động cập nhật điểm số mới trực tiếp lên màn hình qua `DiffUtil`.
  - **Hoàn toàn êm ái:** Không giật lag, không tải lại trang và giữ nguyên 100% vị trí con trỏ remote TV.

---

### 📦 Tải & Cài Đặt:
- Tải trực tiếp file `KenhLive-v7.5.0.apk` bên dưới.
- Bản Universal APK gộp chung cả 32-bit (`armeabi-v7a`) và 64-bit (`arm64-v8a`), tương thích mọi dòng Android TV Box & Smart TV, cài đè giữ nguyên toàn bộ dữ liệu phim đã lưu và lịch sử xem.