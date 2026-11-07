# KL3 Design System — KenhLive TV

Premium streaming direction: **deep-black canvas + warm orange→amber accent + subtle glass**.
Think "Netflix/Disney+ warmth meets tvOS restraint". One token set serves phone & TV;
`values-television/kl3_dimens.xml` auto-scales type/sizes/margins at 10-foot distance.

**Rule:** mọi resource mới đều prefix `kl3_`. Không sửa file cũ. Layout inflate bởi
Kotlin hiện có → giữ nguyên mọi `android:id` hiện tại.

---

## 1. Color tokens (`values/kl3_colors.xml`)

| Token | Value | Dùng khi |
|---|---|---|
| `kl3_bg` | `#FF050508` | Canvas app, sau cùng mọi thứ |
| `kl3_bg_soft` | `#FF0A0C12` | Hero backdrop, vùng trên trang |
| `kl3_surface` | `#FF12141C` | Nền card chuẩn (level 1) |
| `kl3_surface_high` | `#FF1B1F2B` | Card khi focus/hover (level 2) |
| `kl3_surface_top` | `#FF262B3A` | Dialog, menu, popover (level 3) |
| `kl3_glass` / `kl3_glass_strong` | `#14FFFFFF` / `#24FFFFFF` | Fill kính trên ảnh/nền tối |
| `kl3_glass_border` | `#33FFFFFF` | Viền hairline của glass |
| `kl3_divider` | `#1C2433` | Đường kẻ hairline giữa section |
| `kl3_text_primary` | `#FFF7F7F9` | Tiêu đề, nội dung chính |
| `kl3_text_secondary` | `#FFB8C0CE` | Body, mô tả |
| `kl3_text_tertiary` | `#FF7C8497` | Meta, timestamp, placeholder |
| `kl3_on_accent` | `#FFFFFFFF` | Chữ trên nền cam/amber |
| `kl3_accent` | `#FFFF6500` | **Focus ring, CTA, selected** — màu chủ đạo |
| `kl3_accent_bright` / `kl3_accent_deep` | `#FFFF8A3D` / `#FFE04A00` | 2 đầu gradient nút primary |
| `kl3_accent_soft` / `kl3_accent_dim` | `#1FFF6500` / `#80FF6500` | Tint chip/nav selected, border accent nhẹ |
| `kl3_gold` | `#FFFFB300` | **Tỉ số, view count, VIP meta** |
| `kl3_info` | `#FF00E5FF` | Provider/nguồn stream, link |
| `kl3_live` / `kl3_live_bright` / `kl3_live_deep` | `#FFFF334B` / `#FFFF4D66` / `#FFE01A34` | Badge LIVE, chấm live |
| `kl3_upcoming` | `#FF38BDF8` | Badge "SẮP ĐẤU" |
| `kl3_ended` | `#FF7C8497` | Badge "ĐÃ KẾT THÚC" |
| `kl3_success` / `kl3_error` / `kl3_warning` | `#FF34D399` / `#FFFF4D4D` / `#FFFBBF24` | Semantic states |
| `kl3_scrim_85/60/40/20` | black alpha | Scrim overlay (dùng qua drawable) |
| `kl3_skeleton` / `kl3_skeleton_hi` | `#14FFFFFF` / `#26FFFFFF` | Loading placeholder |
| `kl3_poster_a` / `kl3_poster_b` | `#FF181B26` / `#FF0C0E15` | Gradient placeholder poster |

## 2. Dimens (`values/kl3_dimens.xml`, TV override `values-television/`)

