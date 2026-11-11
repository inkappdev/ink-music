package app.ink.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What people picked ([Curation]), here: the critics' records and songs with their covers (the feed's own, else
 * iTunes'), read twice a day and kept, so explore opens with them; the record of the day; what's on the air, asked
 * again each minute while it's shown; and each pick found on YouTube Music as it's chosen (a record's page, a song).
 */
object Picks {
    /** A pick with its cover found (null: none anywhere). */
    data class Shown(val pick: Curation.Pick, val art: String?, val by: List<String>) {
        val face: Song get() = Song(id = "ypick:" + Curation.key(pick), path = "", title = pick.title, artist = pick.artist, album = pick.title,
            // its art in its id: a cover found after the tile first drew (none, then) is asked for anew
            albumId = "pick:" + Curation.key(pick) + (art ?: ""), albumArtist = pick.artist, duration = 0, disc = 1, track = 0, year = 0, art = art)
    }

    val records = MutableStateFlow<List<Shown>>(emptyList())
    val songs = MutableStateFlow<List<Shown>>(emptyList())
    val today = MutableStateFlow<Shown?>(null)
    /** Every record a critic picked with what they said, each publication's own (records shown keep only the first's): for a record's page (Curation.saidOf). */
    val said = MutableStateFlow<List<Curation.Pick>>(emptyList())
    val onAir = MutableStateFlow<List<Curation.Aired>>(emptyList())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file = File(Home.cache, "picks.json")
    private var readAt = 0L

    /**
     * Kept ones at once; read again when they're half a day old or yesterday's (the record of the day turns at midnight),
     * on any visit, not only the first: an app left open for days kept its first (2026-10-30).
     */
    fun start() {
        // local files only: not started (Local)
        if (Local.on) return
        if (readAt == 0L) {
            val kept = runCatching { JSONObject(file.readText()) }.getOrNull()
            kept?.let { show(it) }
            // a day kept before it was checked against YouTube Music: read again now
            readAt = kept?.takeIf { it.optBoolean("checked") }?.optLong("at") ?: 1L
            // no quotes kept yet (2026-11-09, before what critics said was kept): the feeds read now, not in half a day
            if (!loadSaid()) readAt = 1L
            // the day's record kept without its cover (it wasn't among those asked): asked for now, not at the next read
            today.value?.takeIf { it.art == null }?.let { d ->
                scope.launch {
                    Curation.cover(d.pick.title, d.pick.artist, d.pick.record)?.let { art -> if (today.value?.pick == d.pick) today.value = d.copy(art = art) }
                }
            }
        }
        if (Curation.stale(readAt) && reading?.isActive != true) reading = scope.launch { read() }
    }
    private var reading: kotlinx.coroutines.Job? = null

    private suspend fun read() = coroutineScope {
        // the critics' feeds, and BIRP!'s monthly playlist (Birp): the newest first
        val all = (Curation.picks() + runCatching {
            val d = java.time.LocalDate.now()
            Birp.picks(System.currentTimeMillis(), d.monthValue, d.year).also { Log.d("picks: birp! ${it.size} songs") }
        }.getOrDefault(emptyList()) + runCatching {
            // triple j's New Music Hitlist, alexrainbirdMusic's New Indie Music (TubeCurators)
            TubeCurators.picks(System.currentTimeMillis(), java.time.LocalDate.now().year)
        }.getOrDefault(emptyList()) + runCatching {
            // Spotify's editorial lists, The Line of Best Fit's and DIY's (SpotifyLists: song data from their embed pages)
            SpotifyLists.picks(System.currentTimeMillis()) { Log.d("picks: $it") }
        }.getOrDefault(emptyList())).sortedByDescending { it.at }
        if (all.isEmpty()) return@coroutineScope
        // what was said, kept for good: added to what earlier reads kept (a record keeps its quote after its review
        // leaves the feeds), each publication's once, the newest 5000
        said.value = (all.filter { it.record && it.said.isNotBlank() } + said.value)
            .distinctBy { it.from.lowercase() + "|" + Curation.key(it) }.sortedByDescending { it.at }.take(5000)
        runCatching { keepSaid() }
        // one of each (a record several named shows once, with all who named it), its cover found
        val firsts = all.groupBy(Curation::key).values.map { group -> group.first() to group.map { it.from }.distinct() }
        // covers: the feed's own, else one found before, else iTunes', asked a few at a time and forty at most a read (it
        // turns a crowd of asks away); what's left shows its name, and is asked for next time
        val before = (records.value + songs.value).associate { Curation.key(it.pick) to it.art }
        val asks = firsts.filter { (p, _) -> p.art == null && before[Curation.key(p)] == null }.take(40)
        val found = HashMap<String, String?>()
        asks.chunked(4).forEach { group ->
            group.map { (p, _) -> async { Curation.key(p) to Curation.cover(p.title, p.artist, p.record) } }.awaitAll().forEach { (k, v) -> found[k] = v }
            kotlinx.coroutines.delay(800)
        }
        val firstShown = firsts.map { (p, by) -> Shown(p, p.art ?: before[Curation.key(p)] ?: found[Curation.key(p)], by) }
        // the day's record: the first in turn that YouTube Music has, so play always plays (eight asked at most)
        val chosen = Curation.recordsOfTheDay(all).take(8).firstOrNull { record(it) != null }
            ?.let { d -> firstShown.firstOrNull { Curation.key(it.pick) == Curation.key(d) }?.copy(pick = d) }
        // its cover always asked for: it's the page's biggest, and the forty asked above are the newest picks, which it
        // needn't be among (2026-11-09: The Universal Bubba showed its name all day; Deezer had its cover)
        val dayArt = chosen?.art ?: chosen?.let { Curation.cover(it.pick.title, it.pick.artist, it.pick.record) }
        val dayKey = chosen?.let { Curation.key(it.pick) }
        val shown = firstShown.map { if (dayArt != null && it.art == null && Curation.key(it.pick) == dayKey) it.copy(art = dayArt) else it }
        val day = chosen?.copy(art = dayArt)
        // the records several named first, then the publications in turn (Curation.fair: no one of them fills the shelf)
        val recs = shown.filter { it.pick.record }.sortedByDescending { it.pick.at }
        records.value = recs.filter { it.by.size > 1 }.sortedByDescending { it.by.size } + Curation.fair(recs.filter { it.by.size <= 1 }) { it.pick.from }
        songs.value = Curation.fair(shown.filter { !it.pick.record }.sortedByDescending { it.pick.at }) { it.pick.from }
        today.value = day
        readAt = System.currentTimeMillis()
        runCatching {
            Home.write(file, JSONObject().put("at", readAt).put("checked", true).put("today", day?.let(::json)).put("records", JSONArray(records.value.map(::json)))
                .put("songs", JSONArray(songs.value.map(::json))).toString())
        }
    }

    /** What critics said, kept for good (not with the day's picks in the cache, which the system may clear). */
    private val saidFile by lazy { File(Home.dir, "critics.json") }
    private fun keepSaid() { saidFile.writeText(JSONArray(said.value.map { saidJson(it) }).toString()) }
    /** The kept quotes, read once at start; false when there are none yet (the feeds are read at once then). */
    private fun loadSaid(): Boolean = runCatching {
        val a = JSONArray(saidFile.readText())
        said.value = (0 until a.length()).map { a.getJSONObject(it) }.map { j ->
            Curation.Pick(true, j.getString("title"), j.getString("artist"), j.getString("from"), j.optString("said"), j.optString("link"), j.optLong("at"), null)
        }
        // one cut short at an initial (kept before that was fixed): the feeds read again now, its whole sentence over it
        said.value.none { Curation.cutShort(it.said) }
    }.getOrDefault(false)

    private fun saidJson(p: Curation.Pick) = JSONObject().put("title", p.title).put("artist", p.artist).put("from", p.from)
        .put("said", p.said).put("link", p.link).put("at", p.at)

    private fun json(s: Shown) = JSONObject().put("record", s.pick.record).put("title", s.pick.title).put("artist", s.pick.artist)
        .put("from", s.pick.from).put("said", s.pick.said).put("link", s.pick.link).put("at", s.pick.at).put("art", s.art ?: "")
        .put("by", JSONArray(s.by))

    private fun shown(o: JSONObject) = Shown(
        Curation.Pick(o.getBoolean("record"), o.getString("title"), o.getString("artist"), o.getString("from"), o.optString("said"),
            o.optString("link"), o.optLong("at"), null),
        o.optString("art").ifEmpty { null }, o.optJSONArray("by")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
    )

    private fun show(o: JSONObject) {
        fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { shown(a.getJSONObject(it)) } }.orEmpty()
        records.value = list("records"); songs.value = list("songs")
        today.value = o.optJSONObject("today")?.let(::shown)
    }

    // ---- out this friday ----

    /** This week's records (r/indieheads' New Music Friday), read with the rest; null till read, or when Reddit won't say. */
    val friday = MutableStateFlow<Curation.Week?>(null)
    @Volatile private var fridayAt = 0L

    /** Read again when it's half a day old. */
    fun startFriday() {
        // local files only: not started (Local)
        if (Local.on) return
        if (System.currentTimeMillis() - fridayAt < 12 * 3_600_000L) return
        fridayAt = System.currentTimeMillis()
        scope.launch { Curation.outThisFriday()?.let { friday.value = it } }
    }

    private val arts = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<String>>()
    private val asking = kotlinx.coroutines.sync.Semaphore(3)

    /** A record's cover: the thread's own (when its link is one), else its Bandcamp or Apple Music page's, else iTunes', asked as its tile comes into sight, kept. */
    suspend fun art(r: Curation.Release): String? = art(r.title, r.artist, r.link, true)

    /**
     * A pick's cover that loads: its own, else looked for anew (2026-11-08: SWALLOW! blank, its link kept from an
     * earlier read gone dead, and a tile only looked when it had none). Null only when there's none anywhere.
     */
    suspend fun artOf(s: Shown, record: Boolean = s.pick.record): String? {
        val own = s.art
        if (own != null && Covers.of(s.face) != null) return own
        return art(s.pick.title, s.pick.artist, s.pick.link.takeIf { it != own }.orEmpty(), record)?.takeIf { it != own } ?: own
    }

    suspend fun art(title: String, artist: String, link: String, record: Boolean): String? {
        if (Regex("\\.(jpe?g|png)$", RegexOption.IGNORE_CASE).containsMatchIn(link)) return link
        val k = (artist + "/" + title).lowercase()
        arts[k]?.let { return it.orElse(null) }
        return asking.withPermit {
            arts[k]?.let { return@withPermit it.orElse(null) }
            // a cover found, or none anywhere, is kept; a lookup that couldn't be made (offline, iTunes turning a crowd
            // away) isn't, so the tile asks again next time it's shown (#1: INK's covers gray for good)
            var unasked = false
            withContext(Dispatchers.IO) {
                Curation.coverAt(link) ?: runCatching { Curation.coverOrThrow(title, artist, record) }.onFailure { unasked = true }.getOrNull()
                    // the review's own picture of it, when it's square (a sleeve, not a press photo)
                    ?: Curation.squareImageAt(link)
                    // the last place: YouTube Music's own search (where it'd play from anyway)
                    ?: YouTube.search("$artist $title")?.let { (songs, records) ->
                        if (record) records.firstOrNull { Pipe.same(it.title, it.artist, title, artist) }?.art
                        else songs.firstOrNull { Pipe.same(it.title, it.artist, title, artist) }?.art
                    }
            }.also { if (it != null || !unasked) arts[k] = java.util.Optional.ofNullable(it) }
        }
    }

    // ---- new from your artists ----

    /**
     * Everything picked lately (the critics' records and songs, out this Friday, electronic's) by artists you've played
     * or keep, the most played first, each said why: out friday, or who picked it, and how often you've played them.
     */
    fun yours(listened: Listening, library: List<Song>, brought: List<Brought> = emptyList()): List<Shown> {
        val known = HashMap<String, Int>()
        library.forEach { known.putIfAbsent(it.albumArtist.lowercase(), 0) }
        // and the near ones of your listening brought in (Last.fm, ListenBrainz): someone new has a first shelf on day
        // one (2026-11-11); ranked as a little played, so what's played here comes first
        val elsewhere = HashMap<String, String>()
        for (b in brought) for ((a, v) in b.artists) if (v >= ForYou.NEAR) {
            val k = a.lowercase()
            known[k] = maxOf(known[k] ?: 0, (v * 10).toInt()); elsewhere.putIfAbsent(k, b.from)
        }
        listened.plays.forEach { (k, n) -> if (k.startsWith("a:")) { known[k.removePrefix("a:")] = n; elsewhere.remove(k.removePrefix("a:")) } }
        val friday = friday.value?.releases.orEmpty().map { Curation.Pick(true, it.title, it.artist, "out friday", "", it.link, 0, null) }
        val all = friday + records.value.map { it.pick } + songs.value.map { it.pick } + electronic.value
        return Curation.yours(all, known).take(16).map { y ->
            // short, to fit under a tile: "stereogum · 40 plays", "out friday · yours"
            Shown(y.pick, y.pick.art, listOf("${y.pick.from} · " + (elsewhere[y.artist.lowercase()]?.let { "from your $it" } ?: if (y.plays > 0) "${y.plays} plays" else "yours")))
        }
    }

    // ---- electronic ----

    /** Electronic's own writers' picks (Curation.electronic): records, and club tracks; read with the rest. */
    val electronic = MutableStateFlow<List<Curation.Pick>>(emptyList())
    @Volatile private var electronicAt = 0L

    fun startElectronic() {
        // local files only: not started (Local)
        if (Local.on) return
        if (System.currentTimeMillis() - electronicAt < 12 * 3_600_000L) return
        electronicAt = System.currentTimeMillis()
        scope.launch { Curation.electronic().takeIf { it.isNotEmpty() }?.let { electronic.value = it } }
    }

    /** A week's record as a pick (its genres where a critic's name would be), to show and find as picks are. */
    fun shown(r: Curation.Release, art: String?) = Shown(Curation.Pick(true, r.title, r.artist, "r/indieheads", "", r.link, 0, art), art,
        listOf(r.genres.take(2).joinToString(", ").lowercase()))

    /**
     * What's on the air, asked again (explore asks each minute while it's up). A station that answered keeps its song for
     * a song's length after (six minutes), so a minute of DJs talking or a feed slow to answer doesn't take it off the
     * shelf (2026-11-10: two left of six); a song it moves on to replaces it.
     */
    suspend fun air() {
        // local files only: not started (Local)
        if (Local.on) return
        val a = withContext(Dispatchers.IO) { Curation.onAir() }
        val now = System.currentTimeMillis()
        synchronized(heardOnAir) {
            a.forEach { heardOnAir[it.station] = it to now }
            heardOnAir.entries.removeAll { now - it.value.second > 6 * 60_000L }
            // the order the stations are asked in, as before
            val order = (a.map { it.station } + onAir.value.map { it.station }).distinct()
            val shown = heardOnAir.values.map { it.first }.sortedBy { s -> order.indexOf(s.station).let { if (it < 0) 99 else it } }
            if (shown.isNotEmpty()) onAir.value = shown
        }
    }
    private val heardOnAir = LinkedHashMap<String, Pair<Curation.Aired, Long>>()

    // ---- found on YouTube Music ----

    /** A picked song (or one on the air) found to play; null when YouTube Music hasn't it. */
    suspend fun song(title: String, artist: String): Song? = withContext(Dispatchers.IO) { YouTube.find(title, artist) }

    /**
     * A shelf played from a song (a genre page's: the phone's, 2026-11-08): all found side by side; it begins with the
     * next five ready behind it (one alone, radio took it for the queue's end), the rest following as they're found.
     */
    suspend fun playFrom(list: List<Shown>): Boolean = kotlinx.coroutines.coroutineScope {
        val finds = list.take(31).map { r -> async(Dispatchers.IO) { YouTube.find(r.pick.title, r.pick.artist) } }
        val first = finds.firstOrNull()?.await() ?: return@coroutineScope false
        val ahead = finds.drop(1).take(5).awaitAll().filterNotNull()
        Player.play((listOf(first) + ahead).distinctBy { it.id })
        Player.follow(first, finds.drop(6).awaitAll().filterNotNull().distinctBy { it.id })
        true
    }

    /**
     * A genre's songs from its editors' lists (GenreLists), kept on the disk (as the phone keeps them: a genre opened
     * before shows at once, a restart too) and asked again after half a day, its lists read all at once. 2026-11-09: in
     * memory only and read a list at a time, a genre's whole top block came in a second or two after the page.
     */
    private val genres = HashMap<String, Pair<Long, List<Shown>>>()
    private const val GENRE_KEEP = 12 * 3_600_000L
    private val lists = java.util.concurrent.Executors.newFixedThreadPool(6) { r -> Thread(r, "genre-lists").apply { isDaemon = true } }
    private val fetching = java.util.Collections.synchronizedSet(HashSet<String>())

    /** What's kept for this genre now, any age (none: empty), without asking: the page's first frame. */
    fun genreNow(g: String): List<Shown> = kept(g.lowercase())?.second.orEmpty()

    suspend fun genre(g: String): List<Shown> = withContext(Dispatchers.IO) {
        val k = g.lowercase(); val now = System.currentTimeMillis()
        val have = kept(k)
        // kept and fresh: that; kept but old: that now, and asked again behind it
        if (have != null && have.second.isNotEmpty()) {
            if (now - have.first >= GENRE_KEEP) Thread { fetch(k) }.apply { isDaemon = true }.start()
            return@withContext have.second
        }
        fetch(k)
    }

    /** The genres you open most, read ahead after start, so their pages open with their block there (Moods.counts). */
    fun warmGenres(names: List<String>) {
        // local files only: not started (Local)
        if (Local.on) return
        Thread({
            runCatching {
                Thread.sleep(25_000)
                names.filter { GenreLists.known(it) }.forEach { g ->
                    val k = g.lowercase()
                    val have = kept(k)
                    if (have == null || System.currentTimeMillis() - have.first >= GENRE_KEEP) fetch(k)
                }
            }
        }, "genres-warm").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    private fun fetch(k: String): List<Shown> {
        if (!fetching.add(k)) return kept(k)?.second.orEmpty()
        try {
            val now = System.currentTimeMillis()
            val songs = GenreLists.songs(k, now, { Log.d("picks: $it") }) { all ->
                all.map { f -> lists.submit(java.util.concurrent.Callable { f() }) }.map { runCatching { it.get() }.getOrDefault(emptyList()) }
            }.map { Shown(it, it.art, listOf(it.from)) }
            if (songs.isEmpty()) return kept(k)?.second.orEmpty()
            synchronized(genres) { genres[k] = now to songs }
            runCatching {
                genreFile(k).parentFile.mkdirs()
                Home.write(genreFile(k), JSONObject().put("at", now).put("songs", JSONArray(songs.map { s ->
                    JSONObject().put("title", s.pick.title).put("artist", s.pick.artist).put("from", s.pick.from).put("art", s.art ?: "")
                })).toString())
            }
            return songs
        } finally { fetching.remove(k) }
    }

    private fun genreFile(k: String) = File(File(Home.cache, "genres"), k.replace(Regex("[^a-z0-9]+"), "-") + ".json")

    private fun kept(k: String): Pair<Long, List<Shown>>? = synchronized(genres) { genres[k] } ?: runCatching {
        val o = JSONObject(genreFile(k).readText())
        val a = o.getJSONArray("songs")
        (o.getLong("at") to (0 until a.length()).map { a.getJSONObject(it) }.map { s ->
            val art = s.optString("art").ifEmpty { null }
            Shown(Curation.Pick(false, s.getString("title"), s.getString("artist"), s.getString("from"), "", "", o.getLong("at"), art), art, listOf(s.getString("from")))
        }).also { synchronized(genres) { genres[k] = it } }
    }.getOrNull()

    /** A picked record's page on YouTube Music, its name and artist matched; null when it hasn't it. */
    suspend fun record(p: Curation.Pick): TubeAlbum? = withContext(Dispatchers.IO) {
        YouTube.search("${p.artist} ${p.title}")?.albums?.firstOrNull { Pipe.same(it.title, it.artist, p.title, p.artist) }
    }

    /** The picked songs, found and played in turn (each found as the list is made: a moment). */
    suspend fun playSongs(list: List<Shown>) {
        val found = withContext(Dispatchers.IO) { list.take(25).map { s -> async { YouTube.find(s.pick.title, s.pick.artist) } }.awaitAll().filterNotNull() }
        if (found.isNotEmpty()) Player.play(found)
    }
}
