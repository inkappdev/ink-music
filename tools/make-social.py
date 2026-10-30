#!/usr/bin/env python3
"""
The GitHub social preview (.github/social-preview.jpg, 2560 x 1280: GitHub's 1280 x 640 at twice the size, sharp on
any screen; a jpg, as GitHub takes 1 MB at most): INK MUSIC and what it is on the left; on the right the computer's
player (a window shot from the Mac, .github/desktop/) running off the edge, a phone (one of the store's screenshots)
standing in front of its right side. Set by hand in the repo's settings (Settings, Social preview) after it's made.

    python3 tools/make-social.py [--tall] [phone] [window]   # a phone screenshot's number or a picture's path
                                                             # (default 1), a desktop shot (default westside)

--tall: 16:9 (2560 x 1440) for a post (Reddit's and the like show it whole), the phone bigger; to build/social-tall.jpg.
"""
import os
import sys
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.join(os.path.dirname(__file__), "..")
SHOTS = os.path.join(ROOT, "fastlane/metadata/android/en-US/images/phoneScreenshots")
DESK = os.path.join(ROOT, ".github/desktop")
FONTS = os.path.join(ROOT, "app/src/main/res/font")
INK = (242, 240, 235)
MID = tuple(c * 70 // 100 for c in INK)   # ink at 70%, as the app's hints
EDGE = (40, 40, 39)                       # the 12% hairline

tall = "--tall" in sys.argv
args = [a for a in sys.argv[1:] if a != "--tall"]
W, H = (2560, 1440) if tall else (2560, 1280)
dy = (H - 1280) // 2   # the same picture, centred in the taller frame
phone = args[0] if args else "1"
window = args[1] if len(args) > 1 else "westside"

img = Image.new("RGB", (W, H), (0, 0, 0))
d = ImageDraw.Draw(img)
archivo = lambda size: ImageFont.truetype(os.path.join(FONTS, "archivo_black.ttf"), size)
silk = lambda size: ImageFont.truetype(os.path.join(FONTS, "silkscreen.ttf"), size)

# the computer: the window alone (its shadow, made for a light page, cut away), big, running off the right edge
shot = Image.open(os.path.join(DESK, f"{window}.png")).convert("RGBA")
box = shot.getchannel("A").point(lambda a: 255 if a > 250 else 0).getbbox()
shot = shot.crop(box)
ww = 1700
shot = shot.resize((ww, shot.height * ww // shot.width), Image.LANCZOS)
wx, wy = 980, 130 + dy
img.paste(shot, (wx, wy), shot)
d.rounded_rectangle((wx - 1, wy - 1, wx + ww, wy + shot.height), radius=22, outline=EDGE, width=2)

# the phone: in front of the window's right side (the cover and title left clear), a hairline round it and black between them
p = Image.open(phone if os.path.isfile(phone) else os.path.join(SHOTS, f"{phone}.png")).convert("RGB")
pw = 500 if tall else 440
p = p.resize((pw, p.height * pw // p.width), Image.LANCZOS)
px, py = W - pw - 120, 400 + dy   # below the title, running just off the foot
d.rectangle((px - 14, py - 14, px + pw + 13, py + p.height + 13), fill=(0, 0, 0))
img.paste(p, (px, py))
d.rectangle((px - 2, py - 2, px + pw + 1, py + p.height + 1), outline=EDGE, width=2)

# the words
x = 150
d.text((x, 170 + dy), "INK", font=archivo(200), fill=INK)
d.text((x, 370 + dy), "MUSIC", font=archivo(200), fill=INK)
for i, line in enumerate(["A FREE MUSIC PLAYER", "FOR YOUR PHONE", "AND YOUR COMPUTER"]):
    d.text((x, 650 + dy + i * 56), line, font=silk(36), fill=INK)
for i, line in enumerate(["ANDROID, MAC,", "WINDOWS, LINUX"]):
    d.text((x, 860 + dy + i * 56), line, font=silk(36), fill=MID)
d.text((x, 1094 + dy), "NO ADS. NO ACCOUNTS. OPEN SOURCE.", font=silk(26), fill=MID)
d.text((x, 1140 + dy), "GITHUB.COM/INKAPPDEV/INK-MUSIC", font=silk(26), fill=INK)

out = os.path.join(ROOT, "build/social-tall.jpg" if tall else ".github/social-preview.jpg")
img.save(out, quality=90, optimize=True, progressive=True, subsampling=0)
print(f"made {out} ({os.path.getsize(out) // 1024} KB) from phone {os.path.basename(phone)} and the computer's {window}")
