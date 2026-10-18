# 🚀 KENHLIVE TV v7.6.3 (Build 132) — TỐI ƯU HÓA TOÀN DIỆN BỘ NHỚ ĐỆM API & DỌN DẸP CACHE TRIỆT ĐỂ

### ⚡ 1. Quản Lý Bộ Nhớ Đệm NguonC & KKPhim Có Cơ Chế Dọn Sạch
- **Tích hợp `clearCache()` cho Kho phim**: Cho phép dọn sạch toàn bộ `detailCache`, `listCache` và `fallbackCache` của `NguoncRepository` khi nhận tín hiệu dọn dẹp hoặc khi hệ thống Android TV thiếu bộ nhớ.
- **Tự động giải phóng khi ẩn ứng dụng**: Kết nối `onTrimMemory` và `onLowMemory` từ Application cấp hệ thống tới toàn bộ các tầng Cache (Coil ImageLoader + NguoncRepository) giúp app chạy êm ái, không bao giờ bị Android TV tắt ngầm.
- **Nút Dọn bộ nhớ đệm trong Cài Đặt**: Dọn dẹp sạch sẽ cùng lúc cả 4 tầng: Coil Memory, Coil Disk, OkHttp Network Cache và NguoncRepository InMemory Cache.

---

# 🚀 KENHLIVE TV v7.6.2 (Build 131) — KIẾN TRÚC BỘ NHỚ CHUẨN FPT PLAY & NETFLIX CHO ANDROID TV BOX

### ⚡ 1. Quản Lý Bộ Nhớ Đệm Ảnh Coil 2 Tầng Cực Đại (RAM & Disk Cap 45MB)
- **Hạ trần Disk Cache xuống 45MB**: Cắt giảm từ 150MB xuống 45MB (`45L * 1024 * 1024`), giúp các dòng TV Box có bộ nhớ trong thấp (8GB/16GB) không bao giờ bị báo đầy ổ đĩa.
- **Tối ưu RAM Bitmap RGB_565 & Tắt Crossfade**: Tắt hoạt ảnh crossfade khi lướt danh sách phim, loại bỏ hoàn toàn hiện tượng tạo 2 Bitmap đồng thời trong RAM gây giật lag GPU Mali yếu và tràn heap.
- **Tự động giải phóng RAM khi ẩn màn hình**: Lắng nghe `onTrimMemory` ở các mức `TRIM_MEMORY_UI_HIDDEN`, `TRIM_MEMORY_BACKGROUND`, `TRIM_MEMORY_RUNNING_LOW` để xóa sạch memory cache ngay khi người dùng về Launcher TV hoặc chuyển app, chống 100% việc bị Android Low Memory Killer (LMK) tắt ngầm.

### 🧩 2. Tái Sử Dụng View Bậc Cao Với Shared RecycledViewPool
- **Chia sẻ chung RecycledViewPool**: Mọi hàng phim ngang (Horizontal Carousel) trong màn hình chính chia sẻ một ViewPool duy nhất (`sharedRowPool`), giảm hơn 60% số lượng ViewHolder và view allocation trong RAM.
- **Prefetch phần tử trước (`initialPrefetchItemCount = 4`)**: Giúp các poster phim được nạp sẵn vào bộ đệm trước khi lọt vào tầm mắt, di chuyển remote D-pad siêu mượt 60fps không giật khung hình.

### 🎬 3. Giới Hạn Video Buffer ExoPlayer & Giải Phóng Bộ Giải Mã Phần Cứng
- **Khống chế Buffer RAM tối đa 6MB**: Cấu hình `DefaultLoadControl` với `setTargetBufferBytes(6 * 1024 * 1024)` và `maxBufferMs = 8_000ms`, giữ mức tiêu thụ RAM của video 1080p luôn ổn định dưới 20MB.
- **Xả sạch luồng phát khi thoát Player**: Gọi `player.stop()` và `player.clearMediaItems()` trước khi `release()` để giải phóng tức thì các bộ đệm GraphicBuffer và MediaCodec phần cứng.

