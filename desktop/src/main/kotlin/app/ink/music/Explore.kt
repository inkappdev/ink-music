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
import androidx.compose.runtime.key
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext

/**
 * INK (explore and ink's own place, one since 2026-10-29: Bronson), what isn't yours yet, picked by people: ink weekly
 * on top, ink radio after what's on the air; (Bronson, 2026-10-28: "as cool and curated as possible, even signed
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
    LaunchedEffect(Unit) { Picks.start(); Picks.startFriday(); Picks.startElectronic() }
    val electronic by Picks.electronic.collectAsState()
    val friday by Picks.friday.collectAsState()
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
        // one rhythm down the page: the same room between every section (Bronson, 2026-10-29)
        LazyColumn(Modifier.fillMaxSize().feather(list), list, contentPadding = PaddingValues(start = SIDE, end = SIDE, top = 6.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(GAP)) {
            // ink weekly, the cover: ours, new each Friday
            item(key = "weekly") { InkWeekly() }
            today?.let { d -> item(key = "today") { RecordOfTheDay(d, open) } }
            if (air.isNotEmpty()) item(key = "air") { OnTheAir(air) }
            item(key = "radio") { InkRadio(air) }
            item(key = "festivals") { FestivalsShelf(open) }
            item(key = "moods") { InkMoodsShelf() }
            if (!moods.isNullOrEmpty()) item(key = "genres") { Genres(moods.orEmpty(), open) }
            if (records.isNotEmpty()) item(key = "picked") {
                Column {
                    Section("NEW RECORDS, PICKED BY CRITICS", "the critics' favorites lately")
                    records.take(across * 2).chunked(across).forEach { row -> TileRow(row, across) { s -> PickTile(s, open) } }
                }
            }
            friday?.let { w -> item(key = "friday") { OutThisFriday(w, open) } }
            if (electronic.isNotEmpty()) item(key = "electronic") { Electronic(electronic, open, wide) }
            chart.orEmpty().takeIf { it.isNotEmpty() }?.let { c -> item(key = "artist") { ArtistOfTheWeek(c, open) } }
            item(key = "songs") {
                // the songs the critics picked, beside the stations' chart: side by side wide, one above the other narrow
                val picked = @Composable { m: Modifier -> PickedSongs(songs, m) }
                val charted = @Composable { m: Modifier -> Chart(chart, before, open, m) }
                if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(56.dp)) {
                    charted(Modifier.weight(1f)); picked(Modifier.weight(1f))
                } else Column(verticalArrangement = Arrangement.spacedBy(GAP)) { charted(Modifier.fillMaxWidth()); picked(Modifier.fillMaxWidth()) }
            }
            // yours from YouTube Music, signed in
            if (!fresh.isNullOrEmpty()) item(key = "fresh") {
                Column {
                    Section("NEW RECORDS ON YOUTUBE MUSIC", "this week, for you")
                    fresh.orEmpty().take(across * 2).chunked(across).forEach { row ->
                        TileRow(row, across) { a -> TubeTile(a, Modifier.fillMaxWidth()) { open(Page.Tube(a)) } }
                    }
                }
            }
            shelves.orEmpty().forEach { (title, items) ->
                item(key = "s$title") {
                    Column {
                        Section(title.uppercase(), "on youtube music, for you")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            items(items.take(16), key = { it.url }) { a -> TubeTile(a, Modifier.width(170.dp)) { open(Page.Tube(a)) } }
                        }
                    }
                }
            }
        }
    }
}

/** The room between a page's sections, the same everywhere. */
val GAP = 52.dp

