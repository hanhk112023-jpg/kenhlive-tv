# 🚀 KENHLIVE TV v7.4.8 (Build 124) — LƯU TẬP & THỜI GIAN XEM CHI TIẾT, BẢO TOÀN DỮ LIỆU BỀN VỮNG KHI CÀI BẢN MỚI

### 🛡️ 1. Khắc Phục Triệt Để Lỗi Mất Phim & Tập Khi Cài Bản Mới / Nâng Cấp APK
- **Nâng cấp Database SQLite lên DB_VERSION 2:** Tự động tạo và kiểm tra schema (`ensureColumn`) cho bảng phim đã lưu (`favorite_films`), bảng lịch sử xem (`watch_history`) và bảng chi tiết từng tập (`watched_episodes`). Không bao giờ xảy ra lỗi thiếu bảng khi nâng cấp từ các bản cũ.
- **Hệ Thống Sao Lưu Đa Tầng Bền Vững (Dual-Layer Persistent Vault):**
  - Mọi thao tác lưu phim, đánh dấu tập hay cập nhật tiến độ xem đều được tự động đồng bộ tức thì sang **SharedPreferences** và tệp sao lưu JSON bền vững (`kenhlive_cinema_vault.json`) lưu tại bộ nhớ trong và bộ nhớ ngoài của thiết bị.
  - **Tự động khôi phục 100%:** Khi cài bản mới, cập nhật APK hoặc sau khi xóa dữ liệu, ứng dụng tự động nhận diện và khôi phục toàn bộ danh sách phim yêu thích, các tập đã lưu và thời gian xem mà người dùng không bị mất bất kỳ dữ liệu nào.
  - **Bảo vệ danh sách Đã Lưu:** Thuật toán dọn dẹp lịch sử thông minh không bao giờ xóa các bộ phim nằm trong mục Phim Đã Lưu.

---

### ⏱️ 2. Lưu Chi Tiết Từng Tập Phim & Thời Gian Xem (Giờ:Phút:Giây)
- **Lưu chính xác thời gian xem:** Ghi nhận chi tiết từng giây mili-giây, định dạng rõ ràng dạng `15:20 / 45:00 (34%)` hoặc `01:12:30 / 02:05:00` thay vì chỉ làm tròn phút.
- **Hiển thị trực quan trên giao diện:**
  - **Trên Poster phim:** Hiển thị nhãn tập và thời gian (ví dụ: `Tập 1 · 15:20/45:00`) kèm thanh tiến độ xem màu cam nổi bật.
  - **Trong Hộp thoại Chọn Tập (Episode Picker):** Nút lưu hiển thị rõ ràng `Đã Lưu (Tập 1 · 15:20/45:00)`, và trên từng ô chip tập phim hiển thị thời gian đang xem dở.
  - **Trên Billboard Hero:** Nút `✓ ĐÃ LƯU` tự động đính kèm tên tập và thời gian xem hiện tại.
- **Tự động lưu tiến độ liên tục (Auto-Save):**
  - Tự động lưu ngầm định kỳ mỗi 5 giây khi đang phát video.
  - Tự động lưu ngay lập tức vị trí mới khi bấm phím D-pad Trái/Phải để tua nhanh/tua lùi 15s.
  - Sử dụng tiến trình độc lập (`SupervisorJob`), đảm bảo dữ liệu ghi vào đĩa 100% thành công ngay cả khi người dùng bấm phím BACK thoát nhanh khỏi trình phát.

---

### 🎬 3. Tự Động Tiếp Tục Phát (Resume Playback) Tức Thì
- Khi bấm vào bất kỳ phim nào trong mục **TIẾP TỤC XEM** hoặc **BỘ PHIM ĐÃ LƯU**, trình phát native ExoPlayer sẽ tự động tua ngay đến chính xác vị trí phút/giây mà bạn đang xem dở.
- Người dùng có thể chọn bất kỳ tập nào trong danh sách, ứng dụng ghi nhớ riêng tiến độ của từng tập độc lập.

---

### 📦 Tải & Cài Đặt:
- Tải trực tiếp file `KenhLive-v7.4.8.apk` bên dưới.
