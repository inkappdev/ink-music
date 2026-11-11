#!/usr/bin/env python3
"""
r/inkmusic's pictures, in build/reddit/: the community icon (icon.png, 512 px: Reddit shows it as a circle), and its
banners, the desktop's (banner-desktop.png, 1920 x 384) and the phone's (banner-mobile.png, 1600 x 480).

Strict ink (2026-11-11: the maroon ring and the coloured dots read as brand colours, and ink has none; album art is the
only colour, and on Reddit that's what's posted): black, the app's warm ink at its three steps (ink, 70%, 35%) and the 12%
hairline, Archivo Black and Silkscreen. The icon is INK over music, nothing round it. The banners are the app's own top:
its places in a row, listen lit, the rest at 35%, the hairline under them, and "ink music" small in the pixel face.
The subreddit's own colours to go with it (mod tools, look and feel): base #000000, highlight #F1F0EC.

    python3 tools/make-reddit.py
"""
import os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.join(os.path.dirname(__file__), "..")
FONTS = os.path.join(ROOT, "app/src/main/res/font")
OUT = os.path.join(ROOT, "build/reddit")
archivo = lambda size: ImageFont.truetype(os.path.join(FONTS, "archivo_black.ttf"), size)
silk = lambda size: ImageFont.truetype(os.path.join(FONTS, "silkscreen.ttf"), size)

# the app's ink (Theme.kt) over black at its steps: lit, 70% (a hint, a label), 35% (not chosen), 12% (a hairline)
INK = (241, 240, 236)
step = lambda a: tuple(round(c * a) for c in INK)
MID, DIM, LINE = step(.70), step(.35), step(.12)


def icon(size=512):
    img = Image.new("RGB", (size, size), (0, 0, 0))
    d = ImageDraw.Draw(img)
    ink = archivo(int(size * 0.27))
    w = d.textlength("INK", font=ink)
    d.text(((size - w) / 2, size * 0.30), "INK", font=ink, fill=INK)
    music = silk(int(size * 0.10))
    w = d.textlength("music", font=music)
    d.text(((size - w) / 2, size * 0.60), "music", font=music, fill=INK)
    return img


def banner(w, h, size, left):
    """The places in a row (LISTEN lit), the hairline under, "ink music" under that at 70%."""
    img = Image.new("RGB", (w, h), (0, 0, 0))
    d = ImageDraw.Draw(img)
    font = archivo(size)
    words = ["LISTEN", "ALBUMS", "ARTISTS", "SONGS", "LISTS", "FIND"]
    top = d.textbbox((0, 0), "A", font=font)
    cap = top[3] - top[1]
    gap = int(size * 0.55)
    y = int(h * 0.40) - cap // 2 - top[1]
    x = left
    for i, word in enumerate(words):
        if x > w:
            break
        d.text((x, y), word, font=font, fill=INK if i == 0 else DIM)
        x += int(d.textlength(word, font=font)) + gap
    # the hairline under the row, edge to edge, as under the app's top
    line_y = y + top[3] + int(size * 0.42)
    d.rectangle((0, line_y, w, line_y + max(1, size // 40)), fill=LINE)
    small = silk(max(12, int(size * 0.30)))
    d.text((left, line_y + int(size * 0.34)), "ink music", font=small, fill=MID)
    return img


os.makedirs(OUT, exist_ok=True)
icon().save(os.path.join(OUT, "icon.png"), optimize=True)
banner(1920, 384, 56, 240).save(os.path.join(OUT, "banner-desktop.png"), optimize=True)
banner(1600, 480, 72, 120).save(os.path.join(OUT, "banner-mobile.png"), optimize=True)
for f in sorted(os.listdir(OUT)):
    print(f, os.path.getsize(os.path.join(OUT, f)) // 1024, "KB")
