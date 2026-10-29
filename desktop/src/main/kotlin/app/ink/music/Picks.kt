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
    val onAir = MutableStateFlow<List<Curation.Aired>>(emptyList())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file = File(Home.cache, "picks.json")
    private var readAt = 0L

    /** Kept ones at once; read again when they're half a day old. */
    fun start() {
        if (readAt > 0L) return
        val kept = runCatching { JSONObject(file.readText()) }.getOrNull()
        kept?.let { show(it) }
        // a day kept before it was checked against YouTube Music: read again now
        readAt = kept?.takeIf { it.optBoolean("checked") }?.optLong("at") ?: 1L
        if (System.currentTimeMillis() - readAt > 12 * 3_600_000L) scope.launch { read() }
    }

    private suspend fun read() = coroutineScope {
        val all = Curation.picks()
        if (all.isEmpty()) return@coroutineScope
        // one of each (a record several named shows once, with all who named it), its cover found
        val firsts = all.groupBy(Curation::key).values.map { group -> group.first() to group.map { it.from }.distinct() }
        // covers: the feed's own, else one found before, else iTunes', asked a few at a time and forty at most a read (it
        // turns a crowd of asks away); what's left shows its name, and is asked for next time
        val before = (records.value + songs.value).associate { Curation.key(it.pick) to it.art }
        val asks = firsts.filter { (p, _) -> p.art == null && before[Curation.key(p)] == null }.take(40)
        val found = HashMap<String, String?>()
        asks.chunked(4).forEach { group ->
            group.map { (p, _) -> async { Curation.key(p) to Curation.itunes(p.title, p.artist, p.record) } }.awaitAll().forEach { (k, v) -> found[k] = v }
            kotlinx.coroutines.delay(800)
        }
        val shown = firsts.map { (p, by) -> Shown(p, p.art ?: before[Curation.key(p)] ?: found[Curation.key(p)], by) }
        // the day's record: the first in turn that YouTube Music has, so play always plays (eight asked at most)
        val day = Curation.recordsOfTheDay(all).take(8).firstOrNull { record(it) != null }
            ?.let { d -> shown.firstOrNull { Curation.key(it.pick) == Curation.key(d) }?.copy(pick = d) }
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
        if (System.currentTimeMillis() - fridayAt < 12 * 3_600_000L) return
        fridayAt = System.currentTimeMillis()
        scope.launch { Curation.outThisFriday()?.let { friday.value = it } }
    }

    private val arts = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<String>>()
    private val asking = kotlinx.coroutines.sync.Semaphore(3)

    /** A record's cover: the thread's own (when its link is one), else its Bandcamp or Apple Music page's, else iTunes', asked as its tile comes into sight, kept. */
    suspend fun art(r: Curation.Release): String? = art(r.title, r.artist, r.link, true)

    suspend fun art(title: String, artist: String, link: String, record: Boolean): String? {
        if (Regex("\\.(jpe?g|png)$", RegexOption.IGNORE_CASE).containsMatchIn(link)) return link
        val k = (artist + "/" + title).lowercase()
        arts[k]?.let { return it.orElse(null) }
        return asking.withPermit {
            arts[k]?.let { return@withPermit it.orElse(null) }
            withContext(Dispatchers.IO) { Curation.coverAt(link) ?: Curation.itunes(title, artist, record) }.also { arts[k] = java.util.Optional.ofNullable(it) }
        }
    }

    // ---- new from your artists ----

    /**
     * Everything picked lately (the critics' records and songs, out this Friday, electronic's) by artists you've played
     * or keep, the most played first, each said why: out friday, or who picked it, and how often you've played them.
     */
    fun yours(listened: Listening, library: List<Song>): List<Shown> {
        val known = HashMap<String, Int>()
        library.forEach { known.putIfAbsent(it.albumArtist.lowercase(), 0) }
        listened.plays.forEach { (k, n) -> if (k.startsWith("a:")) known[k.removePrefix("a:")] = n }
        val friday = friday.value?.releases.orEmpty().map { Curation.Pick(true, it.title, it.artist, "out friday", "", it.link, 0, null) }
        val all = friday + records.value.map { it.pick } + songs.value.map { it.pick } + electronic.value
        return Curation.yours(all, known).take(16).map { y ->
            // short, to fit under a tile: "stereogum · 40 plays", "out friday · yours"
            Shown(y.pick, y.pick.art, listOf("${y.pick.from} · " + if (y.plays > 0) "${y.plays} plays" else "yours"))
        }
    }

    // ---- electronic ----

    /** Electronic's own writers' picks (Curation.electronic): records, and club tracks; read with the rest. */
    val electronic = MutableStateFlow<List<Curation.Pick>>(emptyList())
    @Volatile private var electronicAt = 0L

    fun startElectronic() {
        if (System.currentTimeMillis() - electronicAt < 12 * 3_600_000L) return
        electronicAt = System.currentTimeMillis()
        scope.launch { Curation.electronic().takeIf { it.isNotEmpty() }?.let { electronic.value = it } }
    }

    /** A week's record as a pick (its genres where a critic's name would be), to show and find as picks are. */
    fun shown(r: Curation.Release, art: String?) = Shown(Curation.Pick(true, r.title, r.artist, "r/indieheads", "", r.link, 0, art), art,
        listOf(r.genres.take(2).joinToString(", ").lowercase()))

    /** What's on the air, asked again (explore asks each minute while it's up). */
    suspend fun air() { val a = withContext(Dispatchers.IO) { Curation.onAir() }; if (a.isNotEmpty()) onAir.value = a }

    // ---- found on YouTube Music ----

    /** A picked song (or one on the air) found to play; null when YouTube Music hasn't it. */
    suspend fun song(title: String, artist: String): Song? = withContext(Dispatchers.IO) { YouTube.find(title, artist) }

    /** A picked record's page on YouTube Music, its name and artist matched; null when it hasn't it. */
    suspend fun record(p: Curation.Pick): TubeAlbum? = withContext(Dispatchers.IO) {
        YouTube.search("${p.artist} ${p.title}")?.second?.firstOrNull { Pipe.same(it.title, it.artist, p.title, p.artist) }
    }

    /** The picked songs, found and played in turn (each found as the list is made: a moment). */
    suspend fun playSongs(list: List<Shown>) {
        val found = withContext(Dispatchers.IO) { list.take(25).map { s -> async { YouTube.find(s.pick.title, s.pick.artist) } }.awaitAll().filterNotNull() }
        if (found.isNotEmpty()) Player.play(found)
    }
}
