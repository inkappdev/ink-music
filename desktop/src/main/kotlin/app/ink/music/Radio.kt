package app.ink.music

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
    /**
     * Where radio's songs come from (2026-11-12, a tester: "too ambiguous and unpredictable ... sometimes it searches
     * online, other times it simply uses local files"): ONLINE only songs from online (YouTube Music), never your files,
     * whatever the song it starts from; OFFLINE only the files on this computer, near it by sound; BOTH, as it was (your
     * server's similar, YouTube's, then your own). Neither ever comes back empty while there's anything to play.
     */
    // said as "similar" (2026-11-12, Bronson): radio is the stations; this is the queue going on with songs like it
    enum class Mode(val said: String, val word: String) { BOTH("similar", "both"), ONLINE("similar, online", "online"), OFFLINE("similar, your files", "your files") }

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
    fun after(seeds: List<Seed>, queued: Set<String>, tempo: Double? = null, mode: Mode = Mode.BOTH): List<Song> {
        if (seeds.isEmpty()) return emptyList()
        val lists = seeds.take(WEIGHT.size).map { when (mode) { Mode.BOTH -> related(it); Mode.ONLINE -> online(it); Mode.OFFLINE -> offline(it) } }
        val since = System.currentTimeMillis() - RECENT
        val heard = History.read().filter { it.at >= since }.flatMap { listOf(it.id, key(it.title, it.artist)) }.toHashSet()
        if (tempo == null) return rank(seeds, lists, queued, heard)
        // after a mix (Mixes), keeping to its tempo (the phone's): radio's songs read, those within 6% taken
        val read = ArrayList<Pair<Song, Tempos.Read>>()
        for (s in rank(seeds, lists, queued, heard, batch = 30)) {
            val r = Tempos.of(s)?.takeIf { it.usable } ?: continue
            read += s to r
            if (read.count { Tempos.fits(it.second.bpm, tempo, .06) } >= BATCH) break
        }
        Tempos.save()
        val near = read.filter { Tempos.fits(it.second.bpm, tempo, .06) }
        return (if (near.size >= 3) near else read.sortedBy { Tempos.apart(it.second.bpm, tempo) }.take(5)).take(BATCH).map { it.first }
    }

    /** The seeds' lists (in the seeds' order) made one: scored, sifted, no artist twice running. */
    fun rank(seeds: List<Seed>, lists: List<List<Song>>, queued: Set<String>, heard: Set<String>, batch: Int = BATCH): List<Song> {
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
        }.take(batch).map { it.value.song }
        return apart(picked, seeds.first().artist)
    }

    /**
     * One seed's songs: the server's similar, else YouTube Music's radio from it; a song YouTube doesn't know (2026-11-11,
     * a tester's Video 2: radio came only for the better-known of their files), the artist's own radio there, then your
     * own songs by them and from the same folder: radio always starts.
     */
    fun related(s: Seed): List<Song> {
        // your server's similar for its songs; otherwise the three ears blended, as online radio is (online)
        if (!s.id.startsWith("y")) Server.similar(s.id, s.title, s.artist).takeIf { it.isNotEmpty() }?.let { return it }
        online(s).takeIf { it.isNotEmpty() }?.let { return it }
        val known = s.artist.isNotBlank() && s.artist != "unknown"
        val by = s.artist.substringBefore(", ").trim()
        if (known && !Local.on) runCatching { kotlinx.coroutines.runBlocking { YouTube.search(by) }?.songs?.firstOrNull { it.artist.substringBefore(", ").trim().equals(by, true) } }
            .getOrNull()?.let { YouTube.related(it.id) }?.takeIf { it.isNotEmpty() }?.let { return it }
        return yours(s)
    }

    /**
     * Online only, none of it your files (2026-11-12, Bronson: radio as good as Chosic's suggestions): three ears blended,
     * YouTube Music's radio from the song, Deezer's radio for its artist, and Last.fm's artists most like them with their
     * best-known songs; a song more than one names, or by an artist more than one names, first; the song's own artist
     * once at most; the picks found on YouTube Music to play. The song found by its tags, or by what it is (Recognize)
     * or its file's name when it has none. Never empty: the critics' picks near its genre, then any.
     */
    fun online(s: Seed): List<Song> {
        if (Local.on) return emptyList()
        val seed = Library.songs.value.firstOrNull { it.id == s.id }
        var title = s.title; var artist = s.artist.substringBefore(", ").trim()
        if (artist.isBlank() || artist == "unknown") {
            val heard = if (seed != null && seed.path.isNotEmpty() && !seed.tube) runCatching { Recognize.of(seed) }.getOrNull() else null
            if (heard != null) { title = heard.title; artist = heard.artist.substringBefore(" & ").substringBefore(", ").trim() }
            else { title = spoken(seed?.path?.let { java.io.File(it).nameWithoutExtension } ?: s.title); artist = "" }
        }
        val tube = if (s.id.startsWith("y")) s.id else (YouTube.find(title, artist)
            ?: runCatching { kotlinx.coroutines.runBlocking { YouTube.search("$artist $title".trim()) }?.songs?.firstOrNull() }.getOrNull())?.id
        if (artist.isBlank()) tube?.let { YouTube.known(it)?.artist?.substringBefore(", ")?.let { a -> artist = a } }

        // the three, asked at once
        val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
        val fromTube = pool.submit<List<Song>> { tube?.let { YouTube.related(it) }.orEmpty() }
        val fromDeezer = pool.submit<List<Similar.Track>> { if (artist.isNotBlank()) Similar.deezerRadio(artist) else emptyList() }
        val fromLastFm = pool.submit<List<Similar.Track>> {
            if (artist.isBlank()) emptyList()
            else Similar.lastFmArtists(artist, LastFm.key, 8).take(6).flatMap { (a, m) -> Similar.deezerTop(a, 2).map { it.copy(weight = m * it.weight) } }
        }
        val yt = runCatching { fromTube.get(20, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(emptyList())
        val dz = runCatching { fromDeezer.get(20, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(emptyList())
        val lf = runCatching { fromLastFm.get(25, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(emptyList())
        pool.shutdownNow()

        fun who(a: String) = a.lowercase().substringBefore(",").substringBefore(" & ").trim()
        // which artists each ear names: one named by more than one is surely near
        val named = HashMap<String, Int>()
        listOf(yt.map { who(it.artist) }, dz.map { who(it.artist) }, lf.map { who(it.artist) }).forEach { l -> l.toSet().forEach { named[it] = (named[it] ?: 0) + 1 } }
        class Pick(val title: String, val artist: String, var score: Double, var song: Song? = null)
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
            if (odd(p.title) && !odd(title)) return@filter false
            if (key(p.title, p.artist) == key(title, artist)) return@filter false
            val most = if (w == me) 1 else 2
            if ((by[w] ?: 0) >= most) return@filter false
            by[w] = (by[w] ?: 0) + 1
            true
        }.take(14)
        // played from YouTube Music: those it gave already, the rest found there (in turn, the strongest first)
        val songs = chosen.mapNotNull { p -> p.song ?: runCatching { YouTube.find(p.title, p.artist) }.getOrNull() }
        if (songs.isNotEmpty()) return songs
        // never empty: what the critics are picking, those of the seed's genre first
        val picksNow = Picks.songs.value.map { it.pick }
        val tags = Genres.tags(artist)?.take(3).orEmpty()
        val near = picksNow.filter { p -> tags.isNotEmpty() && Genres.tags(p.artist)?.take(3)?.any { it in tags } == true }
        return (near + picksNow.shuffled()).distinctBy { it.artist + it.title }.take(12)
            .mapNotNull { p -> runCatching { YouTube.find(p.title, p.artist) }.getOrNull() }.take(8)
    }

    /** A file's name as words: "03_some-song_(official)" as "some song". */
    private fun spoken(name: String) = name.replace(Regex("""[_\-.]+"""), " ").replace(Regex("""^\s*\d{1,3}\s+"""), "")
        .replace(Regex("""\((official|audio|video|lyrics?)[^)]*\)""", RegexOption.IGNORE_CASE), "").trim()

    /**
     * Offline only: the files on this computer nearest the seed by what's known of their sound: the same artist, artists
     * Last.fm's listeners hear as near, the same genre tags (Last.fm's), a tempo near (where it's been read), the same folder; scored and the best taken, the
     * rest of the library after, so it never stops while there's a file to play.
     */
    fun offline(s: Seed): List<Song> {
        val all = Library.songs.value.filter { !it.tube && !it.server && !it.station && !it.episode && it.path.isNotEmpty() && it.id != s.id }
        if (all.isEmpty()) return emptyList()
        val seed = Library.songs.value.firstOrNull { it.id == s.id }
        fun who(a: String) = a.lowercase().substringBefore(",").trim()
        val me = who(s.artist)
        val myTags = Genres.tags(s.artist)?.take(5).orEmpty().toSet()
        val myTempo = Tempos.kept(s.id)?.takeIf { it.bpm > 0 }?.bpm
        val folder = seed?.path?.let { java.io.File(it).parent }
        // your artists Last.fm's listeners hear as near this one (only who's near is asked; what plays is all yours)
        val kin = if (me.isNotEmpty() && me != "unknown" && !Local.on) runCatching { Similar.lastFmArtists(s.artist.substringBefore(", "), LastFm.key, 50) }
            .getOrDefault(emptyList()).associate { who(it.first) to it.second } else emptyMap()
        val scored = all.map { x ->
            var score = 0.0
            if (me.isNotEmpty() && me != "unknown" && who(x.artist) == me) score += 3
            kin[who(x.artist)]?.let { score += 2.5 * it }
            Genres.tags(x.artist)?.take(5)?.let { t -> score += 1.5 * t.count { it in myTags } }
            if (myTempo != null) Tempos.kept(x.id)?.takeIf { it.bpm > 0 }?.let { if (Tempos.fits(it.bpm, myTempo, .06)) score += 1 }
            if (folder != null && java.io.File(x.path).parent == folder) score += 1
            x to score + Math.random() * .5
        }.sortedByDescending { it.second }
        return scored.map { it.first }.take(40)
    }

    /** Your own songs near a seed: the same artist's, then the same folder's, shuffled; the seed itself left out. */
    private fun yours(s: Seed): List<Song> {
        val all = Library.songs.value
        val seed = all.firstOrNull { it.id == s.id }
        val who = s.artist.lowercase().substringBefore(",").trim()
        val same = if (who.isNotEmpty() && who != "unknown") all.filter { it.id != s.id && it.artist.lowercase().substringBefore(",").trim() == who } else emptyList()
        val folder = seed?.path?.takeIf { it.isNotEmpty() }?.let { java.io.File(it).parent }
        val near = if (folder != null) all.filter { it.id != s.id && it.path.isNotEmpty() && java.io.File(it.path).parent == folder } else emptyList()
        return (same.shuffled() + near.shuffled()).distinctBy { it.id }.take(30)
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

/**
 * Radio from a song (2026-11-11, a tester: "the song immediately restarts from the beginning ... as if the feature
 * punishes you for using it"): the one playing plays on, the songs queued after it let go and radio's following it;
 * any other song begins, radio after it, as before.
 */
fun Player.radioFrom(s: Song, mode: Radio.Mode = radioMode.value) {
    radioMode(mode)
    if (now.value?.song?.id != s.id) return startRadio(s)
    for (i in queue.value.size - 1 downTo index() + 1) remove(i)
    // (on already: asked again, it fills after this song)
    radio(true)
}
