# -*- coding: utf-8 -*-
"""把 icon_src.png（白底 Emby 造型）抠成透明图形，再打包成各密度 + 自适应图标。"""
from PIL import Image, ImageDraw, ImageFilter
import os

SRC = r"C:\embytv\icon_src.png"
RES = r"C:\embytv\app\res"
EDGE = (120, 130, 140, 110)

def cutout():
    im = Image.open(SRC).convert("RGBA")
    W, H = im.size
    # 从四角泛洪把白底刷成洋红，再转透明（三角被绿包住，不会被刷到）
    for xy in [(0, 0), (W - 1, 0), (0, H - 1), (W - 1, H - 1)]:
        ImageDraw.floodfill(im, xy, (255, 0, 255, 255), thresh=28)
    px = list(im.getdata())
    px = [(0, 0, 0, 0) if (p[0] == 255 and p[1] == 0 and p[2] == 255) else p for p in px]
    im.putdata(px)
    # 收紧透明边缘，去掉残留白边
    a = im.split()[3]
    a = a.filter(ImageFilter.MinFilter(3))
    im.putalpha(a)
    return im

def fit(mark, canvas, ratio):
    """把图形按 ratio 等比塞进 canvas 正方形，居中"""
    box = int(canvas * ratio)
    w, h = mark.size
    sc = box / max(w, h)
    m = mark.resize((max(1, int(w * sc)), max(1, int(h * sc))), Image.LANCZOS)
    out = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    out.paste(m, ((canvas - m.width) // 2, (canvas - m.height) // 2), m)
    return out

mark = cutout()
b = mark.getbbox()
mark = mark.crop(b)
print("mark", mark.size)

def legacy(size):
    ss = 4
    S = size * ss
    base = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(base)
    rad = int(S * 0.18)
    d.rounded_rectangle((0, 0, S - 1, S - 1), radius=rad, fill=(255, 255, 255, 255))
    d.rounded_rectangle((1, 1, S - 2, S - 2), radius=rad, outline=EDGE, width=max(2, int(S * 0.005)))
    base = Image.alpha_composite(base, fit(mark, S, 0.78))
    return base.resize((size, size), Image.LANCZOS)

def foreground(size):
    ss = 4
    S = size * ss
    return fit(mark, S, 0.66).resize((size, size), Image.LANCZOS)

def background(size):
    return Image.new("RGB", (size, size), (255, 255, 255))

for name, px in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
    folder = os.path.join(RES, "mipmap-" + name)
    os.makedirs(folder, exist_ok=True)
    legacy(px).save(os.path.join(folder, "ic_launcher.png"))

fg_dir = os.path.join(RES, "mipmap-xxxhdpi")
foreground(432).save(os.path.join(fg_dir, "ic_launcher_foreground.png"))
background(432).save(os.path.join(fg_dir, "ic_launcher_background.png"))

os.makedirs(os.path.join(RES, "drawable"), exist_ok=True)
legacy(512).save(os.path.join(RES, "drawable", "ic_launcher.png"))
legacy(320).save(os.path.join(RES, "drawable", "ic_launcher_320.png"))
print("icons ok")