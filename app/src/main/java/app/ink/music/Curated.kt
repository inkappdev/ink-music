package app.ink.music

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * What people picked ([Curation], shared with the Mac): the critics' records and songs with their covers (the feed's own,
 * else iTunes'), read twice a day and kept; the record of the day; what's on the air, asked each minute while explore's
 * up; a pick found on YouTube Music as it's chosen (the full build; the play build has the radio).
 */
object Picks {
    data class Shown(val pick: Curation.Pick, val art: String?, val by: List<String>) {
        val face: Song get() = Song(id = "ypick:" + Curation.key(pick), uri = android.net.Uri.EMPTY, title = pick.title, artist = pick.artist, album = pick.title,
            albumId = "pick:" + Curation.key(pick) + (art ?: ""), albumArtist = pick.artist, duration = 0, disc = 1, track = 0, year = 0, art = art)
    }

    val records = MutableStateFlow<List<Shown>>(emptyList())
    val songs = MutableStateFlow<List<Shown>>(emptyList())
    val today = MutableStateFlow<Shown?>(null)
    /** Every record a critic picked with what they said, each publication's own (records shown keep only the first's): for a record's page (Curation.saidOf). */
    val said = MutableStateFlow<List<Curation.Pick>>(emptyList())
    val onAir = MutableStateFlow<List<Curation.Aired>>(emptyList())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readAt = 0L

    /**
     * Kept ones at once; read again when they're half a day old or yesterday's (the record of the day turns at midnight),
     * on any visit, not only the first: an app left open for days kept its first (2026-10-30).
     */
    fun start(c: Context) {
        app = c.applicationContext
        val file = File(c.cacheDir, "picks.json")
        if (readAt == 0L) {
            val kept = runCatching { JSONObject(file.readText()) }.getOrNull()
            kept?.let(::show)
            // a day kept before it was checked against YouTube Music, or before the editors' lists (v2): read again now
            readAt = kept?.takeIf { it.optBoolean("checked") && it.optInt("v") >= 7 }?.optLong("at") ?: 1L
            // no quotes kept yet (2026-11-09, before what critics said was kept): the feeds read now, not in half a day
            if (!loadSaid()) readAt = 1L
        }
        if (Curation.stale(readAt) && reading?.isActive != true) reading = scope.launch { read(file) }
    }
    private var reading: kotlinx.coroutines.Job? = null

    private suspend fun read(file: File) = coroutineScope {
        // the critics' feeds, and (the full build) editors' lists: the newest first
        val all = (Curation.picks() + EditorsPicks.read()).sortedByDescending { it.at }
        if (all.isEmpty()) return@coroutineScope
        // what was said, kept for good: added to what earlier reads kept (a record keeps its quote after its review
        // leaves the feeds), each publication's once, the newest 5000
        said.value = (all.filter { it.record && it.said.isNotBlank() } + said.value)
            .distinctBy { it.from.lowercase() + "|" + Curation.key(it) }.sortedByDescending { it.at }.take(5000)
        runCatching { keepSaid() }
        val firsts = all.groupBy(Curation::key).values.map { group -> group.first() to group.map { it.from }.distinct() }
        // covers: the feed's own, one found before, else iTunes', a few at a time and forty a read at most (it turns crowds away)
        val before = (records.value + songs.value).associate { Curation.key(it.pick) to it.art }
        val found = HashMap<String, String?>()
        firsts.filter { (p, _) -> p.art == null && before[Curation.key(p)] == null }.take(40).chunked(4).forEach { group ->
            group.map { (p, _) -> async { Curation.key(p) to Curation.cover(p.title, p.artist, p.record) } }.awaitAll().forEach { (k, v) -> found[k] = v }
            kotlinx.coroutines.delay(800)
        }
        val shown = firsts.map { (p, by) -> Shown(p, p.art ?: before[Curation.key(p)] ?: found[Curation.key(p)], by) }
        // the day's record: the first in turn that YouTube Music has, so play always plays (eight asked at most; the
        // play build, without YouTube, the first)
        val day = Curation.recordsOfTheDay(all).take(8).let { l -> if (YouTubeMusic.available) l.firstOrNull { record(it) != null } else l.firstOrNull() }
            ?.let { d -> shown.firstOrNull { Curation.key(it.pick) == Curation.key(d) }?.copy(pick = d) }
        // the records several named first, then the publications in turn (Curation.fair: no one of them fills the shelf)
        val recs = shown.filter { it.pick.record }.sortedByDescending { it.pick.at }
        records.value = recs.filter { it.by.size > 1 }.sortedByDescending { it.by.size } + Curation.fair(recs.filter { it.by.size <= 1 }) { it.pick.from }
        songs.value = Curation.fair(shown.filter { !it.pick.record }.sortedByDescending { it.pick.at }) { it.pick.from }
        today.value = day
        readAt = System.currentTimeMillis()
        runCatching {
            file.writeText(JSONObject().put("at", readAt).put("v", 7).put("checked", true).put("today", day?.let(::json)).put("records", JSONArray(records.value.map(::json)))
                .put("songs", JSONArray(songs.value.map(::json))).toString())
        }
    }

