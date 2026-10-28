"""ink music's icon: INK in Archivo Black with MUSIC in Silkscreen under it, warm white on black,
as ink tv's is INK with TV (one family: ink's word, the product's name in the pixel face). From the
bundled fonts' own outlines. Adapted from ink's tools/make-icon.py.

Writes the adaptive icon's foreground (also its monochrome layer, for themed icons), the store's
512 px icon and its 1024 x 500 feature graphic (fastlane's, which IzzyOnDroid and F-Droid read), and
the Mac app's icon (desktop/ink-music.icns).
Needs fontTools and Pillow:
    python3 tools/make-icon.py
"""
import os
from fontTools.ttLib import TTFont
from fontTools.pens.basePen import BasePen
from fontTools.pens.boundsPen import BoundsPen
from PIL import Image, ImageDraw, ImageChops, ImageFont

ROOT = os.path.join(os.path.dirname(__file__), "..")
FONT = os.path.join(ROOT, "app/src/main/res/font/archivo_black.ttf")
PIXEL = os.path.join(ROOT, "app/src/main/res/font/silkscreen.ttf")
INK = "#F1F0EC"
TRACK = -0.02      # headline()'s letter spacing
CAP = 0.17         # the word's height as a share of the adaptive icon's 108 dp (inside the 66 dp safe circle)


class Outline(BasePen):
    """The glyphs as SVG path data and as flattened polygons, through a transform."""

    def __init__(self, gs, tf):
        super().__init__(gs); self.tf = tf; self.d = []; self.polys = []

    def _moveTo(self, p):
        self.last = p; self.polys.append([self.tf(p)]); self.d.append("M%s,%s" % self.tf(p, 2))

    def _lineTo(self, p):
        self.last = p; self.polys[-1].append(self.tf(p)); self.d.append("L%s,%s" % self.tf(p, 2))

    def _qCurveToOne(self, a, b):
        p0 = self.last
        for i in range(1, 9):
            t = i / 8; u = 1 - t
            self.polys[-1].append(self.tf(tuple(u * u * p0[k] + 2 * u * t * a[k] + t * t * b[k] for k in (0, 1))))
        self.last = b; self.d.append("Q%s,%s %s,%s" % (*self.tf(a, 2), *self.tf(b, 2)))

    def _curveToOne(self, a, b, c):
        p0 = self.last
        for i in range(1, 17):
            t = i / 16; u = 1 - t
            self.polys[-1].append(self.tf(tuple(u ** 3 * p0[k] + 3 * u * u * t * a[k] + 3 * u * t * t * b[k] + t ** 3 * c[k] for k in (0, 1))))
        self.last = c; self.d.append("C%s,%s %s,%s %s,%s" % (*self.tf(a, 2), *self.tf(b, 2), *self.tf(c, 2)))

    def _closePath(self): self.d.append("Z")


def word(text="INK", font=FONT, track=TRACK):
    """Each glyph with its x, and the word's inked bounds, in font units."""
    f = TTFont(font); gs = f.getGlyphSet(); cmap = f.getBestCmap(); upm = f["head"].unitsPerEm
    glyphs, x = [], 0
    for ch in text:
        g = cmap[ord(ch)]; glyphs.append((g, x)); x += f["hmtx"][g][0] + track * upm
    xs, ys = [], []
    for g, gx in glyphs:
        b = BoundsPen(gs); gs[g].draw(b)
        if b.bounds: xs += [b.bounds[0] + gx, b.bounds[2] + gx]; ys += [b.bounds[1], b.bounds[3]]
    return gs, glyphs, (min(xs), min(ys), max(xs), max(ys))


def placed(cap, left, bottom, text="INK", font=FONT, track=TRACK):
    """The word's outline with its ink `cap` tall, its left edge at `left`, its foot at `bottom`."""
    gs, glyphs, (x0, y0, x1, y1) = word(text, font, track)
    sc = cap / (y1 - y0)
    pens = []
    for g, gx in glyphs:
        def tf(p, nd=None, gx=gx):
            q = (left + (p[0] + gx - x0) * sc, bottom - (p[1] - y0) * sc)
            return q if nd is None else tuple(round(v, nd) for v in q)
        pen = Outline(gs, tf); gs[g].draw(pen); pens.append(pen)
    return pens, (x1 - x0) * sc


def centred(size, cap):
    _, w = placed(cap, 0, 0)
    return placed(cap, (size - w) / 2, (size + cap) / 2)[0]


def png(path, w, h, pens, ss=4):
    """Black, the word in ink, supersampled; contours combined even-odd."""
    mask = Image.new("1", (w * ss, h * ss), 0)
    for pen in pens:
        for poly in pen.polys:
            m = Image.new("1", mask.size, 0)
            ImageDraw.Draw(m).polygon([(x * ss, y * ss) for x, y in poly], fill=1)
            mask = ImageChops.logical_xor(mask, m)
    im = Image.new("RGB", mask.size, "black")
    im.paste(Image.new("RGB", mask.size, INK), mask=mask.convert("L"))
    im.resize((w, h), Image.LANCZOS).save(path)


