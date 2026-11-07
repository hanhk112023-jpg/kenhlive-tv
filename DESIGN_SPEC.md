# KenhLive TV — Design Redesign Spec (KL4)

Redesign toàn bộ UI theo hướng **premium streaming / cinema** cho Android TV + phone.
Người dùng yêu cầu: giao diện đẹp hơn, trình phát hợp lý hơn, **không icon — chỉ chữ**.

## Nguyên tắc cốt lõi

1. **Không icon trong UI điều hướng/điều khiển** — mọi nút là TextView chữ. Icon chỉ cho
   nội dung (logo kênh, avatar, poster phim, logo đội bóng) — đó là dữ liệu, không phải chrome.
2. **10-foot TV first**: chữ to, tương phản cao, focus ring rõ, đệm rộng.
3. **Dark-first**: nền đen sâu, accent ấm (cam/amber), accent phụ cyan cho IPTV.
4. **Typography-driven**: Oswald cho tiêu đề/display, Roboto(sans-serif) cho body.
5. **Nhất quán**: mọi màn hình dùng chung token KL4, focus state đồng nhất.

## Design tokens (KL4)

### Màu — `res/values/kl4_colors.xml`
- Canvas: `kl4_bg #050507`, `kl4_bg_soft #0B0D12`, `kl4_surface #11141B`,
  `kl4_surface_high #1A1F2A`, `kl4_surface_top #242A38`
- Text: `kl4_text_primary #F5F6F8`, `kl4_text_secondary #A8B0BE`,
  `kl4_text_tertiary #6B7484`, `kl4_on_accent #FFFFFF`
- Accent chính (sports/live/CTA): `kl4_accent #FF5C1F` → `#FF8A3D` gradient
- Accent phụ IPTV: `kl4_accent_tv #22D3EE` (cyan)
- Accent phim: `kl4_accent_film #FBBF24` (amber/gold)
- Semantic: `kl4_live #FF3B5C`, `kl4_success #34D399`, `kl4_error #FF4D4D`,
  `kl4_upcoming #38BDF8`, `kl4_ended #7C8497`
- Glass: `kl4_glass #14FFFFFF`, `kl4_glass_strong #26FFFFFF`, border `#33FFFFFF`
- Scrim: giữ pattern hiện có (kl3p_scrim_top/bottom hoạt động tốt)

### Spacing/radius/type — tái dùng `kl3_dimens`/`kl3_ts_*` nhưng tinh chỉnh TV:
- Section title 26sp Oswald bold, letterSpacing 0.08
- Card title 20sp, body 17sp, meta 15sp (TV)
- Focus ring: accent 3dp + glow 8dp (đã có `kl3_fg_focus`, giữ)

### Component rules
- **Nút**: pill radius 999, min-height 44dp TV / 48dp phone, text Oswald bold
  uppercase letterSpacing 0.08. Primary = gradient accent + text trắng.
  Ghost = glass + border hairline.
- **Card**: radius 20dp, surface fill, hairline border, khi focus: border accent
  + glow + scale nhẹ (1.02). Không icon trong card chrome.
- **Bottom nav**: text-only label, selected = accent underline/bar + text accent.
- **Top bar**: logo + tên app trái, clock/phải; hairline dưới.

## Màn hình

### 1. HomeHub (`fragment_home_hub.xml` + `HomeHubFragment.kt`)
Hiện tại: 3 card dọc có icon to + mô tả dài + hàng nút dưới. Vấn đề: rối, icon
clipart không đồng bộ, nhiều nút trùng bottom nav.

**Thiết kế mới — "Hero portal":**
- Top: logo + `KENHLIVE TV` + live dot + đồng hồ phải (giữ, gọn lại).
- Giữa: 3 card portal **layout ngang trong card** (không dọc):
  - Trái: khối chữ lớn — badge nhỏ (VD `TRỰC TIẾP`, `150+ KÊNH`, `33.000+ PHIM`),
    title 40sp Oswald, mô tả 1–2 dòng ngắn, hint `Nhấn OK →`.
  - Phải: một con số/stat lớn làm "visual anchor" bằng chữ (VD `4` nhà đài,
    `150+` kênh, `4K` chất lượng) — thay cho icon.
  - Mỗi card một accent riêng: sports=cam, tv=cyan, phim=amber.
  - Focus: scale 1.03 + ring accent + border sáng hơn.