    /** What critics said, kept for good (not with the day's picks in the cache, which the system may clear). */
    private val saidFile by lazy { File(app!!.filesDir, "critics.json") }
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
        .put("from", s.pick.from).put("said", s.pick.said).put("link", s.pick.link).put("at", s.pick.at).put("art", s.art ?: "").put("by", JSONArray(s.by))

    private fun shown(o: JSONObject) = Shown(
        Curation.Pick(o.getBoolean("record"), o.getString("title"), o.getString("artist"), o.getString("from"), o.optString("said"),
            o.optString("link"), o.optLong("at"), null),
        o.optString("art").ifEmpty { null }, o.optJSONArray("by")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
    )

    private fun show(o: JSONObject) {
        fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { shown(a.getJSONObject(it)) } }.orEmpty()
        records.value = list("records"); songs.value = list("songs"); today.value = o.optJSONObject("today")?.let(::shown)
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

    private var app: Context? = null

    /**
     * A pick's cover that loads: its own, else looked for anew (2026-11-08: SWALLOW! blank on a genre page, its link
     * kept from an earlier read gone dead, and a tile only looked when it had none). Null only when there's none anywhere.
     */
    suspend fun artOf(s: Shown, record: Boolean = s.pick.record): String? {
        val own = s.art
        val c = app
        if (own != null && (c == null || Covers.of(c, s.face) != null)) return own
        return art(s.pick.title, s.pick.artist, s.pick.link.takeIf { it != own }.orEmpty(), record)?.takeIf { it != own } ?: own
    }

    suspend fun art(title: String, artist: String, link: String, record: Boolean): String? {
        if (Regex("\\.(jpe?g|png)$", RegexOption.IGNORE_CASE).containsMatchIn(link)) return link
        val k = (artist + "/" + title).lowercase()
        arts[k]?.let { return it.orElse(null) }
        val t0 = System.currentTimeMillis()
        return asking.withPermit {
            arts[k]?.let { return@withPermit it.orElse(null) }
            val t1 = System.currentTimeMillis()
            // a cover found, or none anywhere, is kept; a lookup that couldn't be made (offline, iTunes turning a crowd
            // away) isn't, so the tile asks again next time it's shown (#1: INK's covers gray for good)
            var unasked = false
            withContext(Dispatchers.IO) {
                Curation.coverAt(link) ?: runCatching { Curation.coverOrThrow(title, artist, record) }.onFailure { unasked = true }.getOrNull()
                    // the review's own picture of it, when it's square (a sleeve, not a press photo)
                    ?: Curation.squareImageAt(link)
                    // the last place: YouTube Music's own search (where it'd play from anyway; the full build)
                    ?: runCatching { if (record) YouTubeMusic.findRecord(title, artist)?.art else YouTubeMusic.find(title, artist)?.art }.getOrNull()
            }.also {
                if (it != null || !unasked) arts[k] = java.util.Optional.ofNullable(it)
                android.util.Log.d("ink-picks", "art $artist - $title: ${if (it != null) "found" else if (unasked) "couldn't ask" else "none"}" +
                    " (waited ${t1 - t0} ms, asked ${System.currentTimeMillis() - t1} ms)")
            }
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
            // who picked it, nothing more (play counts were text for its own sake, 2026-11-07)
            Shown(y.pick, y.pick.art, listOf(y.pick.from))
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

    /**
     * A cover, the record's or song's own (2026-11-07: the record of the day showed only its name, its feed's picture
     * not loading; songs on the air with "(Prince Fatty Remix)" found nothing): iTunes', Deezer's, then again without
     * what's in brackets and who's featured, then YouTube Music's. Null when none anywhere.
     */
    suspend fun coverFor(title: String, artist: String, record: Boolean): String? = withContext(Dispatchers.IO) {
        val plain = title.replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), "").replace(Regex("\\s+(feat\\.?|ft\\.?|with)\\s.*$", RegexOption.IGNORE_CASE), "").trim()
        val who = artist.substringBefore(",").substringBefore(" & ").trim()
        runCatching { Curation.cover(title, artist, record) }.getOrNull()
            ?: plain.takeIf { it.isNotEmpty() && (it != title || who != artist) }?.let { runCatching { Curation.cover(it, who, record) }.getOrNull() }
            ?: runCatching { if (record) YouTubeMusic.findRecord(plain.ifEmpty { title }, who)?.art else YouTubeMusic.find(plain.ifEmpty { title }, who)?.art }.getOrNull()
    }

