package app.ink.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * Lists made from your listening (after Cellar's, Bronson's pick, 2026-10-28), nothing signed in:
 * - fresh finds: songs you've never played and don't have, from the radios of the songs you play most (each Monday);
 * - deep cuts: your most played artists' songs you've never played, yours first (each Monday);
 * - because you loved: a loved song's radio, only songs you've never played (a different love each day);
 * - new to you: artists their fans also like whom you've never played, each "via" whose fans they came from;
 * and four from your own plays alone: the rest of the record (the songs you've never played from records you know one
 * song of), loved long ago (loves not heard in two months), a year ago (this week, a year back), one of each (a song
 * from every artist you have, each day). Kept, so the page opens with them; made again when their week or day turns.
 */
object Discover {
    /** A made list: its name, what it is, its songs; `fresh`, the line about when it changes. */
    data class Mix(val key: String, val title: String, val said: String, val songs: List<Song>, val fresh: String)
    data class New(val artist: TubeArtist, val via: String)

    val mixes = MutableStateFlow<List<Mix>>(emptyList())
    val because = MutableStateFlow<Pair<Song, List<Song>>?>(null)
    val newToYou = MutableStateFlow<List<New>>(emptyList())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file = File(Home.cache, "discover.json")
    private var started = false

    private fun week() = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()
    private fun day() = LocalDate.now().toString()

    /** Once the library's read: what's kept at once, then whatever's out of date made again. */
    fun start(library: List<Song>) {
        if (started || library.isEmpty()) return
        started = true
        scope.launch {
            val kept = runCatching { JSONObject(file.readText()) }.getOrNull()
            kept?.let(::show)
            runCatching { make(library, kept) }.onFailure { Log.d("discover: $it") }
        }
    }

