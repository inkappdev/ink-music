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

    /** The map drawn, `side` square (ARGB, black where there's no line), its named summits, and its biggest regions' names. */
    class Drawn(val side: Int, val pixels: IntArray, val peaks: List<Peak>, val regions: List<Peak> = emptyList())

    const val LEVELS = 14

    private class Hill(val x: Double, val y: Double, val h: Double, val r: Double)

    /**
     * The map of `artists` (each its name and how near you are, 0 to 1, nearest first), `side` px square, in `accent`;
     * the `named` highest named. Blocking (a few hundred ms on a phone at 720).
     */
    fun draw(artists: List<Pair<String, Double>>, appetite: Double, accent: Int, side: Int = 720, named: Int = 3,
             tags: (String) -> List<String>? = { null }): Drawn {
        val many = 8 + ((appetite - .1) / .4 * 10).roundToInt().coerceIn(0, 10)
        val chosen = artists.filter { it.second > 0 }.take(many)
        val at = chosen.associate { (name, _) -> name to place(name, tags(name)) }
        val hills = chosen.map { (name, v) ->
            val (x, y) = at.getValue(name)
            // narrow enough that a region's artists stay peaks of a range, not one smooth mountain
            Hill(x, y, v, .025 + .035 * v)
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
        // the lines drawn smooth (2026-11-11, Bronson: "a little jagged up close"): each pixel shaded by how far it is
        // from the nearest level's line, in pixels (its distance in heights over how steep the ground is there), so a
        // line has soft, even edges at any angle; half a line's width about 1.1 px at 720, wider as the map is
        val fr = FloatArray(n * n) { field[it] / top * LEVELS }
        val half = 1.1f * n / 720f
        val px = IntArray(n * n) { BLACK }
        for (j in 1 until n - 1) for (i in 1 until n - 1) {
            val k = j * n + i
            val v = fr[k]
            val b = kotlin.math.round(v)
            if (b < 1f || b > LEVELS - 1) continue
            val gx = (fr[k + 1] - fr[k - 1]) / 2; val gy = (fr[k + n] - fr[k - n]) / 2
            val g = kotlin.math.sqrt(gx * gx + gy * gy)
            if (g < 1e-6f) continue
            val a = (half + .5f - kotlin.math.abs(v - b) / g).coerceIn(0f, 1f)
            if (a <= 0f) continue
            val t = b / (LEVELS - 1)
            px[k] = shade(accent, a * (if (t > .6f) 1f else if (t > .3f) .7f else .4f))
        }
        // the highest named, each on its own summit: the highest ground near where its hill stands (a neighbour's had
        // pushed the top aside, the name beside a slope); two on one summit, the second passed over for the next
        val peaks = ArrayList<Peak>()
        for ((name, _) in chosen) {
            if (peaks.size >= named) break
            val (hx, hy) = at.getValue(name)
            val cx = (hx * (n - 1)).toInt(); val cy = (hy * (n - 1)).toInt(); val rr = (n * .07).toInt()
            var best = cy * n + cx
            for (j in (cy - rr).coerceAtLeast(0)..(cy + rr).coerceAtMost(n - 1)) for (i in (cx - rr).coerceAtLeast(0)..(cx + rr).coerceAtMost(n - 1))
                if (field[j * n + i] > field[best]) best = j * n + i
            val px0 = (best % n).toFloat() / (n - 1); val py0 = (best / n).toFloat() / (n - 1)
            if (peaks.any { (it.x - px0) * (it.x - px0) + (it.y - py0) * (it.y - py0) < .09f * .09f }) continue
            peaks.add(Peak(name, px0, py0))
        }
        // the regions your land is most of, named where they lie (the genres your nearest are, weighed by how near)
        val weight = HashMap<String, Double>()
        for ((name, v) in chosen) genresOf(tags(name)).forEach { (g, w) -> weight[g] = (weight[g] ?: 0.0) + v * w }
        val regions = weight.entries.sortedByDescending { it.value }.take(3).filter { it.value > .3 }
            .map { (g, _) -> WHEEL.getValue(g).let { (x, y) -> Peak(SHORT[g] ?: g, x.toFloat(), y.toFloat()) } }
            // not over a summit's name
            .filter { r -> peaks.none { (it.x - r.x) * (it.x - r.x) + (it.y - r.y) * (it.y - r.y) < .1f * .1f } }
        return Drawn(n, px, peaks, regions)
    }

    /**
     * Where an artist's hill stands (0.12 to 0.88 each way). By their sound (2026-11-11, Bronson's pick: "place them by
     * sound"): among their genres on [WHEEL], weighed by how high each tag is in their top tags (Last.fm's listeners'),
     * nudged a little by their name so a genre's artists don't stack; one of indie and pop lands between the two.
     * With no tags read yet: by their name alone, the same every week and on every device.
     */
    fun place(name: String, tags: List<String>? = null): Pair<Double, Double> {
        var h = 0x811c9dc5.toInt()
        for (ch in name.lowercase()) { h = h xor ch.code; h *= 0x01000193 }
        val jx = ((h and 0xFF) / 255.0 - .5); val jy = (((h ushr 8) and 0xFF) / 255.0 - .5)
        val genres = genresOf(tags)
        if (genres.isEmpty()) return (.18 + .64 * (jx + .5)) to (.18 + .64 * (jy + .5))
        var x = 0.0; var y = 0.0; var sum = 0.0
        for ((g, w) in genres) { val (gx, gy) = WHEEL.getValue(g); x += gx * w; y += gy * w; sum += w }
        return (x / sum + jx * .22).coerceIn(.12, .88) to (y / sum + jy * .22).coerceIn(.12, .88)
    }

    /** An artist's genres from their top five tags, each weighed by its tag's place (the first counts most). */
    private fun genresOf(tags: List<String>?): Map<String, Double> {
        val out = HashMap<String, Double>()
        tags?.take(5)?.forEachIndexed { i, t -> Genres.genresOf(t).forEach { g -> if (g in WHEEL) out[g] = (out[g] ?: 0.0) + 1.0 / (i + 1) } }
        return out
    }

    /**
     * The genres' places on the map, a wheel with neighbours that are kin all the way round (metal by rock by indie by
     * folk by country by blues by jazz by soul by hip-hop by African by Caribbean by Latin by Brazilian by pop by k-pop by
     * j-pop by electronic by soundtracks by classical, back to metal's heavy and orchestral side): one world, shared by
     * everyone's map, so the same corner means the same music on yours and a friend's.
     */
    private val WHEEL: Map<String, Pair<Double, Double>> = listOf(
        "metal", "rock", "indie & alternative", "folk & acoustic", "country & americana", "blues", "jazz", "r&b & soul", "hip-hop",
        "african", "reggae & caribbean", "latin", "brazilian", "pop", "k-pop", "j-pop", "dance & electronic", "soundtracks & musicals", "classical",
    ).let { order -> order.mapIndexed { i, g ->
        val a = 2 * kotlin.math.PI * i / order.size - kotlin.math.PI / 2
        g to ((.5 + .32 * kotlin.math.cos(a)) to (.5 + .32 * kotlin.math.sin(a)))
    }.toMap() }

    /** A region's name as the map says it, short. */
    private val SHORT = mapOf("indie & alternative" to "indie", "dance & electronic" to "electronic", "r&b & soul" to "soul",
        "folk & acoustic" to "folk", "country & americana" to "country", "soundtracks & musicals" to "soundtracks", "reggae & caribbean" to "caribbean")

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
