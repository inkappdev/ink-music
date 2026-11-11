#!/usr/bin/env python3
"""
r/inkmusic's banners on the site's paper (2026-11-11): warm off-white, its dot grid, dark covers in the app's dither
print (ink dots on black) from the left, INK MUSIC and a tagline on the right, clear of the community icon at the
lower left. No artists named.

    python3 tools/make-reddit-paper.py <covers dir> <out.png> <w> <h> "<tagline>" a.jpg b.jpg ...
    (BIG: the name's size, COVER: the covers', each a share of the height; desktop 1920 384, phone 1600 480 COVER=.68 BIG=.21)
"""
from PIL import Image, ImageDraw, ImageFont
covers_dir, out, W, H, tag = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4]), sys.argv[5]
names = sys.argv[6:]
F = "app/src/main/res/font/"
PAGE = (236, 235, 230); INKD = (18, 18, 17); INK = (241, 240, 236)
mix = lambda a: tuple(round(PAGE[i] * (1 - a) + INKD[i] * a) for i in range(3))
BAYER = [0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5]
def dithered(path, side, n=56):
    im = Image.open(path).convert("RGB")
    small = im.resize((max(16, n // 3),) * 2, Image.BILINEAR).resize((n, n), Image.BILINEAR)
    lum = [(.3 * r + .59 * g + .11 * b) / 255 for r, g, b in small.getdata()]
    lo, hi = min(lum), max(lum); span = max(hi - lo, .01)
    o = Image.new("RGB", (n, n), (0, 0, 0)); px = o.load()
    for y in range(n):
        for x in range(n):
            if (lum[y * n + x] - lo) / span > (BAYER[(y & 3) * 4 + (x & 3)] + .5) / 16: px[x, y] = INK
    return o.resize((side, side), Image.NEAREST)
img = Image.new("RGB", (W, H), PAGE); d = ImageDraw.Draw(img)
for y in range(5, H, 10):
    for x in range(5, W, 10): d.ellipse((x - .9, y - .9, x + .9, y + .9), fill=mix(.14))
h = round(H * float(__import__("os").environ.get("COVER", ".74"))); top = (H - h) // 2; gap = round(H * .06)
# the words on the right, clear of the community icon at the lower left
right = W - round(H * .22)
size = round(H * float(__import__("os").environ.get("BIG", ".25")))
big = ImageFont.truetype(F + "archivo_black.ttf", size)
small = ImageFont.truetype(F + "silkscreen.ttf", round(H * .062))
tw = max(big.getlength("INK MUSIC"), small.getlength(tag))
textleft = right - tw
b = d.textbbox((0, 0), "INK MUSIC", font=big)
y = H // 2 - (b[3] - b[1]) // 2 - b[1] - round(H * .06)
d.text((right - big.getlength("INK MUSIC"), y), "INK MUSIC", font=big, fill=INKD)
d.text((right - small.getlength(tag), y + b[3] + round(H * .07)), tag, font=small, fill=mix(.62))
# the covers from the left, as many as fit before the words, the last cut off by nothing: whole ones only
x = round(H * .14)
for n in names:
    if x + h > textleft - round(H * .2): break
    img.paste(dithered(f"{covers_dir}/{n}", h), (x, top)); x += h + gap
img.save(out)
