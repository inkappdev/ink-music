package app.ink.music

/**
 * What your frequency remembers across sittings (2026-11-27, Bronson: "make it spotify level smart"), beside each lane's
 * record ([FrequencyMix.Learned]):
 * - each artist it played you: played through, left at once, loved, and when it first brought them to you. One left
 *   twice and never taken stays away for a month, not just the sitting;
 * - each source it found songs through (YouTube's related, your server's similar, Last.fm's similar, ListenBrainz's
 *   similar artists): its songs played through or loved against left at once, so the sources that find for you count for
 *   more;
 * - when each sitting ran, so what you play away from it is told apart.
 * And from those, what stuck: an artist it brought you whom you've since loved, or played through twice away from it.
 * The one measure of whether it finds songs you actually love. Kept as frequency-memory.json by each app; one process
 * writes it (the phone's player, the computer).
 */
class FrequencyMemory private constructor(
    private val artists: HashMap<String, Mark>,
    private val sources: HashMap<String, IntArray>,
    private val sittings: ArrayList<LongArray>,
) {
    /** An artist's record with you here: songs played through, left at once, loved; when first brought, last left. */
    class Mark(var through: Int = 0, var left: Int = 0, var loved: Int = 0, var brought: Long = 0, var leftAt: Long = 0, var source: String = "",
        /** Their name as said (the key's compared: "slowpulp"). */
        var name: String = "")

    /** A song as it went (its artist, what found it, how): each artist's and source's record moved. */
    fun went(artist: String, source: String, how: FrequencyMix.How, at: Long) {
        val m = artists.getOrPut(FrequencyMix.who(artist)) { Mark() }
        if (m.name.isEmpty()) m.name = artist.substringBefore(", ").trim()
        when (how) {
            FrequencyMix.How.THROUGH -> m.through++
            FrequencyMix.How.LOVED -> m.loved++
            FrequencyMix.How.LEFT -> { m.left++; m.leftAt = at }
        }
        if (source.isNotEmpty()) {
            val s = sources.getOrPut(source) { IntArray(2) }
            if (how == FrequencyMix.How.LEFT) s[1]++ else s[0]++
        }
    }

    /** An artist new to you brought by it (its first song given): when, and through which source. */
    fun brought(artist: String, source: String, at: Long) {
        val m = artists.getOrPut(FrequencyMix.who(artist)) { Mark() }
        if (m.name.isEmpty()) m.name = artist.substringBefore(", ").trim()
        if (m.brought == 0L) { m.brought = at; m.source = source }
    }

    /** Away for a month: left at once twice or more, never played through nor loved, the last time within 30 days. */
    fun away(artist: String, now: Long): Boolean {
        val m = artists[FrequencyMix.who(artist)] ?: return false
        return m.left >= 2 && m.through == 0 && m.loved == 0 && now - m.leftAt < 30 * 86_400_000L
    }

    /** How much nearer (above 0) or further (below) an artist is for what's gone before: -.4 to +.4. */
    fun lean(artist: String): Double {
        val m = artists[FrequencyMix.who(artist)] ?: return 0.0
        val good = m.through + 2.0 * m.loved
        return (((good + 1) / (m.left * 1.5 + 1)).let { kotlin.math.ln(it) } * .2).coerceIn(-.4, .4)
    }

    /** A source's worth with you: its songs taken against left, three of each assumed; .6 to 1.6. */
    fun worth(source: String): Double {
        val s = sources[source] ?: return 1.0
        return ((s[0] + 3.0) / (s[1] + 3.0)).coerceIn(.6, 1.6)
    }

    /** A sitting running: begun at `start`, active at `now` (one record a sitting, its end moved on as it plays). */
    fun sitting(start: Long, now: Long) {
        val last = sittings.lastOrNull()
        if (last != null && last[0] == start) last[1] = now else sittings += longArrayOf(start, now)
        while (sittings.size > 400) sittings.removeAt(0)
    }

    /** Whether a moment fell in a sitting (its end taken 15 minutes on: the last song it gave still playing). */
    fun inSitting(at: Long) = sittings.any { at >= it[0] && at <= it[1] + 15 * 60_000L }

    /**
     * What stuck: the artists it brought you that you've since loved (`lovedAt`: an artist, when last loved), or played
     * through twice away from it (`heard`: each listen's artist, when, how long). Newest brought first.
     */
    fun stuck(heard: List<Triple<String, Long, Long>>, lovedAt: Map<String, Long>): List<String> {
        val away = HashMap<String, Int>()
        for ((a, at, ms) in heard) {
            if (ms < 60_000 || inSitting(at)) continue
            val w = FrequencyMix.who(a)
            val m = artists[w] ?: continue
            if (m.brought in 1 until at) away[w] = (away[w] ?: 0) + 1
        }
        val loved = lovedAt.mapKeys { FrequencyMix.who(it.key) }
        return artists.filter { (w, m) -> m.brought > 0 && ((loved[w] ?: 0L) > m.brought || (away[w] ?: 0) >= 2) }
            .entries.sortedByDescending { it.value.brought }.map { it.value.name.ifEmpty { it.key } }
    }

    /** How many artists it's brought you, all told. */
    val broughtCount get() = artists.values.count { it.brought > 0 }

    fun json(): String {
        val a = JSONObject()
        artists.forEach { (w, m) ->
            a.put(w, JSONArray().put(m.through).put(m.left).put(m.loved).put(m.brought).put(m.leftAt).put(m.source).put(m.name))
        }
        val s = JSONObject(); sources.forEach { (k, v) -> s.put(k, JSONArray().put(v[0]).put(v[1])) }
        val t = JSONArray(); sittings.forEach { t.put(JSONArray().put(it[0]).put(it[1])) }
        return JSONObject().put("artists", a).put("sources", s).put("sittings", t).toString()
    }

    companion object {
        fun empty() = FrequencyMemory(HashMap(), HashMap(), ArrayList())

        fun of(text: String?): FrequencyMemory = runCatching {
            val o = JSONObject(text ?: return empty())
            val artists = HashMap<String, Mark>()
            o.optJSONObject("artists")?.let { a ->
                a.keys().forEach { w ->
                    val r = a.optJSONArray(w) ?: return@forEach
                    artists[w] = Mark(r.optInt(0), r.optInt(1), r.optInt(2), r.optLong(3), r.optLong(4), r.optString(5), r.optString(6))
                }
            }
            val sources = HashMap<String, IntArray>()
            o.optJSONObject("sources")?.let { s -> s.keys().forEach { k -> s.optJSONArray(k)?.let { sources[k] = intArrayOf(it.optInt(0), it.optInt(1)) } } }
            val sittings = ArrayList<LongArray>()
            o.optJSONArray("sittings")?.let { t -> for (i in 0 until t.length()) t.optJSONArray(i)?.let { sittings += longArrayOf(it.optLong(0), it.optLong(1)) } }
            FrequencyMemory(artists, sources, sittings)
        }.getOrDefault(empty())
    }
}
