package app.ink.music

import kotlin.math.exp
import kotlin.random.Random

/**
 * Your frequency (2026-11-25, #16: "my vibe"; after Yandex Music's My Vibe and ListenBrainz's LB Radio): one endless station made from
 * you, not from a song, steered as it plays. Shared by the phone and the computer; each finds the songs, this chooses.
 *
 * Its songs come in four lanes:
 * - "yours": songs you already know (played, loved, kept), not heard lately;
 * - "near": songs new to you by artists you're near;
 * - "far": artists new to you, found from what you've been playing;
 * - "mood": the mood chosen (InkMoods' tags), its songs by the artists nearest you first.
 * How much of each is set by the needle (yours, near, new: LB Radio's easy, medium, hard as one line), moved by what each lane has done
 * with you before ([Learned], kept across sittings as TUNER's sources are), and by this sitting ([Sitting]): a song
 * played through, or loved, brings its artist and what was found through it nearer; one left in its first seconds puts
 * them back, twice and they're out for the sitting.
 */
object FrequencyMix {
    /**
     * What's chosen: where the needle's tuned (0 all yours, through near, to 1 all new: dragged along the spectrum on
     * listen, LB Radio's easy to hard made one line), and a mood (an InkMoods key; empty, any).
     */
    data class Dials(val tuned: Double = .5, val mood: String = "")

    /** Where the needle is, in nine steps: its words, and what they mean. */
    private val steps = listOf(
        "all yours" to "only the songs you know",
        "mostly yours" to "the songs you know, and a few next to them",
        "yours, opening up" to "your songs, with their neighbors coming in",
        "yours first" to "your songs first, new ones mixed in",
        "both" to "half yours, half new to you",
        "leaning new" to "more new than yours, still close to home",
        "new, some yours" to "mostly new to you, some of yours to keep it close",
        "mostly new" to "new artists, near what you love",
        "all new" to "almost all new: artists you haven't heard yet",
    )
    fun said(t: Double) = steps[(t.coerceIn(0.0, 1.0) * steps.size).toInt().coerceAtMost(steps.size - 1)].first
    fun meaning(t: Double) = steps[(t.coerceIn(0.0, 1.0) * steps.size).toInt().coerceAtMost(steps.size - 1)].second

    /**
     * The needle as a frequency (2026-11-25, Bronson: finer than five steps): 20 Hz at all yours to 20 kHz at all new,
     * on a log scale as hearing is, so every place on it reads as its own; its number and unit ("612", "Hz"; "1.4", "kHz").
     */
    fun hz(t: Double): Pair<String, String> {
        val f = 20 * kotlin.math.exp(t.coerceIn(0.0, 1.0) * kotlin.math.ln(1000.0))
        return if (f < 1000) kotlin.math.round(f).toInt().toString() to "Hz"
            else (if (f < 10_000) (kotlin.math.round(f / 100) / 10.0).toString() else kotlin.math.round(f / 1000).toInt().toString()) to "kHz"
    }

    const val YOURS = "yours"; const val NEAR = "near"; const val FAR = "far"; const val MOOD = "mood"
    val LANES = listOf(YOURS, NEAR, FAR, MOOD)

    /**
     * A song it could play: its key (Radio.key's: title and first artist), artist, lane, how high its finders put it
     * (0 to 1), and what it was found through (a seed's artist; empty for yours).
     */
    data class Found(val key: String, val artist: String, val lane: String, val rank: Double, val through: String = "")

    /**
     * Each lane's share of what comes, before learning: the dial's, with appetite (Taste's, .1 to .5) nudging both
     * toward new or toward yours. A mood takes a third of whatever's left when chosen.
     */
    fun shares(d: Dials, appetite: Double = ForYou.STRETCH): Map<String, Double> {
        val lean = (appetite - ForYou.STRETCH) * .5            // -.1 to +.1
        // three settings along the needle, between them in proportion: all yours, both, all new
        val t = d.tuned.coerceIn(0.0, 1.0)
        val ends = listOf(doubleArrayOf(.65, .3, .05), doubleArrayOf(.35, .35, .3), doubleArrayOf(.05, .35, .6))
        val (a, b, k) = if (t <= .5) Triple(ends[0], ends[1], t * 2) else Triple(ends[1], ends[2], (t - .5) * 2)
        fun at(i: Int) = a[i] + (b[i] - a[i]) * k
        val base = mapOf(YOURS to (at(0) - lean).coerceAtLeast(.02), NEAR to at(1), FAR to (at(2) + lean).coerceAtLeast(.02))
        return if (d.mood.isEmpty()) base else base.mapValues { it.value * 2 / 3 } + (MOOD to 1.0 / 3)
    }

    /**
     * What each lane has done with you, across sittings: songs played through (good) against left at once (bad), two of
     * each assumed so a few songs don't swing it. A lane's share is multiplied by it, never under half nor over twice:
     * the dial still says most of it.
     */
    class Learned(val good: Map<String, Int> = emptyMap(), val bad: Map<String, Int> = emptyMap()) {
        fun times(lane: String) = (((good[lane] ?: 0) + 2.0) / ((bad[lane] ?: 0) + 2.0)).coerceIn(.5, 2.0)
        fun with(lane: String, through: Boolean) =
            if (through) Learned(good + (lane to (good[lane] ?: 0) + 1), bad) else Learned(good, bad + (lane to (bad[lane] ?: 0) + 1))
    }

