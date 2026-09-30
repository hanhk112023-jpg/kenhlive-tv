# 🚀 KÊNHLIVE v7.0 — KẾ HOẠCH UPDATE (Android TV + Mobile)

> Mục tiêu: **đổi toàn bộ giao diện** (nút, điều hướng, màn xem), thêm chức năng mới và làm app **mượt** trên cả TV box yếu lẫn điện thoại.
> Phiên bản hiện tại: `6.6.1` (versionCode 104) → mục tiêu: `7.0.0` (versionCode 200).
> Tài liệu này được viết sau khi **đọc toàn bộ mã nguồn** (chưa chạy app/build trên thiết bị thật — các mục "cần đo" ở cuối phải xác minh trước khi chốt).

---

## 0. TÓM TẮT NHANH (đọc phần này nếu bận)

| # | Kết luận | Hành động chính |
|---|---|---|
| 1 | UI hiện tại là **1 bộ layout dùng chung, chỉnh bằng dimens** (phone ↔ TV). Giao diện TV thực chất là phone phóng to + rail bên trái | Làm **Design System mới** + tách rõ 2 trải nghiệm (TV 10-foot / Mobile touch) |
| 2 | **Player là điểm nghẽn lớn nhất**: `PlayerActivity.kt` 1154 dòng + `activity_player.xml` 634 dòng, 10 nút icon nhỏ xếp hàng, menu phone là `AlertDialog` dạng list, **không có** cử chỉ vuốt, **không có** immersive mode | Viết lại Player: control bar mới, bottom sheet (mobile) / side panel (TV), cử chỉ, immersive |
| 3 | Bấm xem → phải **đợi fetch stream** rồi mới mở Player (`LiveFragment.openRoom`) → cảm giác chậm, không có phản hồi | Mở Player ngay lập tức + prefetch stream URL + failover nhiều nguồn |
| 4 | Khởi động nguội luôn chờ mạng (cache chỉ trong RAM 60s) | Cache đĩa + "stale-while-revalidate" → vào app có nội dung ngay |
| 5 | Chưa có **Yêu thích / Lịch sử / Nhắc trận** dù đã có icon `ic_fav` (không được dùng) | Thêm nhóm tính năng cá nhân hoá (DataStore/Room) |
| 6 | Code UI rải rác: 133 drawable (nhiều cái trùng ý), ~50 chuỗi tiếng Việt hard-code, 214 `findViewById`, chỉ **9 unit test** | Dọn drawable, chuyển ViewBinding, đưa chuỗi vào `strings.xml`, bổ sung test |

**Khuyến nghị công nghệ (cần bạn chốt – xem mục 9, câu D1):** giữ **View system + RecyclerView** cho v7.0 (an toàn cho TV box 1–2GB/32-bit mà app đang nhắm tới), làm Design System mới bằng token + style; **spike 2 ngày** thử Jetpack Compose for TV trên 1 box yếu thật trước khi quyết định có chuyển ở v7.1 hay không.

---

## 1. PHÂN TÍCH HIỆN TRẠNG

### 1.1 Cấu trúc & quy mô

| Hạng mục | Số liệu đo được |
|---|---|
| Kotlin | 36 file, ~6.600 dòng |
| Layout XML | 37 file, ~3.150 dòng |
| Drawable | 133 file (phần lớn là `shape/selector` XML) |
| Stack | Kotlin 1.8.22, AGP 8.1.4, compileSdk/targetSdk 34, minSdk 21, AppCompat, RecyclerView, Media3 1.3.0, Coil 2.5, OkHttp 4.12 |
| Kiến trúc | 1 Activity chính + 5 Fragment (Live / Lịch / IPTV / Tìm / Cài đặt) + `PlayerActivity` + `MultiViewActivity`; ViewModel + StateFlow cho Live/Schedule/Search |
| Nguồn dữ liệu | Socolive JSON (`json.vnres.co`), IPTV m3u (iptv-org), update qua Cloudflare Worker |
| Test | 1 file `SocoliveParserTest` (9 test). Có bộ QA python chạy emulator trên CI (`qa/`) |

Điểm tốt nên **giữ**: phát hiện TV/phone (`DeviceMode`), ViewModel + `UiState`, single-flight cache (`SocoliveRepository`), `Http` dùng chung, `Enhancer` (load control live, EQ âm thanh), `UpdateManager` (đã vá nhiều lỗi), `FocusKit` (logic D-pad), bộ QA trên CI.

### 1.2 Vấn đề UI/UX (theo màn hình)

**Điều hướng (`MainActivity`, `layout/`, `layout-television/`)**
- Phone: top bar + bottom nav 5 mục (Live, Lịch, IPTV, Tìm, Cài đặt). TV: rail icon-only 60dp, **ẩn mặc định** và chỉ hiện khi bấm Trái → người dùng TV khó phát hiện menu, không có nhãn chữ.
- Tablet (`sw600dp`) chỉ phóng to phone, chưa có bố cục thích ứng (rail + 2 cột).
- `MainActivity` còn chứa code debug (`handleDebugIntent`, `open=mv|pip|...`) lẫn vào logic thật.
- Tab chuyển bằng `show/hide` Fragment, không có hiệu ứng chuyển cảnh → cảm giác "cứng".

**Trang chủ Live (`LiveFragment`, `SportAdapter` 379 dòng, `item_hero`, `item_match_card`…)**
- Một `RecyclerView` dọc chứa hero + rail lồng (RecyclerView trong RecyclerView) + danh sách theo ngày: nặng khi refresh, phải dùng `FocusKit` retry-polling (80ms × 6) để giữ focus → mong manh.
- `LiveFragment.rebuild()` (map icon, lọc giải, gom nhóm) chạy ở main thread mỗi lần 1 trong 2 state đổi.
- Hero dùng đồng hồ + lịch âm dương: trang trí nhiều, chiếm diện tích đắt giá trên điện thoại.
- Chưa có: Yêu thích, "Tiếp tục xem", lọc nhanh theo giải trên TV bằng phím màu, nhắc trận.

