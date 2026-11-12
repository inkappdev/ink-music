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
 * is ink music's own, no backdrop): blurred to a colour field, as pixels on
 * Silkscreen's grid, or dithered in its own colour as an iPod screen would.
 * Each is drawn by hand once a cover, off the main thread, into a bitmap the panel only shows:
 * nothing is worked out while the clock is up, and it looks the same on every Android (Compose's
 * blur did nothing before 12, so Android 11 TVs showed the cover sharp).
 */
enum class Look(val word: String, val alpha: Float, val crisp: Boolean) {
    BLUR("blur", .3f, false),
    PIXELS("pixels", .4f, true),
    DITHER("dither", .4f, true),
    // the cover (or a song's canvas) itself, no effect, held to half over the black so the words over it read
    AS_IS("clear", .5f, false),
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
    /** A dither dot's side, in dp: 2.5 (3 till 2026-11-11, Bronson: "15 percent more detail"), the computer's too. */
    const val DOT = 2.5f

    /** A dot in whole pixels (7.5 would draw dots of two sizes): 8 on a 3x phone, 7 on a 2.75x. */
    fun cell(density: Float): Int = (DOT * density).roundToInt().coerceAtLeast(2)

    /** How many dots cover `shown` pixels, whole-pixel dots (the last cut by the edge). */
    fun dots(shown: Float, density: Float): Int = kotlin.math.ceil(shown / cell(density)).toInt().coerceIn(24, 400)

    fun draw(art: Bitmap, look: Look, accent: Color, shown: Float, density: Float): Bitmap? = if (look == Look.PLAIN) null else try {
        val src = if (art.config == Bitmap.Config.HARDWARE) art.copy(Bitmap.Config.ARGB_8888, false) else art
        when (look) {
            // a lens's blur (the covers' and artist pages' too, 2026-11-11: one blur), its film grain drawn over it in the player
            Look.BLUR -> Grain.lens(src)
            Look.PIXELS -> pixels(src, (shown / (18 * density)).roundToInt().coerceIn(4, 80))
            Look.DITHER -> dither(src, dots(shown, density), accent.toArgb())
            Look.AS_IS -> src
            Look.PLAIN -> null
        }
    } catch (e: Exception) { null }

    /**
     * The cover developing in the dither, as a print does in its tray: `frames` steps from black to
     * the whole of it, the brightest dots first (shown one after another as it arrives).
     */
    fun develop(art: Bitmap, accent: Color, shown: Float, density: Float, frames: Int = 12, print: Print = Print.DITHER): List<Bitmap>? = try {
        val src = if (art.config == Bitmap.Config.HARDWARE) art.copy(Bitmap.Config.ARGB_8888, false) else art
        val dots = dots(shown, density)
        if (print == Print.DITHER) (1..frames).map { dither(src, dots, accent.toArgb(), it.toFloat() / frames) }
        else {
            // tiles (Prints in core) from the cover drawn a pixel a dot (grain is drawn apart: Grainy)
            val px = IntArray(dots * dots)
            Bitmap.createScaledBitmap(src, dots, dots, true).getPixels(px, 0, dots, 0, 0, dots, dots)
            Prints.tiles(px, dots, frames).map { Bitmap.createBitmap(it, dots, dots, Bitmap.Config.ARGB_8888) }
        }
    } catch (e: Exception) { null }

    /** Shrunk to `n` px and box-blurred `passes` times each way (three: drawn back up, a colour field). */
    private fun soft(src: Bitmap, n: Int, r: Int, passes: Int = 3): Bitmap {
        val px = IntArray(n * n)
        Bitmap.createScaledBitmap(src, n, n, true).getPixels(px, 0, n, 0, 0, n, n)
        repeat(passes) { boxBlur(px, n, r, true); boxBlur(px, n, r, false) }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
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
        // softened less since 2026-11-11 (half the dots, blurred once; were a third, three times): faces and type come back
        Bitmap.createScaledBitmap(soft(src, (n / 2).coerceAtLeast(16), 1, passes = 1), n, n, true).getPixels(px, 0, n, 0, 0, n, n)
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
 * moving, or still where they are (settings, the look; 2026-10-30). Kept beside the look's own choice.
 */
/** How mixes and lists are drawn from their covers (settings, the look, COVERS): the dither, tiles or grain. */
object CoverPrint {
    private val _on = MutableStateFlow(Print.DITHER)
    val on: StateFlow<Print> = _on
    private fun prefs(c: Context) = c.getSharedPreferences("look", Context.MODE_PRIVATE)
    fun start(c: Context) { _on.value = Print.entries.firstOrNull { it.name == prefs(c).getString("print", null) } ?: Print.DITHER }
    fun set(c: Context, print: Print) { _on.value = print; prefs(c).edit().putString("print", print.name).apply() }
}

object Marquees {
    private val _on = MutableStateFlow(true)
    val on: StateFlow<Boolean> = _on
    private fun prefs(c: Context) = c.getSharedPreferences("look", Context.MODE_PRIVATE)
    fun start(c: Context) { _on.value = prefs(c).getBoolean("marquee", true) }
    fun set(c: Context, on: Boolean) { _on.value = on; prefs(c).edit().putBoolean("marquee", on).apply() }
}

/**
 * Covers beside a list's songs (playlists, loved, kept, YouTube's lists): a word on each list's page, one choice for
 * them all (a Reddit tester's ask, 2026-10-30). Off at first: a list is words.
 */
/** The lists tab as words in a list, or as a grid of tiles with each list's art (2026-10-30). */
object ListsGrid {
    private val _on = MutableStateFlow(false)
    val on: StateFlow<Boolean> = _on
    private fun prefs(c: Context) = c.getSharedPreferences("look", Context.MODE_PRIVATE)
    fun start(c: Context) { _on.value = prefs(c).getBoolean("lists-grid", false) }
    fun set(c: Context, on: Boolean) { _on.value = on; prefs(c).edit().putBoolean("lists-grid", on).apply() }
}

object ListCovers {
    private val _on = MutableStateFlow(false)
    val on: StateFlow<Boolean> = _on
    private fun prefs(c: Context) = c.getSharedPreferences("look", Context.MODE_PRIVATE)
    fun start(c: Context) { _on.value = prefs(c).getBoolean("list-covers", false) }
    fun set(c: Context, on: Boolean) { _on.value = on; prefs(c).edit().putBoolean("list-covers", on).apply() }
}