| Token | Phone | TV | Dùng khi |
|---|---|---|---|
| `kl3_sp_xs…xxxl` | 4/8/12/16/24/32/48dp | — | **Chỉ dùng scale này cho spacing** |
| `kl3_margin_x` | 16dp | 56dp | Padding ngang màn hình |
| `kl3_gap` / `kl3_gap_l` / `kl3_rail_gap` | 12/20/8dp | 14/28/12dp | Gap item / section / trong rail |
| `kl3_r_sm/md/lg/xl/pill` | 10/14/20/28/999dp | (giữ nguyên) | Corner radius |
| `kl3_elev_1/2/3/focus` | 2/6/14/10dp | focus=14dp | Elevation |
| `kl3_focus_ring_w` / `kl3_focus_glow_w` | 3/6dp | 3.5/8dp | Độ dày vòng focus |
| `kl3_ts_*` (micro→hero) | 10→30sp | 12→40sp | Type scale — xem §4 |
| `kl3_card_w` / `kl3_card_thumb_h` | 236/133dp | 330/186dp | Card ngang chuẩn |
| `kl3_poster_w` / `kl3_poster_h` | 140/210dp | 170/255dp | Poster dọc ~2:3 |
| `kl3_channel_card_w/h` | 196/124dp | 252/158dp | Card kênh IPTV |
| `kl3_match_card_w` | 250dp | 320dp | Card trận đấu |
| `kl3_btn_h` / `kl3_btn_h_s` | 48/40dp | 44/38dp | Nút chuẩn / compact (phone ≥48dp touch) |
| `kl3_icon_btn` / `_s` | 48/40dp | 52/44dp | Nút icon tròn |
| `kl3_chip_h` | 36dp | 42dp | Chip filter |
| `kl3_badge_h` | 22dp | 26dp | Badge pill |
| `kl3_input_h` | 48dp | 46dp | Ô search/input |
| `kl3_hero_h` | 300dp | 360dp | Hero banner đầu trang |
| `kl3_avatar_s` / `kl3_crest_s` | 32/20dp | 36/24dp | Avatar / logo đội |
| `kl3_divider_h`, `kl3_dot_live`, `kl3_section_bar_w` | 1dp, 8dp, 4dp | — | Chi tiết trang trí |
| `kl3_skel_*` | card 236×160, poster 140×210 | card 330×200, poster 170×255 | Skeleton loading |

## 3. Component drawables (`drawable/kl3_*.xml`)

| Drawable | Loại | Dùng làm |
|---|---|---|
| `kl3_card_bg` | selector | `android:background` của card (state normal/focus/press/selected) |
| `kl3_fg_focus` | selector (foreground) | `android:foreground` — vòng glow cam 2 lớp, trong suốt khi không focus |
| `kl3_fg_focus_pill` | selector (foreground) | Như trên nhưng bo pill — cho chip/nút tròn |
| `kl3_btn_primary` | selector | Nền nút CTA — gradient cam→hổ phách, focus viền trắng 3dp |
| `kl3_btn_ghost` | selector | Nút phụ — kính mờ, focus viền cam |
| `kl3_btn_icon` | selector | Nút icon hình tròn (topbar, player controls) |
| `kl3_chip_bg` | selector | Chip filter — selected = nền cam đặc + chữ trắng |
| `kl3_pill_bg` | shape | Pill meta trung tính (provider, quality, count) — không focus |
| `kl3_input_bg` | selector | Ô nhập liệu, focus viền cam 2dp |
| `kl3_badge_live` | shape | Badge LIVE — gradient đỏ |
| `kl3_badge_soon` | shape | Badge "SẮP ĐẤU" — xanh sky đặc |
| `kl3_badge_ended` | shape | Badge "ĐÃ KẾT THÚC" — slate mờ |
| `kl3_badge_hd` | shape | Badge chất lượng — amber glass |
| `kl3_badge_provider` | shape | Badge nguồn — cyan glass |
| `kl3_score_pill` | shape | Bảng tỉ số / VS giữa — slate đậm bo 14dp |
| `kl3_divider` | shape | Hairline 1dp `kl3_divider` |
| `kl3_scrim_bottom` / `kl3_scrim_left` / `kl3_hero_scrim` | layer-list | Fade đen cho ảnh hero/list overlay |
| `kl3_poster_placeholder` | shape | Placeholder poster khi chưa có ảnh |
| `kl3_skeleton` | shape | Khối loading |
| `kl3_topbar_bg` | layer-list | App bar — `kl3_bg_soft` + hairline dưới |
| `kl3_dot_live` | oval | Chấm LIVE nhấp nháy (animate alpha trong code) |
| `kl3_nav_item_bg` | selector | Item nav rail TV / bottom nav phone |

Color selectors (`color/`): `kl3_nav_text` (nav item text), `kl3_icon_tint` (icon tint),
`kl3_chip_text` (chip label) — focus→primary/accent, selected→accent/on-accent.

## 4. Typography (`values/kl3_styles.xml`)

Font: **Oswald** cho display/section/badge/button/score (uppercase feel, letterSpacing
0.05–0.08); **sans-serif-medium** cho title/body; `tnum` cho mọi số (score, giờ, view).

