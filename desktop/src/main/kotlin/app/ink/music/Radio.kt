package app.ink.music

/**
 * Radio: when the queue runs out, more like it. Your server's first (Navidrome's similar songs: Last.fm's
 * idea of it, from what you have), else YouTube Music's (the radio it makes from a song, yours when signed
 * in; the full build only). Run in the player's process, so it goes on with the app closed.
 *
 * Seeded by more than the last song (Bronson, 2026-10-28: "very smart, keep the vibe going"): the last one,
 * and the last two you chose yourself, so a few rounds of radio don't wander off from where you were (each
 * round seeded only by radio's own last song drifted). A song near the top for more than one seed comes
 * first; one heard in the last few hours, or a sped-up, slowed, nightcore or karaoke version, doesn't come.
 */
object Radio {
    /** How many come at a time; and at most how many by one artist among them. */
    private const val BATCH = 10
    private const val SAME_ARTIST = 2
    /** How much each seed counts, the last song first. */
    private val WEIGHT = floatArrayOf(1f, .6f, .4f)
    /** Heard this recently: not again yet. */
    private const val RECENT = 6 * 60 * 60_000L

    /** A song to find more like. */
    data class Seed(val id: String, val title: String, val artist: String)

    /** A sped-up, slowed, nightcore or karaoke version (radio's and the mixes' own sifting). */
    fun odd(title: String) = versions.containsMatchIn(title)
    private val versions = Regex("""\b(sped ?up|slowed|nightcore|karaoke|8d audio|bass ?boosted|reverb)\b""", RegexOption.IGNORE_CASE)

    /** Songs to follow the seeds (blocking; the last song first): none already queued (ids, and title/artist keys). */
    fun after(seeds: List<Seed>, queued: Set<String>): List<Song> {
        if (seeds.isEmpty()) return emptyList()
        val lists = seeds.take(WEIGHT.size).map { related(it) }
        val since = System.currentTimeMillis() - RECENT
        val heard = History.read().filter { it.at >= since }.flatMap { listOf(it.id, key(it.title, it.artist)) }.toHashSet()
        return rank(seeds, lists, queued, heard)
    }

    /** The seeds' lists (in the seeds' order) made one: scored, sifted, no artist twice running. */
    fun rank(seeds: List<Seed>, lists: List<List<Song>>, queued: Set<String>, heard: Set<String>): List<Song> {
        // a seed's own kind of version is fine (a slowed song's radio may be slowed songs)
        val seedVersions = seeds.any { versions.containsMatchIn(it.title) }
        val seedKeys = seeds.map { key(it.title, it.artist) }.toHashSet()

        // each song's score: what each seed's list makes of it (higher up, more), added up
        class Found(val song: Song, var score: Float)
        val found = LinkedHashMap<String, Found>()
        lists.forEachIndexed { k, list ->
            list.forEachIndexed { r, s ->
                val w = WEIGHT[k] * (1f - r.toFloat() / (list.size + 1))
                found.getOrPut(key(s.title, s.artist)) { Found(s, 0f) }.score += w
            }
        }
        val by = HashMap<String, Int>()
        val picked = found.entries.sortedByDescending { it.value.score }.filter { (k, f) ->
            val s = f.song
            val who = s.artist.lowercase().substringBefore(",").trim()
            if (s.id in queued || k in queued || k in seedKeys || s.id in heard || k in heard) return@filter false
            if (!seedVersions && versions.containsMatchIn(s.title)) return@filter false
            if ((by[who] ?: 0) >= SAME_ARTIST) return@filter false
            by[who] = (by[who] ?: 0) + 1
            true
        }.take(BATCH).map { it.value.song }
        return apart(picked, seeds.first().artist)
    }

    /** One seed's songs: the server's similar, else YouTube Music's radio from it. */
    fun related(s: Seed): List<Song> =
        if (s.id.startsWith("y")) YouTube.related(s.id)
        else Server.similar(s.id, s.title, s.artist).ifEmpty {
            YouTube.find(s.title, s.artist)?.let { YouTube.related(it.id) }.orEmpty()
        }

    /** The same order, but no artist twice in a row (nor straight after the song before them), where it can be helped. */
    fun apart(songs: List<Song>, before: String): List<Song> {
        fun who(a: String) = a.lowercase().substringBefore(",").trim()
        val left = songs.toMutableList()
        val out = ArrayList<Song>(songs.size)
        var last = who(before)
        while (left.isNotEmpty()) {
            val i = left.indexOfFirst { who(it.artist) != last }.takeIf { it >= 0 } ?: 0
            val s = left.removeAt(i)
            out += s
            last = who(s.artist)
        }
        return out
    }

    /** A song as said, whatever it's from: its title and first artist, case and marks aside. */
    fun key(title: String, artist: String) =
        title.lowercase().substringBefore(" (").filter { it.isLetterOrDigit() } + "/" + artist.lowercase().substringBefore(",").filter { it.isLetterOrDigit() }
}