/** A section's head: its name big, a short line under it, a word at its right (play all). */
@Composable
fun Section(title: String, line: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            BasicText(title, style = headline(30.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            BasicText(line, style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        action?.let { Box(Modifier.padding(start = 20.dp)) { it() } }
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
    BoxWithConstraints(Modifier.fillMaxWidth()) {
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
    Column {
        Section("ON THE AIR NOW", "what the djs are playing this minute: a click tunes in")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            items(air, key = { it.station }) { a ->
                var said by remember(a) { mutableStateOf<String?>(null) }
                Column(Modifier.width(170.dp)) {
                    // the station's cover, else iTunes' (asked aside, never while drawing)
                    val art by produceState(a.art, a) { if (value == null) value = withContext(Dispatchers.IO) { Curation.itunes(a.title, a.artist, false) } }
                    val face = remember(a, art) { Song(id = "yair:" + a.station + a.title, path = "", title = a.title, artist = a.artist, album = a.title,
                        albumId = "air:" + a.station + a.title + (art ?: ""), albumArtist = a.artist, duration = 0, disc = 1, track = 0, year = 0, art = art) }
                    // a click tunes in to the station (its song, if the station's not one ink keeps)
                    val station = remember(a) { Stations.known(a.station) }
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).clickable(remember { MutableInteractionSource() }, indication = null) {
                        if (station != null) Player.play(listOf(station))
                        else scope.launch { said = "finding it"; val s = Picks.song(a.title, a.artist); said = if (s == null) "not on youtube music" else null; s?.let { Player.play(listOf(it)) } }
                    }) { CoverOf(face, a.title, Modifier.fillMaxSize(), px = 340) }
                    Spacer(Modifier.height(8.dp))
                    UnderCover(a.title, a.artist)
                    BasicText("on ${a.name}", style = pixel(12.sp, Ink.mid))
                    said?.let { BasicText(it, style = pixel(12.sp, Ink.dim)) }
                }
            }
        }
    }
}

/**
 * Out this Friday: every record out this week (r/indieheads' New Music Friday thread, typed up by one of them each
 * Friday), filtered by kind, in two rows to slide through; each cover found as it comes into sight.
 */
@Composable
private fun OutThisFriday(w: Curation.Week, open: (Page) -> Unit) {
    val kinds = remember(w) { Curation.kinds(w.releases) }
    var kind by remember(w) { mutableStateOf<String?>(null) }
    val shown = kind?.let { k -> kinds.firstOrNull { it.first == k }?.second } ?: w.releases
    Column {
        Section("OUT THIS FRIDAY", "every record out ${w.day}, ${w.releases.size} of them, from r/indieheads") {
            Word("the thread", pixel(14.sp, Ink.mid), touch = 8.dp) { browse(w.thread) }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(bottom = 14.dp)) {
            item { Word("all", pixel(13.sp, if (kind == null) Ink.ink else Ink.dim), touch = 8.dp) { kind = null } }
            items(kinds, key = { it.first }) { (k, l) -> Word("$k ${l.size}", pixel(13.sp, if (kind == k) Ink.ink else Ink.dim), touch = 8.dp) { kind = k } }
        }
        key(kind) {
            androidx.compose.foundation.lazy.grid.LazyHorizontalGrid(androidx.compose.foundation.lazy.grid.GridCells.Fixed(2), Modifier.height(492.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                items(shown, key = { it.artist + "/" + it.title }) { r ->
                    val art by produceState<String?>(null, r) { value = Picks.art(r) }
                    Box(Modifier.width(160.dp)) { PickTile(remember(r, art) { Picks.shown(r, art) }, open) }
                }
            }
        }
    }
}

/**
 * Electronic, picked by its own writers: their records in turn (Bandcamp Daily's columns, A Closer Listen, Igloo,
 * Headphone Commute), and the club tracks premiered (Inverted Audio's, Headphone Commute's) beside them.
 */
@Composable
private fun Electronic(all: List<Curation.Pick>, open: (Page) -> Unit, wide: Boolean) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var said by remember { mutableStateOf<String?>(null) }
    val records = remember(all) { Curation.fair(all.filter { it.record }) { it.from }.take(24) }
    val tracks = remember(all) { all.filter { !it.record }.take(8) }
    Column {
        Section("ELECTRONIC", said ?: "picked by bandcamp daily, a closer listen, igloo and headphone commute")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            items(records, key = { it.from + it.artist + it.title }) { p ->
                val art by produceState<String?>(null, p) { value = Picks.art(p.title, p.artist, p.link, true) }
                Box(Modifier.width(170.dp)) { PickTile(remember(p, art) { Picks.Shown(p.copy(art = art), art, listOf(p.from)) }, open) }
            }
        }
        if (tracks.isNotEmpty()) {
            Spacer(Modifier.height(26.dp))
            Section("CLUB TRACKS", "premiered by inverted audio and headphone commute") {
                Word("play all", pixel(14.sp), touch = 8.dp) {
                    scope.launch { said = "finding them on youtube music"; Picks.playSongs(tracks.map { Picks.Shown(it, null, listOf(it.from)) }); said = null }
                }
            }
            val rows = @Composable { l: List<Curation.Pick>, m: Modifier ->
                Column(m) {
                    l.forEach { p ->
                        val art by produceState<String?>(null, p) { value = Picks.art(p.title, p.artist, p.link, false) }
                        PickedRow(null, "", Picks.Shown(p, art, emptyList()).face, p.title, p.artist, p.from) {
                            scope.launch { Picks.song(p.title, p.artist)?.let { Player.play(listOf(it)) } ?: run { said = "${p.title.lowercase()}: not on youtube music yet" } }
                        }
                    }
                }
            }
            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(56.dp)) {
                rows(tracks.take((tracks.size + 1) / 2), Modifier.weight(1f)); rows(tracks.drop((tracks.size + 1) / 2), Modifier.weight(1f))
            } else rows(tracks, Modifier.fillMaxWidth())
        }
    }
}

