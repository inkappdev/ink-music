package app.ink.music

import kotlin.math.exp
import kotlin.math.min

/**
 * Your taste, as near as listening says it (2026-11-07): how close any artist is to you (0 never heard of, to 1 the ones
 * you live in; below 0, ones you've said "not for me" to), and why. Made on the device from what's kept there: what you
 * played (each listen weighed by how long, fading over three months), what you skipped in its first seconds, what you
 * love, what you keep, and whom your artists' fans also like (YouTube Music's "fans also like"). Nothing leaves.
 *
 * Shared by the phone and the computer: each hands it its own listening.
 */
class Taste private constructor(
    private val near: Map<String, Double>,
    private val why: Map<String, Why>,
) {
    /** A listen (or a skip): who, when (ms), how long it played (ms). */
    data class Play(val artist: String, val at: Long, val ms: Long)

    /** Why an artist is near: played (how often), loved, kept, fans of one of yours like them, or said not for you. */
    sealed class Why {
        data class Played(val times: Int) : Why()
        data object Loved : Why()
        data object Kept : Why()
        data class Like(val of: String) : Why()
        data object NotForYou : Why()
    }

    /** How near (the nearest of a shared name's artists: "Axel Boman & Superpitcher"). */
    fun of(artist: String): Double = names(artist).maxOfOrNull { near[it] ?: 0.0 } ?: 0.0

    /** Why it's near, for the nearest of its artists; null for a stranger. */
    fun why(artist: String): Why? = names(artist).maxByOrNull { near[it] ?: 0.0 }?.let { why[it] }

    /** Said not for you. */
    fun refused(artist: String): Boolean = names(artist).any { why[it] == Why.NotForYou }

    /** The artists nearest you, nearest first (bare names). */
    fun top(n: Int): List<String> = near.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(n).map { it.key }

    val empty get() = near.isEmpty()

    companion object {
        private const val DAY = 86_400_000L
        private val split = Regex("""\s*(?:,|&|/|\+|\bx\b|\band\b|\bwith\b|\bfeat\.?|\bft\.?|\bvs\.?)\s*""", RegexOption.IGNORE_CASE)

        /** An artist's name as compared: lower case, "the" off its front, letters and digits only. */
        fun bare(name: String) = name.lowercase().trim().removePrefix("the ").filter { it.isLetterOrDigit() }

        /** A name and, where several share it, each of them. */
        fun names(artist: String): List<String> =
            (listOf(artist) + artist.split(split)).map { bare(it) }.filter { it.length >= 2 }.distinct()

        /**
         * Made from what's kept. `plays` (30 s or more) and `skips` (left in the first seconds) with who and when;
         * `loved` and `kept` artists' names; `related`, an artist of yours to whom their fans also like; `refused`, the
         * artists said not for you.
         */
        fun of(
            plays: List<Play>, skips: List<Play> = emptyList(), loved: Collection<String> = emptyList(), kept: Collection<String> = emptyList(),
            related: Map<String, List<String>> = emptyMap(), refused: Collection<String> = emptyList(), now: Long = nowMs(),
        ): Taste {
            val weight = HashMap<String, Double>()
            val times = HashMap<String, Int>()
            for (p in plays) {
                val age = (now - p.at).coerceAtLeast(0) / DAY.toDouble()
                // a whole song (three minutes or more) counts 1 today, fading by half every two months
                val w = min(p.ms, 300_000L) / 180_000.0 * exp(-age / 90.0)
                for (n in names(p.artist).take(3)) { weight[n] = (weight[n] ?: 0.0) + w; times[n] = (times[n] ?: 0) + 1 }
            }
            val near = HashMap<String, Double>()
            val why = HashMap<String, Why>()
            // three songs' worth lately is near (.63); ten, very (.96)
            weight.forEach { (n, w) -> near[n] = 1 - exp(-w / 3.0); why[n] = Why.Played(times[n] ?: 0) }
            for (a in loved) for (n in names(a).take(1)) { near[n] = min(1.0, (near[n] ?: 0.0) + .35); if (why[n] == null || (near[n] ?: 0.0) < .5) why[n] = Why.Loved }
            for (a in kept) for (n in names(a).take(1)) if ((near[n] ?: 0.0) < .15) { near[n] = .15; why.putIfAbsent(n, Why.Kept) }
            // left in its first seconds, lately: a little further each time (never more than .4 off)
            val off = HashMap<String, Double>()
            for (s in skips) if (now - s.at < 60 * DAY) for (n in names(s.artist).take(1)) off[n] = min(.4, (off[n] ?: 0.0) + .06)
            off.forEach { (n, d) -> near[n] = ((near[n] ?: 0.0) - d).coerceAtLeast(if (near.containsKey(n)) .02 else 0.0) }
            // whom your nearest artists' fans also like: some of their nearness, said so
            val nearest = near.entries.sortedByDescending { it.value }.take(20)
            for ((n, v) in nearest) {
                val theirs = related.entries.firstOrNull { bare(it.key) == n }?.value ?: continue
                for (r in theirs) for (m in names(r).take(1)) {
                    val given = .45 * v
                    if ((near[m] ?: 0.0) < given) { near[m] = given; why[m] = Why.Like(related.keys.first { bare(it) == n }) }
                }
            }
            for (a in refused) for (n in names(a).take(1)) { near[n] = -1.0; why[n] = Why.NotForYou }
            return Taste(near, why)
        }
    }
}

/**
 * The 70/30 (2026-11-07): of every ten shown, seven near your taste and three on purpose outside it, so what's picked for
 * you stays yours and still opens a door. Each scored by how near (taste), how many picked it (several critics, a
 * chart: `picked`, 0 to 1), how new (`fresh`, 0 to 1), less for being shown and passed over (`tired`, 1 to 0), with a
 * day's own small shuffle so the same day holds still and the next one doesn't.
 */
object ForYou {
    /** One placed: near your taste, or a stretch. */
    data class Placed<T>(val item: T, val stretch: Boolean)

    /** Below this an artist is a stranger: what's shown of them is a stretch. */
    const val NEAR = .2

    fun <T> order(
        items: List<T>, taste: (T) -> Double, picked: (T) -> Double, fresh: (T) -> Double, tired: (T) -> Double,
        day: Long, stretch: Double = .3,
    ): List<Placed<T>> {
        val r = kotlin.random.Random(day * 31 + items.size)
        val jitter = items.associateWith { r.nextDouble() * .08 }
        val shown = items.filter { taste(it) >= 0 && tired(it) > 0 }
        val near = shown.filter { taste(it) >= NEAR }
            .sortedByDescending { (.55 * taste(it) + .3 * picked(it) + .15 * fresh(it) + jitter.getValue(it)) * tired(it) }
        val far = shown.filter { taste(it) < NEAR }
            .sortedByDescending { (.6 * picked(it) + .4 * fresh(it) + jitter.getValue(it)) * tired(it) }
        val out = ArrayList<Placed<T>>(shown.size)
        var i = 0; var j = 0
        // three of every ten a stretch: places 3, 6 and 9 of each ten (never first: the first is yours)
        while (i < near.size || j < far.size) {
            val slot = out.size % 10
            val wantFar = stretch > 0 && (slot == 2 || slot == 5 || slot == 8)
            if ((wantFar && j < far.size) || i >= near.size) out.add(Placed(far[j++], true)) else out.add(Placed(near[i++], false))
        }
        return out
    }
}
