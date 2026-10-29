package app.ink.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.items
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext

/**
 * Explore, what isn't yours yet, picked by people (Bronson, 2026-10-28: "as cool and curated as possible, even signed
 * out"): the record of the day; what DJs are playing this minute; right now's moods and every mood and genre;
 * new records the critics picked; an artist of the week from the indie chart; the songs the critics picked beside the
 * chart, each song's move over the week; YouTube Music's own new records and shelves only as you, signed in (signed
 * out they're mostly made by AI).
 */
@Composable
fun ExplorePage(open: (Page) -> Unit) {
    val chart by produceState<List<IndieChart.Entry>?>(null) { value = IndieChart.week() }
    val before = remember(chart) { IndieChart.before() }
    val who by TubeAccount.who.collectAsState()
    val fresh by produceState<List<TubeAlbum>?>(null, who) {
        val t = TubeAccount.tube() ?: run { value = emptyList(); return@produceState }
        value = withContext(Dispatchers.IO) { runCatching { t.items("FEmusic_new_releases_albums", 24).mapNotNull(::tubeAlbum) }.getOrDefault(emptyList()) }
    }
    val shelves by produceState<List<Pair<String, List<TubeAlbum>>>?>(null, who) {
        val t = TubeAccount.tube() ?: run { value = emptyList(); return@produceState }
        value = withContext(Dispatchers.IO) {
            runCatching { t.home(12).map { it.title to it.items.mapNotNull(::tubeAlbum) }.filter { it.first.isNotEmpty() && it.second.size >= 3 } }
                .getOrDefault(emptyList())
        }
    }
    val moods by produceState<List<InnerTube.Mood>?>(Moods.kept()) { value = Moods.read() ?: value }
    LaunchedEffect(Unit) { Picks.start() }
    // what's on the air, asked again each minute while explore is up
    LaunchedEffect(Unit) { while (true) { Picks.air(); kotlinx.coroutines.delay(60_000) } }
    val records by Picks.records.collectAsState()
    val songs by Picks.songs.collectAsState()
    val today by Picks.today.collectAsState()
    val air by Picks.onAir.collectAsState()
    val list = rememberLazyListState()
    // the record of the day arriving above: the page stays at its top (it had opened partway down)
    LaunchedEffect(today != null) { if (list.firstVisibleItemIndex <= 2) list.scrollToItem(0) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val across = ((maxWidth - SIDE * 2) / 170.dp).toInt().coerceAtLeast(2)
        val wide = maxWidth >= 860.dp
        LazyColumn(Modifier.fillMaxSize().feather(list), list, contentPadding = PaddingValues(start = SIDE, end = SIDE, top = 14.dp, bottom = 40.dp)) {
            today?.let { d -> item(key = "today") { RecordOfTheDay(d, open) } }
            if (air.isNotEmpty()) item(key = "air") { OnTheAir(air) }
            item(key = "moods") { MoodsAndGenres(moods, open) }
            if (records.isNotEmpty()) {
                item(key = "picked") {
                    Column(Modifier.padding(top = 8.dp, bottom = 12.dp)) {
                        BasicText("NEW RECORDS, PICKED BY CRITICS", style = headline(30.sp))
                        BasicText("the critics' favorites lately", style = pixel(12.sp, Ink.mid))
                    }
                }
                records.take(across * 4).chunked(across).forEachIndexed { r, row ->
                    item(key = "pr$r") { TileRow(row, across) { s -> PickTile(s, open) } }
                }
            }
            chart.orEmpty().takeIf { it.isNotEmpty() }?.let { c -> item(key = "artist") { ArtistOfTheWeek(c, open) } }
            item(key = "songs") {
                // the songs the critics picked, beside the stations' chart: side by side wide, one above the other narrow
                val picked = @Composable { m: Modifier -> PickedSongs(songs, m) }
                val charted = @Composable { m: Modifier -> Chart(chart, before, open, m) }
                if (wide) Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                    charted(Modifier.weight(1f)); picked(Modifier.weight(1f))
                } else Column(Modifier.padding(top = 18.dp)) { charted(Modifier.fillMaxWidth()); Spacer(Modifier.height(24.dp)); picked(Modifier.fillMaxWidth()) }
            }
            // yours from YouTube Music, signed in
            if (!fresh.isNullOrEmpty()) {
                item(key = "fresh") {
                    Column(Modifier.padding(top = 30.dp, bottom = 12.dp)) {
                        BasicText("NEW RECORDS ON YOUTUBE MUSIC", style = headline(30.sp))
                        BasicText("this week, for you", style = pixel(12.sp, Ink.mid))
                    }
                }
                fresh.orEmpty().take(across * 2).chunked(across).forEachIndexed { r, row ->
                    item(key = "f$r") { TileRow(row, across) { a -> TubeTile(a, Modifier.fillMaxWidth()) { open(Page.Tube(a)) } } }
                }
            }
            shelves.orEmpty().forEach { (title, items) ->
                item(key = "s$title") {
                    Label(title.lowercase())
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        items(items.take(16), key = { it.url }) { a -> TubeTile(a, Modifier.width(160.dp)) { open(Page.Tube(a)) } }
                    }
                }
            }
        }
    }
}

