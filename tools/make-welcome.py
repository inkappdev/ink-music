#!/usr/bin/env python3
"""
The picture for r/inkmusic's welcome post (build/welcome.jpg, 2560 x 1440): the post's three things side by side,
each its number, its words and a screen: how it looks (a phone's player), what to listen to next (INK), it's yours
(the computer with a phone in front of it). Screens from the store's and the README's.

    python3 tools/make-welcome.py [look] [next] [yours-phone]   # phone screenshots: a number or a picture's path
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
DIM = tuple(c * 35 // 100 for c in INK)   # 35%, what's not chosen
EDGE = (40, 40, 39)                       # the 12% hairline

W, H = 2560, 1440
look, nxt, yours = (sys.argv[1:4] + ["1", "2", "4"][len(sys.argv[1:4]):])

img = Image.new("RGB", (W, H), (0, 0, 0))
d = ImageDraw.Draw(img)
archivo = lambda size: ImageFont.truetype(os.path.join(FONTS, "archivo_black.ttf"), size)
silk = lambda size: ImageFont.truetype(os.path.join(FONTS, "silkscreen.ttf"), size)


def phone(n, width):
    p = Image.open(n if os.path.isfile(n) else os.path.join(SHOTS, f"{n}.png")).convert("RGB")
    return p.resize((width, p.height * width // p.width), Image.LANCZOS)


def framed(p, x, y):
    d.rectangle((x - 14, y - 14, x + p.width + 13, y + p.height + 13), fill=(0, 0, 0))
    img.paste(p, (x, y))
    d.rectangle((x - 2, y - 2, x + p.width + 1, y + p.height + 1), outline=EDGE, width=2)


# the top: whose it is, and the welcome
M = 120
d.text((M, 96), "INK MUSIC", font=archivo(56), fill=INK)
d.text((M, 172), "WELCOME TO R/INKMUSIC", font=silk(30), fill=MID)
d.text((W - M, 112), "FREE  ·  OPEN SOURCE  ·  ANDROID, MAC, WINDOWS, LINUX", font=silk(26), fill=MID, anchor="ra")

# three columns, each its number and words over its screen; the screens run off the foot
col = (W - 2 * M) // 3
words = [("1", "HOW IT LOOKS", "MADE TO BE LOOKED AT"),
         ("2", "WHAT'S NEXT", "PICKED BY PEOPLE, NOT A FEED"),
         ("3", "IT'S YOURS", "PHONE AND COMPUTER, IN STEP")]
top = 300
for i, (n, head, said) in enumerate(words):
    x = M + i * col
    d.text((x, top), n, font=archivo(44), fill=DIM)
    d.text((x + 56, top), head, font=archivo(44), fill=INK)
    d.text((x + 56, top + 66), said, font=silk(24), fill=MID)

sy = top + 150
pw = 470
framed(phone(look, pw), M + 56, sy)
framed(phone(nxt, pw), M + col + 56, sy)

# it's yours: the computer's window (its light-page shadow cut away), a phone standing in front of it
shot = Image.open(os.path.join(DESK, "westside.png")).convert("RGBA")
shot = shot.crop(shot.getchannel("A").point(lambda a: 255 if a > 250 else 0).getbbox())
ww = col - 56   # its right edge on the margin
shot = shot.resize((ww, shot.height * ww // shot.width), Image.LANCZOS)
wx, wy = M + 2 * col + 56, sy
img.paste(shot, (wx, wy), shot)
d.rounded_rectangle((wx - 1, wy - 1, wx + ww, wy + shot.height), radius=14, outline=EDGE, width=2)
framed(phone(yours, 360), wx + 40, wy + shot.height - 120)

os.makedirs(os.path.join(ROOT, "build"), exist_ok=True)
out = os.path.join(ROOT, "build/welcome.jpg")
img.save(out, quality=90, optimize=True, progressive=True, subsampling=0)
print(f"made {out} ({os.path.getsize(out) // 1024} KB)")
