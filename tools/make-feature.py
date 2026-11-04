#!/usr/bin/env python3
"""
The feature picture with every device (2026-11-04): a computer's window, a TV and a phone, each showing its own
thing. 16:9, 2560 x 1440, so Reddit and the like show it whole.

Its look (Bronson's, after nothing.tech): ink's own paper (#F2F0EB) under a grid of small square dots, the texture;
INK MUSIC solid in Archivo Black in the corner the screens leave; the screens laid straight on it, their own black
their edge (no borders, shadows or rounded corners: the Mac's window frame and the phone's status bar trimmed off); one small line of Silkscreen saying where it runs.
--dark: the first look instead, black as .github/hero.png, each screen a 12% hairline.

    python3 tools/make-feature.py [--dark] <window.png> <tv.png> <phone.png> [out.jpg]

window: a Mac window shot (shift-cmd-4, space: its shadow and frame are cut away); tv: `adb exec-out screencap -p`
from the TV (1920 x 1080); phone: the same from the phone. Writes build/feature.jpg unless told otherwise.
"""
import os
import sys
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.join(os.path.dirname(__file__), "..")
FONTS = os.path.join(ROOT, "app/src/main/res/font")
W, H = 2560, 1440
GAP = 14                    # between one screen and the one in front of it
PAPER = (242, 240, 235)     # Ink.ink: the light the app's words are, here the ground
DOT = (198, 196, 190)       # the grid's dots
NAME = (28, 28, 27)         # the dots inside the name
SAID = (110, 108, 104)      # the small line
STEP = 12                   # the grid, px
EDGE = (40, 40, 39)         # --dark: the 12% hairline


def window(path):
    """The window with its shadow, rounded corners and title bar cut away."""
    i = Image.open(path).convert("RGBA")
    box = i.getchannel("A").point(lambda v: 255 if v > 250 else 0).getbbox()
    w = i.crop(box).convert("RGB")
    s = w.width / 1440            # the window's scale (a Retina shot is twice its points)
    return w.crop((int(14 * s), int(28 * s), w.width - int(14 * s), w.height - int(14 * s)))


def phone(path):
    """The phone's screen without its status bar and the gesture bar's strip."""
    p = Image.open(path).convert("RGB")
    s = p.width / 1080
    return p.crop((0, int(120 * s), p.width, p.height - int(64 * s)))


def place(canvas, img, x, y, width, apart, edge=None):
    img = img.resize((width, round(img.height * width / img.width)), Image.LANCZOS)
    d = ImageDraw.Draw(canvas)
    # (dark) kept apart from what's behind it by black; on the paper, laid straight on (its own black edge is enough)
    if apart: d.rectangle((x - GAP, y - GAP, x + img.width + GAP - 1, y + img.height + GAP - 1), fill=apart)
    canvas.paste(img, (x, y))
    if edge: d.rectangle((x - 1, y - 1, x + img.width, y + img.height), outline=edge, width=2)


def paper():
    """The ground: the dot grid, and INK MUSIC in Archivo Black, solid, in the bottom left corner (made of the grid's
    dots at first: its M broke up)."""
    c = Image.new("RGB", (W, H), PAPER)
    d = ImageDraw.Draw(c)
    for y in range(STEP // 2, H, STEP):
        for x in range(STEP // 2, W, STEP):
            d.rectangle((x - 1, y - 1, x + 1, y + 1), fill=DOT)
    f = ImageFont.truetype(os.path.join(FONTS, "archivo_black.ttf"), 200)
    d.text((64, 1010), "INK", font=f, fill=NAME)
    d.text((64, 1196), "MUSIC", font=f, fill=NAME)
    return c


def main():
    dark = "--dark" in sys.argv
    args = [a for a in sys.argv[1:] if a != "--dark"]
    if len(args) < 3:
        print(__doc__); sys.exit(1)
    out = args[3] if len(args) > 3 else os.path.join(ROOT, "build/feature.jpg")
    if dark:
        c, apart, edge = Image.new("RGB", (W, H), (0, 0, 0)), (0, 0, 0), EDGE
    else:
        c, apart, edge = paper(), None, None
    # the computer behind at the top left; the TV in front of its lower right, its left side (a lit cover, what plays
    # elsewhere in its bar) kept clear; the phone in front of the TV's right edge, standing tall
    place(c, window(args[0]), 50, 60, 1640, apart, edge)
    place(c, Image.open(args[1]).convert("RGB"), 900, 656, 1200, apart, edge)
    place(c, phone(args[2]), 2010, 160, 500, apart, edge)
    if not dark:
        # where it runs, small, a column under the phone (right of the TV), as a spec sheet lists it
        f = ImageFont.truetype(os.path.join(FONTS, "silkscreen.ttf"), 24)
        d = ImageDraw.Draw(c)
        for i, line in enumerate(["android", "android tv", "mac", "windows", "linux", "", "free, made by ink"]):
            d.text((2510 - f.getlength(line), 1196 + i * 32), line, font=f, fill=SAID)
    c.save(out, quality=92)
    print(out)


if __name__ == "__main__":
    main()