/** A row of tiles, `across` to it, the last row's room kept so tiles are one width. */
@Composable
private fun <T> TileRow(row: List<T>, across: Int, tile: @Composable (T) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 22.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        row.forEach { Box(Modifier.weight(1f)) { tile(it) } }
        repeat(across - row.size) { Spacer(Modifier.weight(1f)) }
    }
}

/** The record of the day: its cover big, its name, what was said of it and by whom; play, or its page. */
@Composable
private fun RecordOfTheDay(d: Picks.Shown, open: (Page) -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var said by remember { mutableStateOf<String?>(null) }
    fun go(play: Boolean) = scope.launch {
        said = "finding it on youtube music"
        val a = Picks.record(d.pick)
        if (a == null) { said = "not on youtube music"; return@launch }
        said = null
        if (play) Player.play(YouTube.album(a)) else open(Page.Tube(a))
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 30.dp)) {
        val side = minOf(maxWidth * .42f, 380.dp)
        Row(verticalAlignment = Alignment.Bottom) {
            Box(Modifier.size(side).clickable(remember { MutableInteractionSource() }, indication = null) { go(false) }) {
                CoverOf(d.face, d.pick.title, Modifier.fillMaxSize(), px = 800)
            }
            Spacer(Modifier.width(32.dp))
            Column(Modifier.weight(1f)) {
                BasicText("RECORD OF THE DAY", style = headline(18.sp, Ink.mid))
                BasicText("picked by ${d.by.joinToString(", ")}", style = pixel(12.sp, Ink.mid))
                Spacer(Modifier.height(14.dp))
                BasicText(d.pick.title.uppercase(), style = headline(48.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                BasicText(d.pick.artist.lowercase(), style = pixel(16.sp))
                if (d.pick.said.isNotBlank()) {
                    Spacer(Modifier.height(16.dp))
                    BasicText("“${d.pick.said}”".uppercase(), style = headline(20.sp).copy(lineHeight = 26.sp), maxLines = 5, overflow = TextOverflow.Ellipsis)
                    BasicText("— ${d.pick.from}", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 6.dp))
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                    Word("play", pixel(14.sp), touch = 8.dp) { go(true) }
                    Word("its page", pixel(14.sp, Ink.mid), touch = 8.dp) { go(false) }
                    if (d.pick.link.isNotBlank()) Word("read it", pixel(14.sp, Ink.mid), touch = 8.dp) { browse(d.pick.link) }
                }
                said?.let { BasicText(it, style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 8.dp)) }
            }
        }
    }
}

/** On the air now: what each station is playing this minute, its cover, where; the song, or the station itself. */
@Composable
private fun OnTheAir(air: List<Curation.Aired>) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Column(Modifier.padding(bottom = 28.dp)) {
        BasicText("ON THE AIR NOW", style = headline(30.sp))
        BasicText("what the djs are playing this minute: a click plays the song, tune in plays the station", style = pixel(12.sp, Ink.mid))
        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            items(air, key = { it.station }) { a ->
                var said by remember(a) { mutableStateOf<String?>(null) }
                Column(Modifier.width(170.dp)) {
                    // the station's cover, else iTunes' (asked aside, never while drawing)
                    val art by produceState(a.art, a) { if (value == null) value = withContext(Dispatchers.IO) { Curation.itunes(a.title, a.artist, false) } }
                    val face = remember(a, art) { Song(id = "yair:" + a.station + a.title, path = "", title = a.title, artist = a.artist, album = a.title,
                        albumId = "air:" + a.station + a.title + (art ?: ""), albumArtist = a.artist, duration = 0, disc = 1, track = 0, year = 0, art = art) }
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).clickable(remember { MutableInteractionSource() }, indication = null) {
                        scope.launch { said = "finding it"; val s = Picks.song(a.title, a.artist); said = if (s == null) "not on youtube music" else null; s?.let { Player.play(listOf(it)) } }
                    }) { CoverOf(face, a.title, Modifier.fillMaxSize(), px = 340) }
                    Spacer(Modifier.height(8.dp))
                    UnderCover(a.title, a.artist)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicText("on ${a.name}", style = pixel(12.sp, Ink.mid), modifier = Modifier.weight(1f))
                        Stations.known(a.station)?.let { st -> Word("tune in", pixel(12.sp), touch = 4.dp) { Player.play(listOf(st)) } }
                    }
                    said?.let { BasicText(it, style = pixel(12.sp, Ink.dim)) }
                }
            }
        }
    }
}

