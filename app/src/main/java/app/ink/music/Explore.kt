package app.ink.music

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Explore (the Mac's, here): what isn't yours yet, picked by critics and DJs. The record of the day; what DJs are playing this
 * minute; right now's moods and every mood and genre (the full build); new records and songs the critics picked; an
 * artist of the week from the indie chart; the chart, each song's move over the week.
 */
@Composable
fun ExplorePage(pages: Pages, pad: PaddingValues, state: LazyListState) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { Picks.start(ctx); Picks.startFriday(); Picks.startElectronic() }
    val electronic by Picks.electronic.collectAsState()
    val friday by Picks.friday.collectAsState()
    LaunchedEffect(Unit) { while (true) { Picks.air(); kotlinx.coroutines.delay(60_000) } }
    val records by Picks.records.collectAsState()
    val songs by Picks.songs.collectAsState()
    val today by Picks.today.collectAsState()
    val air by Picks.onAir.collectAsState()
    val chart by Listen.indie.collectAsState()
    val before = remember(chart) { IndieChart.before(ctx) }
    val moods by produceState<List<TubeMood>?>(Moods.kept(ctx)) { value = Moods.read(ctx) ?: value }
    val scope = rememberCoroutineScope()
    // the record of the day arriving above: the page stays at its top (it had opened partway down)
    LaunchedEffect(today != null) { if (state.firstVisibleItemIndex <= 2) state.scrollToItem(0) }
    LazyColumn(state = state, contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding())) {
        // ink weekly, the cover: ours, new each Friday
        item(key = "weekly") { InkWeeklyCover(pages) }
        today?.let { d -> item(key = "today") { RecordOfTheDay(d, pages) } }
        if (air.isNotEmpty()) item(key = "air") {
            Heading("on the air now", "what the djs are playing this minute: a tap tunes in")
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(air, key = { it.station }) { a -> AiredTile(a) }
            }
            Spacer(Modifier.height(14.dp))
        }
        item(key = "radio") { InkRadioShelf(pages) }
        item(key = "festivals") { FestivalsShelf(pages) }
        // ink's own moods (Last.fm listeners' tags), then YouTube Music's genres (its own moods gone: Bronson, 2026-10-28)
        item(key = "inkmoods") { InkMoodsShelf() }
        moods?.takeIf { it.isNotEmpty() }?.let { all -> item(key = "moods") { MoodsAndGenres(all, pages) } }
        if (records.isNotEmpty()) item(key = "picked") {
            Heading("new records, picked by critics", "the critics' favorites lately")
            ShelfIndexed(records.take(30)) { _, s -> PickTile(s, pages) }
        }
        friday?.let { w -> item(key = "friday") { OutThisFriday(w, pages) } }
        if (electronic.isNotEmpty()) item(key = "electronic") { Electronic(electronic, pages) }
        if (chart.isNotEmpty()) item(key = "artist") { ArtistOfTheWeek(chart, pages) }
        if (songs.isNotEmpty()) item(key = "songs") {
            var said by remember { mutableStateOf<String?>(null) }
            Heading("songs, picked by critics", "the newest from pitchfork, the fader, stereofox and gorilla vs. bear") {
                Word("play all", pixel(13.sp), touch = 8.dp) { scope.launch { said = "finding them"; Picks.playSongs(songs); said = null } }
            }
            said?.let { BasicText(it, style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(horizontal = 20.dp)) }
            ShelfIndexed(songs.take(20)) { _, s ->
                Tile(s.face, s.pick.title, "${s.pick.artist.lowercase()} · ${s.by.joinToString(" · ")}") {
                    scope.launch { Picks.song(s.pick.title, s.pick.artist)?.let { Playback.play(listOf(it), 0) } }
                }
            }
        }
        if (chart.isNotEmpty()) {
            item(key = "chart") {
                Heading("the indie chart", "what kexp, kcrw and wxpn played most this week")
            }
            val playable = chart.mapNotNull { it.song }
            chart.forEachIndexed { i, e ->
                val s = e.song ?: return@forEachIndexed
                item(key = "c$i") {
                    val was = before?.let { b -> if (IndieChart.key(e) in b) b[IndieChart.key(e)] else null }
                    val move = when { before == null -> ""; was == null -> "new"; was > i + 1 -> "↑${was - i - 1}"; was < i + 1 -> "↓${i + 1 - was}"; else -> "=" }
                    Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null,
                        onClick = felt { Playback.play(playable, playable.indexOf(s).coerceAtLeast(0)) }).padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.width(52.dp)) {
                            BasicText("${i + 1}", style = headline(26.sp))
                            if (move.isNotEmpty()) BasicText(move, style = pixel(12.sp, if (move == "new" || move.startsWith("↑")) Ink.ink else Ink.dim))
                        }
                        Column(Modifier.weight(1f)) {
                            BasicText(e.title.uppercase(), style = headline(17.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            BasicText((e.artist + "  ·  " + e.stations.joinToString(" ")).lowercase(), style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** The record of the day: its cover across the width, its name, what was said and by whom; play or its page. */
@Composable
private fun RecordOfTheDay(d: Picks.Shown, pages: Pages) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<String?>(null) }
    fun go(play: Boolean) = scope.launch {
        said = "finding it on youtube music"
        val a = Picks.record(d.pick) ?: run { said = "not on youtube music"; return@launch }
        said = null
        if (play) Playback.play(YouTubeMusic.album(a), 0) else pages.open(Page.Tube(a))
    }
    Column(Modifier.padding(horizontal = 20.dp).padding(top = 6.dp, bottom = 22.dp)) {
        BasicText("RECORD OF THE DAY", style = headline(22.sp))
        BasicText("picked by ${d.by.joinToString(", ")}", style = pixel(12.sp, Ink.mid))
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clickable(remember { MutableInteractionSource() }, indication = null) { go(false) }) {
            CoverOf(d.face, d.pick.title, Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(14.dp))
        BasicText(d.pick.title.uppercase(), style = headline(36.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
        BasicText(d.pick.artist.lowercase(), style = pixel(14.sp))
        if (d.pick.said.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            BasicText("“${d.pick.said}”".uppercase(), style = headline(18.sp).copy(lineHeight = 23.sp), maxLines = 6, overflow = TextOverflow.Ellipsis)
            BasicText("— ${d.pick.from}", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
            if (YouTubeMusic.available) {
                Word("play", pixel(14.sp), touch = 8.dp) { go(true) }
                Word("its page", pixel(14.sp, Ink.mid), touch = 8.dp) { go(false) }
            }
            if (d.pick.link.isNotBlank()) Word("read it", pixel(14.sp, Ink.mid), touch = 8.dp) {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(d.pick.link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
        said?.let { BasicText(it, style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 8.dp)) }
    }
}

/** A song on the air now: its cover, the song, the station; a tap plays the song, "tune in" the station. */
@Composable
private fun AiredTile(a: Curation.Aired) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var said by remember(a) { mutableStateOf<String?>(null) }
    val art by produceState(a.art, a) { if (value == null) value = withContext(Dispatchers.IO) { Curation.cover(a.title, a.artist, false) } }
    val face = remember(a, art) { Song(id = "yair:" + a.station + a.title, uri = Uri.EMPTY, title = a.title, artist = a.artist, album = a.title,
        albumId = "air:" + a.station + a.title + (art ?: ""), albumArtist = a.artist, duration = 0, disc = 1, track = 0, year = 0, art = art) }
    val station = remember(a) { Stations.curated(ctx).firstOrNull { it.song.id == a.station }?.song }
    Column(Modifier.width(150.dp)) {
        // a tap tunes in to the station (its song, if the station's not one ink keeps)
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clickable(remember { MutableInteractionSource() }, indication = null) {
            if (station != null) Playback.play(listOf(station), 0)
            else scope.launch { said = "finding it"; val s = Picks.song(a.title, a.artist); said = if (s == null) "not on youtube music" else null; s?.let { Playback.play(listOf(it), 0) } }
        }) { CoverOf(face, a.title, Modifier.fillMaxSize()) }
        Spacer(Modifier.height(8.dp))
        UnderCover(a.title, a.artist, pixel(12.sp, Ink.mid))
        BasicText("on ${a.name}", style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
        said?.let { BasicText(it, style = pixel(12.sp, Ink.dim)) }
    }
}

/** A record or song someone picked: its cover, name, who picked it; a tap opens the record or plays the song. */
@Composable
internal fun PickTile(s: Picks.Shown, pages: Pages) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var said by remember(s) { mutableStateOf<String?>(null) }
    Column(Modifier.width(150.dp).clickable(remember { MutableInteractionSource() }, indication = null) {
        // the play build has no YouTube Music: the review is all there is to open
        if (!YouTubeMusic.available) { if (s.pick.link.isNotBlank()) runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.pick.link))) } }
        // never out to the review (Bronson, 2026-10-29: The War on Drugs' "Who's That", a song, said "not streaming
        // yet" and sent you to Pitchfork while YouTube Music had it): a song picked plays; a record opens, and one
        // not there yet plays its song of the same name (its single) where there is one
        else scope.launch {
            said = "finding it"
            val a = if (s.pick.record) Picks.record(s.pick) else null
            val song = if (a == null) Picks.song(s.pick.title, s.pick.artist) else null
            said = if (a == null && song == null) "not on youtube music yet" else null
            a?.let { pages.open(Page.Tube(it)) } ?: song?.let { Playback.play(listOf(it), 0) }
        }
    }) {
        CoverOf(s.face, s.pick.title, Modifier.fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(8.dp))
        UnderCover(s.pick.title, s.pick.artist, pixel(12.sp, Ink.mid))
        BasicText(said ?: s.by.joinToString(" · "), style = pixel(12.sp, if (s.by.size > 1) Ink.ink else Ink.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The artist of the week: from the indie chart's top fifteen, the very famous passed over; their photo dithered, a line, play. */
/**
 * ink weekly as INK's cover (Bronson, 2026-10-29: "really make that top ink weekly design shine"): its four covers in
 * the dither across the page, its name on a black tab in their corner; the week's artists running across in the giant
 * marquee, as the player's do (still under less motion); its note, play the week; its songs as a numbered shelf. A tap
 * on the covers or the songs' count opens the week.
 */
@Composable
private fun InkWeeklyCover(pages: Pages) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { Weekly.load(ctx) }
    val weeks by Weekly.weeks.collectAsState()
    val w = weeks.firstOrNull() ?: return
    val less by Moves.less.collectAsState()
    Column(Modifier.padding(top = 6.dp, bottom = 24.dp)) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("INK WEEKLY", style = headline(16.sp))
            Spacer(Modifier.width(12.dp))
            BasicText(listOf("vol ${w.week}", Weekly.day(w.date), "this week").filter { it.isNotEmpty() }.joinToString("  ·  "), style = pixel(12.sp, Ink.mid))
        }
        Spacer(Modifier.height(10.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 20.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { pages.open(Page.Weekly(w.week)) }),
            contentAlignment = Alignment.BottomStart) {
            Mosaic(w.songs, Modifier.size(maxWidth))
            // its name on its covers, on a black tab in their corner (a sleeve's label: pale covers would swallow it)
            BasicText(w.title.uppercase(), style = headline(52.sp).copy(lineHeight = 50.sp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.background(Ink.black).padding(start = 0.dp, end = 14.dp, top = 8.dp, bottom = 0.dp))
        }
        Spacer(Modifier.height(16.dp))
        val names = remember(w) { w.songs.map { it.artist.substringBefore(",").trim() }.distinct().joinToString("  ·  ") }
        Marquee(names, !less, Modifier.fillMaxWidth(), size = 64.sp)
        if (w.note.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            BasicText(w.note.lowercase(), style = pixel(13.sp, Ink.ink.copy(alpha = .75f)).copy(lineHeight = 21.sp), modifier = Modifier.padding(horizontal = 20.dp))
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Word("PLAY THE WEEK", headline(20.sp), Modifier.padding(end = 24.dp), touch = 8.dp) { Playback.play(w.songs, 0) }
            Word("shuffle", pixel(14.sp, Ink.mid), Modifier.padding(end = 24.dp), touch = 8.dp) { Playback.play(w.songs.shuffled(), 0) }
            Word("${w.songs.size} songs", pixel(14.sp, Ink.mid), touch = 8.dp) { pages.open(Page.Weekly(w.week)) }
        }
        Spacer(Modifier.height(14.dp))
        ShelfIndexed(w.songs) { i, s -> Tile(s, s.title, s.artist, number = i + 1) { Playback.play(w.songs, i) } }
    }
}

/** Ink radio on INK: the stations we keep, the stars first, each its sleeve; all of them a tap away. */
@Composable
private fun InkRadioShelf(pages: Pages) {
    val ctx = LocalContext.current
    val now by Playback.now.collectAsState()
    val ink = remember { Stations.curated(ctx).sortedByDescending { it.star } }
    if (ink.isEmpty()) return
    Heading("ink radio", "the stations we keep: real djs, picked by people") {
        Word("all", pixel(13.sp, Ink.mid), touch = 8.dp) { pages.open(Page.InkRadio) }
    }
    ShelfIndexed(ink) { i, st ->
        val on = now?.song?.id == st.song.id
        StationTile(st.song, on, now?.playing == true, said = if (on) "on now" else st.city) { Playback.play(ink.drop(i).map { it.song }, 0) }
    }
    Spacer(Modifier.height(12.dp))
}

/**
 * Out this Friday: every record out this week (r/indieheads' New Music Friday thread, typed up by one of them each
 * Friday), filtered by kind, a shelf to slide through; each cover found as it comes into sight.
 */
@Composable
private fun OutThisFriday(w: Curation.Week, pages: Pages) {
    val ctx = LocalContext.current
    val kinds = remember(w) { Curation.kinds(w.releases) }
    var kind by remember(w) { mutableStateOf<String?>(null) }
    val shown = kind?.let { k -> kinds.firstOrNull { it.first == k }?.second } ?: w.releases
    Heading("out this friday", "every record out ${w.day}, from r/indieheads") {
        Word("thread", pixel(13.sp, Ink.mid), touch = 8.dp) {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(w.thread)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
    Spacer(Modifier.height(8.dp))
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Word("all", pixel(13.sp, if (kind == null) Ink.ink else Ink.dim), touch = 8.dp) { kind = null } }
        items(kinds, key = { it.first }) { (k, l) -> Word("$k ${l.size}", pixel(13.sp, if (kind == k) Ink.ink else Ink.dim), touch = 8.dp) { kind = k } }
    }
    Spacer(Modifier.height(10.dp))
    androidx.compose.runtime.key(kind) {
        Shelf(shown) { r ->
            val art by produceState<String?>(null, r) { value = Picks.art(r) }
            PickTile(remember(r, art) { Picks.shown(r, art) }, pages)
        }
    }
    Spacer(Modifier.height(12.dp))
}

/**
 * Electronic, picked by its own writers: their records in turn (Bandcamp Daily's columns, A Closer Listen, Igloo,
 * Headphone Commute), then the club tracks premiered (Inverted Audio's, Headphone Commute's).
 */
@Composable
private fun Electronic(all: List<Curation.Pick>, pages: Pages) {
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<String?>(null) }
    val records = remember(all) { Curation.fair(all.filter { it.record }) { it.from }.take(24) }
    val tracks = remember(all) { all.filter { !it.record }.take(10) }
    Heading("electronic", said ?: "picked by bandcamp daily, a closer listen, igloo and headphone commute")
    Shelf(records) { p ->
        val art by produceState<String?>(null, p) { value = Picks.art(p.title, p.artist, p.link, true) }
        PickTile(remember(p, art) { Picks.Shown(p.copy(art = art), art, listOf(p.from)) }, pages)
    }
    if (tracks.isNotEmpty()) {
        Heading("club tracks", "premiered by inverted audio and headphone commute") {
            if (YouTubeMusic.available) Word("play all", pixel(13.sp), touch = 8.dp) {
                scope.launch { said = "finding them"; Picks.playSongs(tracks.map { Picks.Shown(it, null, listOf(it.from)) }); said = null }
            }
        }
        Shelf(tracks) { p ->
            val art by produceState<String?>(null, p) { value = Picks.art(p.title, p.artist, p.link, false) }
            val shown = remember(p, art) { Picks.Shown(p, art, listOf(p.from)) }
            Tile(shown.face, p.title, "${p.artist.lowercase()} · ${p.from}") {
                if (YouTubeMusic.available) scope.launch { Picks.song(p.title, p.artist)?.let { Playback.play(listOf(it), 0) } ?: run { said = "${p.title.lowercase()}: not on youtube music yet" } }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun ArtistOfTheWeek(chart: List<IndieChart.Entry>, pages: Pages) {
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
            val a = withTimeoutOrNull(8_000) { YouTubeAccount.artist(names[(from + k) % names.size], "").firstOrNull { it != null } } ?: continue
            val n = audience(a.audience)
            // and new-ish: a bio whose story starts before 2005 ("born 1948", "formed in 1968") is a legend's, small page or not
            val veteran = Regex("\\b(19\\d\\d|200[0-4])\\b").containsMatchIn(a.about.take(400))
            if (n in 1..1_500_000 && a.photo != null && !veteran) { value = a; return@produceState }
        }
    }
    val i = info ?: return
    Column(Modifier.padding(horizontal = 20.dp).padding(top = 18.dp, bottom = 20.dp)) {
        BasicText("ARTIST OF THE WEEK", style = headline(22.sp))
        BasicText("someone the djs and critics are into, a new one each monday", style = pixel(12.sp, Ink.mid))
        Spacer(Modifier.height(12.dp))
        i.photo?.let { Dithered(remember(it) { faceSong(i.name, it) }, Modifier.fillMaxWidth().aspectRatio(1f)) }
        Spacer(Modifier.height(12.dp))
        BasicText(i.name.uppercase(), style = headline(40.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (i.audience.isNotEmpty()) BasicText(i.audience.lowercase().replace("monthly audience", "a month"), style = pixel(12.sp, Ink.mid))
        pullQuote(i.about).first?.let {
            Spacer(Modifier.height(10.dp))
            BasicText(it.uppercase(), style = headline(18.sp).copy(lineHeight = 23.sp), maxLines = 5, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
            if (i.top.isNotEmpty()) Word("play", pixel(14.sp), touch = 8.dp) { Playback.play(i.top, 0) }
            if (i.top.isNotEmpty()) Word("station", pixel(14.sp, Ink.mid), touch = 8.dp) { Playback.startRadio(i.top.first()) }
            Word("their page", pixel(14.sp, Ink.mid), touch = 8.dp) { pages.open(Page.Artist(i.name)) }
        }
    }
}

/** "50.5M monthly audience" as a number (0: not said). */
private fun audience(said: String): Long {
    val m = Regex("([\\d.]+)\\s*([KMB]?)").find(said.uppercase()) ?: return 0
    val n = m.groupValues[1].toDoubleOrNull() ?: return 0
    return (n * when (m.groupValues[2]) { "K" -> 1e3; "M" -> 1e6; "B" -> 1e9; else -> 1.0 }).toLong()
}

/**
 * YouTube Music's moods and genres (the full build), kept; right now picks three for the hour, the day and the season;
 * the genres you open most come first; a region's flavors after the rest on a mood's page.
 */
object Moods {
    /** ink's own order for the genres (Bronson, 2026-10-28: alphabetical put the odd ones first): the ones you open most still lead. */
    val GENRES = listOf("Indie & alternative", "Dance & electronic", "Hip-hop", "R&B & soul", "Jazz", "Rock", "Folk & acoustic", "Pop",
        "Soundtracks & musicals", "Reggae & caribbean", "Blues", "Latin", "Brazilian", "African", "Classical", "Metal", "Country & Americana",
        "K-Pop", "J-Pop", "Decades", "Songwriters & producers")
    fun rank(title: String) = GENRES.indexOfFirst { it.equals(title, true) }.let { if (it < 0) GENRES.size else it }
    val counts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val REGIONAL = listOf("latin", "bollywood", "indian", "k-pop", "kpop", "j-pop", "arab", "turk", "african", "brazil", "mandopop", "cantopop", "opm",
        "punjabi", "tamil", "telugu", "desi", "afro", "reggaeton", "mexican", "french", "german", "italian", "spanish", "korean", "japanese", "chinese")
    private fun file(c: Context) = File(c.cacheDir, "moods.json")
    private fun opened(c: Context) = File(c.filesDir, "moods-opened.json")

    fun kept(c: Context): List<TubeMood>? = runCatching {
        if (counts.value.isEmpty()) counts.value = runCatching { JSONObject(opened(c).readText()).let { o -> o.keys().asSequence().associateWith { o.getInt(it) } } }.getOrDefault(emptyMap())
        val a = JSONArray(file(c).readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map { TubeMood(it.getString("title"), it.getString("params"), it.getBoolean("genre")) }
    }.getOrNull()

    suspend fun read(c: Context): List<TubeMood>? = withContext(Dispatchers.IO) {
        runCatching { YouTubeMusic.moods() }.getOrNull()?.takeIf { it.isNotEmpty() }?.also { list ->
            runCatching { file(c).writeText(JSONArray(list.map { JSONObject().put("title", it.title).put("params", it.params).put("genre", it.genre) }).toString()) }
        }
    }

    fun open(c: Context, m: TubeMood) {
        counts.value = counts.value + (m.title to (counts.value[m.title] ?: 0) + 1)
        runCatching { opened(c).writeText(JSONObject(counts.value).toString()) }
    }

    fun now(all: List<TubeMood>): List<TubeMood> {
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
            ?.let { picks.add(0, it.title) }
        return picks.mapNotNull { p -> all.firstOrNull { it.title.equals(p, true) } }.take(3)
    }

    fun said(): String {
        val t = java.time.LocalDateTime.now()
        val part = when (t.hour) { in 5..11 -> "morning"; in 12..16 -> "afternoon"; in 17..20 -> "evening"; else -> "night" }
        val season = when (t.monthValue) { 12, 1, 2 -> "winter"; 3, 4, 5 -> "spring"; 6, 7, 8 -> "summer"; else -> "autumn" }
        return "a ${t.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH).lowercase()} $part in $season"
    }

    suspend fun station(m: TubeMood) {
        val songs = withContext(Dispatchers.IO) { YouTubeMusic.moodStation(m.params) }
        if (songs.isEmpty()) return
        Playback.radio(true)
        Playback.play(songs, 0)
    }
}

/** Right now (three moods for the hour, a station each), then every mood and genre as words. */
@Composable
private fun MoodsAndGenres(all: List<TubeMood>, pages: Pages) {
    val ctx = LocalContext.current
    val counts by Moods.counts.collectAsState()
    val scope = rememberCoroutineScope()
    // the genres as a row of tiles to slide through, the ones you open most first (as words they were a wall of
    // text on a phone: Bronson, 2026-10-28)
    Heading("genres", "youtube music's editors' playlists, the ones you open most first")
    Shelf(all.filter { it.genre }.sortedWith(compareByDescending<TubeMood> { counts[it.title] ?: 0 }.thenBy { Moods.rank(it.title) })) { m ->
        GenreTile(m.title, (counts[m.title] ?: 0) > 0) { Moods.open(ctx, m); pages.open(Page.Mood(m)) }
    }
    Spacer(Modifier.height(12.dp))
}

/**
 * A genre as a tile: its name big in the pixel face on black in a hairline frame (as ink radio's kinds are), lit if it's
 * yours. As big as its longest word fits the tile's 96 dp across, at the phone's own text size (#1: on a phone set to
 * larger text, "alternative" broke as "alternati / ve"): a Silkscreen letter is about 0.68 of its size wide, 0.72 to be safe.
 */
@Composable
private fun GenreTile(name: String, yours: Boolean, onClick: () -> Unit) {
    val scale = LocalDensity.current.fontScale
    val size = remember(name, scale) {
        val longest = name.split(' ').maxOf { it.length }.coerceAtLeast(1)
        (96f / (longest * 0.72f) / scale).coerceIn(12f, 19f).sp
    }
    Box(Modifier.width(120.dp).aspectRatio(1f).border(1.dp, if (yours) Ink.mid else Ink.line)
        .clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick)).padding(12.dp)) {
        BasicText(name.lowercase(), style = pixel(size, if (yours) Ink.ink else Ink.mid), maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}

/** A mood's page: its flavors, each a shelf of YouTube Music's playlists in the dither; a station from all of it. */
@Composable
fun MoodPage(m: TubeMood, pages: Pages) {
    val counts by Moods.counts.collectAsState()
    val shelves by produceState<List<Pair<String, List<TubeList>>>?>(null, m.params) {
        val all = withContext(Dispatchers.IO) { runCatching { YouTubeMusic.mood(m.params) }.getOrDefault(emptyList()) }
        val opened = counts.filterValues { it > 0 }.keys.joinToString(" ").lowercase()
        value = all.sortedBy { (t, _) -> Moods.REGIONAL.any { w -> w in t.lowercase() && w !in opened } }
    }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    FrostedPage(onClose = { pages.close() }) { frost, top -> LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                BasicText(m.title.uppercase(), style = headline(44.sp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText(shelves?.let { "${it.size} flavors on youtube music" } ?: "reading youtube music", style = pixel(12.sp, Ink.mid), modifier = Modifier.weight(1f))
                    Word("station", pixel(14.sp), touch = 8.dp) { scope.launch { Moods.station(m) } }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
        shelves.orEmpty().forEach { (title, lists) ->
            item(key = title) {
                Label(title.lowercase())
                Shelf(lists) { l -> Tile(null, l.name, "youtube music", face = l.art, dither = true) { pages.open(Page.TubeList(l)) } }
            }
        }
    } }
}

/** Made for you on listen: each list its four covers, its name, what it is and when it changes; a tap plays it. */
@Composable
fun MadeForYouShelf() {
    val mixes by Discover.mixes.collectAsState()
    if (mixes.isEmpty()) return
    Label("made for you")
    Shelf(mixes) { m ->
        Column(Modifier.width(150.dp).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { Playback.play(m.songs, 0) })) {
            val four = remember(m) { m.songs.distinctBy { it.albumId }.take(4) }
            Column(Modifier.fillMaxWidth().aspectRatio(1f)) {
                four.chunked(2).forEach { pair -> Row(Modifier.weight(1f)) { pair.forEach { s -> CoverOf(s, s.album.ifEmpty { s.title }, Modifier.weight(1f).aspectRatio(1f)) } } }
            }
            Spacer(Modifier.height(8.dp))
            UnderCover(m.title, m.fresh, pixel(12.sp, Ink.mid))
        }
    }
}

/** Because you loved: the love it's from, and its radio's songs you've never played. */
@Composable
fun BecauseYouLovedShelf() {
    val b by Discover.because.collectAsState()
    val (seed, songs) = b ?: return
    Row(verticalAlignment = Alignment.Bottom) {
        Label("because you loved ${seed.title.lowercase()}")
        Spacer(Modifier.weight(1f))
        Word("play all", pixel(12.sp), Modifier.padding(end = 20.dp), touch = 8.dp) { Playback.play(songs, 0) }
    }
    ShelfIndexed(songs) { i, s -> Tile(s, s.title, s.artist) { Playback.play(songs, i) } }
}

/** New to you: artists close to what you play whom you've never played, in the dither, via whom; a station of them. */
@Composable
fun NewToYouShelf(pages: Pages) {
    val list by Discover.newToYou.collectAsState()
    if (list.isEmpty()) return
    Row(verticalAlignment = Alignment.Bottom) {
        Label("new to you")
        Spacer(Modifier.weight(1f))
        Word("station", pixel(12.sp), Modifier.padding(end = 20.dp), touch = 8.dp) { Discover.station() }
    }
    Shelf(list) { n -> Tile(null, n.artist.name, "via ${n.via.lowercase()}", face = n.artist.face, dither = true) { pages.open(Page.TubeArtist(n.artist)) } }
}

/** ink's moods here ([InkMoods]): each mood's songs and its tile's cover kept for the week; its station. */
object Moodboard {
    data class Kept(val songs: List<Pair<String, String>>, val art: String?)

    val kept = MutableStateFlow<Map<String, Kept>>(emptyMap())
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private var started = false
    private fun week() = java.time.LocalDate.now().toEpochDay() / 7

    fun start(c: Context) {
        if (started) return
        started = true
        val file = File(c.cacheDir, "ink-moods.json")
        scope.launch {
            val o = runCatching { JSONObject(file.readText()) }.getOrNull()
            if (o != null) kept.value = InkMoods.ALL.mapNotNull { m ->
                val k = o.optJSONObject(m.key) ?: return@mapNotNull null
                val a = k.optJSONArray("songs") ?: return@mapNotNull null
                m.key to Kept((0 until a.length()).map { a.getJSONArray(it).let { p -> p.getString(0) to p.getString(1) } }, k.optString("art").ifEmpty { null })
            }.toMap()
            if (o?.optLong("week") == week() && kept.value.size == InkMoods.ALL.size) return@launch
            val made = HashMap<String, Kept>()
            for (m in InkMoods.ALL) {
                val songs = InkMoods.songs(m, LastFm.key, week())
                if (songs.isEmpty()) continue
                val art = songs.take(4).firstNotNullOfOrNull { (t, a) -> Curation.cover(t, a, false).also { kotlinx.coroutines.delay(400) } }
                made[m.key] = Kept(songs, art)
                kept.value = kept.value + (m.key to made.getValue(m.key))
            }
            if (made.isNotEmpty()) runCatching {
                file.writeText(JSONObject().put("week", week()).apply {
                    made.forEach { (key, k) -> put(key, JSONObject().put("art", k.art ?: "").put("songs", JSONArray(k.songs.map { JSONArray(listOf(it.first, it.second)) }))) }
                }.toString())
            }
        }
    }

    /** The mood as a station: its songs in a new order, the first few found at once, the rest after. */
    suspend fun play(m: InkMoods.Mood): Boolean {
        val songs = (kept.value[m.key]?.songs ?: withContext(Dispatchers.IO) { InkMoods.songs(m, LastFm.key, week()) }).shuffled()
        if (songs.isEmpty()) return false
        val first = find(songs.take(8))
        if (first.isEmpty()) return false
        Playback.radio(true)
        Playback.play(first, 0)
        scope.launch {
            songs.drop(8).take(40).chunked(8).forEach { group ->
                find(group).takeIf { it.isNotEmpty() }?.let { more -> withContext(Dispatchers.Main) { Playback.follow(first.first(), more) } }
            }
        }
        return true
    }

    private suspend fun find(group: List<Pair<String, String>>): List<Song> = withContext(Dispatchers.IO) {
        kotlinx.coroutines.coroutineScope { group.map { (t, a) -> async { YouTubeMusic.find(t, a) } }.map { it.await() }.filterNotNull() }
    }
}

/** Right now: ink's moods as tiles to slide through, the hour's three first and bigger, each a station. */
@Composable
fun InkMoodsShelf() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { Moodboard.start(ctx) }
    val kept by Moodboard.kept.collectAsState()
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<String?>(null) }
    fun go(m: InkMoods.Mood) = scope.launch { said = "${m.name}: finding its songs"; said = if (Moodboard.play(m)) null else "${m.name}: couldn't find its songs" }
    val now = remember { InkMoods.now() }
    // one row of tiles, right now's three bigger, then the rest (as the Mac's: the three as words and the rest as
    // tiles didn't belong together)
    Heading("right now", said ?: "moods for ${Moods.said()}, then the rest")
    Shelf(now + InkMoods.ALL.filter { it !in now }) { m ->
        val big = m in now
        Column(Modifier.width(if (big) 200.dp else 150.dp).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { go(m) })) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.BottomStart) {
                kept[m.key]?.art?.let { art -> Dithered(remember(art) { faceSong("mood " + m.key, art) }, Modifier.fillMaxSize(), px = 400) }
                // its name on a black tab in the corner (pale dithers swallowed it)
                BasicText(m.name.uppercase(), style = headline(if (big) 26.sp else 20.sp), maxLines = 2,
                    modifier = Modifier.background(Ink.black).padding(start = 6.dp, end = 8.dp, top = 4.dp, bottom = 2.dp))
            }
            Spacer(Modifier.height(8.dp))
            BasicText(m.line, style = pixel(12.sp, Ink.mid), maxLines = 2)
        }
    }
    Spacer(Modifier.height(8.dp))
}

/** ink's festivals here: the file read from the app's assets, and a festival's station (each artist's best known song in turn). */
object Fests {
    @Volatile private var all: List<Festivals.Festival>? = null
    fun all(c: Context): List<Festivals.Festival> = all ?: Festivals.read(
        runCatching { c.assets.open("ink-festivals.json").bufferedReader().use { it.readText() } }.getOrDefault("{}"),
    ).also { all = it }

    suspend fun play(f: Festivals.Festival): Boolean {
        val songs = withContext(Dispatchers.IO) {
            kotlinx.coroutines.coroutineScope {
                f.artists.take(16).map { name ->
                    async { withTimeoutOrNull(10_000) { YouTubeAccount.artist(name, "").firstOrNull { it != null } }?.top?.filter { it.tube }?.take(2).orEmpty() }
                }.map { it.await() }
            }.let { each -> (0 until 2).flatMap { i -> each.mapNotNull { it.getOrNull(i) } } }
        }
        if (songs.isEmpty()) return false
        Playback.radio(true)
        Playback.play(songs, 0)
        return true
    }
}

/** Festivals soon: one row of posters (a festival's name, when and where, its lineup as type); a tap opens the festival. */
@Composable
fun FestivalsShelf(pages: Pages) {
    val ctx = LocalContext.current
    val soon = remember { Festivals.soon(Fests.all(ctx)) }
    if (soon.isEmpty() || !YouTubeMusic.available) return
    Heading("festivals soon", "who's playing, to know before you go")
    Shelf(soon) { f -> FestivalPoster(f) { pages.open(Page.Festival(f.name)) } }
    Spacer(Modifier.height(8.dp))
}

/** A festival as its poster: when and where small, its name big, the lineup in type that shrinks down the bill, its dates. */
@Composable
private fun FestivalPoster(f: Festivals.Festival, onClick: () -> Unit) {
    Column(Modifier.width(200.dp).aspectRatio(.78f).border(1.dp, Ink.line).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick)).padding(14.dp)) {
        BasicText("${f.when_()} · ${f.city}", style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        BasicText(f.name.uppercase(), style = headline(22.sp).copy(lineHeight = 24.sp), maxLines = 2)
        Spacer(Modifier.height(10.dp))
        f.artists.take(2).forEach { BasicText(it.uppercase(), style = headline(15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Spacer(Modifier.height(3.dp))
        f.artists.drop(2).take(4).forEach { BasicText(it.uppercase(), style = headline(11.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        if (f.artists.size > 6) BasicText("and ${f.artists.size - 6} more", style = pixel(12.sp, Ink.dim), modifier = Modifier.padding(top = 3.dp))
        Spacer(Modifier.weight(1f))
        BasicText(dates(f), style = pixel(12.sp, Ink.mid))
    }
}

/** "nov 2 – 8", "nov 30 – dec 2". */
private fun dates(f: Festivals.Festival): String {
    val m = java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.ENGLISH)
    return (if (f.start.month == f.end.month) "${m.format(f.start)} – ${f.end.dayOfMonth}" else "${m.format(f.start)} – ${m.format(f.end)}").lowercase()
}

/** A festival's page: its name and when, a station, its whole lineup as faces, two across. */
@Composable
fun FestivalPage(name: String, pages: Pages) {
    val ctx = LocalContext.current
    val f = remember(name) { Fests.all(ctx).firstOrNull { it.name == name } } ?: return
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    FrostedPage(onClose = { pages.close() }) { frost, top -> LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
    ) {
        item {
            BasicText(f.name.uppercase(), style = headline(44.sp))
            BasicText("${dates(f)} · ${f.city} · ${f.when_()}", style = pixel(12.sp, Ink.mid))
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                Word("station", pixel(14.sp), touch = 8.dp) { scope.launch { said = "finding their songs"; said = if (Fests.play(f)) null else "couldn't find their songs" } }
                if (f.link.isNotBlank()) Word("the lineup", pixel(14.sp, Ink.mid), touch = 8.dp) {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(f.link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
            said?.let { BasicText(it, style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 6.dp)) }
            Spacer(Modifier.height(16.dp))
        }
        items(f.artists.chunked(2)) { pair ->
            Row(Modifier.padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                pair.forEach { n -> Box(Modifier.weight(1f)) { LineupTile(n, pages) } }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    } }
}

/** An artist on a lineup: their photo in the dither (read from YouTube Music, kept), their name; a tap opens their page. */
@Composable
private fun LineupTile(name: String, pages: Pages) {
    val info by produceState<ArtistInfo?>(null, name) { YouTubeAccount.artist(name, "").collect { if (it != null) value = it } }
    Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { pages.open(Page.Artist(info?.name ?: name)) })) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            info?.photo?.let { Dithered(remember(it) { faceSong(name, it) }, Modifier.fillMaxSize(), px = 240) } ?: NameCover(name, 160.dp)
        }
        Spacer(Modifier.height(6.dp))
        BasicText(name.uppercase(), style = headline(13.sp), maxLines = 2)
    }
}
