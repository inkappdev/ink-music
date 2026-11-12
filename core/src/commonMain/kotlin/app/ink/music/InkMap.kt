package app.ink.music

import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Ink mapped (2026-11-11, Bronson's: "your ink", named after Flow's taste page, then "ink mapped"): your listening as
 * land. A hill for each artist you're near ([Taste]), where it stands fixed by its name (so it stays put week to week),
 * as high and wide as you're near; hills close by grow into ridges; the more you wander ([Taste.appetite]) the more of
 * them rise. Drawn as a map draws land: a line wherever the ground crosses one of [LEVELS] heights, the higher the
 * brighter (ink's three steps), in the colour of the record you play most; its highest named, as a map names its peaks.
 * Pixels only: each app draws the names over it in its own Silkscreen.
 */
object InkMap {
    /** A named summit: whose, and where (0 to 1 across and down). */
    data class Peak(val name: String, val x: Float, val y: Float)

    /** The map drawn, `side` square (ARGB, black where there's no line), and its named summits. */
    class Drawn(val side: Int, val pixels: IntArray, val peaks: List<Peak>)

    const val LEVELS = 14

    private class Hill(val x: Double, val y: Double, val h: Double, val r: Double)

    /**
     * The map of `artists` (each its name and how near you are, 0 to 1, nearest first), `side` px square, in `accent`;
     * the `named` highest named. Blocking (a few hundred ms on a phone at 720).
     */
    fun draw(artists: List<Pair<String, Double>>, appetite: Double, accent: Int, side: Int = 720, named: Int = 3): Drawn {
        val many = 8 + ((appetite - .1) / .4 * 10).roundToInt().coerceIn(0, 10)
        val chosen = artists.filter { it.second > 0 }.take(many)
        val hills = chosen.map { (name, v) ->
            val (x, y) = place(name)
            Hill(x, y, v, .035 + .05 * v)
        }
        val n = side
        val field = FloatArray(n * n)
        for (h in hills) {
            // only where it reaches (four of its widths): the rest of the field untouched by it
            val reach = h.r * 4
            val x0 = ((h.x - reach) * n).toInt().coerceAtLeast(0); val x1 = ((h.x + reach) * n).toInt().coerceAtMost(n - 1)
            val y0 = ((h.y - reach) * n).toInt().coerceAtLeast(0); val y1 = ((h.y + reach) * n).toInt().coerceAtMost(n - 1)
            val k = 1.0 / (2 * h.r * h.r)
            for (j in y0..y1) {
                val dy = j.toDouble() / (n - 1) - h.y
                for (i in x0..x1) {
                    val dx = i.toDouble() / (n - 1) - h.x
                    field[j * n + i] += (h.h * exp(-(dx * dx + dy * dy) * k)).toFloat()
                }
            }
        }
        val top = (field.maxOrNull() ?: 0f).coerceAtLeast(1e-6f)
        val q = IntArray(n * n) { (field[it] / top * LEVELS).toInt().coerceAtMost(LEVELS - 1) }
        val steps = intArrayOf(shade(accent, .4f), shade(accent, .7f), accent or (0xFF shl 24))
        val px = IntArray(n * n) { BLACK }
        // a line a little over two pixels wide wherever the level changes, brighter the higher it is
        val w = maxOf(1, n / 300)
        for (j in 0 until n - 1) for (i in 0 until n - 1) {
            val a = q[j * n + i]; val r = q[j * n + i + 1]; val d = q[(j + 1) * n + i]
            if (a == r && a == d) continue
            val k = maxOf(a, r, d).toFloat() / (LEVELS - 1)
            if (maxOf(a, r, d) == 0) continue
            val c = steps[if (k > .6f) 2 else if (k > .3f) 1 else 0]
            for (dj in 0..w) for (di in 0..w) {
                val y = j + dj; val x = i + di
                if (x < n && y < n) px[y * n + x] = c
            }
        }
        // the highest named, each on its own summit: the highest ground near where its hill stands (a neighbour's had
        // pushed the top aside, the name beside a slope)
        val peaks = chosen.take(named).map { (name, _) ->
            val (hx, hy) = place(name)
            val cx = (hx * (n - 1)).toInt(); val cy = (hy * (n - 1)).toInt(); val rr = (n * .07).toInt()
            var best = cy * n + cx
            for (j in (cy - rr).coerceAtLeast(0)..(cy + rr).coerceAtMost(n - 1)) for (i in (cx - rr).coerceAtLeast(0)..(cx + rr).coerceAtMost(n - 1))
                if (field[j * n + i] > field[best]) best = j * n + i
            Peak(name, (best % n).toFloat() / (n - 1), (best / n).toFloat() / (n - 1))
        }
        return Drawn(n, px, peaks)
    }

    /** Where an artist's hill stands (0.18 to 0.82 each way), from their name: the same every week, on every device. */
    fun place(name: String): Pair<Double, Double> {
        var h = 0x811c9dc5.toInt()
        for (ch in name.lowercase()) { h = h xor ch.code; h *= 0x01000193 }
        return (.18 + .64 * (h and 0xFF) / 255.0) to (.18 + .64 * ((h ushr 8) and 0xFF) / 255.0)
    }

    /** How you're listening lately, as a word: wandering (a stretch picked for you half the time), settled in, or staying close. */
    fun mood(appetite: Double): String = when { appetite >= .4 -> "wandering"; appetite <= .2 -> "staying close"; else -> "settled in" }

    /** When you listen, said: the parts of the day with most of it, from each listen's hour ("afternoons, and late"). */
    fun hours(hourOfEach: List<Int>): String? {
        if (hourOfEach.size < 10) return null
        fun part(h: Int) = when (h) { in 5..11 -> "mornings"; in 12..16 -> "afternoons"; in 17..20 -> "evenings"; else -> "late" }
        val by = hourOfEach.groupingBy(::part).eachCount().entries.sortedByDescending { it.value }
        val first = by[0]; val second = by.getOrNull(1)?.takeIf { it.value >= first.value * .6 }
        return if (second != null) "${first.key}, and ${second.key}" else "mostly ${first.key}"
    }

    private const val BLACK = 0xFF000000.toInt()
    private fun shade(c: Int, k: Float) = BLACK or (((c shr 16 and 255) * k).toInt() shl 16) or (((c shr 8 and 255) * k).toInt() shl 8) or ((c and 255) * k).toInt()
}
