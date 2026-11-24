package app.ink.music

/**
 * When someone's singing in a song, from its timed lyrics, so automix never lays one voice over another (2026-11-09;
 * the rule from LastWave's automix, GPL-3.0, its "vocal protection": no more than a third of a second of both singing
 * at once). Its lyrics are ink's own, timed by the line, and by the word where Better Lyrics has them.
 */
object Vocals {
    /** A lyric line: when it starts (ms), its words, and when its last word starts where it's timed word by word. */
    data class Sung(val at: Long, val words: String, val lastWord: Long? = null)

    /** Both singing at once for longer than this (ms) is a clash. */
    const val MOST = 350L

    /**
     * The stretches someone's singing (ms in the song): each line from its start to its end, the end its last word and
     * a little (timed word by word), else as long as its words take to sing (a third of a second a word, two to seven
     * seconds), never past the next line. Untimed or empty lines: none.
     */
    fun spans(lines: List<Sung>): List<LongRange> {
        val timed = lines.filter { it.at >= 0 }.sortedBy { it.at }
        return timed.mapIndexedNotNull { i, l ->
            val words = l.words.trim()
            if (words.isEmpty() || words.all { it in "♪♫ .…-" }) return@mapIndexedNotNull null
            val next = timed.getOrNull(i + 1)?.at ?: Long.MAX_VALUE
            val sung = l.lastWord?.let { it - l.at + 600 } ?: (words.split(' ').size * 330L).coerceIn(2_000, 7_000)
            val end = minOf(l.at + sung, next - 100)
            if (end > l.at) l.at..end else null
        }
    }

    /**
     * How long (ms) both would be singing in a blend: the old song from `outAt` (its time), played `speed` times as
     * fast, the new one from `inAt` (its time), over `fade` ms of the blend.
     */
    fun overlap(old: List<LongRange>, outAt: Double, speed: Double, new: List<LongRange>, inAt: Double, fade: Long): Long {
        fun inBlend(r: LongRange, from: Double, rate: Double) = ((r.first - from) / rate).coerceAtLeast(0.0) to ((r.last - from) / rate).coerceAtMost(fade.toDouble())
        val a = old.map { inBlend(it, outAt, speed) }.filter { it.second > it.first }
        val b = new.map { inBlend(it, inAt, 1.0) }.filter { it.second > it.first }
        var both = 0.0
        for ((a0, a1) in a) for ((b0, b1) in b) both += (minOf(a1, b1) - maxOf(a0, b0)).coerceAtLeast(0.0)
        return both.toLong()
    }

    /**
     * A blend as heard (after LastWave's VocalCheck, 2026-11-24): `clash` the ms both are singing while both are up (each
     * at 0.35 or more, through the blend's own gains), `cutsLine` the old one falling under half in the middle of one
     * of its lines, `hidesEntry` a line of the new one starting while it's still under half. `cost` weighs them.
     */
    data class Check(val clash: Long, val cutsLine: Boolean, val hidesEntry: Boolean) {
        val cost get() = clash / 1000.0 * 4 + (if (cutsLine) 6 else 0) + (if (hidesEntry) 4 else 0)
        val clean get() = clash <= MOST && !cutsLine && !hidesEntry
    }

    /**
     * [Check] of a blend: the old song from `outAt` (its time) at `speed`, the new one from `inAt`, over `fade` ms,
     * `gains(k)` each one's gain (new, old) at k through it; looked at in 128 steps.
     */
    fun check(old: List<LongRange>, outAt: Double, speed: Double, new: List<LongRange>, inAt: Double, fade: Long,
              gains: (Double) -> Pair<Double, Double>): Check {
        val n = 128
        val dt = fade.toDouble() / n
        var clash = 0.0; var cuts = false; var hides = false
        var oldWas = 1.0
        fun sings(spans: List<LongRange>, t: Double) = spans.any { t >= it.first && t <= it.last }
        for (i in 0 until n) {
            val t = (i + .5) * dt
            val (gn, go) = gains((i + .5) / n)
            val so = sings(old, outAt + t * speed); val sn = sings(new, inAt + t)
            if (so && sn && gn >= .35 && go >= .35) clash += dt
            if (so && oldWas >= .5 && go < .5) cuts = true
            oldWas = go
        }
        for (r in new) {
            val t = r.first - inAt
            if (t in 0.0..fade.toDouble() && gains(t / fade).first < .5) hides = true
        }
        return Check(clash.toLong(), cuts, hides)
    }
}