**Player (`PlayerActivity`, `activity_player.xml`)** — *vấn đề nặng nhất*
- 1 class làm tất cả: OSD, sidebar kênh, carousel kênh, quick-OSD, HUD stats, sleep timer, audio boost, PiP, phím số… (1154 dòng) → khó sửa, dễ lỗi.
- Top bar TV có **10 nút icon** cùng cỡ (chất lượng, âm thanh, tỉ lệ, hẹn giờ, track, boost, stats, multi, PiP, danh sách kênh) — không phân cấp, không nhãn.
- Phone: menu "Tùy chọn phát" là `AlertDialog` list 8 dòng (`showPhoneMoreOptions`), không phải bottom sheet; các nút nhỏ bị ẩn đi vào menu.
- **Không có** cử chỉ: vuốt dọc trái = độ sáng, phải = âm lượng, chạm đúp = pause/zoom, vuốt xuống thu nhỏ.
- **Không có immersive mode** (không thấy `WindowInsetsController`/`systemUiVisibility` trong code) → thanh hệ thống có thể lộ khi xem toàn màn hình trên phone.
- Toast dùng để báo mọi trạng thái ("Tạm dừng", "Tiếp tục phát"…) → nhiễu; nên dùng OSD nhỏ.
- Nút Play/Pause giữa màn hình chỉ có ở phone; TV dùng OK để hiện OSD → hành vi OK không nhất quán giữa IPTV và trận.
- Player bị `release()` ở `onStop` và khởi tạo lại ở `onStart` → quay lại từ app khác phải tải lại luồng.
- Sửa tỉ lệ: `RESIZE_MODE_FIXED_WIDTH` được gọi "Cố định (Fixed)" — tên và hành vi chưa rõ với người dùng.

**Lịch / IPTV / Tìm kiếm / Cài đặt**
- Lịch: 7 ngày tải song song (tốt) nhưng không có chip chọn ngày, không lọc "đang live / sắp diễn ra", không đặt nhắc.
- IPTV: kênh lấy từ iptv-org, không có Yêu thích/kênh gần đây, không kiểm tra kênh chết (nhiều link iptv-org hỏng), không nhóm theo thể loại rõ ràng.
- Cài đặt: chỉ 5 dòng (chất lượng hình, chế độ âm thanh, bố cục multiview, xoá cache, cập nhật) + 1 dòng version → thiếu: giao diện, giọng nói/ngôn ngữ, mặc định tỉ lệ, bộ đệm, giới hạn dữ liệu, "thông tin máy".
- Dialog (thoát, chọn BLV, update) dùng `AlertDialog` – chưa đồng bộ với phong cách mới.

**MultiView (`MultiViewActivity`, 466 dòng)**
- Đã có chia 2/4 ô, tiếng riêng từng ô, tự phục hồi — cần giữ logic, **làm lại khung giao diện** (viền focus, thanh điều khiển ô, chọn trận nhanh).

### 1.3 Vấn đề hiệu năng / độ mượt

| # | Vấn đề | Vị trí | Hậu quả |
|---|---|---|---|
| P1 | Fetch stream **sau khi bấm** rồi mới `startActivity` | `LiveFragment.openRoom/onFixtureTap`, `SocoliveRepository.fetchStream` | Bấm xong 1–3 giây không phản hồi |
| P2 | `parseStream` chỉ trả về **1 URL** (hdM3u8 → m3u8 → hdFlv → flv) | `SocoliveParser.parseStream` | Khi nguồn chết chỉ retry lại cùng URL (`streamRetries`), không chuyển nguồn dự phòng. *(commit gần nhất nhắc "instant failover" — cần xác minh trong code nó thực sự chuyển nguồn hay chỉ `prepare()` lại)* |
| P3 | Cache Live/lịch chỉ trong RAM (TTL 60s / 5 phút) | `SocoliveRepository` | Mở app nguội luôn hiện skeleton chờ mạng |
| P4 | Xử lý dữ liệu nặng trên main thread khi state thay đổi | `LiveFragment.rebuild()` | Giật khi auto-refresh 3 phút |
| P5 | Focus TV dựa vào retry-polling và scroll rồi thử lại | `FocusKit.retry/restore` | Focus nhảy/lạc khi dữ liệu refresh; khó mở rộng |
| P6 | RecyclerView lồng nhau + `DiffUtil` so sánh gần như toàn bộ (`areContentsTheSame` bằng `map{}`) | `SportAdapter` | Tạo object thừa mỗi lần diff |
| P7 | `UpdateManager` tự tạo `OkHttpClient()` riêng; `Http.getWithRetry` dùng `Thread.sleep` | `UpdateManager`, `Http` | Không dùng chung connection pool; chặn luồng IO |
| P8 | Hiệu ứng focus (scale 1.06 + elevation) áp cho mọi thẻ, kể cả máy yếu | `FocusKit.decorateCard` | Tụt FPS trên box yếu (chỉ tắt `itemAnimator` khi `lowRam`) |
| P9 | 133 drawable XML `shape/selector` — nhiều cái gần như trùng (`bg_button_*`, `btn_*`, `card_a/b`, `bg_nav_item*`) | `res/drawable` | Inflate chậm, khó đổi theme, dễ lệch style |
| P10 | Không có Baseline Profile / Startup profile; Application init đồng bộ `DeviceMode` + `Http` | `KenhLiveApp` | Khởi động lạnh chậm trên máy yếu |
| P11 | Player tạo mới `ExoPlayer` mỗi lần vào xem, chuyển kênh IPTV cũng vậy | `PlayerActivity.initPlayer` | Zapping chậm |