- Dưới: chỉ giữ 2 nút ghost `CÀI ĐẶT` và `BÁO LỖI` + text hướng dẫn.
  Bỏ `btnHubMultiview` (đã GONE), bỏ `LỊCH THI ĐẤU`/`TÌM KIẾM` khỏi đây
  (đã có bottom nav) — giảm 3 nút thừa.
- Giữ nguyên mọi `android:id` mà Kotlin đang bind: cardSports, cardIptv,
  cardPhim, btnHubSettings, btnHubFeedback, btnHubMultiview, btnHubSchedule,
  btnHubSearch, tvHubClock, portalContainer. Nút nào bỏ khỏi layout thì Kotlin
  phải chịu null (đã dùng `?.` — an toàn). Nếu giữ id nhưng ẩn, focus chain
  trong `HomeHubFragment` cần cập nhật (dòng 86-88, 128-130).
- `applyHubLayout()` cho phone: giữ logic dọc, nhưng card con phải chuyển
  sang orientation dọc khi portal dọc — đặt `android:orientation` động được
  hoặc dùng layout riêng. Đơn giản nhất: trong card dùng `LinearLayout` ngang
  cho TV; khi phone portrait, fragment set orientation của card content = VERTICAL.
  → Bọc content card trong 1 LinearLayout có id riêng (vd `cardSportsContent`)
  để code đổi orientation; hoặc dùng `android:layoutDirection`/weight khéo.
  Chọn: thêm id `content` cho LinearLayout con đầu tiên của mỗi card
  (`cardSportsContent`, `cardIptvContent`, `cardPhimContent`) và sửa
  `applyHubLayout` set `orientation=VERTICAL` khi portrait.

### 2. Player (`activity_player.xml` + `PlayerActivity.kt`)
Hiện tại: top overlay 2 hàng nút chữ đã OK nhưng **quá nhiều nút** (10 nút:
KÊNH, CHẤT LƯỢNG, ÂM THANH, TỈ LỆ, HẸN GIỜ, KÊNH TIẾNG, TĂNG ÂM, THÔNG SỐ,
ĐA MÀN HÌNH, PIP, TÙY CHỌN) — rối, tràn ngang.

**Thiết kế mới — "Cinema OSD":**
- **Top overlay** (1 hàng duy nhất, gọn):
  `[◀ QUAY LẠI]` | `Tên kênh/phim` + sub-info (live badge / tập / thời gian) | spacer | `[TÙY CHỌN]`
  → gom KÊNH/CHẤT LƯỢNG/ÂM THANH/TỈ LỆ/HẸN GIỜ/KÊNH TIẾNG/TĂNG ÂM/THÔNG SỐ/
  ĐA MÀN HÌNH/PIP vào trong panel `TÙY CHỌN` (dialog/bottom sheet).
  Nhưng để không phá logic PlayerActivity (2678 dòng, đang bind từng nút),
  **phương án an toàn**: giữ nguyên tất cả id nút, nhưng chia 2 nhóm hiển thị:
  - Hàng 1 (luôn hiện): QUAY LẠI, TÙY CHỌN(=phoneMenuBtn), và nút context chính
    (KÊNH cho TV hub, TẬP/DANH SÁCH tập cho phim).
  - Hàng 2 (panel mở khi bấm TÙY CHỌN, mặc định GONE): các nút còn lại.
  → Cần đọc PlayerActivity để biết nút nào đang show theo hub. PlayerActivity
  đã có `applyHubLayout()` ẩn/hiện nút theo hub — ta mở rộng: thêm
  `advancedRow` chứa các nút phụ, toggle khi `phoneMenuBtn` click.
  Nếu quá rủi ro, phương án B: giữ 1 hàng nhưng cho `HorizontalScrollView`
  — xấu. Chọn phương án A (2 hàng, hàng 2 collapse).
