package app.ink.music

import kotlin.random.Random

/**
 * What TUNER plays next (2026-11-18, Bronson: a recommendation engine of its own, and "what if someone's music taste
 * doesn't align with ink curation?"). Chosen a clip at a time, as you go, from a pool its sources keep filling: your
 * artists' new videos ("yours"), artists your artists' fans also like ("fans"), YouTube's up next for what you liked
 * ("related"), the editors' new-music lists nearest your taste ("editors"). None is ink's taste: each starts from
 * yours, and each source earns its share by how it's done with you (`shares`, from Tuned: likes and clips watched
 * against swipes past), so a source that isn't for someone fades to a trickle and one that is grows.
 *
 * Within a source, the nearest you first, moved by this sitting: a like brings that artist's neighbours and up next
 * forward (their `tag` is the liked artist); not for me drops the artist and everything found through them; a swipe
 * past at once cools them a little. Never one artist within three of themselves.
 */
class TunerMix(private val shares: Map<String, Double>, private val random: Random = Random.Default) {
    /** A clip as the mix sees it: its id, artist, source, what it was found through (an artist, a list), how near you. */
    class Pick(val id: String, val artist: String, val from: String, val tag: String, val near: Double)

    // how near this sitting has moved an artist (their own clips), and what was found through them (their up next,
    // their fans' favorites): a like moves the second far more (2026-11-18, Bronson: a liked artist came round "maybe
    // too many")
    private val lean = HashMap<String, Double>()
    private val through = HashMap<String, Double>()
    private fun up(m: HashMap<String, Double>, k: String, by: Double) { m[k] = (m[k] ?: 0.0) + by }
    private val dropped = HashSet<String>()
    private fun key(s: String) = Curation.folded(s.substringBefore(" & ")).trim()

    /** Liked: the artist, and whatever was found through them, nearer this sitting. */
    fun liked(artist: String) { up(lean, key(artist), .2); up(through, key(artist), .6); likes += key(artist) }
    private val likes = HashSet<String>()
    /** Liked this sitting. */
    fun isLiked(artist: String) = key(artist) in likes
    /** Dug (round its clip again, played in full, its artist's page opened): nearer, nearly as a like. */
    fun dug(artist: String, tag: String) { up(lean, key(artist), .15); up(through, key(artist), .35); up(through, key(tag), .1) }
    /** Watched a while: a little nearer. */
    fun kept(artist: String, tag: String) { up(lean, key(artist), .1); up(through, key(artist), .1); up(through, key(tag), .05) }
    /** Swiped past at once: a little further, and what it was found through a touch. */
    fun passed(artist: String, tag: String) { up(lean, key(artist), -.25); up(through, key(tag), -.08) }
    /** Not for me: the artist, and what was found through them, gone. */
    fun refused(artist: String) { dropped += key(artist) }

    /** Whether a clip's still wanted (not dropped by a not for me). */
    fun wanted(p: Pick) = key(p.artist) !in dropped && key(p.tag) !in dropped

    /**
     * The next clip from `pool`, never an artist of the last eight (`recent`, every clip shown this sitting); one
     * already shown twice this sitting further back with each more. Null when there's none.
     */
    fun next(pool: List<Pick>, recent: List<String>): Pick? {
        val away = recent.takeLast(8).map(::key).toSet()
        val times = recent.groupingBy(::key).eachCount()
        val open = pool.filter { wanted(it) && key(it.artist) !in away }.ifEmpty { pool.filter(::wanted) }
        if (open.isEmpty()) return null
        // the source, by its share among those with something to give
        val sources = open.map { it.from }.distinct()
        val weights = sources.map { (shares[it] ?: 1.0).coerceAtLeast(.05) }
        var r = random.nextDouble() * weights.sum()
        val from = sources.indices.firstOrNull { i -> r -= weights[i]; r <= 0 }?.let { sources[it] } ?: sources.last()
        // then within it, the nearest you this sitting (a little chance so two sittings don't run the same)
        fun score(p: Pick) = p.near + (lean[key(p.artist)] ?: 0.0) + (through[key(p.tag)] ?: 0.0) -
            .5 * ((times[key(p.artist)] ?: 0) - 1).coerceAtLeast(0) + random.nextDouble() * .1
        return open.filter { it.from == from }.maxByOrNull(::score)
    }

    companion object {
        /** Where each source starts before it's earned anything: up next for a like a little ahead, a rabbit hole well ahead. */
        val START = mapOf(
            "yours" to 1.0, "fans" to 1.0, "related" to 1.3, "editors" to 1.0,
            // the older and further out: an artist's deep cuts, the decades' lists, fans of fans, a rabbit hole's
            "deep" to .8, "decades" to .6, "far" to .7, "hole" to 1.6,
            // bands live in a room: KEXP, Tiny Desk, COLORS and the rest
            "sessions" to 1.0,
        )
    }
}
