#!/usr/bin/env python3
"""
The feature picture with every device (2026-11-04): a computer's window, a TV and a phone, each showing its own
thing, frameless on black as .github/hero.png has them (each screen a 12% hairline, the one in front kept apart by a
black gap, no rounded corners: the Mac's window frame and the phone's status bar trimmed off). 16:9, 2560 x 1440, so
Reddit and the like show it whole.

    python3 tools/make-feature.py <window.png> <tv.png> <phone.png> [out.jpg]

window: a Mac window shot (shift-cmd-4, space: its shadow and frame are cut away); tv: `adb exec-out screencap -p`
from the TV (1920 x 1080); phone: the same from the phone. Writes build/feature.jpg unless told otherwise.
"""
import os
import sys
from PIL import Image, ImageDraw

ROOT = os.path.join(os.path.dirname(__file__), "..")
EDGE = (40, 40, 39)   # the 12% hairline
W, H = 2560, 1440
GAP = 14              # black between one screen and the one in front of it


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


def place(canvas, img, x, y, width):
    img = img.resize((width, round(img.height * width / img.width)), Image.LANCZOS)
    d = ImageDraw.Draw(canvas)
    # kept apart from what's behind it, then its hairline
    d.rectangle((x - GAP, y - GAP, x + img.width + GAP - 1, y + img.height + GAP - 1), fill=(0, 0, 0))
    canvas.paste(img, (x, y))
    d.rectangle((x - 1, y - 1, x + img.width, y + img.height), outline=EDGE, width=2)
    return img.size


def main():
    if len(sys.argv) < 4:
        print(__doc__); sys.exit(1)
    out = sys.argv[4] if len(sys.argv) > 4 else os.path.join(ROOT, "build/feature.jpg")
    c = Image.new("RGB", (W, H), (0, 0, 0))
    # the computer behind at the top left; the TV in front of its lower right, its left side (a lit cover, what plays
    # elsewhere in its bar) kept clear; the phone in front of the TV's right edge, standing tall
    place(c, window(sys.argv[1]), 50, 86, 1640)
    place(c, Image.open(sys.argv[2]).convert("RGB"), 860, 656, 1240)
    place(c, phone(sys.argv[3]), 2010, 186, 500)
    c.save(out, quality=92)
    print(out)


if __name__ == "__main__":
    main()
