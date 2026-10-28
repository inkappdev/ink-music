package app.ink.music

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import kotlin.math.roundToInt

/**
 * A picture in the dither (the phone's Looks.develop, its pixels by Skia): softened, then 1-bit through a Bayer 4 x 4,
 * 3 dp dots, in the picture's own colour on black, developing from black (the brightest dots first) in `frames` steps.
 * An artist's photo is shown this way, album art the only full colour.
 */
object Dither {
    private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

    fun develop(art: ImageBitmap, accent: Color, shown: Float, density: Float, frames: Int = 12): List<ImageBitmap>? = runCatching {
        val img = Image.makeFromBitmap(art.asSkiaBitmapCopy())
        val dots = (shown / (3 * density)).roundToInt().coerceIn(24, 400)
        val small = (dots / 3).coerceAtLeast(16)
        val soft = pixels(img, small).also { px -> repeat(3) { blur(px, small, 1, true); blur(px, small, 1, false) } }
        val px = pixels(image(soft, small), dots)
        val lum = FloatArray(dots * dots) { i -> val p = px[i]; (.3f * (p shr 16 and 255) + .59f * (p shr 8 and 255) + .11f * (p and 255)) / 255 }
        val lo = lum.min(); val span = (lum.max() - lo).coerceAtLeast(.01f)
        val on = (255 shl 24) or (accent.toArgb() and 0xFFFFFF)
        (1..frames).map { f ->
            val gain = f.toFloat() / frames
            val out = IntArray(dots * dots)
            for (y in 0 until dots) for (x in 0 until dots) {
                val t = (BAYER[(y and 3) * 4 + (x and 3)] + .5f) / 16
                out[y * dots + x] = if ((lum[y * dots + x] - lo) / span * gain > t) on else 0xFF000000.toInt()
            }
            image(out, dots).toComposeImageBitmap()
        }
    }.getOrNull()

    private fun ImageBitmap.asSkiaBitmapCopy(): org.jetbrains.skia.Bitmap = asSkiaBitmap()

    /** The picture drawn `n` px square (smoothly), as ARGB ints. */
    private fun pixels(img: Image, n: Int): IntArray {
        val s = Surface.makeRaster(ImageInfo(n, n, ColorType.BGRA_8888, ColorAlphaType.OPAQUE))
        s.canvas.drawImageRect(img, Rect.makeWH(img.width.toFloat(), img.height.toFloat()), Rect.makeWH(n.toFloat(), n.toFloat()),
            SamplingMode.LINEAR, org.jetbrains.skia.Paint(), true)
        val b = org.jetbrains.skia.Bitmap().apply { allocPixels(ImageInfo(n, n, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)) }
        s.readPixels(b, 0, 0)
        val bytes = b.readPixels() ?: return IntArray(n * n)
        val ints = IntArray(n * n)
        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(ints)
        return ints
    }

    /** ARGB ints as a Skia image (BGRA bytes, little-endian: the same memory). */
    private fun image(px: IntArray, n: Int): Image {
        val bytes = java.nio.ByteBuffer.allocate(px.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply { asIntBuffer().put(px) }.array()
        return Image.makeRaster(ImageInfo(n, n, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), bytes, n * 4)
    }

    /** One box blur pass, `r` px each side, along rows (or columns), edges held. */
    private fun blur(px: IntArray, n: Int, r: Int, rows: Boolean) {
        val out = IntArray(n)
        for (line in 0 until n) {
            fun at(i: Int) = px[if (rows) line * n + i.coerceIn(0, n - 1) else i.coerceIn(0, n - 1) * n + line]
            for (i in 0 until n) {
                var red = 0; var g = 0; var b = 0
                for (k in -r..r) { val c = at(i + k); red += c shr 16 and 255; g += c shr 8 and 255; b += c and 255 }
                val d = 2 * r + 1
                out[i] = (255 shl 24) or (red / d shl 16) or (g / d shl 8) or (b / d)
            }
            for (i in 0 until n) px[if (rows) line * n + i else i * n + line] = out[i]
        }
    }
}