| Style | Size token | Khi dùng |
|---|---|---|
| `KL3.Text.Hero` | `kl3_ts_hero` (30/40sp) | Tiêu đề lớn trong hero banner |
| `KL3.Text.Display` | `kl3_ts_display` (26/34sp) | Tiêu đề màn hình, số liệu lớn |
| `KL3.Text.Section` | `kl3_ts_section` (19/24sp) | Header hàng: "TRỰC TIẾP", "PHIM MỚI" — đặt cạnh `kl3_section_bar_w` accent bar |
| `KL3.Text.Title` / `TitleLg` | `kl3_ts_title`/`body_lg` | Tên card, tên kênh, tên phim |
| `KL3.Text.Body` | `kl3_ts_body` (14/18sp) | Mô tả, mô tả dài |
| `KL3.Text.Meta` | `kl3_ts_meta` (12/15sp) | Thời gian, số lượng, phụ đề — màu `kl3_text_tertiary` |
| `KL3.Text.Micro` | `kl3_ts_micro` (10/12sp) | Nhãn cực nhỏ trên ảnh |
| `KL3.Text.Score` | `kl3_ts_title`, gold | Tỉ số, view count |
| `KL3.Text.Button` | `kl3_ts_body`, Oswald caps | Nhãn nút |
| `KL3.Text.Chip` / `KL3.Text.Badge` | chip/body, badge | Nhãn chip & badge |

Component styles: `KL3.Button.Primary` / `.PrimarySm` / `.Ghost` / `.Danger` / `.Icon`,
`KL3.Card` (và `.Poster`), `KL3.Chip`, `KL3.Pill`, `KL3.Input`,
`KL3.Badge.Live` / `.Soon` / `.Ended` / `.Hd` / `.Provider` / `.Score`,
`KL3.AppBar`, `KL3.Skeleton`.

## 5. Spec component

### Card chuẩn (`KL3.Card`)
```
background = @drawable/kl3_card_bg      (surface gradient + hairline divider)
foreground = @drawable/kl3_fg_focus     (ring glow khi focus)
elevation  = kl3_elev_1 → kl3_elev_focus khi focused (set trong code)
```
- Bo góc `kl3_r_lg` (20dp); poster dùng `kl3_r_md` (14dp) hoặc vuông.
- Padding trong `kl3_card_h_pad` (14/18dp).
- Kích thước: `kl3_card_w × thumb kl3_card_thumb_h` (16:9 thumb), hoặc
  `kl3_poster_w × kl3_poster_h` (2:3), hoặc `kl3_channel_card_w/h`.

### Nút chuẩn
- **Primary**: `KL3.Button.Primary` + `KL3.Text.Button` — gradient cam, chữ trắng caps.
  Chỉ 1 CTA chính mỗi cụm ("XEM NGAY", "PHÁT"…).
- **Ghost**: `KL3.Button.Ghost` — glass, chữ Oswald. Dùng cho hành động phụ.
- **Icon**: `KL3.Button.Icon` — tròn glass, icon tint `color/kl3_icon_tint`.
- Height `kl3_btn_h` (48dp phone — đạt touch target; TV 44dp), padding ngang `kl3_btn_pad_x`.

### Focus treatment
- **TV (D-pad)**: focus = `kl3_fg_focus`/`kl3_fg_focus_pill` foreground (glow `kl3_focus_glow_w`
  + ring `kl3_focus_ring_w` màu `kl3_accent`) **+** scale `1.06–1.08` **+** elevation
  `kl3_elev_focus`, animate ~120–150ms. Nút primary focus có viền trắng 3dp.
- **Phone (touch)**: cùng drawable states — `pressed` = overlay `kl3_pressed`;
  `focused` (khi nhận focus ảo/keyboard) vẫn hiện ring cam. Không scale trên phone
  trừ khi là TV layout.
- Mọi phần tử focusable: `focusable=true`, `clickable=true`,
  `descendantFocusability="blocksDescendants"` trên card.

