package app.ink.music

import androidx.compose.ui.draw.clipToBounds
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
 * A picture in the dither (the phone's Looks.develop, its pixels by Skia): softened a little, then 1-bit through a Bayer 4 x 4,
 * 3 dp dots, in the picture's own colour on black, developing from black (the brightest dots first) in `frames` steps.
 * An artist's photo is shown this way, album art the only full colour.
 */
object Dither {
    private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

    /** Pictures' frames as developed, the last 32 MB of them (48 till 2026-11-10's memory pass), by picture, size and colour ([Dithered]). */
    private val made = Lru<String, List<ImageBitmap>>(32 * 1024 * 1024) { f -> f.sumOf { it.width * it.height * 4 } }
    fun kept(key: String): List<ImageBitmap>? = made.get(key)
    fun keep(key: String, frames: List<ImageBitmap>) { made.put(key, frames) }

    /**
     * Each dot a whole number of the screen's pixels, about 3 dp (2026-11-11: zoomed in or out, a dot was 6.6 pixels,
     * some drawn 6 and some 7, and the pattern lost its evenness; "at fit they look normal"): how many pixels, and how
     * many dots cover `shown` pixels at that size (the last one cut by the edge).
     */
    fun cells(shown: Float, density: Float): Pair<Int, Int> {
        val cell = cell(density)
        return cell to kotlin.math.ceil(shown / cell).toInt().coerceIn(24, 400)
    }

    /**
     * A dot's size in the screen's own pixels: [DOT] dp of the screen, not of the zoom (the window's size, settings), so a
     * dither looks the same at every zoom, only more or fewer dots (2026-11-11: "dots still change" when they followed
     * the zoom); 5 pixels on a Retina screen, 3 on an ordinary one. `density` the zoomed one, as Compose has it.
     */
    /** A dot's side in the screen's dp: 2.5 (3 till 2026-11-11, Bronson: "15 percent more detail"), whole pixels on every common screen. */
    const val DOT = 2.5f

    fun cell(density: Float, k: Float = 1f): Int = (DOT * k * density / Size.of(Size.chosen.value)).roundToInt().coerceAtLeast(2)

    fun develop(art: ImageBitmap, accent: Color, dots: Int, frames: Int = 12, print: Print = Print.DITHER, word: String = ""): List<ImageBitmap>? = runCatching {
        val img = Image.makeFromBitmap(art.asSkiaBitmapCopy())
        if (print != Print.DITHER) {
            // the other looks (Prints in core) from the cover drawn a pixel a dot
            val px = pixels(img, dots)
            val a = accent.toArgb()
            return@runCatching when (print) {
                Print.TILES -> Prints.tiles(px, dots, frames)
                Print.CONTOURS -> Prints.contours(px, dots, a, frames)
                else -> Prints.letters(px, dots, a, word, frames)
            }.map { image(it, dots).toComposeImageBitmap() }
        }
        // softened less since 2026-11-11 (half the dots, blurred once; were a third, three times): faces and type come back
        val small = (dots / 2).coerceAtLeast(16)
        val soft = pixels(img, small).also { px -> blur(px, small, 1, true); blur(px, small, 1, false) }
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

/**
 * A picture of dots drawn `cell` whole pixels a dot: the grid centered on whole pixels and cut by the edges, never
 * stretched to fit (a stretch of 1.1 made dots of two sizes, 2026-11-11). Covers, sleeves, the radio tower.
 */
@androidx.compose.runtime.Composable
fun Dots(frame: ImageBitmap, cell: Int, modifier: androidx.compose.ui.Modifier) {
    androidx.compose.foundation.Canvas(modifier.clipToBounds()) {
        val w = frame.width * cell; val h = frame.height * cell
        drawImage(frame, srcOffset = androidx.compose.ui.unit.IntOffset.Zero, srcSize = androidx.compose.ui.unit.IntSize(frame.width, frame.height),
            dstOffset = androidx.compose.ui.unit.IntOffset(((size.width - w) / 2).roundToInt(), ((size.height - h) / 2).roundToInt()),
            dstSize = androidx.compose.ui.unit.IntSize(w, h), filterQuality = androidx.compose.ui.graphics.FilterQuality.None)
    }
}