### 1.4 Rủi ro kỹ thuật cần dọn cùng lúc

- `build.gradle` (root) có block **CI DIAGNOSTICS** dùng `GITHUB_TOKEN` tự đẩy log lỗi lên một branch cố định (`arena/01a094ce-kenhlive-tv`). Nên chuyển sang upload artifact bằng workflow, bỏ code gọi GitHub API trong Gradle.
- `file_paths.xml` mở `external-path "."` (toàn bộ thẻ nhớ) cho FileProvider — chỉ cần thư mục `updates/`.
- `usesCleartextTraffic="true"` toàn app (cần vì IPTV/HTTP stream) → nên giới hạn bằng `networkSecurityConfig` (HTTPS cho API, HTTP chỉ cho stream).
- `allowBackup="true"`; chưa có `dataExtractionRules` cho Android 12+.
- `targetSdk 34` đã cũ — Google Play nâng yêu cầu `targetSdk` mỗi năm (cần kiểm tra mức yêu cầu hiện hành nếu định lên Play); APK sideload không bắt buộc nhưng nên kiểm tra hành vi Android 15+ (edge-to-edge bắt buộc).
- Kotlin 1.8.22 đã cũ; nâng Kotlin/AGP là điều kiện nếu muốn Compose/Baseline Profile plugin.
- Chuỗi tiếng Việt hard-code trong Kotlin (20 `Toast`, 33 `text = "…"`), cản trở đa ngôn ngữ.

---

## 2. ĐỊNH HƯỚNG THIẾT KẾ v7.0

### 2.1 Nguyên tắc
1. **Hai trải nghiệm, một lõi logic:** TV = "10-foot UI" (ít chữ, thẻ lớn, focus rõ, không cần chạm); Mobile = "thumb UI" (thao tác 1 tay, bottom sheet, cử chỉ).
2. **Nội dung là nhân vật chính:** giảm trang trí (đồng hồ + lịch âm ở hero), tăng ảnh trận/logo đội/trạng thái LIVE.
3. **Mọi thao tác xem ≤ 2 lần bấm** từ trang chủ; đổi nguồn/chất lượng/âm thanh ≤ 3 lần bấm.
4. **Một ngôn ngữ thiết kế:** toàn bộ màu/kích thước/bo góc/bóng lấy từ token, không hard-code.
5. **Mượt > đẹp:** hiệu ứng nặng chỉ bật khi `!lowRam`.

### 2.2 Design tokens (thay cho `colors.xml`/`dimens.xml` cũ + alias legacy)

| Nhóm | Đề xuất |
|---|---|
| Màu nền | Giữ nền đen (OLED) `#000000` → bề mặt `#0D0E12 / #13151B / #1E2430` (đã khá tốt) |
| Màu thương hiệu | Xanh lục `#00E676` (chủ đạo), **LIVE đỏ** `#FF334B`, focus **trắng + viền cyan** (đã có `kl_focus_ring`); bỏ amber/purple/indigo không dùng |
| Chữ | Display: Oswald (giữ), Body: Roboto/Inter; chỉ 6 cấp cỡ chữ (Display/Title/Body/Meta/Badge/Button) cho mỗi chế độ |
| Bo góc | 3 mức: 8 / 16 / 24dp (TV: 12 / 20 / 28) |
| Khoảng cách | Lưới 4dp (mobile), 8dp (TV); lề an toàn TV 48dp (overscan) |
| Chuyển động | 3 thời lượng: 120 / 200 / 320 ms, interpolator `FastOutSlowIn` |
| Focus TV | Viền 3dp + phóng 1.04 + sáng nền; **không** dùng bóng (elevation) trên `lowRam` |

Việc dọn: hợp nhất 133 drawable → khoảng 30–40 (dùng `MaterialShapeDrawable`/style + tint theo token); xoá `brand_*`, `accent_*`, `bg_*` alias đã thay.

### 2.3 Mobile — sơ đồ màn hình

```
Bottom nav (4): Trang chủ · Lịch · TV · Của tôi      (Tìm kiếm = icon trên top bar, Cài đặt nằm trong "Của tôi")
Trang chủ: [Top bar: logo · tìm · chuông nhắc]
           [Chip giải: Tất cả · Bóng đá · Bóng rổ · … (sticky)]
           [Hero carousel 16:9: trận tâm điểm, LIVE, nút ▶ Xem]
           [Đang live  ← thẻ dọc lớn 2 cột, badge LIVE + số người xem]
           [Sắp diễn ra ← đếm ngược + nút 🔔]
           [BLV nổi bật ← avatar tròn]
Player:    [Dọc: video 16:9 trên + nội dung dưới (trận liên quan/BLV khác)]
           [Ngang: toàn màn hình, immersive, cử chỉ]
           [Bottom sheet "Cài đặt": Nguồn · Chất lượng · Âm thanh · Tỉ lệ · Hẹn giờ · Thống kê]
```

### 2.4 TV — sơ đồ màn hình

```
Top tab bar ngang (Trang chủ · Lịch · TV · Tìm · Cài đặt)  — thay rail ẩn, luôn thấy, có nhãn chữ
Trang chủ: Hero lớn (ảnh + 2 nút: ▶ Xem ngay | ➕ Yêu thích) + hàng thẻ
           Hàng: Đang live → Sắp diễn ra → Yêu thích/Gần đây → BLV → Theo giải
           Phím màu: 🔴 Multiview · 🟢 Lọc giải · 🟡 Yêu thích · 🔵 Làm mới
Player:    OK = thanh điều khiển dưới (nhóm nút có chữ: Nguồn · Chất lượng · Âm thanh · Tỉ lệ · Thêm)
           ↑ = danh sách kênh/trận (panel phải) · ↓ = thông tin trận · INFO = thống kê
           Phím số = nhảy kênh (đã có, giữ)
```

