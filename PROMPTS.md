# 🎨 Thư viện Prompt Pollinations — KênhLive

Endpoint: `https://image.pollinations.ai/prompt/{prompt}?width=1024&height=576&nologo=true&seed=N&model=flux`
- **model=flux** cho ảnh chính (đẹp hơn), `turbo` cho bản nháp nhanh
- **seed=N** cố định để regenerate giống hệt; đổi seed để lấy biến thể
- Anonymous: ~30-45s/ảnh mới, cache hit = tức thì; kích thước thực tế tối đa ~1024 cạnh
- **Quy tắc theme**: luôn thêm `dark background, no text, no letters, no watermark` — chữ AI luôn vỡ

## ✅ Đã dùng trong app

| Ảnh | Prompt | Dùng ở |
|---|---|---|
| `hero_fallback.jpg` (8/10) | cinematic wide shot of a football stadium at night, bright floodlights, dark moody atmosphere, deep black and teal color grading with subtle amber highlights, empty green pitch, dramatic clouds, photorealistic, high detail, no text, no letters, no watermark | Hero khi cover BLV fail |
| `empty_schedule.jpg` | minimalist flat illustration of empty stadium tribune seats at night, one warm amber floodlight beam, very dark background, muted colors, clean vector style, lots of negative space, no text | Empty state lịch trình |

## ❌ Không dùng AI cho (bài học verify)

- **Logo/icon**: Flux vẽ mockup 3D có bóng + texture → vi phạm taste flat 1-ý-tưởng. Tam giác còn bị quay SAI hướng (rewind thay vì play). Logo phải vẽ vector (PIL/XML) — đang đúng với K+PLAY
- **Avatar placeholder**: 2 lần regen đều ra chân dung painterly nền SÁNG (2/10, 6/10) thay vì flat silhouette navy. Placeholder giữ vector XML

## Công thức prompt theo mục đích

**Nền hero/banner (được):**
```
cinematic wide shot of [cảnh], dark moody atmosphere, deep black and teal color grading
with subtle amber highlights, photorealistic, high detail, no text, no letters, no watermark
```

**Ảnh minh hoạ/empty state (được):**
```
minimalist flat illustration of [cảnh], very dark background, muted colors, clean vector style,
lots of negative space, single warm amber accent, no text
```

**Cấu trúc chung 5 lớp:**
1. `flat vector / cinematic photo / minimalist illustration` (định dạng)
2. `[chủ thể]` cụ thể — càng mơ hồ AI càng tự do
3. `very dark navy/black background` (khớp theme)
4. `amber/cyan accent` (đúng 2 màu thương hiệu, không thêm màu)
5. `no text, no letters, no watermark, no glow` (chặn lỗi AI)

## Checklist trước khi dùng ảnh AI vào app
- [ ] Vision kiểm tra: có chữ vỡ / watermark / anatomy lỗi?
- [ ] Nền có đủ tối (đặt text trắng nổi)?
- [ ] Phẳng/sạch đủ cho vị trí đặt (placeholder≠minh hoạ)?
- [ ] Crop/nén về dung lượng nhỏ (drawable-nodpi, JPEG quality 80)?