/** A record someone picked: its cover, its name, who picked it; a click opens it on YouTube Music. */
@Composable
internal fun PickTile(s: Picks.Shown, open: (Page) -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var said by remember(s) { mutableStateOf<String?>(null) }
    // not on YouTube Music (often reviewed before it's streaming, or only on Bandcamp): a second click reads the review
    var missing by remember(s) { mutableStateOf(false) }
    Column(Modifier.clickable(remember { MutableInteractionSource() }, indication = null) {
        if (missing && s.pick.link.isNotBlank()) browse(s.pick.link)
        else scope.launch {
            said = "finding it"; val a = Picks.record(s.pick)
            missing = a == null
            said = if (a == null) (if (s.pick.link.isNotBlank()) "not streaming yet: click to read" else "not on youtube music yet") else null
            a?.let { open(Page.Tube(it)) }
        }
    }) {
        CoverOf(s.face, s.pick.title, Modifier.fillMaxWidth().aspectRatio(1f), px = 400)
        Spacer(Modifier.height(8.dp))
        UnderCover(s.pick.title, s.pick.artist)
        BasicText(said ?: s.by.joinToString(" · "), style = pixel(12.sp, if (s.by.size > 1) Ink.ink else Ink.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A song's name without what crowds a row ("[From GTAVI]", "(feat. ...)", "(with ...)"): the featured are on its
 * page, and a chart reads best as names.
 */
fun plain(title: String) = title.replace(Regex("""\s*[\[(](feat\.?|ft\.?|with|from|prod\.?)\b[^\])]*[\])]""", RegexOption.IGNORE_CASE), "")
    .replace(Regex("""\s*\[[^\]]*\]"""), "").trim().ifEmpty { title }

/**
 * A row of a list of songs picked by others (the chart, the critics'): a place or nothing, its cover, its name big,
 * who under it, and dim at its right who chose it; room above and below so a column of them reads (Bronson,
 * 2026-10-29: "cramped and hard to read").
 */
@Composable
private fun PickedRow(place: Int?, move: String, face: Song?, title: String, artist: String, by: String, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val over by src.collectIsHoveredAsState()
    Row(Modifier.fillMaxWidth().hoverable(src).clickable(src, indication = null, onClick = felt(then = onClick)).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (place != null) Column(Modifier.width(52.dp)) {
            BasicText("$place", style = headline(24.sp, if (over) Ink.ink else Ink.mid))
            if (move.isNotEmpty()) BasicText(move, style = pixel(12.sp, if (move == "new" || move.startsWith("↑")) Ink.ink else Ink.dim))
        }
        CoverOf(face, title, Modifier.size(56.dp), px = 140)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            BasicText(plain(title).uppercase(), style = headline(17.sp, if (over) brighter(Ink.ink) else Ink.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            BasicText(artist.lowercase(), style = pixel(13.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (by.isNotEmpty()) BasicText(by, style = pixel(12.sp, Ink.dim), maxLines = 1, modifier = Modifier.padding(start = 16.dp))
    }
}

/** The songs the critics picked: ten, who picked each, play all. */
@Composable
private fun PickedSongs(songs: List<Picks.Shown>, modifier: Modifier) {
    if (songs.isEmpty()) return
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var said by remember { mutableStateOf<String?>(null) }
    Column(modifier) {
        Section("PICKED BY CRITICS", said ?: "the newest from pitchfork, the fader, stereofox and gorilla vs. bear") {
            Word("play all", pixel(14.sp), touch = 8.dp) { scope.launch { said = "finding them on youtube music"; Picks.playSongs(songs); said = null } }
        }
        songs.take(10).forEach { s ->
            PickedRow(null, "", s.face, s.pick.title, s.pick.artist, s.by.joinToString(" · ").lowercase()) {
                scope.launch { Picks.song(s.pick.title, s.pick.artist)?.let { Player.play(listOf(it)) } ?: run { said = "${s.pick.title.lowercase()}: not on youtube music" } }
            }
        }
    }
}

/** The indie chart's ten: the place, how it moved this week (new, up, down), the song; the stations in the line above. */
@Composable
private fun Chart(chart: List<IndieChart.Entry>?, before: Map<String, Int?>?, open: (Page) -> Unit, modifier: Modifier) {
    val songs = chart.orEmpty().mapNotNull { it.song }
    Column(modifier) {
        Section("THE INDIE CHART", if (chart == null) "counting this week's plays" else "what kexp, kcrw and wxpn played most this week")
        chart.orEmpty().take(10).forEachIndexed { i, e ->
            val s = e.song ?: return@forEachIndexed
            // how it moved: new this week, up or down so many, or where it was
            val was = before?.let { b -> if (IndieChart.key(e) in b) b[IndieChart.key(e)] else null }
            val move = when {
                before == null -> ""
                was == null -> "new"
                was > i + 1 -> "↑${was - i - 1}"
                was < i + 1 -> "↓${i + 1 - was}"
                else -> ""
            }
            PickedRow(i + 1, move, s, s.title, s.artist, "") { Player.play(songs, songs.indexOf(s).coerceAtLeast(0)) }
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
    // the chart's artists and the critics' picks', the week's turn through them; someone with under a million and a half
    // listeners a month (Bronson, 2026-10-28: "a bit more niche"), with a photo to show
    val picked by Picks.records.collectAsState()
    val names = remember(chart, picked) {
        (chart.map { it.artist } + picked.map { it.pick.artist }).map { it.substringBefore(",").substringBefore(" & ").trim() }
            .filter { it.isNotBlank() && !it.equals("various artists", true) }.distinctBy { it.lowercase() }
    }
    val week = java.time.LocalDate.now().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).toEpochDay() / 7
    val info by produceState<ArtistInfo?>(null, names) {
        if (names.isEmpty()) return@produceState
        val from = (week % names.size).toInt()
        for (k in 0 until minOf(names.size, 20)) {
            val name = names[(from + k) % names.size]
            val a = withContext(Dispatchers.IO) { kotlinx.coroutines.withTimeoutOrNull(8_000) { Artists.of(name, "").firstOrNull { it != null } } } ?: continue
            val n = audience(a.audience)
            // and new-ish: a bio whose story starts before 2005 ("born 1948", "formed in 1968") is a legend's, small page or not
            val veteran = Regex("\\b(19\\d\\d|200[0-4])\\b").containsMatchIn(a.about.take(400))
            if (n in 1..1_500_000 && a.photo != null && !veteran) { value = a; return@produceState }
        }
    }
    val i = info ?: return
    Column {
        Section("ARTIST OF THE WEEK", "someone the djs and critics are into, a new one each monday")
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

/**
 * The genres (YouTube Music's own, its editors' playlists), the ones you open most first: a heading of their own
 * and two rows of tiles to slide through (Bronson, 2026-10-29: as a wall of words under the moods they had none).
 */
@Composable
private fun Genres(all: List<InnerTube.Mood>, open: (Page) -> Unit) {
    val counts by Moods.counts.collectAsState()
    val genres = remember(all, counts) {
        all.filter { it.genre }.sortedWith(compareByDescending<InnerTube.Mood> { counts[it.title] ?: 0 }.thenBy { Moods.rank(it.title) })
    }
    Column {
        Section("GENRES", "youtube music's editors' playlists, the ones you open most first")
        androidx.compose.foundation.lazy.grid.LazyHorizontalGrid(androidx.compose.foundation.lazy.grid.GridCells.Fixed(2), Modifier.height(GENRE * 2 + 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(genres, key = { it.params }) { m -> GenreTile(m.title, (counts[m.title] ?: 0) > 0) { Moods.open(m); open(Page.Mood(m)) } }
        }
    }
}

private val GENRE = 84.dp

/** A genre as a tile: its name in a hairline frame (as the phone's), lit if it's yours, brighter under the mouse. */
@Composable
private fun GenreTile(name: String, yours: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val over by src.collectIsHoveredAsState()
    Box(Modifier.width(186.dp).height(GENRE).border(1.dp, if (yours || over) Ink.mid else Ink.line).hoverable(src)
        .clickable(src, indication = null, onClick = felt(then = onClick)).padding(14.dp), contentAlignment = Alignment.BottomStart) {
        BasicText(name.uppercase(), style = headline(17.sp, if (yours || over) Ink.ink else Ink.mid).copy(lineHeight = 19.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
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
