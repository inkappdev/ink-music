"""The Windows installer's two pictures, for WiX's wizard (desktop/windows/, read by main.wxs):
the welcome and finish pages' panel (WixUIDialogBmp) and the strip over the pages between
(WixUIBannerBmp). WiX writes its own words over them in black, from about 36% across the panel
and from the strip's left, so ink's black is the left of the panel and the strip's right end;
the rest stays white for the words. INK in Archivo Black, MUSIC in Silkscreen, as the icon; a
radio's waves dithered under them, as the stations' sleeves. Drawn at twice WiX's size (493 x 312,
493 x 58): WiX stretches them to the dialog, so a 2x screen gets them sharp.
Needs Pillow:
    python3 tools/make-installer-art.py
"""
import os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.join(os.path.dirname(__file__), "..")
FONT = os.path.join(ROOT, "app/src/main/res/font/archivo_black.ttf")
PIXEL = os.path.join(ROOT, "app/src/main/res/font/silkscreen.ttf")
OUT = os.path.join(ROOT, "desktop/windows")
INK = (0xF1, 0xF0, 0xEC)
BLACK = (0, 0, 0)
WHITE = (255, 255, 255)
X2 = 2

# a 4 x 4 Bayer matrix: a level from 0 to 1 becomes dots, as the app's dither print
BAYER = [[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]


def dim(c, a):
    return tuple(round(v * a) for v in c)


def word(d, x, y, size):
    """INK, and MUSIC under it as wide, as the icon has them; where its foot is."""
    big = ImageFont.truetype(FONT, size)
    l, t, r, b = d.textbbox((0, 0), "INK", font=big)
    d.text((x - l, y - t), "INK", font=big, fill=INK)
    px = 4
    while True:                              # Silkscreen's size that makes MUSIC as wide as INK
        f = ImageFont.truetype(PIXEL, px + 1)
        ml, _, mr, _ = d.textbbox((0, 0), "MUSIC", font=f)
        if mr - ml > r - l: break
        px += 1
    small = ImageFont.truetype(PIXEL, px)
    ml, mt, mr, mb = d.textbbox((0, 0), "MUSIC", font=small)
    gap = round((b - t) * .3)
    d.text((x - ml, y + (b - t) + gap - mt), "MUSIC", font=small, fill=INK)
    return y + (b - t) + gap + (mb - mt)


def waves(d, box, dot):
    """A radio's rings from the box's lower left corner, dithered: bright near the tower, fading out."""
    x0, y0, x1, y1 = box
    cx, cy = x0, y1
    reach = ((x1 - x0) ** 2 + (y1 - y0) ** 2) ** .5
    for y in range(y0, y1, dot):
        for x in range(x0, x1, dot):
            r = ((x - cx) ** 2 + (y - cy) ** 2) ** .5
            ring = (r / (dot * 7)) % 1          # a ring every 7 dots
            if ring > .55:
                continue
            level = max(0, 1 - r / reach) ** .8
            i, j = (x // dot) % 4, (y // dot) % 4
            if level * 16 > BAYER[j][i]:
                d.rectangle([x, y, x + dot - 2, y + dot - 2], fill=dim(INK, .7))


def dialog():
    w, h = 493 * X2, 312 * X2
    im = Image.new("RGB", (w, h), WHITE)
    d = ImageDraw.Draw(im)
    panel = 168 * X2                       # WiX's words start at 135 dialog units, about 180 px
    d.rectangle([0, 0, panel, h], fill=BLACK)
    pad = 18 * X2
    word(d, pad, pad + 4 * X2, 58 * X2)
    waves(d, (0, 150 * X2, panel, h - 34 * X2), 4 * X2)
    note = ImageFont.truetype(PIXEL, 8 * X2)
    d.text((pad, h - 24 * X2), "made by ink", font=note, fill=dim(INK, .7))
    im.save(os.path.join(OUT, "dialog.bmp"))


def banner():
    w, h = 493 * X2, 58 * X2
    im = Image.new("RGB", (w, h), WHITE)
    d = ImageDraw.Draw(im)
    d.rectangle([w - h, 0, w, h], fill=BLACK)
    word(d, w - h + 10 * X2, 17 * X2, 18 * X2)
    im.save(os.path.join(OUT, "banner.bmp"))


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    dialog()
    banner()