- **Bottom**: giữ `channelCarouselPanel` (dưới đáy) + `vodControlBar` (phim)
  + `phoneBottomBar`/`phoneSeekRow` (phone). Cải tiến:
  - `vodControlBar`: icon Button hiện dùng `drawableTop` icon — user nói
    "không icon". Đổi sang TextView chữ thuần: `Cài đặt | Tập trước | Tua lại |
    ▶ Tạm dừng | Tua tới | Tập tiếp | Danh sách tập` — hoặc giữ Button nhưng
    bỏ drawableTop, chỉ chữ. Chọn: Button không icon, chữ Oswald.
  - Progress/seek: giữ SeekBar cho VOD, thêm thin progress bar cho live
    (indeterminate hoặc theo EPG nếu có).
- **OSD quick channel** (`quickChannelOsd`): giữ, polish — số kênh to, tên,
  EPG now/next, progress EPG.
- **Error/buffering/stats HUD**: giữ glass panel, đồng bộ token KL4.
- **Per-hub player**: `PlayerActivity` đã có `object Hub` + `hubKind` +
  `applyHubLayout()`. Mở rộng để mỗi hub có **accent + label riêng**:
  - SPORTS: accent cam, hiện nút KÊNH(context: đổi luồng/phòng), ĐA MÀN HÌNH.
  - TV: accent cyan, hiện KÊNH (sidebar/carousel), PIP.
  - FILM/GENERIC: accent amber, hiện TẬP TRƯỚC/TIẾP, TUA, SEEK.
  → Review `applyHubLayout()` hiện tại rồi chuẩn hóa: định nghĩa
  `hubAccentColor` + `hubTitle` để top bar đổi màu/nhãn theo hub.

### 3. Hub screens (Live/Sports, Iptv, Phim)
Không redesign sâu (quá lớn) — chỉ **đồng bộ token + polish**:
- Mọi card/chip/button dùng style KL4 (tự kế thừa nếu ta map KL3→KL4 alias).
- **Cách nhanh nhất**: tạo `kl4_colors.xml` + `kl4_styles.xml` + `kl4_dimens.xml`,
  rồi trong `values/kl3_colors.xml`… không — tốt hơn: **định nghĩa KL4 là alias
  của KL3 cho phần lớn**, chỉ override những gì đổi. Nhưng user muốn "thiết kế
  lại từ đầu" → nên đổi giá trị KL3 luôn (vì mọi layout đã dùng kl3_*).
  → **Quyết định**: cập nhật trực tiếp `kl3_colors.xml`/`kl3_dimens.xml`/
  `kl3_styles.xml` (và `values-television/kl3_dimens.xml`) thành giá trị KL4.
  Tên resource giữ nguyên → không phải sửa hàng trăm layout. Đây là cách
  "redesign từ đầu" khả thi nhất trong codebase 20k dòng.

## Thứ tự triển khai
1. Cập nhật tokens: `kl3_colors.xml`, `kl3_styles.xml`, `kl3_dimens.xml`,
   `values-television/kl3_dimens.xml`, các drawable gốc (btn, card, chip,
   hub card, nav, topbar, scrim).
2. `fragment_home_hub.xml` + `HomeHubFragment.kt` — portal mới.
3. `activity_main.xml` + `item_nav_phone.xml` — top bar + bottom nav text-only.
4. `activity_player.xml` + `PlayerActivity.kt` — OSD 2 hàng + per-hub accent.
5. Build trên GitHub Actions, test bằng Porthole, gửi ảnh.

## Ràng buộc
- Build/test CHỈ trên GitHub Actions (không build local).
- Giữ mọi `android:id` hiện có (Kotlin bind trực tiếp).
- Phone layout phải còn dùng được (applyHubLayout portrait).
- Không đổi logic nghiệp vụ (streaming, API, focus chain cơ bản).
