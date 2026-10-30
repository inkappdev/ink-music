package app.ink.music

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * How the cover sits behind the player (ink's desk clock looks, copied from ink's Look.kt; "plain"
 * is ink music's own, no backdrop): blurred to a colour field, behind fluted glass, as pixels on
 * Silkscreen's grid, or dithered in its own colour as an iPod screen would.
 * Each is drawn by hand once a cover, off the main thread, into a bitmap the panel only shows:
 * nothing is worked out while the clock is up, and it looks the same on every Android (Compose's
 * blur did nothing before 12, so Android 11 TVs showed the cover sharp).
 */
enum class Look(val word: String, val alpha: Float, val crisp: Boolean) {
    BLUR("blur", .3f, false),
    FLUTED("fluted glass", .45f, false),
    PIXELS("pixels", .4f, true),
    DITHER("dither", .4f, true),
    // the cover (or a song's canvas) itself, no effect, held to half over the black so the words over it read
    AS_IS("as it is", .5f, false),
    PLAIN("plain", 0f, false),
}

object Looks {
    private val _on = MutableStateFlow(Look.BLUR)
    val on: StateFlow<Look> = _on

    private fun prefs(c: Context) = c.getSharedPreferences("look", Context.MODE_PRIVATE)
    fun start(c: Context) { _on.value = Look.entries.firstOrNull { it.name == prefs(c).getString("art", null) } ?: Look.BLUR }
    fun set(c: Context, look: Look) {
        _on.value = look
        prefs(c).edit().putString("art", look.name).apply()
    }
    fun next(c: Context) {
        _on.value = Look.entries[(_on.value.ordinal + 1) % Look.entries.size]
        prefs(c).edit().putString("art", _on.value.name).apply()
    }

    /**
     * The cover in `look`, square. `unit` is the size of a dp in the bitmap's own pixels: the panel
     * shows the square at `shown` px a side, so ribs, cells and dots come out the size meant on any
     * screen. Null if the cover can't be read.
     */
    fun draw(art: Bitmap, look: Look, accent: Color, shown: Float, density: Float): Bitmap? = if (look == Look.PLAIN) null else try {
        val src = if (art.config == Bitmap.Config.HARDWARE) art.copy(Bitmap.Config.ARGB_8888, false) else art
        when (look) {
            Look.BLUR -> soft(src, 48, 2)
            Look.FLUTED -> fluted(src, 540, 20 * density * 540 / shown)
            Look.PIXELS -> pixels(src, (shown / (18 * density)).roundToInt().coerceIn(4, 80))
            Look.DITHER -> dither(src, (shown / (3 * density)).roundToInt().coerceIn(24, 400), accent.toArgb())
            Look.AS_IS -> src
            Look.PLAIN -> null
        }
    } catch (e: Exception) { null }

    /**
     * The cover developing in the dither, as a print does in its tray: `frames` steps from black to
     * the whole of it, the brightest dots first (shown one after another as it arrives).
     */
    fun develop(art: Bitmap, accent: Color, shown: Float, density: Float, frames: Int = 12): List<Bitmap>? = try {
        val src = if (art.config == Bitmap.Config.HARDWARE) art.copy(Bitmap.Config.ARGB_8888, false) else art
        val dots = (shown / (3 * density)).roundToInt().coerceIn(24, 400)
        (1..frames).map { dither(src, dots, accent.toArgb(), it.toFloat() / frames) }
    } catch (e: Exception) { null }

    /** Shrunk to `n` px and box-blurred three times each way: drawn back up, a colour field. */
    private fun soft(src: Bitmap, n: Int, r: Int): Bitmap {
        val px = IntArray(n * n)
        Bitmap.createScaledBitmap(src, n, n, true).getPixels(px, 0, n, 0, 0, n, n)
        repeat(3) { boxBlur(px, n, r, true); boxBlur(px, n, r, false) }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    }

    /**
     * Fluted glass: vertical ribs `rib` px wide, each a cylindrical lens. A rib shows a wider slice
     * of what's behind it, flipped and squeezed in, darker toward its seams, with a fine highlight
     * just past the left one. What's behind is a little out of focus, as through real glass.
     */
    private fun fluted(src: Bitmap, n: Int, rib: Float): Bitmap {
        val behind = IntArray(n * n)
        Bitmap.createScaledBitmap(soft(src, 120, 1), n, n, true).getPixels(behind, 0, n, 0, 0, n, n)
        val w = rib.coerceAtLeast(4f)
        val from = IntArray(n); val lens = FloatArray(n); val shine = FloatArray(n)
        for (x in 0 until n) {
            val u = (x % w) / w
            from[x] = (x - u * w + w / 2 + (u - .5f) * w * -1.7f).roundToInt().coerceIn(0, n - 1)
            lens[x] = .55f + .45f * sin(PI * u).pow(.6).toFloat()
            shine[x] = .75f * exp(-((u - .14f) / .035f).pow(2))
        }
        // a channel's value through the lens is v * lens + shine * grey: kept as integer factors (x 256)
        val k = IntArray(n) { (lens[it] * 256).toInt() }
        val h = IntArray(n) { (shine[it] * 256 / 3).toInt() }
        val out = IntArray(n * n)
        for (y in 0 until n) {
            val row = y * n
            for (x in 0 until n) {
                val c = behind[row + from[x]]
                val r = c shr 16 and 255; val g = c shr 8 and 255; val b = c and 255
                val lift = h[x] * (r + g + b)
                val kx = k[x]
                val rr = minOf(255, (r * kx + lift) shr 8); val gg = minOf(255, (g * kx + lift) shr 8); val bb = minOf(255, (b * kx + lift) shr 8)
                out[row + x] = (255 shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
        }
        return Bitmap.createBitmap(out, n, n, Bitmap.Config.ARGB_8888)
    }

    /** `cells` a side, each the average of its square, 16 px with a 1 px black gutter: shown unfiltered. */
    private fun pixels(src: Bitmap, cells: Int): Bitmap {
        val k = 4
        val m = cells * k
        val px = IntArray(m * m)
        Bitmap.createScaledBitmap(src, m, m, true).getPixels(px, 0, m, 0, 0, m, m)
        val c = 16
        val n = cells * c
        val out = IntArray(n * n)
        for (cy in 0 until cells) for (cx in 0 until cells) {
            var r = 0; var g = 0; var b = 0
            for (y in 0 until k) for (x in 0 until k) {
                val p = px[(cy * k + y) * m + cx * k + x]
                r += p shr 16 and 255; g += p shr 8 and 255; b += p and 255
            }
            val avg = (255 shl 24) or (r / (k * k) shl 16) or (g / (k * k) shl 8) or (b / (k * k))
            for (y in 1 until c) for (x in 1 until c) out[(cy * c + y) * n + cx * c + x] = avg
        }
        return Bitmap.createBitmap(out, n, n, Bitmap.Config.ARGB_8888)
    }

    private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

    /** 1-bit, an ordered (Bayer 4 x 4) dither in the cover's colour on black; the cover's own range end to end (`gain` of it: less, darker). */
    private fun dither(src: Bitmap, dots: Int, colour: Int, gain: Float = 1f): Bitmap {
        val n = dots
        val px = IntArray(n * n)
        Bitmap.createScaledBitmap(soft(src, (n / 3).coerceAtLeast(16), 1), n, n, true).getPixels(px, 0, n, 0, 0, n, n)
        val lum = FloatArray(n * n) { i -> val p = px[i]; (.3f * (p shr 16 and 255) + .59f * (p shr 8 and 255) + .11f * (p and 255)) / 255 }
        val lo = lum.min(); val span = (lum.max() - lo).coerceAtLeast(.01f)
        val on = (255 shl 24) or (colour and 0xFFFFFF)
        val out = IntArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val t = (BAYER[(y and 3) * 4 + (x and 3)] + .5f) / 16
            out[y * n + x] = if ((lum[y * n + x] - lo) / span * gain > t) on else 0xFF000000.toInt()
        }
        return Bitmap.createBitmap(out, n, n, Bitmap.Config.ARGB_8888)
    }
}

/** One box blur pass over an `n` x `n` image, `r` px each side, along rows (or columns), edges held. */
internal fun boxBlur(px: IntArray, n: Int, r: Int, rows: Boolean) {
    val out = IntArray(n)
    for (line in 0 until n) {
        fun at(i: Int) = px[if (rows) line * n + i.coerceIn(0, n - 1) else i.coerceIn(0, n - 1) * n + line]
        for (i in 0 until n) {
            var a = 0; var red = 0; var g = 0; var b = 0
            for (k in -r..r) {
                val c = at(i + k)
                a += c ushr 24; red += c shr 16 and 255; g += c shr 8 and 255; b += c and 255
            }
            val d = 2 * r + 1
            out[i] = (a / d shl 24) or (red / d shl 16) or (g / d shl 8) or (b / d)
        }
        for (i in 0 until n) px[if (rows) line * n + i else i * n + line] = out[i]
    }
}

/**
 * The giant names running across (the player's artist at its foot, ink weekly's, a long name on an artist's page):
 * moving, or still where they are (settings, the look; Bronson, 2026-10-30). Kept beside the look's own choice.
 */
object Marquees {
    private val _on = MutableStateFlow(true)
    val on: StateFlow<Boolean> = _on
    private fun prefs(c: Context) = c.getSharedPreferences("look", Context.MODE_PRIVATE)
    fun start(c: Context) { _on.value = prefs(c).getBoolean("marquee", true) }
    fun set(c: Context, on: Boolean) { _on.value = on; prefs(c).edit().putBoolean("marquee", on).apply() }
}

