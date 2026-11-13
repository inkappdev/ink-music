package app.ink.music

import android.content.Context

/**
 * Radio: when the queue runs out, more like it. Your server's first (Navidrome's similar songs: Last.fm's
 * idea of it, from what you have), else YouTube Music's (the radio it makes from a song, yours when signed
 * in; the full build only). Run in the player's process, so it goes on with the app closed.
 *
 * Seeded by more than the last song (2026-10-28): the last one,
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

    /**
     * Songs to follow the seeds (blocking; the last song first): none already queued (`queued`: ids, and
     * title/artist keys, so the same song from another source counts too).
     */
    fun after(c: Context, seeds: List<Seed>, queued: Set<String>, tempo: Double? = null, fromSimilar: Set<String> = emptySet()): List<Song> {
        if (seeds.isEmpty()) return emptyList()
        val lists = seeds.take(WEIGHT.size).map { related(c, it) }
        val since = System.currentTimeMillis() - RECENT
        val recent = History.read(c).filter { it.at >= since }
        val heard = recent.flatMap { listOf(it.id, key(it.title, it.artist)) }.toHashSet()
        // you, in it (2026-11-12, Bronson: "smarter logic"): similar's own songs left inside half a minute, by artist
        // (once, lower; twice, out for now), and artists said not for you, out
        val skips = History.skips(c).filter { it.at >= since && it.id in fromSimilar }.groupingBy { who(it.artist) }.eachCount()
        val refused = Fresh.turnedDownNames(c).map(::who).toSet()
        val ranked = { batch: Int -> rank(seeds, lists, queued, heard, batch, skips, refused) }
        if (tempo == null) return ranked(BATCH)
        return atTempo(c, ranked(30), tempo)
    }

    private fun who(a: String) = a.lowercase().substringBefore(",").trim()

    /**
     * Radio after a mix (Mixes: the queue's first song the mix's), keeping to its tempo (2026-11-08): radio's songs read
     * (Tempos, the kept first), those within 6% of it (half and double counted) taken, ten at most; fewer than three
     * so near, the nearest ones measured. A minute or so of reading, minutes ahead of the queue's end.
     */
    private fun atTempo(c: Context, candidates: List<Song>, tempo: Double): List<Song> {
        val read = ArrayList<Pair<Song, Tempos.Read>>()
        for (s in candidates) {
            val r = Tempos.of(c, s) ?: continue
            if (!r.usable) continue
            read += s to r
            if (read.count { Tempos.fits(it.second.bpm, tempo, .06) } >= BATCH) break
        }
        Tempos.save(c)
        val near = read.filter { Tempos.fits(it.second.bpm, tempo, .06) }
        val picked = if (near.size >= 3) near else read.sortedBy { Tempos.apart(it.second.bpm, tempo) }.take(5)
        android.util.Log.d("ink-radio", "keeping ${"%.0f".format(tempo)} bpm: ${near.size} near of ${read.size} read")
        return picked.take(BATCH).map { it.first }
    }

    /** The seeds' lists (in the seeds' order) made one: scored, sifted, no artist twice running. */
    fun rank(seeds: List<Seed>, lists: List<List<Song>>, queued: Set<String>, heard: Set<String>, batch: Int = BATCH,
             skips: Map<String, Int> = emptyMap(), refused: Set<String> = emptySet()): List<Song> {
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
        // skipped once this session, lower; twice, or said not for you, out
        found.values.forEach { f -> f.score -= .5f * (skips[who(f.song.artist)] ?: 0) }
        val by = HashMap<String, Int>()
        val picked = found.entries.filter { (_, f) -> who(f.song.artist).let { it !in refused && (skips[it] ?: 0) < 2 } }
            .sortedByDescending { it.value.score }.filter { (k, f) ->
            val s = f.song
            val who = s.artist.lowercase().substringBefore(",").trim()
            if (s.id in queued || k in queued || k in seedKeys || s.id in heard || k in heard) return@filter false
            if (!seedVersions && versions.containsMatchIn(s.title)) return@filter false
            if ((by[who] ?: 0) >= SAME_ARTIST) return@filter false
            by[who] = (by[who] ?: 0) + 1
            true
        }.take(batch).map { it.value.song }
        return apart(picked, seeds.first().artist)
    }

    /**
     * One seed's songs: the server's similar for its songs; else three ears blended (the computer's since 2026-11-12,
     * Bronson: radio as good as Chosic's suggestions): YouTube Music's radio from the song, Deezer's radio for its artist,
     * Last.fm's artists most like them with their best-known songs ([blended]); else YouTube Music's radio alone.
     */
    fun related(c: Context, s: Seed): List<Song> {
        if (!s.id.startsWith("y")) Server.similar(c, s.id, s.title, s.artist).takeIf { it.isNotEmpty() }?.let { return it }
        runCatching { blended(s) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        return if (s.id.startsWith("y")) YouTubeMusic.related(s.id)
            else YouTubeMusic.find(s.title, s.artist)?.let { YouTubeMusic.related(it.id) }.orEmpty()
    }

    /**
     * The three blended, as the computer's online radio: a song more than one names, or by an artist more than one names,
     * first; the seed's own artist once at most, none other more than twice; the picks found on YouTube Music to play.
     * Empty in the play build (no YouTube Music) and offline.
     */
    private fun blended(s: Seed): List<Song> {
        // the player's process may not have set it yet (Scrobbling sets it as a song's first told)
        if (LastFm.key.isBlank()) LastFm.key = BuildConfig.LASTFM_KEY
        val artist = s.artist.substringBefore(", ").trim()
        if (artist.isBlank() || artist == "unknown") return emptyList()
        val tube = if (s.id.startsWith("y")) s.id else YouTubeMusic.find(s.title, artist)?.id
        val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
        val fromTube = pool.submit<List<Song>> { tube?.let { YouTubeMusic.related(it) }.orEmpty() }
        val fromDeezer = pool.submit<List<Similar.Track>> { Similar.deezerRadio(artist) }
        val fromLastFm = pool.submit<List<Similar.Track>> {
            Similar.lastFmArtists(artist, LastFm.key, 8).take(6).flatMap { (a, m) -> Similar.deezerTop(a, 2).map { it.copy(weight = m * it.weight) } }
        }
        val yt = runCatching { fromTube.get(20, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(emptyList())
        val dz = runCatching { fromDeezer.get(20, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(emptyList())
        val lf = runCatching { fromLastFm.get(25, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(emptyList())
        pool.shutdownNow()
        // without YouTube Music nothing found can be played
        if (yt.isEmpty() && tube == null) return emptyList()
        fun who(a: String) = a.lowercase().substringBefore(",").substringBefore(" & ").trim()
        val named = HashMap<String, Int>()
        listOf(yt.map { who(it.artist) }, dz.map { who(it.artist) }, lf.map { who(it.artist) }).forEach { l -> l.toSet().forEach { named[it] = (named[it] ?: 0) + 1 } }
        class Pick(val title: String, val artist: String, var score: Double, var song: Song?)
        val picks = LinkedHashMap<String, Pick>()
        fun add(title: String, a: String, w: Double, song: Song?) {
            val p = picks.getOrPut(key(title, a)) { Pick(title, a, 0.0, song) }
            p.score += w; if (p.song == null) p.song = song
        }
        yt.forEachIndexed { i, x -> add(x.title, x.artist, 1.0 - i.toDouble() / (yt.size + 1), x) }
        dz.forEach { add(it.title, it.artist, it.weight, null) }
        lf.forEach { add(it.title, it.artist, it.weight * .9, null) }
        picks.values.forEach { p -> p.score += .5 * ((named[who(p.artist)] ?: 1) - 1) }
        val me = who(artist)
        val by = HashMap<String, Int>()
        val chosen = picks.values.sortedByDescending { it.score }.filter { p ->
            val w = who(p.artist)
            if (versions.containsMatchIn(p.title) && !versions.containsMatchIn(s.title)) return@filter false
            if (key(p.title, p.artist) == key(s.title, artist)) return@filter false
            if ((by[w] ?: 0) >= (if (w == me) 1 else 2)) return@filter false
            by[w] = (by[w] ?: 0) + 1
            true
        }.take(14)
        return chosen.mapNotNull { p -> p.song ?: runCatching { YouTubeMusic.find(p.title, p.artist) }.getOrNull() }
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
