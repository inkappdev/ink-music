package app.ink.music

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** A picture as `n` x `n` ARGB pixels, drawn smoothly to that size (its centre square). */
expect fun pixelsOf(img: ImageBitmap, n: Int): IntArray
/** ARGB pixels as a picture. */
expect fun imageOf(px: IntArray, n: Int): ImageBitmap

/** A record's cover and its colour (the line, the dither). */
class Art(val image: ImageBitmap, val accent: Color)

/**
 * How the cover sits behind the player: the phone's Look.kt (ink's desk clock looks), the same looks drawn the same
 * way, by hand once a cover off the main thread. Kept in look.json.
 */
enum class Look(val word: String, val alpha: Float, val crisp: Boolean) {
    BLUR("blur", .3f, false),
    FLUTED("fluted glass", .45f, false),
    PIXELS("pixels", .4f, true),
    DITHER("dither", .4f, true),
    AS_IS("as it is", .5f, false),
    PLAIN("plain", 0f, false),
}

object Looks {
    private val file get() = File(Here.dir, "look.json")
    private val _on = MutableStateFlow(runCatching { Look.valueOf(JSONObject(File(Here.dir, "look.json").readText()).getString("art")) }.getOrDefault(Look.BLUR))
    val on: StateFlow<Look> = _on
    fun set(look: Look) { _on.value = look; runCatching { Keep.write(file, JSONObject().put("art", look.name).toString()) } }

    /** The cover in `look`, square, shown `shown` px a side (so ribs, cells and dots are the size meant). Null if it can't. */
    fun draw(art: ImageBitmap, look: Look, accent: Color, shown: Float, density: Float): ImageBitmap? = if (look == Look.PLAIN) null else runCatching {
        when (look) {
            Look.BLUR -> imageOf(soft(art, 48, 2), 48)
            Look.FLUTED -> imageOf(fluted(art, 540, 20 * density * 540 / shown), 540)
            Look.PIXELS -> (shown / (18 * density)).roundToInt().coerceIn(4, 80).let { c -> imageOf(pixels(art, c), c * 16) }
            Look.DITHER -> (shown / (3 * density)).roundToInt().coerceIn(24, 400).let { d -> imageOf(dither(art, d, accent.toArgb()), d) }
            Look.AS_IS -> art
            Look.PLAIN -> null
        }
    }.getOrNull()

    private fun soft(src: ImageBitmap, n: Int, r: Int): IntArray {
        val px = pixelsOf(src, n)
        repeat(3) { boxBlur(px, n, r, true); boxBlur(px, n, r, false) }
        return px
    }

    private fun fluted(src: ImageBitmap, n: Int, rib: Float): IntArray {
        val behind = pixelsOf(imageOf(soft(src, 120, 1), 120), n)
        val w = rib.coerceAtLeast(4f)
        val from = IntArray(n); val lens = FloatArray(n); val shine = FloatArray(n)
        for (x in 0 until n) {
            val u = (x % w) / w
            from[x] = (x - u * w + w / 2 + (u - .5f) * w * -1.7f).roundToInt().coerceIn(0, n - 1)
            lens[x] = .55f + .45f * sin(PI * u).pow(.6).toFloat()
            shine[x] = .75f * exp(-((u - .14f) / .035f).pow(2))
        }
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
        return out
    }

    private fun pixels(src: ImageBitmap, cells: Int): IntArray {
        val k = 4
        val m = cells * k
        val px = pixelsOf(src, m)
        val c = 16
        val n = cells * c
        val out = IntArray(n * n) { 0xFF000000.toInt() }
        for (cy in 0 until cells) for (cx in 0 until cells) {
            var r = 0; var g = 0; var b = 0
            for (y in 0 until k) for (x in 0 until k) {
                val p = px[(cy * k + y) * m + cx * k + x]
                r += p shr 16 and 255; g += p shr 8 and 255; b += p and 255
            }
            val avg = (255 shl 24) or (r / (k * k) shl 16) or (g / (k * k) shl 8) or (b / (k * k))
            for (y in 1 until c) for (x in 1 until c) out[(cy * c + y) * n + cx * c + x] = avg
        }
        return out
    }