    private fun show(o: JSONObject) {
        fun songs(a: JSONArray?) = a?.let { (0 until it.length()).map { i -> songOf(it.getJSONObject(i)).also { s -> if (s.tube) YouTube.remember(s) } } }.orEmpty()
        mixes.value = o.optJSONArray("mixes")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
            Mix(it.getString("key"), it.getString("title"), it.getString("said"), songs(it.optJSONArray("songs")), it.optString("fresh"))
        } }.orEmpty().filter { it.songs.isNotEmpty() }
        because.value = o.optJSONObject("because")?.let { b -> songOf(b.getJSONObject("seed")) to songs(b.optJSONArray("songs")) }?.takeIf { it.second.isNotEmpty() }
        newToYou.value = o.optJSONArray("new")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
            New(TubeArtist(it.getString("name"), it.optString("face").ifEmpty { null }, it.optString("id")), it.optString("via"))
        } }.orEmpty()
    }

    private suspend fun make(library: List<Song>, kept: JSONObject?) {
        val all = History.read()
        val now = System.currentTimeMillis()
        val heard = all.flatMap { listOf(it.id, Radio.key(it.title, it.artist)) }.toHashSet()
        val mine = library.flatMap { listOf(it.id, Radio.key(it.title, it.artist)) }.toHashSet()
        val recent = all.filter { it.at >= now - 60L * 86_400_000 }
        // the songs and artists you play most (by time played), else what you have most of
        val topSongs = recent.groupBy { it.id }.entries.sortedByDescending { e -> e.value.sumOf { it.ms } }.take(10).map { it.value.last() }
            .map { Radio.Seed(it.id, it.title, it.artist) }
            .ifEmpty { library.filter { !it.station }.shuffled(java.util.Random(week().hashCode().toLong())).take(8).map { Radio.Seed(it.id, it.title, it.artist) } }
        fun who(a: String) = a.substringBefore(", ").trim()
        val topArtists = recent.groupBy { who(it.artist).lowercase() }.entries.sortedByDescending { e -> e.value.sumOf { it.ms } }.map { who(it.value.last().artist) }
            .filter { it.isNotBlank() && it != "unknown" }
            .ifEmpty { library.groupBy { it.albumArtist }.entries.sortedByDescending { it.value.size }.map { it.key }.filter { it.isNotBlank() && it != "unknown" } }
            .take(8)

        val sameWeek = kept?.optString("week") == week()
        val sameDay = kept?.optString("day") == day()
        val out = JSONObject().put("week", week()).put("day", day())

        // ---- each Monday: fresh finds and deep cuts ----
        val weekly = if (sameWeek) mixes.value.filter { it.key == "fresh" || it.key == "deep" } else {
            listOfNotNull(freshFinds(topSongs, heard, mine), deepCuts(topArtists, library, heard, mine))
        }

        // ---- each day: the rest from your own plays, because you loved, new to you ----
        val daily = listOfNotNull(restOfTheRecord(all, library, heard), lovedLongAgo(all, library), aYearAgo(all, library), oneOfEach(library))
        mixes.value = weekly + daily
        out.put("mixes", JSONArray(mixes.value.map { m ->
            JSONObject().put("key", m.key).put("title", m.title).put("said", m.said).put("fresh", m.fresh).put("songs", JSONArray(m.songs.map { it.json() }))
        }))

        if (!sameDay || because.value == null) because.value = becauseYouLoved(library, heard, mine)
        because.value?.let { (seed, songs) -> out.put("because", JSONObject().put("seed", seed.json()).put("songs", JSONArray(songs.map { it.json() }))) }

        if (!sameDay || newToYou.value.isEmpty()) newToYou.value = newArtists(topArtists, library, all)
        out.put("new", JSONArray(newToYou.value.map { JSONObject().put("name", it.artist.name).put("face", it.artist.face ?: "").put("id", it.artist.id).put("via", it.via) }))
        newToYou.value.take(8).forEach { Artists.warm(it.artist.name, it.artist.id) }

        runCatching { Home.write(file, out.toString()) }
    }

    /** Songs from these seeds' radios, the ones more of them lead to first: never heard, not yours, two an artist at most. */
    private suspend fun fromRadios(seeds: List<Radio.Seed>, heard: Set<String>, mine: Set<String>, n: Int): List<Song> = coroutineScope {
        val lists = seeds.map { async { runCatching { Radio.related(it) }.getOrDefault(emptyList()) } }.awaitAll()
        val score = LinkedHashMap<String, Pair<Song, Float>>()
        lists.forEach { list -> list.forEachIndexed { r, s ->
            val k = Radio.key(s.title, s.artist)
            val (_, was) = score[k] ?: (s to 0f)
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

    private suspend fun freshFinds(seeds: List<Radio.Seed>, heard: Set<String>, mine: Set<String>): Mix? {
        val songs = fromRadios(seeds, heard, mine, 30).takeIf { it.isNotEmpty() } ?: return null
        return Mix("fresh", "fresh finds", "songs you've never played and don't have, from the radios of the songs you play most",
            songs, "${songs.size} songs, new every monday")
    }

    /** Your most played artists' songs you've never played: yours of theirs first, then their best known; a few from each in turn. */
    private suspend fun deepCuts(artists: List<String>, library: List<Song>, heard: Set<String>, mine: Set<String>): Mix? {
        val each = artists.map { name ->
            val yours = library.filter { s -> (s.albumArtist.equals(name, true) || s.artist.split(", ").any { it.equals(name, true) }) &&
                s.id !in heard && Radio.key(s.title, s.artist) !in heard }.shuffled(java.util.Random(week().hashCode().toLong())).take(3)
            val info = withTimeoutOrNull(8_000) { Artists.of(name, "").firstOrNull { it != null } }
            val theirs = info?.top.orEmpty().filter { t -> Radio.key(t.title, t.artist).let { it !in heard && it !in mine } && t.id !in heard }.take(3)
            yours + theirs
        }
        val songs = (0 until (each.maxOfOrNull { it.size } ?: 0)).flatMap { i -> each.mapNotNull { it.getOrNull(i) } }.distinctBy { it.id }.take(30)
        if (songs.isEmpty()) return null
        val names = songs.map { it.albumArtist.ifBlank { it.artist.substringBefore(", ") } }.distinct()
        return Mix("deep", "deep cuts", "songs by your most played artists that you've never played", songs,
            "${songs.size} songs by ${names.take(3).joinToString(", ").lowercase()}${if (names.size > 3) " and more" else ""}")
    }

    /** A loved song's radio, only what you've never played; a different love each day. */
    private suspend fun becauseYouLoved(library: List<Song>, heard: Set<String>, mine: Set<String>): Pair<Song, List<Song>>? {
        val ids = Loved.ids.value
        val loved = (library.filter { it.id in ids } + Loved.tubeSongs()).distinctBy { it.id }.sortedBy { it.id }
        if (loved.isEmpty()) return null
        // today's love; one with no radio to be found (not on YouTube Music, say) passes to the next
        val from = (LocalDate.now().toEpochDay() % loved.size).toInt()
        for (k in 0 until minOf(4, loved.size)) {
            val seed = loved[(from + k) % loved.size]
            val songs = fromRadios(listOf(Radio.Seed(seed.id, seed.title, seed.artist)), heard, mine, 20)
            if (songs.isNotEmpty()) return seed to songs
        }
        return null
    }

    /** Whom your most played artists' fans also like, that you've never played and don't have; each with whose fans. */
    private suspend fun newArtists(artists: List<String>, library: List<Song>, all: List<History.Heard>): List<New> {
        val known = (library.flatMap { listOf(it.albumArtist) + it.artist.split(", ") } + all.flatMap { it.artist.split(", ") })
            .map { it.trim().lowercase() }.toHashSet()
        val each = artists.take(6).map { name ->
            val info = withTimeoutOrNull(8_000) { Artists.of(name, "").firstOrNull { it != null } }
            // not someone you play, nor their fan pages ("alex g archive")
            info?.like.orEmpty().filter { a -> a.name.lowercase().let { l -> l !in known && artists.none { l.contains(it.lowercase()) } } }
                .map { New(it, info?.name ?: name) }
        }
        return (0 until (each.maxOfOrNull { it.size } ?: 0)).flatMap { i -> each.mapNotNull { it.getOrNull(i) } }
            .distinctBy { it.artist.name.lowercase() }.take(14)
    }

    /** Records you know one or two songs of: the rest of them, never played, a record's songs together. */
    private fun restOfTheRecord(all: List<History.Heard>, library: List<Song>, heard: Set<String>): Mix? {
        val played = all.groupBy { it.record }.filterKeys { it.isNotEmpty() }
        val records = library.filter { !it.station && !it.tube }.groupBy { it.albumId }
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

    /** Loves you haven't heard in two months. */
    private fun lovedLongAgo(all: List<History.Heard>, library: List<Song>): Mix? {
        val ids = Loved.ids.value
        val lately = all.filter { it.at >= System.currentTimeMillis() - 60L * 86_400_000 }.map { it.id }.toHashSet()
        val songs = (library.filter { it.id in ids } + Loved.tubeSongs()).distinctBy { it.id }.filter { it.id !in lately }
            .shuffled(java.util.Random(day().hashCode().toLong())).take(25)
        if (songs.size < 4) return null
        return Mix("loved", "loved, long ago", "songs you love that you haven't played in two months", songs, "${songs.size} songs, new each day")
    }

    /** What you played most this same week a year ago (a year of listening, or nothing). */
    private fun aYearAgo(all: List<History.Heard>, library: List<Song>): Mix? {
        val back = LocalDate.now().minusYears(1)
        val from = back.minusDays(3).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val to = back.plusDays(4).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val then = all.filter { it.at in from until to }.groupBy { it.id }.entries.sortedByDescending { e -> e.value.sumOf { it.ms } }
            .mapNotNull { e -> Library.song(e.key) ?: library.firstOrNull { Radio.key(it.title, it.artist) == Radio.key(e.value[0].title, e.value[0].artist) } }
            .take(25)
        if (then.size < 4) return null
        return Mix("year", "a year ago", "what you played most this week last year", then,
            "${then.size} songs from ${java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy").format(back).lowercase()}")
    }

    /** A song from every artist you have, a new pick each day. */
    private fun oneOfEach(library: List<Song>): Mix? {
        val r = java.util.Random(day().hashCode().toLong())
        val songs = library.filter { !it.station && !it.tube }.groupBy { it.albumArtist.ifBlank { it.artist.substringBefore(", ") }.lowercase() }
            .values.map { it[r.nextInt(it.size)] }.shuffled(r).take(40)
        if (songs.size < 8) return null
        return Mix("each", "one of each", "a song from every artist you have", songs, "${songs.size} artists, new each day")
    }

    /** New to you as a station: each of them's best known song in turn, radio after. */
    fun station() = scope.launch {
        val songs = newToYou.value.take(8).mapNotNull { n ->
            withTimeoutOrNull(8_000) { Artists.of(n.artist.name, n.artist.id).firstOrNull { it != null } }?.top?.firstOrNull { it.tube }
        }
        if (songs.isEmpty()) return@launch
        javax.swing.SwingUtilities.invokeLater { if (!Player.radio.value) Player.radio(true); Player.play(songs) }
    }
}

/** Made for you: each list a square of four of its covers, its name, what it is and when it changes, play. Two to a row. */
@Composable
fun MadeForYou() {
    val mixes by Discover.mixes.collectAsState()
    if (mixes.isEmpty()) return
    Column {
        Section("MADE FOR YOU", "from what you play, new each day or each monday")
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val across = if (maxWidth > 1000.dp) 2 else 1
            Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                mixes.chunked(across).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        row.forEach { m -> MixRow(m, Modifier.weight(1f)) }
                        repeat(across - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MixRow(m: Discover.Mix, modifier: Modifier) {
    Row(modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { Player.play(m.songs) }),
        verticalAlignment = Alignment.CenterVertically) {
        // four covers, two by two (what's in it at a look)
        val four = remember(m) { m.songs.distinctBy { it.albumId }.take(4) }
        Column(Modifier.size(112.dp)) {
            four.chunked(2).forEach { pair -> Row { pair.forEach { s -> CoverOf(s, s.album.ifEmpty { s.title }, Modifier.size(56.dp), px = 160) } } }
        }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            BasicText(m.title.uppercase(), style = headline(24.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            BasicText(m.said, style = pixel(12.sp, Ink.mid), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            BasicText(m.fresh, style = pixel(12.sp, Ink.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Word("play", pixel(14.sp), Modifier.padding(start = 12.dp), touch = 8.dp) { Player.play(m.songs) }
    }
}

/** Because you loved: the love it's from, big, and its radio's songs you've never played, as covers. */
@Composable
fun BecauseYouLoved(open: (Page) -> Unit) {
    val b by Discover.because.collectAsState()
    val (seed, songs) = b ?: return
    Column {
        Section("BECAUSE YOU LOVED ${seed.title.uppercase()}", "${seed.artist.lowercase()} · songs from its radio you've never played") {
            Word("play all", pixel(14.sp), touch = 8.dp) { Player.play(songs) }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            itemsIndexed(songs, key = { _, s -> s.id }) { i, s ->
                Column(Modifier.width(150.dp).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { Player.play(songs, i) })) {
                    CoverOf(s, s.album.ifEmpty { s.title }, Modifier.fillMaxWidth().aspectRatio(1f), px = 300)
                    Spacer(Modifier.height(8.dp))
                    UnderCover(s.title, s.artist)
                }
            }
        }
    }
}

/** New to you: artists close to what you play whom you've never played, each in the dither, via whom; a station of them. */
@Composable
fun NewToYou(open: (Page) -> Unit) {
    val list by Discover.newToYou.collectAsState()
    if (list.isEmpty()) return
    Column {
        Section("NEW TO YOU", "close to what you play, and you haven't played them yet") {
            Word("discovery station", pixel(14.sp), touch = 8.dp) { Discover.station() }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            itemsIndexed(list, key = { _, n -> n.artist.name }) { _, n ->
                Column(Modifier.width(150.dp).clickable(remember { MutableInteractionSource() }, indication = null,
                    onClick = felt { open(Page.Artist(n.artist.name, n.artist.id)) })) {
                    val face = remember(n) { n.artist.face?.let { faceSong(n.artist.name, it, photo = true) } }
                    if (face != null) Dithered(face, Modifier.fillMaxWidth().aspectRatio(1f), px = 300)
                    else Box(Modifier.fillMaxWidth().aspectRatio(1f)) { NameCover(n.artist.name, 150.dp) }
                    Spacer(Modifier.height(8.dp))
                    UnderCover(n.artist.name, "via ${n.via.lowercase()}")
                }
            }
        }
    }
}