---

# 🚀 KENHLIVE TV v7.5.2 (Build 128) — GIAO DIỆN TÌM KIẾM PHIM TOÀN MÀN HÌNH CHUẨN NETFLIX & MA TRẬN POSTER 5 CỘT

### 🔍 1. Màn Hình Tìm Kiếm Phim Toàn Màn Hình Chuẩn Netflix (Full-Screen Overlay)
- **Nâng cấp từ hộp thoại thông thường sang giao diện Netflix chuyên nghiệp:**
  - Loại bỏ hoàn toàn hộp thoại nhập liệu nhỏ cũ.
  - Phủ toàn màn hình nền đen obsidian `#0B0E14` với thanh tìm kiếm nổi bật, viền phát sáng neon khi di chuyển remote TV.
  - Phím tắt xóa nhanh (`✖ Xóa chữ`), nút tìm kiếm nhanh và nút đóng trực quan.

---

### 🔥 2. Hàng Gợi Ý Xu Hướng Nhanh (Trending Keyword Chips)
- Tích hợp sẵn dãy nút bấm chủ đề thịnh hành tối ưu cho remote TV D-pad:
  - **One Piece**, **Thám Tử Conan**, **Ao no Hako (Blue Box)**, **Dan Da Dan**, **Doraemon**, **Dragon Ball**, **Lật Mặt**, **Tây Du Ký**, **Naruto**, **Solo Leveling**, **Jujutsu Kaisen**, **Bleach**...
  - Bấm chọn bất kỳ chủ đề nào bằng phím OK trên remote để xem kết quả ngay trong tích tắc mà không cần gõ chữ trên bàn phím ảo.
- Khi vừa mở tìm kiếm: Tự động hiển thị danh sách phim & anime thịnh hành được tìm kiếm nhiều nhất để màn hình luôn ngập tràn nội dung sống động.

---

### 🎬 3. Lưới Ma Trận Poster 5 Cột Chuẩn Điện Ảnh 4K (5-Column Grid)
- Kết quả tìm kiếm bung ra dạng **Lưới Poster 5 Cột** sắc nét trải đều trên màn hình lớn 1080p/4K:
  - Thẻ phim tỉ lệ 2:3 chuẩn rạp điện ảnh.
  - Đầy đủ huy hiệu: Điểm đánh giá sao vàng (`★ 8.8`), chất lượng độ phân giải (`4K` / `FHD`), số tập phát hành (`Tập 24`, `Trọn bộ 12/12`).
  - Hiệu ứng thu phóng **Netflix Zoom Effect** (`scale 1.065x`) cùng viền sáng nổi bật và bóng đổ độ sâu khi lướt remote.
  - Bấm chọn mở ngay bảng xem phim & chọn tập; nhấn giữ (Long Click) để thêm vào Lịch Sử Xem / Yêu Thích.

---

### 🕹️ 4. Điều Hướng Remote TV & Phím BACK Thông Minh
- Điều hướng D-pad trực quan: từ thanh nhập liệu nhấn XUỐNG để duyệt các chủ đề thịnh hành, nhấn tiếp XUỐNG để chọn từng poster trong lưới phim.
- Nhấn phím **BACK** 1 lần trên remote: Tự động đóng màn hình tìm kiếm và quay lại giao diện duyệt phim bình thường, không lo bị thoát ứng dụng ngoài ý muốn.

---

### 📦 Tải & Cài Đặt:
- Tải trực tiếp file `KenhLive-v7.5.2.apk` bên dưới.
- Bản Universal APK gộp chung cả 32-bit (`armeabi-v7a`) và 64-bit (`arm64-v8a`), tương thích mọi dòng Android TV Box & Smart TV, cài đè giữ nguyên toàn bộ dữ liệu phim đã lưu và lịch sử xem.