    suspend fun air() { val a = withContext(Dispatchers.IO) { Curation.onAir() }; if (a.isNotEmpty()) onAir.value = a }

    suspend fun song(title: String, artist: String): Song? = withContext(Dispatchers.IO) { YouTubeMusic.find(title, artist) }
    suspend fun record(p: Curation.Pick): TubeAlbum? = withContext(Dispatchers.IO) { YouTubeMusic.findRecord(p.title, p.artist) }

    /**
     * A shelf played from a song (a genre page's, 2026-11-08: a song tapped there played alone, then radio went on from
     * it and your taste, and nothing after sounded like the genre): all found at once, side by side; it begins with the
     * next five ready behind it (one alone, radio took it for the queue's end and put ten of its own first), the rest
     * following as they're found. False when the first isn't on YouTube Music.
     */
    suspend fun playFrom(list: List<Shown>): Boolean = coroutineScope {
        val finds = list.take(31).map { r -> async(Dispatchers.IO) { YouTubeMusic.find(r.pick.title, r.pick.artist) } }
        val first = finds.firstOrNull()?.await() ?: return@coroutineScope false
        val ahead = finds.drop(1).take(5).awaitAll().filterNotNull()
        Playback.play((listOf(first) + ahead).distinctBy { it.id }, 0)
        val rest = finds.drop(6).awaitAll().filterNotNull()
        Playback.follow(first, rest.distinctBy { it.id })
        true
    }

    suspend fun playSongs(list: List<Shown>) {
        val found = withContext(Dispatchers.IO) { coroutineScope { list.take(25).map { s -> async { YouTubeMusic.find(s.pick.title, s.pick.artist) } }.awaitAll().filterNotNull() } }
        if (found.isNotEmpty()) Playback.play(found, 0)
    }
}

/**
 * Lists made from your listening (the Mac's Discover, here): fresh finds and deep cuts (each Monday); the rest of the
 * record, loved long ago, a year ago, one of each (each day); because you loved (a love's radio, only what you've never
 * played); new to you (whom your artists' fans also like, that you've never played). Kept, made again as weeks and days turn.
 */
object Discover {
    data class Mix(val key: String, val title: String, val said: String, val songs: List<Song>, val fresh: String)
    data class New(val artist: TubeArtist, val via: String)

    val mixes = MutableStateFlow<List<Mix>>(emptyList())
    val because = MutableStateFlow<Pair<Song, List<Song>>?>(null)
    val newToYou = MutableStateFlow<List<New>>(emptyList())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    private fun week() = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()
    private fun day() = LocalDate.now().toString()

    fun start(c: Context, library: List<Song>) {
        if (started || library.isEmpty()) return
        started = true
        val app = c.applicationContext
        scope.launch {
            val file = File(app.cacheDir, "discover.json")
            val kept = runCatching { JSONObject(file.readText()) }.getOrNull()
            kept?.let(::show)
            runCatching { make(app, library, kept, file) }
        }
    }