    private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

    private fun dither(src: ImageBitmap, dots: Int, colour: Int, gain: Float = 1f): IntArray {
        val n = dots
        val small = (n / 3).coerceAtLeast(16)
        val px = pixelsOf(imageOf(soft(src, small, 1), small), n)
        val lum = FloatArray(n * n) { i -> val p = px[i]; (.3f * (p shr 16 and 255) + .59f * (p shr 8 and 255) + .11f * (p and 255)) / 255 }
        val lo = lum.min(); val span = (lum.max() - lo).coerceAtLeast(.01f)
        val on = (255 shl 24) or (colour and 0xFFFFFF)
        val out = IntArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val t = (BAYER[(y and 3) * 4 + (x and 3)] + .5f) / 16
            out[y * n + x] = if ((lum[y * n + x] - lo) / span * gain > t) on else 0xFF000000.toInt()
        }
        return out
    }
}

/** One box blur pass over an `n` x `n` image, `r` px each side, along rows (or columns), edges held. */
internal fun boxBlur(px: IntArray, n: Int, r: Int, rows: Boolean) {
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

/**
 * The cover's colour, as the phone finds it (Palette's vibrant swatch, kept light enough to read; a grey cover stays
 * ink): the most common strongly coloured hue among its pixels, averaged, its lightness held between 52% and 72%.
 */
fun accentOf(img: ImageBitmap): Color = runCatching {
    val px = pixelsOf(img, 40)
    val count = FloatArray(24); val sumH = FloatArray(24); val sumS = FloatArray(24); val sumL = FloatArray(24)
    for (p in px) {
        val r = (p shr 16 and 255) / 255f; val g = (p shr 8 and 255) / 255f; val b = (p and 255) / 255f
        val max = maxOf(r, g, b); val min = minOf(r, g, b); val l = (max + min) / 2; val d = max - min
        if (d < .08f || l < .12f || l > .9f) continue
        val s = d / (1 - kotlin.math.abs(2 * l - 1)).coerceAtLeast(.001f)
        if (s < .3f) continue
        var h = when (max) { r -> ((g - b) / d) % 6; g -> (b - r) / d + 2; else -> (r - g) / d + 4 } * 60
        if (h < 0) h += 360
        val k = (h / 15).toInt().coerceIn(0, 23)
        val w = s * s
        count[k] += w; sumH[k] += h * w; sumS[k] += s * w; sumL[k] += l * w
    }
    val k = count.indices.maxByOrNull { count[it] + (if (it > 0) count[it - 1] else count[23]) * .5f + (if (it < 23) count[it + 1] else count[0]) * .5f }
        ?: return Ink.ink
    if (count[k] < px.size * .01f) return Ink.ink
    val h = sumH[k] / count[k]; val s = (sumS[k] / count[k]).coerceIn(0f, 1f); val l = (sumL[k] / count[k]).coerceIn(.52f, .72f)
    Color.hsl(h, s, l)
}.getOrDefault(Ink.ink)

/** Covers with their colour, the last few kept. */
object Covers {
    private val kept = HashMap<String, Art>()
    private val order = ArrayList<String>()
    fun kept(id: String?, px: Int): Art? = id?.let { kept["$it@$px"] }
    suspend fun of(id: String, px: Int): Art? {
        kept["$id@$px"]?.let { return it }
        val img = Images.load(id, px) ?: return null
        val c = Here.off { Art(img, accentOf(img)) }
        kept["$id@$px"] = c; order += "$id@$px"
        while (order.size > 40) kept.remove(order.removeAt(0))
        return c
    }
}

/** A song's cover with its colour, about `px` across: kept at once, else read (null while reading, or none). */
@Composable
fun rememberCover(id: String?, px: Int = 640): Art? = produceState(Covers.kept(id, px), id, px) {
    value = Covers.kept(id, px)
    if (value == null && id != null) value = Covers.of(id, px)
}.value
