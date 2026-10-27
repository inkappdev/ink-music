#!/usr/bin/env python3
"""
The GitHub social preview (.github/social-preview.png, 1280 x 640): INK MUSIC and what it is on the
left, three of the store's screenshots on the right, stepped down and running off the edge. Set by
hand in the repo's settings (Settings, Social preview) after it's made.

    python3 tools/make-social.py [first second third]   # screenshot numbers, default 2 3 4
"""
import os
import sys
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.join(os.path.dirname(__file__), "..")
SHOTS = os.path.join(ROOT, "fastlane/metadata/android/en-US/images/phoneScreenshots")
FONTS = os.path.join(ROOT, "app/src/main/res/font")
INK = (242, 240, 235)
MID = (242 * 55 // 100, 240 * 55 // 100, 235 * 55 // 100)   # ink at 55%, as the app's secondary words

W, H = 1280, 640
picked = [int(a) for a in sys.argv[1:4]] or [2, 3, 4]

img = Image.new("RGB", (W, H), (0, 0, 0))
d = ImageDraw.Draw(img)
archivo = lambda size: ImageFont.truetype(os.path.join(FONTS, "archivo_black.ttf"), size)
silk = lambda size: ImageFont.truetype(os.path.join(FONTS, "silkscreen.ttf"), size)

# the words
x = 78
d.text((x, 88), "INK", font=archivo(118), fill=INK)
d.text((x, 205), "MUSIC", font=archivo(118), fill=INK)
for i, line in enumerate(["A FREE MUSIC PLAYER", "FOR ANDROID"]):
    d.text((x, 366 + i * 30), line, font=silk(19), fill=INK)
for i, line in enumerate(["YOUR FILES, NAVIDROME,", "YOUTUBE MUSIC, INDIE RADIO"]):
    d.text((x, 452 + i * 30), line, font=silk(19), fill=MID)
d.text((x, 568), "NO ADS. NO ACCOUNTS. OPEN SOURCE.", font=silk(15), fill=MID)
d.text((x, 594), "GITHUB.COM/INKAPPDEV/INK-MUSIC", font=silk(15), fill=INK)

# the phones: each a screenshot at 270 px wide with a hairline edge, stepped, the last running off the page
w = 272
for i, n in enumerate(picked):
    shot = Image.open(os.path.join(SHOTS, f"{n}.png")).convert("RGB")
    h = shot.height * w // shot.width
    shot = shot.resize((w, h), Image.LANCZOS)
    px, py = 530 + i * 288, 130 - i * 65
    img.paste(shot, (px, py))
    d.rectangle((px - 1, py - 1, px + w, py + h), outline=(40, 40, 39))   # the 12% hairline round each

img.save(os.path.join(ROOT, ".github/social-preview.png"), optimize=True)
print("made .github/social-preview.png from screenshots", picked)