/** A record someone picked: its cover, its name, who picked it; a click opens it on YouTube Music. */
@Composable
private fun PickTile(s: Picks.Shown, open: (Page) -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var said by remember(s) { mutableStateOf<String?>(null) }
    Column(Modifier.clickable(remember { MutableInteractionSource() }, indication = null) {
        scope.launch { said = "finding it"; val a = Picks.record(s.pick); said = if (a == null) "not on youtube music" else null; a?.let { open(Page.Tube(it)) } }
    }) {
        CoverOf(s.face, s.pick.title, Modifier.fillMaxWidth().aspectRatio(1f), px = 400)
        Spacer(Modifier.height(8.dp))
        UnderCover(s.pick.title, s.pick.artist)
        BasicText(said ?: s.by.joinToString(" · "), style = pixel(12.sp, if (s.by.size > 1) Ink.ink else Ink.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The songs the critics picked: each, who picked it, play all. */
@Composable
private fun PickedSongs(songs: List<Picks.Shown>, modifier: Modifier) {
    if (songs.isEmpty()) return
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var said by remember { mutableStateOf<String?>(null) }
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                BasicText("SONGS, PICKED BY CRITICS", style = headline(30.sp))
                BasicText(said ?: "pitchfork's and gorilla vs. bear's, the newest first", style = pixel(12.sp, Ink.mid))
            }
            Word("play all", pixel(14.sp), touch = 8.dp) { scope.launch { said = "finding them on youtube music"; Picks.playSongs(songs); said = null } }
        }
        Spacer(Modifier.height(10.dp))
        songs.take(14).forEach { s ->
            Row(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null) {
                scope.launch { Picks.song(s.pick.title, s.pick.artist)?.let { Player.play(listOf(it)) } ?: run { said = "${s.pick.title.lowercase()}: not on youtube music" } }
            }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                CoverOf(s.face, s.pick.title, Modifier.size(48.dp), px = 120)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    BasicText(s.pick.title.uppercase(), style = headline(16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText("${s.pick.artist.lowercase()}  ·  ${s.by.joinToString(" · ")}", style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The indie chart: the place big, the song, how it moved this week (new, up, down), its stations. */
@Composable
private fun Chart(chart: List<IndieChart.Entry>?, before: Map<String, Int?>?, open: (Page) -> Unit, modifier: Modifier) {
    val songs = chart.orEmpty().mapNotNull { it.song }
    Column(modifier) {
        BasicText("THE INDIE CHART", style = headline(30.sp))
        BasicText(if (chart == null) "counting this week's plays" else "this week on kexp, kcrw and wxpn, the songs they played most", style = pixel(12.sp, Ink.mid))
        Spacer(Modifier.height(10.dp))
        chart.orEmpty().forEachIndexed { i, e ->
            val s = e.song ?: return@forEachIndexed
            // how it moved: new this week, up or down so many, or where it was
            val was = before?.let { b -> if (IndieChart.key(e) in b) b[IndieChart.key(e)] else null }
            val move = when {
                before == null -> ""
                was == null -> "new"
                was > i + 1 -> "↑${was - i - 1}"
                was < i + 1 -> "↓${i + 1 - was}"
                else -> "="
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(64.dp)) {
                    BasicText("${i + 1}", style = headline(30.sp))
                    if (move.isNotEmpty()) BasicText(move, style = pixel(12.sp, if (move == "new" || move.startsWith("↑")) Ink.ink else Ink.dim))
                }
                Box(Modifier.weight(1f)) {
                    SongRow(s, said = (s.artist + "  ·  " + e.stations.joinToString(" ")).lowercase(), open = open) {
                        Player.play(songs, songs.indexOf(s).coerceAtLeast(0))
                    }
                }
            }
        }
    }
}

/**
 * The artist of the week: one from the indie chart's top, the same all week, their photo in the dither big, their
 * name, what's said of them; their best known songs, a station from them, their page.
 */
@Composable
private fun ArtistOfTheWeek(chart: List<IndieChart.Entry>, open: (Page) -> Unit) {
    // the week's turn through the chart's top fifteen, passing over the very famous (a discovery, not the Rolling Stones)
    val names = remember(chart) { chart.take(15).map { it.artist.substringBefore(",").trim() }.distinct() }
    val week = java.time.LocalDate.now().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).toEpochDay() / 7
    val info by produceState<ArtistInfo?>(null, names) {
        if (names.isEmpty()) return@produceState
        val from = (week % names.size).toInt()
        for (k in names.indices) {
            val name = names[(from + k) % names.size]
            val a = withContext(Dispatchers.IO) { kotlinx.coroutines.withTimeoutOrNull(8_000) { Artists.of(name, "").firstOrNull { it != null } } } ?: continue
            if (audience(a.audience) < 10_000_000 && a.photo != null) { value = a; return@produceState }
        }
    }
    val i = info ?: return
    Column(Modifier.padding(top = 18.dp, bottom = 18.dp)) {
        BasicText("ARTIST OF THE WEEK", style = headline(18.sp, Ink.mid))
        BasicText("from the indie chart's top, a new one each monday", style = pixel(12.sp, Ink.mid))
        Spacer(Modifier.height(14.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val side = minOf(maxWidth * .42f, 380.dp)
            Row(verticalAlignment = Alignment.Bottom) {
                Box(Modifier.size(side), contentAlignment = Alignment.BottomStart) {
                    i.photo?.let { Dithered(remember(it) { faceSong(i.name, it, photo = true) }, Modifier.fillMaxSize()) }
                }
                Spacer(Modifier.width(32.dp))
                Column(Modifier.weight(1f)) {
                    BasicText(i.name.uppercase(), style = headline(56.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (i.audience.isNotEmpty()) BasicText(i.audience.lowercase().replace("monthly audience", "a month"), style = pixel(12.sp, Ink.mid))
                    val (said, _) = remember(i.about) { Artists.pullQuote(i.about) }
                    said?.let {
                        Spacer(Modifier.height(14.dp))
                        BasicText(it.uppercase(), style = headline(20.sp).copy(lineHeight = 26.sp), maxLines = 5, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                        if (i.top.isNotEmpty()) Word("play", pixel(14.sp), touch = 8.dp) { Player.play(i.top) }
                        if (i.top.isNotEmpty()) Word("station", pixel(14.sp, Ink.mid), touch = 8.dp) { Player.startRadio(i.top.first()) }
                        Word("their page", pixel(14.sp, Ink.mid), touch = 8.dp) { open(Page.Artist(i.name, i.id)) }
                    }
                }
            }
        }
    }
}

/** A record or playlist YouTube Music shows, as one to open: its page, its name, who (from what's said under it). */
private fun tubeAlbum(it: InnerTube.Item): TubeAlbum? = when (it.kind) {
    InnerTube.Kind.RECORD -> TubeAlbum("${InnerTube.BASE}/browse/${it.id}", it.title, who(it.subtitle), it.art)
    InnerTube.Kind.PLAYLIST -> TubeAlbum("${InnerTube.BASE}/playlist?list=${it.id.removePrefix("VL")}", it.title, who(it.subtitle), it.art)
    else -> null
}

/** "Album • Fcukers • 2026" as who: the part that isn't the kind or the year. */
private fun who(subtitle: String) = subtitle.split(" • ").map { it.trim() }
    .firstOrNull { it.isNotEmpty() && it !in setOf("Album", "Single", "EP", "Playlist") && !it.matches(Regex("\\d{4}")) }.orEmpty()

/**
 * Moods and genres, YouTube Music's own (chill, focus, sleep...; african to soundtracks), each a page of "flavors": its
 * playlists in shelves. Right now picks three for the hour, the day and the season; the genres you open most come first.
 * A station from a mood: a playlist from each of its first flavors, mixed, radio after.
 */
object Moods {
    /** ink's own order for the genres (Bronson, 2026-10-28: alphabetical put the odd ones first): the ones you open most still lead. */
    val GENRES = listOf("Indie & alternative", "Dance & electronic", "Hip-hop", "R&B & soul", "Jazz", "Rock", "Folk & acoustic", "Pop",
        "Soundtracks & musicals", "Reggae & caribbean", "Blues", "Latin", "Brazilian", "African", "Classical", "Metal", "Country & Americana",
        "K-Pop", "J-Pop", "Decades", "Songwriters & producers")
    fun rank(title: String) = GENRES.indexOfFirst { it.equals(title, true) }.let { if (it < 0) GENRES.size else it }
    /** Flavors for one region or language: after the rest on a mood's page, unless you open that genre. */
    val REGIONAL = listOf("latin", "bollywood", "indian", "k-pop", "kpop", "j-pop", "arab", "turk", "african", "brazil", "mandopop", "cantopop", "opm",
        "punjabi", "tamil", "telugu", "desi", "afro", "reggaeton", "mexican", "french", "german", "italian", "spanish", "korean", "japanese", "chinese")

    private val file = java.io.File(Home.cache, "moods.json")
    private val opened = java.io.File(Home.dir, "moods-opened.json")
    val counts = kotlinx.coroutines.flow.MutableStateFlow(runCatching {
        org.json.JSONObject(opened.readText()).let { o -> o.keys().asSequence().associateWith { o.getInt(it) } }
    }.getOrDefault(emptyMap()))

    fun kept(): List<InnerTube.Mood>? = runCatching {
        val a = org.json.JSONArray(file.readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map { InnerTube.Mood(it.getString("title"), it.getString("params"), it.getBoolean("genre")) }
    }.getOrNull()

    suspend fun read(): List<InnerTube.Mood>? = withContext(Dispatchers.IO) {
        InnerTube().moods().takeIf { it.isNotEmpty() }?.also { list ->
            runCatching { Home.write(file, org.json.JSONArray(list.map { org.json.JSONObject().put("title", it.title).put("params", it.params).put("genre", it.genre) }).toString()) }
        }
    }

    fun open(m: InnerTube.Mood) {
        counts.value = counts.value + (m.title to (counts.value[m.title] ?: 0) + 1)
        runCatching { Home.write(opened, org.json.JSONObject(counts.value).toString()) }
    }

    /** Three moods for now: the hour, weekday or weekend, and the season's own (halloween, late october). */
    fun now(all: List<InnerTube.Mood>): List<InnerTube.Mood> {
        val t = java.time.LocalDateTime.now()
        val weekend = t.dayOfWeek == java.time.DayOfWeek.SATURDAY || t.dayOfWeek == java.time.DayOfWeek.SUNDAY
        val going = t.dayOfWeek == java.time.DayOfWeek.FRIDAY || t.dayOfWeek == java.time.DayOfWeek.SATURDAY
        val picks = when (t.hour) {
            in 5..8 -> if (weekend) listOf("Feel good", "Chill", "Romance") else listOf("Commute", "Energize", "Feel good")
            in 9..16 -> if (weekend) listOf("Feel good", "Chill", "Energize") else listOf("Focus", "Feel good", "Chill")
            in 17..20 -> if (going) listOf("Party", "Feel good", "Chill") else listOf("Chill", "Feel good", "Romance")
            in 21..23 -> if (going) listOf("Party", "Romance", "Chill") else listOf("Chill", "Romance", "Sleep")
            else -> listOf("Sleep", "Chill", "Sad")
        }.toMutableList()
        all.firstOrNull { !it.genre && it.title !in setOf("Chill", "Commute", "Energize", "Feel good", "Focus", "Gaming", "Party", "Romance", "Sad", "Sleep", "Workout") }
            ?.let { seasonal -> picks.add(0, seasonal.title) }
        return picks.mapNotNull { p -> all.firstOrNull { it.title.equals(p, true) } }.take(3)
    }

    /** "a wednesday evening in autumn". */
    fun said(): String {
        val t = java.time.LocalDateTime.now()
        val part = when (t.hour) { in 5..11 -> "morning"; in 12..16 -> "afternoon"; in 17..20 -> "evening"; else -> "night" }
        val season = when (t.monthValue) { 12, 1, 2 -> "winter"; 3, 4, 5 -> "spring"; 6, 7, 8 -> "summer"; else -> "autumn" }
        return "a ${t.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH).lowercase()} $part in $season"
    }

    /** A station from a mood: the first playlist of each of its first flavors, their songs taken in turn, radio after. */
    suspend fun station(m: InnerTube.Mood) {
        val songs = withContext(Dispatchers.IO) {
            val lists = InnerTube().mood(m.params).take(4).mapNotNull { it.items.firstOrNull { i -> i.kind == InnerTube.Kind.PLAYLIST } }
            val each = lists.map { runCatching { InnerTube().tracks(it.id, 30).map(YouTube::song).shuffled() }.getOrDefault(emptyList()) }
            (0 until (each.maxOfOrNull { it.size } ?: 0)).flatMap { i -> each.mapNotNull { it.getOrNull(i) } }.distinctBy { it.id }.take(80)
        }
        if (songs.isEmpty()) return
        if (!Player.radio.value) Player.radio(true)
        Player.play(songs)
    }
}

/** Right now (three moods for the hour, a station each), then every mood and genre as words. */
@Composable
private fun MoodsAndGenres(moods: List<InnerTube.Mood>?, open: (Page) -> Unit) {
    val counts by Moods.counts.collectAsState()
    Column(Modifier.padding(bottom = 24.dp)) {
        // ink's own moods (Last.fm's listeners' tags), right now's three and all as tiles; YouTube's own moods gone
        // (Bronson, 2026-10-28: they "suck"); its genres stay, its editors' playlists
        InkMoodsShelf()
        val all = moods ?: return@Column
        Label("genres, the ones you open most first")
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            all.filter { it.genre }.sortedWith(compareByDescending<InnerTube.Mood> { counts[it.title] ?: 0 }.thenBy { Moods.rank(it.title) }).forEach { m ->
                Word(m.title.lowercase(), pixel(14.sp, if ((counts[m.title] ?: 0) > 0) Ink.ink else Ink.mid), touch = 4.dp) { Moods.open(m); open(Page.Mood(m)) }
            }
        }
    }
}

/** A mood's page: its flavors, each a row of YouTube Music's playlists for it; a station from all of it. */
@Composable
fun MoodPage(m: InnerTube.Mood, open: (Page) -> Unit, onClose: () -> Unit) {
    val counts by Moods.counts.collectAsState()
    // a region's or a language's flavors after the rest, unless it's a genre you open (Bronson, 2026-10-28: all of it picked-feeling)
    val shelves by produceState<List<InnerTube.Shelf>?>(null, m.params, counts) {
        val all = withContext(Dispatchers.IO) { InnerTube().mood(m.params) }
        val opened = counts.filterValues { it > 0 }.keys.joinToString(" ").lowercase()
        value = all.sortedBy { s -> Moods.REGIONAL.any { w -> w in s.title.lowercase() && w !in opened } }
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val list = rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        Close(onClose)
        LazyColumn(Modifier.fillMaxSize().feather(list), list, contentPadding = PaddingValues(start = SIDE, end = SIDE, top = 10.dp, bottom = 32.dp)) {
            item {
                BasicText(m.title.uppercase(), style = headline(64.sp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText(shelves?.let { "${it.size} flavors on youtube music" } ?: "reading youtube music", style = pixel(12.sp, Ink.mid), modifier = Modifier.weight(1f))
                    Word("start station", pixel(14.sp), touch = 8.dp) { scope.launch { Moods.station(m) } }
                }
                Spacer(Modifier.height(10.dp))
            }
            shelves.orEmpty().forEach { shelf ->
                item(key = shelf.title) {
                    Label(shelf.title.lowercase())
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        items(shelf.items.mapNotNull(::tubeAlbum), key = { it.url }) { a -> TubeTile(a, Modifier.width(160.dp)) { open(Page.Tube(a)) } }
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }
        }
    }
}

/** "50.5M monthly audience" as a number (0: not said). */
private fun audience(said: String): Long {
    val m = Regex("([\\d.]+)\\s*([KMB]?)").find(said.uppercase()) ?: return 0
    val n = m.groupValues[1].toDoubleOrNull() ?: return 0
    return (n * when (m.groupValues[2]) { "K" -> 1e3; "M" -> 1e6; "B" -> 1e9; else -> 1.0 }).toLong()
}