def vector(path, pens, what):
    d = "".join("".join(p.d) for p in pens)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n'
                '<!-- made by tools/make-icon.py: %s, the adaptive icon\'s foreground and monochrome -->\n'
                '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
                '    android:width="108dp" android:height="108dp"\n'
                '    android:viewportWidth="108" android:viewportHeight="108">\n'
                '    <path android:fillColor="%s" android:pathData="%s" />\n'
                '</vector>\n' % (what, INK, d))


def stacked(size, cap):
    """INK with MUSIC under it, as wide as INK, its left on INK's, the two centred as one."""
    _, w = placed(cap, 0, 0)
    _, mw = placed(100, 0, 0, "MUSIC", PIXEL, 0)
    music = 100 * w / mw          # MUSIC as wide as INK: big enough to read on a 48 dp icon
    gap = cap * .3
    left, top = (size - w) / 2, (size - (cap + gap + music)) / 2
    return placed(cap, left, top + cap)[0] + placed(music, left, top + cap + gap + music, "MUSIC", PIXEL, 0)[0]


def feature(path, big_words="free", small_words="your music in ink's look. no ads, ever", cells=34):
    """The feature graphic: INK MUSIC small, a word giant in the pixel face, a line under it (as ink's)."""
    w, h, left = 1024, 500, 72
    im = Image.new("RGB", (w, h), "black"); d = ImageDraw.Draw(im)
    big, small = ImageFont.truetype(PIXEL, 8 * cells), ImageFont.truetype(PIXEL, 8 * 4)

    def say(text, font, foot, alpha=1.0):
        d.fontmode = "1"
        probe = Image.new("1", (w * 2, h)); ImageDraw.Draw(probe).text((0, h // 2), text, font=font, fill=1, anchor="ls")
        x0 = probe.getbbox()[0]
        c = tuple(round(v * alpha) for v in (0xF1, 0xF0, 0xEC))
        d.text((left - x0, foot), text, font=font, fill=c, anchor="ls")

    cap = 44
    top = (h - (cap + 52 + cells * 5 + 40 + 4 * 5)) // 2
    pens, _ = placed(cap, left, top + cap, "INK MUSIC")
    mask = Image.new("1", (w * 4, h * 4), 0)
    for pen in pens:
        for poly in pen.polys:
            m = Image.new("1", mask.size, 0)
            ImageDraw.Draw(m).polygon([(x * 4, y * 4) for x, y in poly], fill=1)
            mask = ImageChops.logical_xor(mask, m)
    im.paste(INK, mask=mask.convert("L").resize((w, h), Image.LANCZOS))
    say(big_words, big, top + cap + 52 + cells * 5)
    say(small_words, small, top + cap + 52 + cells * 5 + 40 + 4 * 5, .55)
    im.save(path)


def mac(path):
    """The Mac's icon: the words on black, in the rounded square every Mac icon sits in (Apple's grid: 824 of
    1024, corners 185; macOS 26 greys out an icon without it), at each size an .icns holds, as PNGs."""
    import io, struct
    big = 1024
    box, r = 824, 185
    off = (big - box) // 2
    ss = 4
    shape = Image.new("L", (big * ss, big * ss), 0)
    ImageDraw.Draw(shape).rounded_rectangle([off * ss, off * ss, (off + box) * ss, (off + box) * ss], r * ss, fill=255)
    shape = shape.resize((big, big), Image.LANCZOS)
    words = os.path.join(os.path.dirname(path), "icon-words.png")
    # the words as large on the square as on the store's icon
    png(words, box, box, stacked(box, CAP * 108 / 72 * box))
    im = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    im.paste(Image.new("RGBA", (big, big), (0, 0, 0, 255)), mask=shape)
    im.paste(Image.open(words).convert("RGBA"), (off, off), mask=shape.crop((off, off, off + box, off + box)))
    os.remove(words)
    entries = b""
    for kind, px in [(b"icp4", 16), (b"icp5", 32), (b"ic11", 32), (b"icp6", 64), (b"ic12", 64), (b"ic07", 128),
                     (b"ic08", 256), (b"ic13", 256), (b"ic09", 512), (b"ic14", 512), (b"ic10", 1024)]:
        buf = io.BytesIO(); im.resize((px, px), Image.LANCZOS).save(buf, "PNG")
        entries += kind + struct.pack(">I", 8 + len(buf.getvalue())) + buf.getvalue()
    with open(path, "wb") as f:
        f.write(b"icns" + struct.pack(">I", 8 + len(entries)) + entries)
    # the same, for the window while it runs (the app bundle shows the .icns)
    im.save(os.path.join(ROOT, "desktop/src/main/resources/ink-music.png"))


def main():
    vector(os.path.join(ROOT, "app/src/main/res/drawable/icon_word.xml"), stacked(108, CAP * 108), "INK in Archivo Black, MUSIC in Silkscreen under it")
    images = os.path.join(ROOT, "fastlane/metadata/android/en-US/images")
    os.makedirs(images, exist_ok=True)
    # the store's icon is the whole square (stores round it): as large as in the 72 dp a launcher shows
    png(os.path.join(images, "icon.png"), 512, 512, stacked(512, CAP * 108 / 72 * 512))
    feature(os.path.join(images, "featureGraphic.png"))
    mac(os.path.join(ROOT, "desktop/ink-music.icns"))


if __name__ == "__main__":
    main()
