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
    val onAir = MutableStateFlow<List<Curation.Aired>>(emptyList())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readAt = 0L

    /**
     * Kept ones at once; read again when they're half a day old or yesterday's (the record of the day turns at midnight),
     * on any visit, not only the first: an app left open for days kept its first (Bronson, 2026-10-30).
     */
    fun start(c: Context) {
        val file = File(c.cacheDir, "picks.json")
        if (readAt == 0L) {
            val kept = runCatching { JSONObject(file.readText()) }.getOrNull()
            kept?.let(::show)
            // a day kept before it was checked against YouTube Music: read again now
            readAt = kept?.takeIf { it.optBoolean("checked") }?.optLong("at") ?: 1L
        }
        if (Curation.stale(readAt) && reading?.isActive != true) reading = scope.launch { read(file) }
    }
    private var reading: kotlinx.coroutines.Job? = null

    private suspend fun read(file: File) = coroutineScope {
        val all = Curation.picks()
        if (all.isEmpty()) return@coroutineScope
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
            file.writeText(JSONObject().put("at", readAt).put("checked", true).put("today", day?.let(::json)).put("records", JSONArray(records.value.map(::json)))
                .put("songs", JSONArray(songs.value.map(::json))).toString())
        }
    }

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

    suspend fun air() { val a = withContext(Dispatchers.IO) { Curation.onAir() }; if (a.isNotEmpty()) onAir.value = a }

    suspend fun song(title: String, artist: String): Song? = withContext(Dispatchers.IO) { YouTubeMusic.find(title, artist) }
    suspend fun record(p: Curation.Pick): TubeAlbum? = withContext(Dispatchers.IO) { YouTubeMusic.findRecord(p.title, p.artist) }

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
            .ifEmpty { library.groupBy { it.albumArtist }.entries.sortedByDescending { it.value.size }.map { it.key }.filter { it.isNotBlank() && it != "unknown" } }
            .take(8)
        val sameWeek = kept?.optString("week") == week()
        val sameDay = kept?.optString("day") == day()
        val out = JSONObject().put("week", week()).put("day", day())
        val weekly = if (sameWeek) mixes.value.filter { it.key == "fresh" || it.key == "deep" }
            else listOfNotNull(freshFinds(c, topSongs, heard, mine), deepCuts(topArtists, library, heard, mine))
        val daily = listOfNotNull(restOfTheRecord(all, library, heard), lovedLongAgo(all, library), aYearAgo(all, library), oneOfEach(library))
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
        val picked = score.entries.sortedByDescending { it.value.second }.map { it.key to it.value.first }.filter { (k, s) ->
            if (k in heard || s.id in heard || k in mine || s.id in mine || Radio.odd(s.title)) return@filter false
            val who = s.artist.lowercase().substringBefore(",").trim()
            if ((by[who] ?: 0) >= 2) return@filter false
            by[who] = (by[who] ?: 0) + 1
            true
        }.take(n).map { it.second }
        Radio.apart(picked, "")
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

    /** New to you as a station: each of them's best known song in turn, radio after. */
    fun station() = scope.launch {
        val songs = newToYou.value.take(8).mapNotNull { n ->
            withTimeoutOrNull(8_000) { YouTubeAccount.artist(n.artist.name, n.artist.id).firstOrNull { it != null } }?.top?.firstOrNull { it.tube }
        }
        if (songs.isEmpty()) return@launch
        withContext(Dispatchers.Main) { Playback.radio(true); Playback.play(songs, 0) }
    }
}