---

## 3. PHẠM VI THAY ĐỔI THEO MÀN HÌNH

### 3.1 Khung ứng dụng (Shell)
- [ ] Thay `MainActivity` bằng shell mới: **bottom nav (mobile)** / **top tabs (TV)** / **nav rail + 2 cột (tablet ≥600dp)**.
- [ ] `ViewPager2`/`FragmentContainer` với hiệu ứng chuyển tab (fade + slide 200ms), giữ state từng tab.
- [ ] Tách code debug (`handleDebugIntent`) sang `DebugIntents.kt` chỉ nằm trong `debug` sourceSet.
- [ ] Màn hình chờ dùng **SplashScreen API** (Android 12+ + compat) thay cho nền đen trống.
- [ ] Edge-to-edge chuẩn (chuẩn bị nâng targetSdk).
- [ ] Dialog thống nhất: `KLDialog` (thoát, cập nhật, chọn BLV) đúng phong cách mới, hỗ trợ D-pad.

### 3.2 Trang chủ Live
- [ ] Bỏ hero đồng hồ + lịch âm (chuyển thành widget nhỏ tuỳ chọn trong Cài đặt); hero mới dùng ảnh trận + logo 2 đội + nút hành động.
- [ ] Tách các hàng thành `ConcatAdapter` / `ListAdapter` đơn giản, **đưa logic `rebuild()` sang `ViewModel` (Dispatchers.Default)**, UI chỉ nhận `HomeUiState` đã dựng sẵn.
- [ ] Thẻ trận mới (mobile dọc / TV ngang): logo 2 đội, giải, badge LIVE, số người xem, nút ♥.
- [ ] Hàng mới: **Tiếp tục xem**, **Yêu thích**, **Sắp diễn ra (đếm ngược)**.
- [ ] Skeleton shimmer khớp đúng khung thẻ thật; không nhảy layout khi dữ liệu về.
- [ ] Pull-to-refresh (mobile), phím màu Xanh dương (TV).

### 3.3 Player (viết lại — ưu tiên #1)
Tách `PlayerActivity` thành các thành phần nhỏ:

```
player/
  PlayerActivity.kt          (chỉ lắp ráp + vòng đời, < 250 dòng)
  PlayerController.kt        (ExoPlayer, failover, retry, stats)
  PlayerSession.kt           (giữ player qua onStop/PiP/xoay màn hình)
  ui/ControlsOverlayTv.kt    (thanh điều khiển TV + panel kênh)
  ui/ControlsOverlayMobile.kt(controller + gesture)
  ui/SettingsSheet.kt        (bottom sheet mobile / side panel TV)
  ui/StatsHud.kt
  gestures/PlayerGestures.kt (vuốt sáng/âm lượng, chạm đúp)
  prefs/PlayerPrefs.kt       (nhớ tỉ lệ, chất lượng, nguồn)
```

Chức năng & giao diện:
- [ ] **Mở ngay**: nhận `roomNum` (không chờ URL) → hiển thị khung + spinner tức thì, tải stream trong Player.
- [ ] **Immersive mode** + tự ẩn thanh hệ thống; `keepScreenOn` giữ nguyên.
- [ ] **Mobile:** vuốt dọc nửa trái = độ sáng, nửa phải = âm lượng (có OSD nhỏ), chạm đúp = tạm dừng, pinch = zoom/fit, nút khoá xoay, chế độ dọc có danh sách trận/BLV liên quan bên dưới.
- [ ] **Mobile:** thay `AlertDialog` "Tùy chọn phát" bằng **Bottom Sheet** nhóm: *Nguồn phát · Chất lượng · Âm thanh · Hình ảnh · Hẹn giờ · Nâng cao*.
- [ ] **TV:** thanh điều khiển dưới có **chữ** cho 5 nhóm nút chính thay vì 10 icon trần; các nút phụ (Stats, Audio boost, Track) vào "Thêm".
- [ ] **TV:** panel phải "Kênh / Trận khác" (đã có sidebar → thiết kế lại); giữ phím số, INFO, phím màu.
- [ ] **Chọn nguồn phát** (Nguồn 1 HD m3u8 / Nguồn 2 m3u8 / Nguồn 3 flv) — xem mục 5.2.
- [ ] Thông báo trạng thái dùng **OSD chip** (đang đệm / đã đổi nguồn / mất mạng) thay cho Toast.
- [ ] Badge chất lượng thật (độ phân giải/bitrate) và độ trễ so với live; nút "Về Live" khi trễ > 5s.
- [ ] PiP (mobile) giữ; thêm nút điều khiển trong PiP (play/pause) bằng `RemoteAction`.
- [ ] Phát nền chỉ âm thanh (tuỳ chọn, cần `MediaSessionService` + thông báo) — *giai đoạn sau, xem mục 8 (P2)*.
- [ ] Đổi tên tỉ lệ rõ nghĩa: *Vừa khung · Lấp đầy · Phóng to · 16:9 · 4:3* (bỏ `FIXED_WIDTH` khó hiểu).

### 3.4 Lịch trình
- [ ] Dải chip ngày cuộn ngang (Hôm nay · Ngày mai · T.Năm…) thay cho list dài liên tục.
- [ ] Bộ lọc: *Tất cả · Đang live · Sắp diễn ra · Yêu thích* + lọc giải.
- [ ] Nút 🔔 **Nhắc trận** (xem 4.3) và ♥ theo dõi đội/giải.
- [ ] Hàng trận mới, cùng component thẻ với Trang chủ.

