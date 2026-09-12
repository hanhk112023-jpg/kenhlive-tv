#!/usr/bin/env python3
"""Sinh visual assets chuẩn brand (flat, tối, xanh lá) bằng PIL — chạy lại được."""
import os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
GEN = os.path.join(ROOT, "assets_gen")

BRAND = (0, 230, 118, 255)
BG = (7, 11, 16, 255)
BG2 = (13, 19, 27, 255)
SS = 4  # supersample


def font(sz, bold=True):
    for p in (os.path.join(RES, "font", "bebas_neue.ttf"), os.path.join(RES, "font", "oswald.ttf")):
        try:
            return ImageFont.truetype(p, sz)
        except Exception:
            continue
    return ImageFont.load_default()


def draw_mark(d, cx, cy, r, lw, color=BRAND):
    """2 cung phát sóng + tam giác play (hướng PHẢI)."""
    import math
    # cung trái / phải (góc -50..50 độ quanh tâm)
    for sgn in (-1, 1):
        bbox = [cx - r, cy - r, cx + r, cy + r]
        if sgn < 0:
            d.arc(bbox, start=140, end=220, fill=color, width=lw)
        else:
            d.arc(bbox, start=-40, end=40, fill=color, width=lw)
    # tam giác play
    tr = r * 0.52
    p = [(cx - tr * 0.72, cy - tr), (cx - tr * 0.72, cy + tr), (cx + tr * 0.95, cy)]
    d.polygon(p, fill=color)


def rounded_mask(size, radius):
    m = Image.new("L", size, 0)
    dm = ImageDraw.Draw(m)
    dm.rounded_rectangle([0, 0, size[0] - 1, size[1] - 1], radius=radius, fill=255)
    return m


def legacy_icon(px, path):
    s = px * SS
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    rad = int(s * 0.22)
    # nền gradient chéo tối
    base = Image.new("RGBA", (s, s), BG)
    g = Image.new("L", (s, s), 0)
    dg = ImageDraw.Draw(g)
    for y in range(s):
        v = int(255 * y / s)
        dg.line([(0, y), (s, y)], fill=v)
    grad = Image.new("RGBA", (s, s), BG2)
    base = Image.composite(grad, base, g.point(lambda v: v // 3))
    img.paste(base, (0, 0), rounded_mask((s, s), rad))
    d = ImageDraw.Draw(img)
    draw_mark(d, s / 2, s / 2, s * 0.30, max(2, int(s * 0.055)))
    img = img.resize((px, px), Image.LANCZOS)
    img.save(path)


def adaptive_foreground():
    """vector drawable foreground 108dp: mark ở vùng an toàn."""
    xml = '''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:pathData="M38.6,38.2 a21.5,21.5 0 0 1 0,31.6"
        android:strokeColor="#00E676" android:strokeWidth="4.6" android:strokeLineCap="round" android:fillColor="#00000000"/>
    <path android:path="M69.4,38.2 a21.5,21.5 0 0 0 0,31.6"
        android:strokeColor="#00E676" android:strokeWidth="4.6" android:strokeLineCap="round" android:fillColor="#00000000"/>
    <path android:pathData="M47.5,45.5 L66.5,54 L47.5,62.5 Z" android:fillColor="#00E676"/>
</vector>
'''
    # sửa lỗi thuộc tính (pathData cho cả 3)
    xml = xml.replace('android:path="M69.4', 'android:pathData="M69.4')
    with open(os.path.join(RES, "drawable", "ic_launcher_foreground.xml"), "w") as f:
        f.write(xml)


def banner():
    """TV banner 320x180 (xhdpi) — logo + wordmark, nền tối gradient."""
    w, h = 320 * 2, 180 * 2
    img = Image.new("RGBA", (w, h), BG)
    d = ImageDraw.Draw(img)
    g = Image.new("L", (w, h), 0)
    dg = ImageDraw.Draw(g)
    for x in range(w):
        dg.line([(x, 0), (x, h)], fill=int(120 * x / w))
    img = Image.composite(Image.new("RGBA", (w, h), BG2), img, g)
    d = ImageDraw.Draw(img)
    # vệt sáng xanh mờ góc phải
    glow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    dg2 = ImageDraw.Draw(glow)
    dg2.ellipse([w * 0.55, -h * 0.6, w * 1.35, h * 0.9], fill=(0, 230, 118, 26))
    glow = glow.filter(ImageFilter.GaussianBlur(60))
    img = Image.alpha_composite(img, glow)
    d = ImageDraw.Draw(img)
    draw_mark(d, h * 0.5, h * 0.5, h * 0.27, int(h * 0.05))
    x0 = h * 0.84
    avail = w - x0 - h * 0.10
    size = int(h * 0.34)
    f1 = font(size)
    while d.textlength("KENH LIVE", font=f1) > avail and size > 20:
        size -= 4
        f1 = font(size)
    d.text((x0, h * 0.50 - size * 0.62), "KENH LIVE", font=f1, fill=(248, 250, 252, 255))
    s2 = int(h * 0.115)
    f2 = font(s2)
    while d.textlength("BONG DA TRUC TIEP", font=f2) > avail and s2 > 10:
        s2 -= 2
        f2 = font(s2)
    d.text((x0 + h * 0.02, h * 0.50 + size * 0.42), "BONG DA TRUC TIEP", font=f2, fill=(0, 230, 118, 255))
    img = img.resize((320, 180), Image.LANCZOS)
    img.convert("RGB").save(os.path.join(RES, "drawable-nodpi", "banner.png"))
    img.convert("RGB").save(os.path.join(RES, "drawable", "banner.png"))


def fit_jpg(src, dst, w, h, q=82):
    im = Image.open(src).convert("RGB")
    # cover-crop
    sw, sh = im.size
    scale = max(w / sw, h / sh)
    im = im.resize((int(sw * scale), int(sh * scale)), Image.LANCZOS)
    sw, sh = im.size
    im = im.crop(((sw - w) // 2, (sh - h) // 2, (sw - w) // 2 + w, (sh - h) // 2 + h))
    im.save(dst, quality=q, optimize=True, progressive=True)


def main():
    dens = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for d, px in dens.items():
        p = os.path.join(RES, f"mipmap-{d}")
        os.makedirs(p, exist_ok=True)
        legacy_icon(px, os.path.join(p, "ic_launcher.png"))
    adaptive_foreground()
    banner()
    nodpi = os.path.join(RES, "drawable-nodpi")
    os.makedirs(nodpi, exist_ok=True)
    fit_jpg(os.path.join(GEN, "hero_stadium.png"), os.path.join(nodpi, "hero_fallback.jpg"), 1280, 720)
    fit_jpg(os.path.join(GEN, "empty_stadium.png"), os.path.join(nodpi, "empty_schedule.jpg"), 800, 480)
    fit_jpg(os.path.join(GEN, "empty_search.png"), os.path.join(nodpi, "empty_search.jpg"), 800, 480)
    print("assets generated")


if __name__ == "__main__":
    main()