    /** One song of this sitting, as it went: its artist, its lane, what it was found through, and how it went. */
    data class Went(val artist: String, val lane: String, val through: String, val how: How, val at: Long)
    enum class How { THROUGH, LEFT, LOVED }

    /** This sitting, so far: who's nearer and who's further, and what to find more like. */
    class Sitting(private val went: List<Went>) {
        private val lean = HashMap<String, Double>()
        private val via = HashMap<String, Double>()
        private val left = HashMap<String, Int>()
        init {
            for (w in went) {
                val a = who(w.artist); val t = who(w.through)
                when (w.how) {
                    How.THROUGH -> { up(lean, a, .25); up(via, a, .2); if (t.isNotEmpty()) up(via, t, .1) }
                    How.LOVED -> { up(lean, a, .5); up(via, a, .6); if (t.isNotEmpty()) up(via, t, .2) }
                    How.LEFT -> { up(lean, a, -.4); left[a] = (left[a] ?: 0) + 1; if (t.isNotEmpty()) up(via, t, -.15) }
                }
            }
        }
        private fun up(m: HashMap<String, Double>, k: String, by: Double) { m[k] = (m[k] ?: 0.0) + by }

        /** Out for the sitting: left at once twice. */
        fun out(artist: String) = (left[who(artist)] ?: 0) >= 2
        /** How much this sitting has moved a song: its artist, and what it was found through. */
        fun moved(f: Found) = (lean[who(f.artist)] ?: 0.0) + (via[who(f.through)] ?: 0.0) + (via[who(f.artist)] ?: 0.0) * .5

        /** The artists to find more like, most wanted first: loved, then played through, the latest first. */
        fun seeds(n: Int): List<String> = went.filter { it.how != How.LEFT }
            .sortedWith(compareByDescending<Went> { it.how == How.LOVED }.thenByDescending { it.at })
            .map { it.artist }.distinctBy(::who).take(n)

        /** How many it's had, all told. */
        val size get() = went.size
    }

    /**
     * The next `n`, from what's been found. Each place goes to a lane by its share (dial times learned), drawn in turn
     * so the shares hold over a few songs; within the lane, the best by its finders' rank, your nearness, the sitting,
     * and a little chance. No artist twice in the last eight (`recent`, the sitting's artists, latest last), none out for
     * the sitting, none said not for you (taste below 0). A lane with nothing gives its place to the next best.
     */
    fun pick(
        found: List<Found>, dials: Dials, taste: (String) -> Double, sitting: Sitting, learned: Learned,
        recent: List<String>, n: Int, appetite: Double = ForYou.STRETCH, random: Random = Random.Default,
    ): List<Found> {
        val shares = shares(dials, appetite).mapValues { (lane, s) -> s * learned.times(lane) }
        val open = found.filter { taste(it.artist) >= 0 && !sitting.out(it.artist) }.distinctBy { it.key }.toMutableList()
        val away = recent.takeLast(8).map(::who).toMutableList()
        val given = HashMap<String, Double>()   // each lane's places so far, against its share
        val out = ArrayList<Found>(n)
        fun score(f: Found) = f.rank * .5 + taste(f.artist).coerceAtLeast(0.0) * (if (f.lane == FAR) .1 else .4) +
            sitting.moved(f) + random.nextDouble() * .15
        while (out.size < n) {
            val lanes = shares.keys.filter { lane -> open.any { it.lane == lane && who(it.artist) !in away } }
            if (lanes.isEmpty()) break
            // the lane furthest behind its share
            val lane = lanes.minByOrNull { (given[it] ?: 0.0) / shares.getValue(it) } ?: break
            val best = open.filter { it.lane == lane && who(it.artist) !in away }.maxByOrNull(::score) ?: break
            out += best; open.remove(best)
            given[lane] = (given[lane] ?: 0.0) + 1
            away += who(best.artist); if (away.size > 8) away.removeAt(0)
        }
        return out
    }

    /**
     * Songs of yours to come back to: the ones heard most (and loved), weighed by how near their artist is, not heard in
     * the last two days; `heard` each listen (key, artist, when), `loved` keys. Best first, with a day's chance so
     * a few sittings don't open the same way.
     */
    fun yours(heard: List<Triple<String, String, Long>>, loved: Set<String>, taste: (String) -> Double, now: Long = nowMs(),
              random: Random = Random(now / 86_400_000)): List<Pair<String, Double>> {
        val last = HashMap<String, Long>(); val times = HashMap<String, Double>(); val by = HashMap<String, String>()
        for ((k, a, at) in heard) {
            last[k] = maxOf(last[k] ?: 0L, at); by[k] = a
            times[k] = (times[k] ?: 0.0) + exp(-(now - at).coerceAtLeast(0) / 86_400_000.0 / 180.0)
        }
        for (k in loved) times[k] = (times[k] ?: 0.0) + 3
        return times.filterKeys { now - (last[it] ?: 0L) > 2 * 86_400_000L }.map { (k, t) ->
            k to (1 - exp(-t / 3)) * .6 + taste(by[k].orEmpty()).coerceAtLeast(0.0) * .4 + random.nextDouble() * .2
        }.sortedByDescending { it.second }
    }

    /** An artist as compared: the first of a shared name, bare. */
    fun who(artist: String) = Taste.bare(artist.substringBefore(",").substringBefore(" & "))
}
