#!/usr/bin/env python3
"""
r/inkmusic's pictures, in build/reddit/: the community icon (icon.png, 512 px: Reddit shows it as a circle), and its
banners, the desktop's (banner-desktop.png, 1920 x 384) and the phone's (banner-mobile.png, 1600 x 480).

The icon is the app's (INK over MUSIC), with the player's own glow round it: the cover's colour in 1-bit dots,
thinning out from the middle (ordered dither, as the app's). The banners are the giant marquee: INK MUSIC over and over in
Archivo Black, cut off at both ends, a colour dot between each.

    python3 tools/make-reddit.py
"""
import math
import os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.join(os.path.dirname(__file__), "..")
FONTS = os.path.join(ROOT, "app/src/main/res/font")
OUT = os.path.join(ROOT, "build/reddit")
INK = (242, 240, 235)
archivo = lambda size: ImageFont.truetype(os.path.join(FONTS, "archivo_black.ttf"), size)
silk = lambda size: ImageFont.truetype(os.path.join(FONTS, "silkscreen.ttf"), size)

# the 4 x 4 ordered-dither thresholds
BAYER = [[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]

# colours from the covers in the shots: Almost Heaven's maroon, Westside Cowboy's green, Holiday's pink, Pale Blue's blue
COLOURS = [(128, 36, 68), (140, 190, 70), (232, 150, 120), (110, 170, 210)]


def glow(draw, size, colour, inner, outer, cell):
    """Dots of `colour` in a ring from `inner` (all lit) to `outer` (none), thinning as the threshold allows."""
    c = size / 2
    for gy in range(0, size, cell):
        for gx in range(0, size, cell):
            r = math.hypot(gx + cell / 2 - c, gy + cell / 2 - c)
            if r < inner or r > outer:
                continue
            level = 1 - (r - inner) / (outer - inner)   # 1 at the inner edge, 0 at the outer
            if level * 16 > BAYER[(gy // cell) % 4][(gx // cell) % 4]:
                draw.rectangle((gx, gy, gx + cell - 2, gy + cell - 2), fill=colour)


def icon(size=512):
    img = Image.new("RGB", (size, size), (0, 0, 0))
    d = ImageDraw.Draw(img)
    # the glow, then black behind the words so they stand clear of it
    glow(d, size, COLOURS[0], size * 0.34, size * 0.50, size // 64)
    d.ellipse((size * 0.17, size * 0.17, size * 0.83, size * 0.83), fill=(0, 0, 0))
    # INK big, MUSIC in the pixel face under it, as the app's icon, inside the circle
    ink = archivo(int(size * 0.235))
    w = d.textlength("INK", font=ink)
    d.text(((size - w) / 2, size * 0.325), "INK", font=ink, fill=INK)
    music = silk(int(size * 0.088))
    w = d.textlength("MUSIC", font=music)
    d.text(((size - w) / 2, size * 0.585), "MUSIC", font=music, fill=INK)
    return img


def banner(w, h, names, size):
    img = Image.new("RGB", (w, h), (0, 0, 0))
    d = ImageDraw.Draw(img)
    font = archivo(size)
    # one line of names from just off the left edge, a coloured dot between each, run past the right edge
    x = -int(size * 1.4)
    top = d.textbbox((0, 0), "A", font=font)
    y = (h - (top[3] - top[1])) // 2 - top[1]
    i = 0
    while x < w:
        name = names[i % len(names)]
        d.text((x, y), name, font=font, fill=INK)
        x += int(d.textlength(name, font=font)) + int(size * 0.28)
        dot = int(size * 0.16)
        cy = y + top[1] + (top[3] - top[1]) // 2
        d.rectangle((x, cy - dot // 2, x + dot, cy + dot // 2), fill=COLOURS[i % len(COLOURS)])
        x += dot + int(size * 0.28)
        i += 1
    return img


os.makedirs(OUT, exist_ok=True)
icon().save(os.path.join(OUT, "icon.png"), optimize=True)
names = ["INK MUSIC"]   # the name, over and over, as the marquee runs an artist's (Bronson, 2026-10-29)
banner(1920, 384, names, 150).save(os.path.join(OUT, "banner-desktop.png"), optimize=True)
banner(1600, 480, names, 170).save(os.path.join(OUT, "banner-mobile.png"), optimize=True)
for f in sorted(os.listdir(OUT)):
    print(f, os.path.getsize(os.path.join(OUT, f)) // 1024, "KB")
