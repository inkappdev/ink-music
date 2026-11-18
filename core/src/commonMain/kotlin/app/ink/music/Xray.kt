package app.ink.music

import kotlin.math.pow

/**
 * X-ray, an artist page's photo (2026-11-18, Bronson's idea, picked from five tried: "x-ray in its color"): the picture
 * inside out, as film is, its shadows glowing and its lights gone dark, in one colour, the photo's own accent. Its
 * brightness stretched end to end (the darkest and lightest 2% let go) and bent (power 1.6) so the black stays deep.
 */
object Xray {
    /** `px` (ARGB) as an x-ray in `tint` (ARGB; its red, green, blue), opaque. */
    fun of(px: IntArray, tint: Int): IntArray {
        val n = px.size
        if (n == 0) return px
        val g = FloatArray(n) { i -> val p = px[i]; 255f - (.3f * (p shr 16 and 255) + .59f * (p shr 8 and 255) + .11f * (p and 255)) }
        val sorted = g.copyOf().also { it.sort() }
        val lo = sorted[(n * .02f).toInt()]
        val hi = sorted[(n * .98f).toInt().coerceAtMost(n - 1)]
        val span = (hi - lo).coerceAtLeast(1f)
        val r = tint shr 16 and 255; val gr = tint shr 8 and 255; val b = tint and 255
        return IntArray(n) { i ->
            val v = ((g[i] - lo) / span).coerceIn(0f, 1f).pow(1.6f)
            (255 shl 24) or ((r * v).toInt() shl 16) or ((gr * v).toInt() shl 8) or (b * v).toInt()
        }
    }
}