### Badge rules
- **LIVE** → `KL3.Badge.Live` + `KL3.Text.Badge`, text trắng. Chỉ khi đang phát.
- **Sắp đá** → `KL3.Badge.Soon` (xanh sky, text trắng). **Kết thúc** → `KL3.Badge.Ended`.
- **HD/FHD/4K** → `KL3.Badge.Hd` + `KL3.Text.Score` (gold). Góc trên-phải thumb/card.
- **Provider/nguồn** → `KL3.Badge.Provider` (cyan) hoặc `KL3.Pill` trung tính.
- **Tỉ số/VS** → `KL3.Badge.Score` + `KL3.Text.Score`, cỡ theo `kl3_badge_h` trở lên.
- Tối đa **2 badge** trên một card (thường LIVE + HD, hoặc league + LIVE).

### Spacing rule
Chỉ dùng `kl3_sp_*` + `kl3_gap*`/`kl3_margin_x`. Padding trong card = `kl3_card_h_pad`.
Khoảng cách giữa các hàng section = `kl3_gap_l`; trong rail = `kl3_rail_gap`;
lề màn hình = `kl3_margin_x`.

## 6. Nguyên tắc bố cục màn hình

1. **Hero-first**: màn hình chính mở đầu bằng hero `kl3_hero_h` full-bleed
   (`kl3_scrim_bottom`/`kl3_hero_scrim` phủ lên ảnh), tiêu đề `KL3.Text.Hero`,
   meta `KL3.Text.Meta` (giờ/phút), tối đa 2 nút: 1 `Primary` + 1 `Ghost`.
   Dưới hero là rail đầu tiên — không chồng section lên hero.

2. **Rail ngang**: mỗi section = header (`KL3.Text.Section` + accent bar 4dp
   `kl3_section_bar_w` màu `kl3_accent`, optional "Xem tất cả" `KL3.Text.Meta`)
   + `RecyclerView`/`HorizontalScrollView` ngang, item cách nhau `kl3_rail_gap`,
   rail kết thúc trước `kl3_margin_x` lề phải (peek item kế tiếp nếu còn).

3. **Grid poster**: phim/series dùng lưới poster `kl3_poster_w × kl3_poster_h`,
   gap `kl3_gap`; card chỉ chứa ảnh + overlay badge (HD góc phải-trên, LIVE góc trái-trên)
   + tiêu đề `KL3.Text.Title` phía dưới ảnh trong padding `kl3_card_h_pad`.

4. **Match card**: `kl3_match_card_w`, layout 3 tầng — hàng badge (league/provider/
   LIVE), hàng đội (crest `kl3_crest_s` + tên `KL3.Text.Title` hai bên, giữa là
   `KL3.Badge.Score` tỉ số), hairline `kl3_divider`, hàng meta (avatar `kl3_avatar_s`,
   BLV `KL3.Text.Meta`, view `KL3.Text.Score` gold, phòng `KL3.Badge.Provider`).

5. **Player overlay**: nền đen tuyệt đối; controls = `KL3.Button.Icon` trên
   `kl3_btn_icon` glass; scrim dưới `kl3_scrim_60`/`kl3_scrim_85`; OSD text
   `KL3.Text.Meta`/`KL3.Text.Score`; buffer = `KL3.Skeleton`-style glass box +
   ProgressBar tint `kl3_accent`; error = `kl3_surface_top` card bo `kl3_r_xl` +
   nút `KL3.Button.Primary` "THỬ LẠI".

6. **Empty/loading/error state**: ảnh minh họa (`ill_empty_*`/`empty_*` có sẵn)
   + tiêu đề `KL3.Text.TitleLg` + mô tả `KL3.Text.Body` + 1 nút `KL3.Button.Primary`
   ("TẢI LẠI"/"KHÁM PHÁ"). Loading: skeleton `kl3_skel_*` đúng kích thước item thật.

## 7. Checklist khi implement màn hình

- [ ] Mọi spacing dùng `kl3_sp_*`/`kl3_gap*`/`kl3_margin_x` — không hardcode dp.
- [ ] Card = `kl3_card_bg` + `kl3_fg_focus` foreground; focus scale 1.06–1.08 + elev.
- [ ] Chữ: đúng style KL3.Text.*; số liệu dùng `KL3.Text.Score`/`tnum`.
- [ ] LIVE/Sắp đá/Kết thúc đúng badge drawable tương ứng.
- [ ] Touch target phone ≥48dp (`kl3_btn_h`, `kl3_icon_btn`, `kl3_input_h`).
- [ ] Mọi `android:id` hiện có giữ nguyên.
