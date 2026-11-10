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
}