    private fun show(o: JSONObject) {
        fun songs(a: JSONArray?) = a?.let { (0 until it.length()).map { i -> songOf(it.getJSONObject(i)).also { s -> if (s.tube) YouTubeMusic.remember(s) } } }.orEmpty()
        mixes.value = o.optJSONArray("mixes")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
            Mix(it.getString("key"), it.getString("title"), it.getString("said"), songs(it.optJSONArray("songs")), it.optString("fresh"))
        } }.orEmpty().filter { it.songs.isNotEmpty() }
        because.value = o.optJSONObject("because")?.let { b -> songOf(b.getJSONObject("seed")) to songs(b.optJSONArray("songs")) }?.takeIf { it.second.isNotEmpty() }
        newToYou.value = o.optJSONArray("new")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
            New(TubeArtist(it.getString("name"), it.optString("face").ifEmpty { null }, it.optString("id")), it.optString("via"))
        } }.orEmpty()
    }

    private suspend fun make(c: Context, library: List<Song>, kept: JSONObject?, file: File) {
        val all = History.read(c)
        val now = System.currentTimeMillis()
        val heard = all.flatMap { listOf(it.id, Radio.key(it.title, it.artist)) }.toHashSet()
        val mine = library.flatMap { listOf(it.id, Radio.key(it.title, it.artist)) }.toHashSet()
        val recent = all.filter { it.at >= now - 60L * 86_400_000 }
        val topSongs = recent.groupBy { it.id }.entries.sortedByDescending { e -> e.value.sumOf { it.ms } }.take(10).map { it.value.last() }
            .map { Radio.Seed(it.id, it.title, it.artist) }
            .ifEmpty { library.shuffled(java.util.Random(week().hashCode().toLong())).take(8).map { Radio.Seed(it.id, it.title, it.artist) } }
        fun who(a: String) = a.substringBefore(", ").trim()
        val topArtists = recent.groupBy { who(it.artist).lowercase() }.entries.sortedByDescending { e -> e.value.sumOf { it.ms } }.map { who(it.value.last().artist) }
            .filter { it.isNotBlank() && it != "unknown" }
            // nothing played here yet: your listening brought in (Last.fm, ListenBrainz), then what you have most of
            .ifEmpty { Fresh.brought.value.flatMap { it.artists.entries }.sortedByDescending { it.value }.map { it.key }.distinct() }
            .ifEmpty { library.groupBy { it.albumArtist }.entries.sortedByDescending { it.value.size }.map { it.key }.filter { it.isNotBlank() && it != "unknown" } }
            .take(8)
        val sameWeek = kept?.optString("week") == week()
        // the day's mixes made again when the day turns, or the part of it (this hour's: mornings, evenings...)
        val sameDay = kept?.optString("day") == day() && kept.optString("part") == Moment.now().part.name
        val out = JSONObject().put("week", week()).put("day", day()).put("part", Moment.now().part.name)
        val weekly = if (sameWeek) mixes.value.filter { it.key == "fresh" || it.key == "deep" }
            else listOfNotNull(freshFinds(c, topSongs, heard, mine), deepCuts(topArtists, library, heard, mine))
        val daily = listOfNotNull(restOfTheRecord(all, library, heard), lovedLongAgo(all, library), aYearAgo(all, library), oneOfEach(library),
            thisHour(all, library), justAdded(library, heard), backOfTheShelf(library, heard), aDecade(library),
            runCatching { spotlight(topArtists, library) }.getOrNull(), runCatching { pickedForYou() }.getOrNull())
            .let { d -> listOfNotNull(runCatching { onThisDay(c) }.getOrNull(), runCatching { favorites(c) }.getOrNull(), runCatching { usedToLove(c, all) }.getOrNull()) + d }
        mixes.value = weekly + daily
        out.put("mixes", JSONArray(mixes.value.map { m ->
            JSONObject().put("key", m.key).put("title", m.title).put("said", m.said).put("fresh", m.fresh).put("songs", JSONArray(m.songs.map { it.json() }))
        }))
        if (!sameDay || because.value == null) because.value = becauseYouLoved(c, library, heard, mine)
        because.value?.let { (seed, songs) -> out.put("because", JSONObject().put("seed", seed.json()).put("songs", JSONArray(songs.map { it.json() }))) }
        if (!sameDay || newToYou.value.isEmpty()) newToYou.value = newArtists(topArtists, library, all)
        out.put("new", JSONArray(newToYou.value.map { JSONObject().put("name", it.artist.name).put("face", it.artist.face ?: "").put("id", it.artist.id).put("via", it.via) }))
        runCatching { file.writeText(out.toString()) }
    }

    private suspend fun fromRadios(c: Context, seeds: List<Radio.Seed>, heard: Set<String>, mine: Set<String>, n: Int): List<Song> = coroutineScope {
        val lists = seeds.map { async { runCatching { Radio.related(c, it) }.getOrDefault(emptyList()) } }.awaitAll()
        val score = LinkedHashMap<String, Pair<Song, Float>>()
        lists.forEach { list -> list.forEachIndexed { r, s ->
            val k = Radio.key(s.title, s.artist)
            val was = score[k]?.second ?: 0f
            score[k] = s to was + (1f - r.toFloat() / (list.size + 1))
        } }
        val by = HashMap<String, Int>()
        // an artist said "not for me" (Fresh) never back in a made mix, however the pool's filled
        val down = Fresh.turnedDown(c)
        // twice as many as wanted, then each one's find-ness on Last.fm (Gems: a song people keep coming back to, over a
        // hit) added to how many radios lead to it; four asked at a time, a few seconds a week
        val pool = score.entries.sortedByDescending { it.value.second }.map { it.key to it.value }.filter { (k, v) ->
            val s = v.first
            if (k in heard || s.id in heard || k in mine || s.id in mine || Radio.odd(s.title)) return@filter false
            if (Curation.folded(s.artist.substringBefore(",").trim()) in down) return@filter false
            val who = s.artist.lowercase().substringBefore(",").trim()
            if ((by[who] ?: 0) >= 2) return@filter false
            by[who] = (by[who] ?: 0) + 1
            true
        }.take(n * 2).map { it.second }
        val asking = kotlinx.coroutines.sync.Semaphore(4)
        val rated = pool.map { (s, radio) -> async(Dispatchers.IO) {
            s to radio + asking.withPermit { Gems.bonus(Gems.heard(s.title, s.artist, LastFm.key)) }
        } }.awaitAll().sortedByDescending { it.second }.take(n)
        val strength = rated.associate { it.first.id to it.second }
        // kept apart by artist, then shaped as a set: the strongest opens it, the second-strongest closes it
        Gems.arc(Radio.apart(rated.map { it.first }, "")) { strength[it.id] ?: 0f }
    }

    private suspend fun freshFinds(c: Context, seeds: List<Radio.Seed>, heard: Set<String>, mine: Set<String>): Mix? {
        val songs = fromRadios(c, seeds, heard, mine, 30).takeIf { it.isNotEmpty() } ?: return null
        return Mix("fresh", "fresh finds", "songs you've never played and don't have, from the radios of the songs you play most", songs, "${songs.size} songs, new every monday")
    }

    private suspend fun deepCuts(artists: List<String>, library: List<Song>, heard: Set<String>, mine: Set<String>): Mix? {
        val each = artists.map { name ->
            val yours = library.filter { s -> (s.albumArtist.equals(name, true) || s.artist.split(", ").any { it.equals(name, true) }) &&
                s.id !in heard && Radio.key(s.title, s.artist) !in heard }.shuffled(java.util.Random(week().hashCode().toLong())).take(3)
            val info = withTimeoutOrNull(8_000) { YouTubeAccount.artist(name, "").firstOrNull { it != null } }
            val theirs = info?.top.orEmpty().filter { t -> Radio.key(t.title, t.artist).let { it !in heard && it !in mine } && t.id !in heard }.take(3)
            yours + theirs
        }
        val songs = (0 until (each.maxOfOrNull { it.size } ?: 0)).flatMap { i -> each.mapNotNull { it.getOrNull(i) } }.distinctBy { it.id }.take(30)
        if (songs.isEmpty()) return null
        val names = songs.map { it.albumArtist.ifBlank { it.artist.substringBefore(", ") } }.distinct()
        return Mix("deep", "deep cuts", "songs by your most played artists that you've never played", songs,
            "${songs.size} songs by ${names.take(3).joinToString(", ").lowercase()}${if (names.size > 3) " and more" else ""}")
    }

    private suspend fun becauseYouLoved(c: Context, library: List<Song>, heard: Set<String>, mine: Set<String>): Pair<Song, List<Song>>? {
        val ids = Loved.ids.value
        val loved = (library.filter { it.id in ids } + Loved.tube.value + YouTubeAccount.liked.value).distinctBy { it.id }.sortedBy { it.id }
        if (loved.isEmpty()) return null
        val from = (LocalDate.now().toEpochDay() % loved.size).toInt()
        for (k in 0 until minOf(4, loved.size)) {
            val seed = loved[(from + k) % loved.size]
            val songs = fromRadios(c, listOf(Radio.Seed(seed.id, seed.title, seed.artist)), heard, mine, 20)
            if (songs.isNotEmpty()) return seed to songs
        }
        return null
    }

    private suspend fun newArtists(artists: List<String>, library: List<Song>, all: List<History.Heard>): List<New> {
        val known = (library.flatMap { listOf(it.albumArtist) + it.artist.split(", ") } + all.flatMap { it.artist.split(", ") }).map { it.trim().lowercase() }.toHashSet()
        val each = artists.take(6).map { name ->
            val info = withTimeoutOrNull(8_000) { YouTubeAccount.artist(name, "").firstOrNull { it != null } }
            info?.like.orEmpty().filter { a -> a.name.lowercase().let { l -> l !in known && artists.none { l.contains(it.lowercase()) } } }.map { New(it, info?.name ?: name) }
        }
        return (0 until (each.maxOfOrNull { it.size } ?: 0)).flatMap { i -> each.mapNotNull { it.getOrNull(i) } }.distinctBy { it.artist.name.lowercase() }.take(14)
    }

    private fun restOfTheRecord(all: List<History.Heard>, library: List<Song>, heard: Set<String>): Mix? {
        val played = all.groupBy { it.record }.filterKeys { it.isNotEmpty() }
        val records = library.filter { !it.tube }.groupBy { it.albumId }
        val picks = played.entries.mapNotNull { (rec, hs) ->
            val songs = records[rec] ?: return@mapNotNull null
            val unheard = songs.filter { it.id !in heard }
            if (songs.size < 4 || hs.map { it.id }.distinct().size > 2 || unheard.size < 3) null else hs.maxOf { it.at } to unheard.sortedWith(compareBy({ it.disc }, { it.track }))
        }.sortedByDescending { it.first }.take(5).map { it.second }
        val songs = picks.flatMap { it.take(6) }
        if (songs.isEmpty()) return null
        return Mix("rest", "the rest of the record", "records you know a song or two of, the songs on them you've never played", songs,
            "${picks.size} ${if (picks.size == 1) "record" else "records"}, ${songs.size} songs")
    }

    private fun lovedLongAgo(all: List<History.Heard>, library: List<Song>): Mix? {
        val ids = Loved.ids.value
        val lately = all.filter { it.at >= System.currentTimeMillis() - 60L * 86_400_000 }.map { it.id }.toHashSet()
        val songs = (library.filter { it.id in ids } + Loved.tube.value + YouTubeAccount.liked.value).distinctBy { it.id }.filter { it.id !in lately }
            .shuffled(java.util.Random(day().hashCode().toLong())).take(25)
        if (songs.size < 4) return null
        return Mix("loved", "loved, long ago", "songs you love that you haven't played in two months", songs, "${songs.size} songs, new each day")
    }

    private fun aYearAgo(all: List<History.Heard>, library: List<Song>): Mix? {
        val back = LocalDate.now().minusYears(1)
        val from = back.minusDays(3).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val to = back.plusDays(4).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val then = all.filter { it.at in from until to }.groupBy { it.id }.entries.sortedByDescending { e -> e.value.sumOf { it.ms } }
            .mapNotNull { e -> Library.song(e.key) ?: library.firstOrNull { Radio.key(it.title, it.artist) == Radio.key(e.value[0].title, e.value[0].artist) } }.take(25)
        if (then.size < 4) return null
        return Mix("year", "a year ago", "what you played most this week last year", then,
            "${then.size} songs from ${java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy").format(back).lowercase()}")
    }

    private fun oneOfEach(library: List<Song>): Mix? {
        val r = java.util.Random(day().hashCode().toLong())
        val songs = library.filter { !it.tube }.groupBy { it.albumArtist.ifBlank { it.artist.substringBefore(", ") }.lowercase() }
            .values.map { it[r.nextInt(it.size)] }.shuffled(r).take(40)
        if (songs.size < 8) return null
        return Mix("each", "one of each", "a song from every artist you have", songs, "${songs.size} artists, new each day")
    }

    // ---- more of them (2026-11-07: "made for you is always the same four things") ----

    /** What you play around this hour (an hour either side, these four months): your mornings, your late nights. */
    private fun thisHour(all: List<History.Heard>, library: List<Song>): Mix? {
        val z = java.time.ZoneId.systemDefault()
        val now = java.time.LocalTime.now().hour
        val since = System.currentTimeMillis() - 120L * 86_400_000
        fun near(h: Int) = minOf(Math.abs(h - now), 24 - Math.abs(h - now)) <= 1
        val songs = all.filter { it.at > since && near(java.time.Instant.ofEpochMilli(it.at).atZone(z).hour) }
            .groupBy { it.id }.entries.sortedByDescending { e -> e.value.size }
            .mapNotNull { e -> Library.song(e.key) ?: library.firstOrNull { Radio.key(it.title, it.artist) == Radio.key(e.value[0].title, e.value[0].artist) } ?: YouTubeMusic.known(e.key) }
            .take(30).shuffled(java.util.Random(day().hashCode().toLong()))
        if (songs.size < 8) return null
        val part = when (Moment.now().part) { Moment.Part.MORNING -> "mornings"; Moment.Part.DAY -> "afternoons"; Moment.Part.EVENING -> "evenings"; Moment.Part.LATE -> "late nights" }
        return Mix("hour", "your $part", "what you play around this hour", songs, "${songs.size} songs, as the hour turns")
    }

    /** Come in these six weeks, not yet played: the newest first. */
    private fun justAdded(library: List<Song>, heard: Set<String>): Mix? {
        val since = System.currentTimeMillis() - 42L * 86_400_000
        val songs = library.filter { !it.tube && it.added > since && it.id !in heard && Radio.key(it.title, it.artist) !in heard }
            .sortedWith(compareByDescending<Song> { it.added }.thenBy { it.disc }.thenBy { it.track }).take(40)
        if (songs.size < 6) return null
        return Mix("added", "just added", "come in these six weeks, not played yet", songs, "${songs.size} songs, newest first")
    }

    /** Had a year and more, never once played: a different handful each day. */
    private fun backOfTheShelf(library: List<Song>, heard: Set<String>): Mix? {
        val before = System.currentTimeMillis() - 365L * 86_400_000
        val songs = library.filter { !it.tube && it.added in 1 until before && it.id !in heard && Radio.key(it.title, it.artist) !in heard }
            .shuffled(java.util.Random(day().hashCode().toLong() * 7)).let { Radio.apart(it.take(60), "") }.take(30)
        if (songs.size < 10) return null
        return Mix("shelf", "back of the shelf", "yours a year and more, never played", songs, "${songs.size} songs, new each day")
    }

    /** One decade of what you have, a different one each day (any with fifteen songs or more). */
    private fun aDecade(library: List<Song>): Mix? {
        val by = library.filter { !it.tube && it.year in 1900..2100 }.groupBy { it.year / 10 * 10 }.filterValues { it.size >= 15 }
        if (by.size < 2) return null
        val decades = by.keys.sorted()
        val d = decades[(LocalDate.now().toEpochDay() % decades.size).toInt()]
        val r = java.util.Random(day().hashCode().toLong() * 11)
        val songs = Radio.apart(by.getValue(d).shuffled(r).take(60), "").take(30)
        return Mix("decade", "your ${d}s", "what you have from the ${d}s", songs, "${songs.size} songs, another decade tomorrow")
    }

    /** One of your nearest artists a day: their songs you have, and their best known you don't. */
    private suspend fun spotlight(artists: List<String>, library: List<Song>): Mix? {
        if (artists.isEmpty()) return null
        val name = artists[(LocalDate.now().toEpochDay() % artists.size).toInt()]
        val yours = library.filter { s -> s.albumArtist.equals(name, true) || s.artist.split(", ").any { it.equals(name, true) } }
            .shuffled(java.util.Random(day().hashCode().toLong())).take(15)
        val info = withTimeoutOrNull(8_000) { YouTubeAccount.artist(name, "").firstOrNull { it != null } }
        val theirs = info?.top.orEmpty().filter { t -> yours.none { Radio.key(it.title, it.artist) == Radio.key(t.title, t.artist) } }.take(10)
        val songs = (theirs + yours).distinctBy { Radio.key(it.title, it.artist) }
        if (songs.size < 6) return null
        return Mix("spotlight", "all ${name.lowercase()}", "one of the artists you play most, a different one each day", Radio.apart(songs, ""),
            "${songs.size} songs by ${name.lowercase()}")
    }

    /**
     * Your all-time favorites (2026-11-11): your most played songs on Last.fm and ListenBrainz (brought in), found on
     * YouTube Music, 25 a day from the hundred, a new 25 each day: something of yours to play the first time ink music opens.
     */
    /** Your listening just brought in: your favorites mix made now, first among the mixes (not waiting for tomorrow). */
    fun addFavorites(c: Context) {
        val app = c.applicationContext
        scope.launch {
            for (make in listOf<suspend () -> Mix?>({ usedToLove(app, History.read(app)) }, { favorites(app) }, { onThisDay(app) }))
                runCatching { make() }.getOrNull()?.let { m -> mixes.value = listOf(m) + mixes.value.filter { it.key != m.key } }
        }
    }

    private suspend fun favorites(c: Context? = null): Mix? {
        if (!YouTubeMusic.available) return null
        // (read from their files when the taste isn't made yet: the mixes are made as the library's read)
        val sources = broughtIn(c)
        if (sources.isEmpty()) return null
        // the sources' songs in turn, each one's order kept
        val all = (0 until sources.maxOf { it.songs.size }).flatMap { i -> sources.mapNotNull { it.songs.getOrNull(i) } }
            .distinctBy { (a, t) -> Radio.key(t, a) }
        if (all.size < 6) return null
        val day = LocalDate.now().toEpochDay()
        val today = all.shuffled(java.util.Random(day)).take(25)
        val found = coroutineScope { today.chunked(6).flatMap { g -> g.map { (a, t) -> async { runCatching { YouTubeMusic.find(t, a) }.getOrNull() } }.awaitAll() } }
            .filterNotNull().distinctBy { it.id }
        if (found.size < 6) return null
        val from = sources.joinToString(" and ") { it.from }
        return Mix("favorites", "your all-time favorites", "your most played on $from", Radio.apart(found, ""), "${found.size} songs, a new few each day")
    }

    /** Your listening brought in (Last.fm, ListenBrainz): the taste's, or read from their files before it's made. */
    private fun broughtIn(c: Context?): List<Brought> =
        Fresh.brought.value.ifEmpty { c?.let { listOfNotNull(LastFm.brought(it), ListenBrainz.brought(it)) }.orEmpty() }

    /**
     * You used to love (2026-11-11): whom you played most of all time and not at all the last year (Last.fm,
     * ListenBrainz), and not lately here either; eight a day, three of their best known each.
     */
    private suspend fun usedToLove(c: Context?, all: List<History.Heard>): Mix? {
        if (!YouTubeMusic.available) return null
        val lately = all.filter { it.at > System.currentTimeMillis() - 90L * 86_400_000 }.map { Taste.bare(it.artist.substringBefore(", ")) }.toHashSet()
        val past = broughtIn(c).flatMap { it.past }.distinctBy { Taste.bare(it) }.filter { Taste.bare(it) !in lately }
        if (past.size < 3) return null
        val today = past.shuffled(java.util.Random(LocalDate.now().toEpochDay())).take(8)
        val songs = coroutineScope {
            today.map { a -> async { withTimeoutOrNull(8_000) { YouTubeAccount.artist(a, "").firstOrNull { it != null } }?.top.orEmpty().filter { it.tube }.take(3) } }.awaitAll()
        }.flatten().distinctBy { it.id }
        if (songs.size < 6) return null
        return Mix("used", "you used to love", "who you played most once, and haven't in a year", Radio.apart(songs, ""),
            today.take(3).joinToString(", ") { it.lowercase() } + " and more")
    }

    /** On this day (2026-11-11): what you played on this date in years past, the newest year first, found on YouTube Music. */
    private suspend fun onThisDay(c: Context?): Mix? {
        if (!YouTubeMusic.available) return null
        val b = broughtIn(c).firstOrNull() ?: return null
        val years = OnThisDay.read(b, LastFm.key)
        if (years.isEmpty()) return null
        // each year's in turn, so a busy year doesn't fill it
        val picked = (0 until years.maxOf { it.songs.size }).flatMap { i -> years.mapNotNull { it.songs.getOrNull(i) } }.distinctBy { (a, t) -> Radio.key(t, a) }.take(25)
        val found = coroutineScope { picked.chunked(6).flatMap { g -> g.map { (a, t) -> async { runCatching { YouTubeMusic.find(t, a) }.getOrNull() } }.awaitAll() } }
            .filterNotNull().distinctBy { it.id }
        if (found.size < 4) return null
        val ys = years.map { it.year }
        return Mix("thisday", "on this day", "what you played on this date in " + ys.take(3).joinToString(", ") + if (ys.size > 3) " and before" else "",
            found, "${found.size} songs from ${ys.size} ${if (ys.size == 1) "year" else "years"}")
    }

    /** The critics' and editors' songs nearest your taste, as one to play (found on YouTube Music: the full build). */
    private suspend fun pickedForYou(): Mix? {
        if (!YouTubeMusic.available) return null
        val near = Fresh.rank(Picks.songs.value).filter { !it.stretch }.take(24).map { it.item }
        if (near.size < 6) return null
        val found = coroutineScope { near.chunked(6).flatMap { g -> g.map { p -> async { runCatching { YouTubeMusic.find(p.pick.title, p.pick.artist) }.getOrNull() } }.awaitAll() } }
            .filterNotNull().distinctBy { it.id }
        if (found.size < 6) return null
        return Mix("picked", "critics' picks", "critics' and editors' songs nearest your taste", found, "${found.size} songs, new each day")
    }

    /** New to you as a station: each of them's best known song in turn, radio after. */
    fun station() = scope.launch {
        val songs = newToYou.value.take(8).mapNotNull { n ->
            withTimeoutOrNull(8_000) { YouTubeAccount.artist(n.artist.name, n.artist.id).firstOrNull { it != null } }?.top?.firstOrNull { it.tube }
        }
        if (songs.isEmpty()) return@launch
        withContext(Dispatchers.Main) { Playback.radio(true); Playback.play(songs, 0) }
    }
}