### 3.5 Truyền hình (IPTV)
- [ ] Bố cục 2 cột TV: nhóm kênh (trái) — lưới kênh (phải); mobile: chip nhóm + lưới 2–3 cột.
- [ ] **Yêu thích** và **Gần đây**; đánh dấu kênh chết (ẩn tự động sau N lần lỗi, lưu cục bộ).
- [ ] Số kênh (LCN) và EPG hiện tại/kế tiếp trên thẻ (đã có `EpgRepository` — nối vào UI mới).
- [ ] Tìm kênh nhanh bằng bàn phím/giọng nói (TV: `SpeechRecognizer` nếu có).

### 3.6 Tìm kiếm
- [ ] Gợi ý theo lịch sử + trận đang live; kết quả nhóm theo *Trận · BLV · Kênh*.
- [ ] TV: bàn phím on-screen bố cục riêng (A–Z lưới) thay vì mở IME hệ thống.

### 3.7 Cài đặt (mở rộng)
Nhóm: **Phát** (chất lượng, tỉ lệ mặc định, bộ đệm *Ổn định/Cân bằng/Độ trễ thấp*, tự đổi nguồn) · **Âm thanh** (chế độ EQ, boost) · **Giao diện** (widget đồng hồ/lịch âm, cỡ chữ TV, giảm hiệu ứng) · **Thông báo** (nhắc trận) · **Dữ liệu** (xoá cache, giới hạn dữ liệu di động) · **Về ứng dụng** (phiên bản, thông tin máy, kiểm tra cập nhật, changelog).

### 3.8 MultiView
- [ ] Giữ toàn bộ logic phục hồi/âm lượng từng ô; làm lại khung: viền focus mới, chip tên ô, thanh điều khiển nhỏ khi focus.
- [ ] Dùng `PlayerPool` (mục 5.4) để tái sử dụng ExoPlayer.
- [ ] Mobile: chỉ cho 2 ô (dọc/ngang), 4 ô chỉ ở tablet/TV.

### 3.9 Cập nhật trong app (`UpdateManager`)
- [ ] Dialog mới: phiên bản, **changelog**, thanh tiến trình, nút *Để sau* (không ép nếu không bắt buộc).
- [ ] Dùng chung `Http` client; hỗ trợ tiếp tục tải (Range) khi rớt mạng.
- [ ] Kiểm tra chữ ký SHA-256 của APK trước khi cài (Worker trả về hash).

---

## 4. CHỨC NĂNG MỚI

### 4.1 Yêu thích (trận/đội/BLV/kênh IPTV)
- Lưu bằng **DataStore** (Preferences) hoặc Room (nếu cần truy vấn). Đồng bộ UI tức thì bằng `Flow`.

### 4.2 Lịch sử / Tiếp tục xem
- Lưu 20 mục gần nhất (kênh/trận + thời điểm), hiện thành hàng "Tiếp tục xem".

### 4.3 Nhắc trận
- `WorkManager` + `AlarmManager` (inexact) tạo thông báo 15 phút trước giờ bóng lăn; bấm vào mở đúng trận. Android 13+: xin `POST_NOTIFICATIONS`. TV không có thông báo → hiện banner trong app.

### 4.4 Chọn nguồn phát & tự đổi nguồn
- Xem 5.2.

### 4.5 Điều khiển từ xa / đa thiết bị (giai đoạn sau)
- Chromecast / Google Cast cho mobile. Ưu tiên thấp.

### 4.6 Khác
- Chia sẻ trận (deep link `kenhlive://room/{roomNum}`), mở bằng link.
- Chế độ tiết kiệm dữ liệu (giới hạn bitrate theo mạng di động).
- Đa ngôn ngữ: Tiếng Việt + Tiếng Anh (đưa chuỗi vào `strings.xml`).

---

## 5. KIẾN TRÚC & HIỆU NĂNG ("SIÊU MƯỢT")

### 5.1 Khởi động nhanh
- [ ] **Baseline Profile** + `ProfileInstaller`; bật R8 full mode (đã `minifyEnabled true` cho release).
- [ ] Application `onCreate` chỉ làm việc tối thiểu; `DeviceMode`/`Http` khởi tạo lười (lazy) hoặc trên luồng nền nếu đo thấy chậm.
- [ ] **Stale-while-revalidate:** lưu JSON cuối cùng xuống đĩa (file/DataStore) → vào app hiện dữ liệu ngay, làm mới nền rồi cập nhật (không nhảy layout).
- [ ] Mục tiêu đo: cold start < 1.5s (điện thoại tầm trung), < 2.5s (TV box 1GB).

### 5.2 Nguồn phát nhiều lớp + failover thật
- Đổi `parseStream` → `parseStreams(): List<StreamSource>` (giữ thứ tự hdM3u8, m3u8, hdFlv, flv kèm nhãn chất lượng).
- `PlayerController`: lỗi/đệm > N giây → chuyển nguồn kế tiếp (có nhớ nguồn tốt cho lần sau), chỉ khi hết nguồn mới báo lỗi; giữ retry mạng hiện tại.
- Cache URL stream theo `roomNum` (TTL ngắn, ~30–60s) và **prefetch** cho trận đang focus (TV) / trận hiển thị đầu (mobile).
- Kiểm thử nguồn `flv`: Media3 đọc FLV như luồng progressive (có `FlvExtractor`) nên **không có live-edge/adaptive bitrate** như HLS → chỉ dùng làm nguồn dự phòng cuối, cần test thực tế độ trễ/ổn định.

### 5.3 Dữ liệu & luồng
- [ ] Repository → `Flow`; ViewModel dựng `HomeUiState` ở `Dispatchers.Default`.
- [ ] Bỏ `Thread.sleep` trong `Http.getWithRetry` → `suspend` + `delay`; `UpdateManager` dùng chung client.
- [ ] Thêm **ETag/If-None-Match** nếu server hỗ trợ (giảm băng thông).
- [ ] `ViewModel` giữ trạng thái qua xoay màn hình; dùng `SavedStateHandle` cho tab/bộ lọc.

