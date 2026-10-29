package app.ink.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
            albumId = "pick:" + Curation.key(pick), albumArtist = pick.artist, duration = 0, disc = 1, track = 0, year = 0, art = art)
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