### 5.4 Player
- [ ] `PlayerPool`: giữ tối đa 1 player dự phòng "ấm" để zapping/đổi nguồn nhanh; Multiview lấy player từ pool.
- [ ] Giữ player qua `onStop` khi vào PiP/đa nhiệm ngắn (chỉ release sau timeout ~30s hoặc khi `isFinishing`).
- [ ] Bộ đệm theo hồ sơ: *Độ trễ thấp* (hiện tại), *Cân bằng*, *Ổn định* (cho mạng yếu); chọn trong Cài đặt.
- [ ] Giảm tần suất cập nhật Stats HUD khi ẩn; dừng mọi `Handler` khi overlay ẩn.

### 5.5 UI runtime
- [ ] **Thay `FocusKit` retry-polling** bằng: `setHasFixedSize`, `RecyclerView.setItemViewCacheSize`, `FocusFinder` tuỳ biến, `onRequestFocusInDescendants`, nhớ vị trí theo key (đã có `lastKey` — giữ). Hoặc dùng thư viện `androidx.leanback` chỉ cho phần grid/row nếu đo thấy tiết kiệm.
- [ ] `lowRam`: tắt bóng/scale, giảm kích thước ảnh, `itemAnimator = null`, crossfade 0.
- [ ] Ảnh: `Coil` với `size()` đúng khung, placeholder = màu token, **prefetch** ảnh hàng kế; logo đội dùng cache đĩa lâu hơn.
- [ ] Chuyển `findViewById` → **ViewBinding** (giảm lỗi + nhanh hơn khi inflate).
- [ ] Gộp drawable, dùng vector cho icon; không inflate layout lồng quá 4 tầng (kiểm bằng Layout Inspector).

### 5.6 Công nghệ UI — phương án
| Phương án | Ưu | Nhược | Khuyến nghị |
|---|---|---|---|
| **A. Views + RecyclerView (giữ)** | Nhẹ, chạy tốt Android 5–7/box 1GB, đã có nền `FocusKit`/adapter | Code UI dài, focus TV tự xử lý | ✅ **Chọn cho v7.0** |
| B. Compose + `androidx.tv:tv-material` | Focus/animation tốt, code ngắn | Cần nâng Kotlin/AGP; nặng hơn trên box yếu/32-bit; APK lớn | Spike thử ở v7.1 |
| C. Leanback | Chuẩn TV | Đã ngừng phát triển, khó tuỳ biến theo thiết kế mới | ❌ |

---

## 6. LỘ TRÌNH THỰC HIỆN (đề xuất 6 sprint ~ 12 tuần, 1 dev; rút gọn được nếu có thêm người)

### Sprint 0 — Nền tảng (1 tuần)
| ID | Việc | Ước tính |
|---|---|---|
| S0-1 | Nâng Kotlin (1.9.x), AGP (8.3+), Media3 (≥1.4), Coil; chạy lại CI | 1 ngày |
| S0-2 | Bật ViewBinding; dọn `build.gradle` (bỏ CI DIAGNOSTICS gọi GitHub API) | 0.5 ngày |
| S0-3 | Thu gọn `file_paths.xml`; thêm `networkSecurityConfig`, `dataExtractionRules` | 0.5 ngày |
| S0-4 | Thiết lập **đo hiệu năng** trên ≥3 thiết bị: box 1GB/32-bit, TV 2GB, phone tầm trung (cold start, FPS cuộn, time-to-first-frame) → **ghi số liệu nền** | 1 ngày |
| S0-5 | Khung `debug` sourceSet cho `handleDebugIntent`; đưa chuỗi hard-code vào `strings.xml` | 1 ngày |

### Sprint 1 — Design System (1.5 tuần)
| ID | Việc |
|---|---|
| S1-1 | `colors.xml`/`dimens.xml`/`type.xml` token mới (mobile + `television` + `sw600dp`) |
| S1-2 | Style thành phần: Button (Primary/Secondary/Ghost/Icon), Chip, Card, Badge, Dialog, Sheet, Skeleton |
| S1-3 | Bộ icon vector thống nhất (24dp, nét 2dp); dọn drawable trùng |
| S1-4 | Trang "Gallery" debug hiển thị toàn bộ component (mobile + TV) để duyệt giao diện |
| S1-5 | `FocusKit v2` (hiệu ứng focus theo token, tắt khi `lowRam`) |

### Sprint 2 — Shell + Trang chủ (2 tuần)
S2-1 Shell mới (bottom nav / top tabs / rail) · S2-2 `HomeViewModel` + `HomeUiState` · S2-3 Hero + thẻ trận mới · S2-4 Hàng Live/Sắp diễn ra/BLV · S2-5 Skeleton + stale-while-revalidate · S2-6 Focus restore mới.

### Sprint 3 — Player mới (2.5 tuần)
S3-1 Tách `PlayerController`/`PlayerSession` · S3-2 Mở ngay + `parseStreams` + failover thật · S3-3 Overlay TV · S3-4 Overlay mobile + gesture + immersive · S3-5 Bottom sheet / side panel cài đặt · S3-6 OSD chip thay Toast · S3-7 PiP `RemoteAction` · S3-8 Stats HUD mới.

### Sprint 4 — Lịch, IPTV, Tìm kiếm, Cài đặt (2 tuần)
S4-1 Chip ngày + lọc · S4-2 IPTV 2 cột + kênh chết + EPG · S4-3 Tìm kiếm mới · S4-4 Cài đặt mở rộng · S4-5 MultiView khung mới.

### Sprint 5 — Tính năng cá nhân + Hoàn thiện (2 tuần)
S5-1 Yêu thích (DataStore) · S5-2 Tiếp tục xem · S5-3 Nhắc trận (WorkManager) · S5-4 Dialog Update mới + SHA-256 · S5-5 Baseline Profile · S5-6 Đa ngôn ngữ VI/EN.

### Sprint 6 — QA & Phát hành (1 tuần)
Chạy lại bộ QA CI (`qa/`), kiểm thử tay theo checklist mục 7, beta nội bộ (3–5 người dùng TV + 3–5 phone), sửa lỗi, tag `v7.0.0`, cập nhật Worker (`/version`) kèm changelog.

**Thứ tự ưu tiên nếu phải cắt:** Player (S3) → Design System (S1) → Trang chủ (S2) → Hiệu năng khởi động (S5-1/5.1) → Yêu thích → phần còn lại.

---

## 7. KIỂM THỬ & TIÊU CHÍ HOÀN THÀNH

### 7.1 Checklist kiểm thử bắt buộc
- **TV (D-pad):** đi được mọi nút chỉ bằng ←↑→↓/OK/BACK; focus không bao giờ "mất"; BACK luôn thoát một cấp; phím số, INFO, phím màu hoạt động; quay lại từ Player nhớ đúng thẻ vừa xem.
- **Mobile:** xoay dọc/ngang không reload luồng; cử chỉ không xung đột cử chỉ hệ thống (back gesture cạnh màn); PiP vào/ra; 2 tay cầm vẫn chạm được nút chính (vùng ≥ 48dp).
- **Mạng:** mất mạng giữa chừng → tự đổi nguồn/khôi phục; Wi-Fi yếu không crash; chế độ máy bay hiện trạng thái rõ.
- **Máy yếu:** box 1GB/32-bit: không OOM, FPS cuộn ổn định, multiview 2 ô chạy.
- **Cập nhật:** cài đè v6.6.1 → v7.0 giữ cài đặt cũ (migration `SharedPreferences` → DataStore); tải APK, kiểm hash, cài.
- **Trợ năng:** `contentDescription` cho mọi nút icon, tương phản chữ ≥ 4.5:1, cỡ chữ TV ≥ 18sp.

### 7.2 Chỉ số mục tiêu (KPI) — *đặt sau khi có số đo nền ở S0-4*
| Chỉ số | Mục tiêu đề xuất |
|---|---|
| Cold start → thấy nội dung | ≤ 1.5s phone / ≤ 2.5s box yếu (nhờ cache đĩa) |
| Bấm "Xem" → khung Player hiện | ≤ 150ms |
| Bấm "Xem" → hình đầu tiên | ≤ 2s (mạng tốt) |
| Đổi kênh IPTV → hình | ≤ 1.2s |
| Jank khi cuộn trang chủ | < 5% frame trễ (phone), cuộn ổn định 30fps+ trên box yếu |
| Crash-free sessions | ≥ 99.5% |

### 7.3 Test tự động bổ sung
- Unit: `SocoliveParser.parseStreams`, `PlayerController` failover (fake source), `HomeUiState` builder, migration prefs.
- Instrumented: điều hướng chính, Player mở/đóng/xoay.
- CI: giữ workflow `QA Clicks`/`UI-Tour`; thêm job `testDebugUnitTest` + lint vào `build.yml`.

---

## 8. PHÂN LOẠI ƯU TIÊN (MoSCoW)

| Mức | Hạng mục |
|---|---|
| **Must (v7.0)** | Design System mới · Shell mới (TV tabs/mobile bottom nav) · Trang chủ mới · Player mới (mở ngay, failover, overlay, gesture, bottom sheet, immersive) · Cache đĩa · Yêu thích · Dọn rủi ro mục 1.4 |
| **Should (v7.0 nếu kịp)** | Lịch có chip ngày + lọc · IPTV 2 cột + kênh chết · Tiếp tục xem · Nhắc trận · Dialog Update mới + hash · Baseline Profile |
| **Could (v7.1)** | Đa ngôn ngữ EN · Tablet 2 cột · Bàn phím on-screen TV · Deep link chia sẻ |
| **Later** | Phát nền chỉ âm thanh · Google Cast · Giọng nói · Spike Compose for TV |

---

## 9. CÂU HỎI CẦN BẠN QUYẾT ĐỊNH

| ID | Câu hỏi | Gợi ý |
|---|---|---|
| D1 | Giữ **Views** hay chuyển **Compose for TV**? | Giữ Views cho v7.0; spike Compose sau |
| D2 | Thiết bị tối thiểu cần hỗ trợ? (hiện `minSdk 21`, có box 32-bit) | Giữ 21 nếu người dùng vẫn dùng box cũ |
| D3 | Phong cách: giữ **đen + xanh lục** hay đổi bảng màu khác? | Giữ đen/xanh lục, nâng cấp cách dùng |
| D4 | Có muốn đăng **Google Play** không? (ảnh hưởng targetSdk, chính sách nội dung stream) | Quyết định sớm vì ảnh hưởng pháp lý/kỹ thuật |
| D5 | Hero có giữ **đồng hồ + lịch âm** không? | Chuyển thành widget tuỳ chọn |
| D6 | Nhắc trận: chỉ trong app hay cả thông báo hệ thống (mobile)? | Có cả hai |
| D7 | Có thêm đa ngôn ngữ (EN) ngay v7.0? | Để v7.1 |
| D8 | Nguồn dữ liệu `json.vnres.co`/IPTV: có phương án dự phòng khi API đổi/chặn? | Nên có cơ chế cấu hình từ xa (Worker) để đổi endpoint không cần phát hành |

---

## 10. RỦI RO & GIẢM THIỂU

| Rủi ro | Mức | Giảm thiểu |
|---|---|---|
| Viết lại Player gây hồi quy (phát live rất nhạy) | Cao | Giữ `Enhancer` nguyên; thêm test failover; beta nội bộ trước; giữ nhánh v6.6.x để hotfix |
| Focus TV lỗi sau khi bỏ `FocusKit` cũ | Cao | Làm `FocusKit v2` tương thích; QA D-pad tự động bằng `qa/` |
| Hiệu ứng mới làm giật trên box yếu | Trung bình | Mọi hiệu ứng có cờ `lowRam`; đo ở S0-4 và mỗi sprint |
| API Socolive/IPTV đổi định dạng | Trung bình | Parser có test; cấu hình endpoint từ xa; hiển thị lỗi rõ ràng |
| Nâng Kotlin/AGP/Media3 kéo theo lỗi build | Trung bình | Làm riêng ở Sprint 0, merge trước khi đụng UI |
| Phạm vi quá lớn | Cao | Theo MoSCoW (mục 8); phát hành beta v7.0-rc sau Sprint 3 |
| Nội dung stream/bản quyền (nếu lên Play) | Cao | Xem câu D4; chỉ sideload nếu chưa có giải pháp |

---

## 11. CÁC FILE DỰ KIẾN BỊ ẢNH HƯỞNG

| Nhóm | File |
|---|---|
| Viết lại | `PlayerActivity.kt`, `activity_player.xml`, `dialog_player_settings.xml`, `MainActivity.kt` + 2 `activity_main.xml`, `item_nav_*`, `item_hero*`, `item_match_card`, `item_fixture_card`, `item_blv_card` |
| Sửa nhiều | `LiveFragment`, `SportAdapter`, `MatchCardsAdapter`, `BlvCardsAdapter`, `ScheduleFragment/Adapter`, `IptvFragment`, `SearchFragment`, `SettingsFragment/Adapter`, `MultiViewActivity`, `UpdateManager`, `FocusKit`, `Enhancer` (thêm hồ sơ bộ đệm) |
| Sửa nhỏ | `SocoliveRepository` (cache đĩa, prefetch), `SocoliveParser` (`parseStreams`), `Http`, `KenhLiveApp`, `DeviceMode` (hợp nhất `init/updateMode` trùng lặp, thêm "tablet"), `AndroidManifest.xml` |
| Mới | `design/` (token + style), `player/*`, `data/FavoritesRepository`, `data/HistoryRepository`, `reminder/*`, `DebugIntents.kt` (debug sourceSet), `baseline-profile` module |
| Tài nguyên | `values*/colors|dimens|styles|themes|strings.xml`, `drawable/*` (dọn ~100 file), `xml/file_paths.xml`, `xml/network_security_config.xml` |
| CI | `.github/workflows/build.yml` (thêm test+lint+upload log), `build.gradle` (bỏ khối DIAGNOSTICS) |

---

## 12. BƯỚC TIẾP THEO NGAY

1. Bạn chốt các câu **D1–D8** (đặc biệt D1, D2, D4, D5).
2. Mình làm **Sprint 0 + Sprint 1** (nền tảng + Design System + trang Gallery) để bạn **duyệt giao diện** trên emulator/ảnh chụp trước khi làm Player.
3. Gửi mình số đo từ thiết bị thật (mẫu TV box/phone bạn đang dùng, RAM, Android version) để đặt KPI chính xác.

---

## ✅ Quyết định đã chốt (30/09/2026)

| # | Quyết định | Áp dụng |
|---|---|---|
| D1 | Giữ XML Views (không chuyển Compose) | Giữ nguyên kiến trúc, tối ưu RAM |
| D2 | Máy mục tiêu **1GB RAM** | `DeviceMode.lowRam/ultraLowRam` theo RAM vật lý; Coil cache nhỏ; MultiView 2 ô |
| D4 | **Không** đăng Google Play (sideload APK) | Không ép targetSdk 35 |
| D5 | **Giữ** đồng hồ + lịch âm ở hero | Không đụng |

## 📦 Đã làm trong v7.0.0 (code)

- **Mở Player tức thì**: Live/Lịch/Tìm kiếm truyền `room_num`, Player tự lấy nguồn (cache 45s) → không còn chờ mạng ở màn hình trước.
- **Nhiều nguồn phát + tự đổi nguồn** khi lỗi/đứng hình (HD HLS → HLS → HD FLV → FLV).
- **Stale-while-revalidate**: Live & Lịch hiện ngay từ cache đĩa rồi làm mới nền; mất mạng vẫn xem được lịch.
- **Player mới**: 1 bảng điều khiển duy nhất (bottom sheet dọc / side panel ngang + TV), thanh nút TV gọn, thông báo dạng chip thay Toast, toàn màn hình immersive, nhớ tỉ lệ khung hình.
- **Cử chỉ (điện thoại)**: vuốt trái = độ sáng, vuốt phải = âm lượng, chạm đúp = tạm dừng, chạm 1 lần = ẩn/hiện điều khiển.
- **Điều hướng**: TV → thanh tab ngang có chữ (←/→ chọn, ↓ vào nội dung, ↑ quay lại tab); điện thoại → pill sáng quanh tab đang chọn.
- **Yêu thích** (chỉ kênh IPTV ở v7.0): giữ OK / nhấn giữ trên thẻ kênh, hoặc trong bảng Player; nhóm "★ Yêu thích" tự xuất hiện.
- Sửa lỗi: chỉ số kênh IPTV khi đang lọc nhóm (Player nhảy sai kênh).
- Dọn 9 layout/drawable không dùng.

## ⏳ Chưa làm / để v7.1
- Yêu thích cho trận/BLV Socolive; prefetch nguồn khi focus thẻ; làm mới Hero/Card; UI-Tour kiểm thử trên emulator.
