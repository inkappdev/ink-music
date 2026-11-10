package app.ink.music

import kotlinx.datetime.toJavaLocalDate
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.ui.draw.alpha
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
    // YouTube's cool lists read as INK opens (the shelf's far down: read only once it showed, it never was), once the
    // critics' picks are in to score them by
    val cooling by Picks.records.collectAsState()
    LaunchedEffect(cooling.isNotEmpty()) { if (cooling.isNotEmpty()) CoolLists.read(ctx) }
    val electronic by Picks.electronic.collectAsState()
    val friday by Picks.friday.collectAsState()
    LaunchedEffect(Unit) { while (true) { Picks.air(); kotlinx.coroutines.delay(60_000) } }
    val records by Picks.records.collectAsState()
    // the picks' artists tagged ahead, on wifi, so a genre's page has its picks when it opens (GenrePicks)
    LaunchedEffect(records.size) { if (records.isNotEmpty() && !Quality.metered(ctx)) GenrePicks.tag(ctx) }
    val songs by Picks.songs.collectAsState()
    val today by Picks.today.collectAsState()
    val air by Picks.onAir.collectAsState()
    val chart by Listen.indie.collectAsState()
    val before = remember(chart) { IndieChart.before(ctx) }
    val moods by produceState<List<TubeMood>?>(Moods.kept(ctx)) { value = Moods.read(ctx) ?: value }
    val scope = rememberCoroutineScope()
    // the record of the day arriving above: the page stays at its top (it had opened partway down)
    LaunchedEffect(today != null) { if (state.firstVisibleItemIndex <= 2) state.scrollToItem(0) }
    // the critics' picks in your order (seven near you, three a stretch, of every ten), each saying why
    val taste by Fresh.taste.collectAsState()
    // one of each on the page (Unique): the record of the day first, then the records, then the songs; no artist twice
    // in a row on a shelf
    val ranked = remember(records, songs, taste, today) {
        val taken = HashSet<String>().apply { today?.let { addAll(Unique.of(it)) } }
        Unique.shelf(Fresh.rank(records, taste), taken, { Unique.of(it.item) }, perArtist = true) { it.item.pick.artist }.take(30) to
            Unique.shelf(Fresh.rank(songs, taste), taken, { Unique.of(it.item) }, perArtist = true) { it.item.pick.artist }.take(20)
    }
    val rankedRecords = ranked.first
    val rankedSongs = ranked.second
    LazyColumn(state = state, contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding())) {
        // the day's issue: its number and a note on what's in it, turning at six each morning
        item(key = "issue") { Issue(records, chart, friday, air) }
        // ink weekly, the cover: ours, new each Friday
        item(key = "weekly") { InkWeeklyCover(pages) }
        today?.let { d -> item(key = "today") { RecordOfTheDay(d, pages) } }
        // the rest of the day's: a song, a station, one of your own you've never played
        item(key = "ofTheDay") { OfTheDay(songs, pages) }
        if (air.isNotEmpty()) item(key = "air") {
            // who's on, said once: "kexp, kcrw and fip", "kexp, kcrw, fip and 3 more"
            val names = air.map { it.name }.distinct()
            Heading("on the air", if (names.size <= 3) names.joinToString(", ").replace(Regex(", ([^,]*)$"), " and $1") else names.take(3).joinToString(", ") + " and ${names.size - 3} more")
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(air, key = { it.station }) { a -> AiredTile(a) }
            }
            Spacer(Modifier.height(14.dp))
        }
        item(key = "radio") { InkRadioShelf(pages) }
        item(key = "festivals") { FestivalsShelf(pages) }
        // ink's own moods (Last.fm listeners' tags), then YouTube Music's genres (its own moods gone: 2026-10-28)
        item(key = "inkmoods") { InkMoodsShelf() }
        moods?.takeIf { it.isNotEmpty() }?.let { all -> item(key = "moods") { MoodsAndGenres(all, pages) } }
        // YouTube Music's own lists, the cool ones only (CoolLists)
        item(key = "coollists") { CoolListsShelf(pages) }
        // your own music at a tempo (Mixes.yours)
        item(key = "yourmix") { YourMix() }
        if (records.isNotEmpty()) item(key = "picked") {
            Heading("the critics' records")
            ShelfIndexed(rankedRecords) { _, p -> PickTile(p.item, pages, Fresh.reason(p.item, p.stretch, taste), near = !p.stretch && taste.of(p.item.pick.artist) >= .3) }
        }
        friday?.let { w -> item(key = "friday") { OutThisFriday(w, pages) } }
        if (electronic.isNotEmpty()) item(key = "electronic") { Electronic(electronic, pages) }
        if (chart.isNotEmpty()) item(key = "artist") { ArtistOfTheWeek(chart, pages) }
        if (songs.isNotEmpty()) item(key = "songs") {
            var said by remember { mutableStateOf<String?>(null) }
            Heading("the critics' songs") {
                Word("play all", pixel(13.sp), touch = 8.dp) { scope.launch { said = "finding them"; Picks.playSongs(songs); said = null } }
            }
            said?.let { BasicText(it, style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(horizontal = 20.dp)) }
            ShelfIndexed(rankedSongs) { _, p ->
                val s = p.item
                LaunchedEffect(s) { if (Fresh.ready) Fresh.seen.shown(Fresh.key(s.pick)) }
                // a cover the read didn't find (it asks for forty), asked for as the tile comes into sight
                val art by produceState(s.art, s) { value = Picks.artOf(s, false) }
                Tile(remember(s, art) { if (art == s.art) s.face else s.copy(art = art).face }, s.pick.title, s.pick.artist.lowercase() + (Fresh.reason(s, p.stretch, taste)?.let { ", $it" } ?: "")) {
                    Fresh.tapped(Fresh.key(s.pick))
                    scope.launch { Picks.song(s.pick.title, s.pick.artist)?.let { Playback.play(listOf(it), 0) } }
                }
            }
        }
        if (chart.isNotEmpty()) {
            item(key = "chart") {
                Heading("the indie chart", "kexp, kcrw and wxpn, this week")
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

/**
 * INK's issue (2026-11-07): one a day, turning at six in the morning, numbered from ink music's first day; the date and a
 * note on what's in it (critics agreeing, the chart's top, a Friday's count, one of yours picked), so a day's visit opens
 * on something that wasn't there yesterday.
 */
@Composable
private fun Issue(records: List<Picks.Shown>, chart: List<IndieChart.Entry>, friday: Curation.Week?, air: List<Curation.Aired>) {
    val ctx = LocalContext.current
    val taste by Fresh.taste.collectAsState()
    val since by Fresh.since.collectAsState()
    // the day's line (DayNote): yours, then the world's, kept all day once the picks are in
    val note by produceState<String?>(null, records, chart, friday, taste, air.isNotEmpty()) {
        value = withContext(Dispatchers.IO) { runCatching { DayNote.of(ctx, records, chart, friday, air, Fests.all(ctx), since, taste) }.getOrNull() }
    }
    val day = remember { java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM", java.util.Locale.ENGLISH).format(Moment.issueDay().toJavaLocalDate()).lowercase() }
    Column(Modifier.padding(horizontal = 20.dp).padding(top = 2.dp, bottom = 18.dp)) {
        BasicText("no. ${Moment.issue()}   $day", style = pixel(12.sp, Ink.mid))
        note?.let { BasicText(it, style = pixel(12.sp, Ink.ink).copy(lineHeight = 20.sp), modifier = Modifier.padding(top = 6.dp)) }
    }
}

/**
 * The rest of the day's, under the record of the day: a song the critics picked (the nearest to you), a station of ink
 * radio (one of your genres when it can), and one of your own you've never played, by an artist near you. Each chosen
 * once an issue and kept all day; a line each, a tap plays.
 */
@Composable
private fun OfTheDay(songs: List<Picks.Shown>, pages: Pages) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val taste by Fresh.taste.collectAsState()
    val library by Library.songs.collectAsState()
    val song = remember(songs, taste) {
        val key = Fresh.ofTheDay(ctx, "song") { Fresh.rank(songs, taste).firstOrNull { !it.stretch }?.item?.let { Curation.key(it.pick) } }
        songs.firstOrNull { Curation.key(it.pick) == key }
    }
    val station = remember {
        val all = Stations.curated(ctx)
        val id = Fresh.ofTheDay(ctx, "station") {
            val liked = Seeds.genres.value
            val pool = all.filter { st -> liked.any { st.genre.contains(it, ignoreCase = true) } }.ifEmpty { all }
            pool.getOrNull(Moment.issue() % pool.size.coerceAtLeast(1))?.song?.id
        }
        all.firstOrNull { it.song.id == id }
    }
    val deep by androidx.compose.runtime.produceState<Song?>(null, library, taste) {
        if (library.isEmpty() || taste.empty) return@produceState
        value = withContext(Dispatchers.IO) {
            val id = Fresh.ofTheDay(ctx, "deep") {
                val heard = History.read(ctx).map { it.id }.toHashSet()
                library.filter { !it.tube && it.id !in heard && taste.of(it.albumArtist.ifBlank { it.artist }) >= .3 }
                    .shuffled(java.util.Random(Moment.issue().toLong())).firstOrNull()?.id
            }
            id?.let { Library.song(it) }
        }
    }
    // the song's cover: the feed's, else looked for as a pick's is (iTunes, the review, YouTube Music)
    val songArt by produceState(song?.art, song) { val s = song; if (value == null && s != null) value = Picks.coverFor(s.pick.title, s.pick.artist, false) ?: Picks.art(s.pick.title, s.pick.artist, s.pick.link, false) }
    if (song == null && station == null && deep == null) return
    val less by Moves.less.collectAsState()
    Heading("today")
    // three blocks side by side, each its picture square (the cover, the station's sleeve), what it is, its name
    Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 22.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        @Composable
        fun Block(i: Int, kind: String, title: String, said: String, art: @Composable (Modifier) -> Unit, go: () -> Unit) {
            Column(Modifier.weight(1f).then(if (less) Modifier else Modifier.arrive(i, kind))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = go))) {
                art(Modifier.fillMaxWidth().aspectRatio(1f))
                Spacer(Modifier.height(8.dp))
                BasicText(kind, style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText(title.uppercase(), style = headline(15.sp), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                BasicText(said, style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        var i = 0
        song?.let { s ->
            val face = remember(s, songArt) { if (songArt == s.art) s.face else s.copy(art = songArt).face }
            Block(i++, "song", s.pick.title, s.pick.artist.lowercase(), { m -> CoverOf(face, s.pick.title, m) }) {
                Fresh.tapped(Fresh.key(s.pick)); scope.launch { Picks.song(s.pick.title, s.pick.artist)?.let { Playback.play(listOf(it), 0) } }
            }
        }
        station?.let { st ->
            Block(i++, "station", st.song.title, st.city.lowercase(),
                { m -> StationCover(st.song, m) }) { Playback.play(listOf(st.song), 0) }
        }
        deep?.let { d ->
            Block(i++, "never played", d.title, d.artist.lowercase(), { m -> CoverOf(d, d.album.ifEmpty { d.title }, m) }) { Playback.play(listOf(d), 0) }
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
        // its cover: the feed's, else looked for (the record's Bandcamp or Apple page, iTunes, the review's own square
        // picture, YouTube Music): the record of the day was showing only its name
        val art by produceState(d.art, d) { value = Picks.coverFor(d.pick.title, d.pick.artist, true) ?: value ?: Picks.art(d.pick.title, d.pick.artist, d.pick.link, true) }
        val face = remember(d, art) { if (art == d.art) d.face else d.copy(art = art).face }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Box(Modifier.size(headerSide(maxWidth)).clickable(remember { MutableInteractionSource() }, indication = null) { go(false) }) {
                CoverOf(face, d.pick.title, Modifier.fillMaxSize())
            }
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
    // the song's own cover first (iTunes, Deezer, YouTube Music); a station's own picture often won't load here
    val art by produceState(a.art, a) { value = Picks.coverFor(a.title, a.artist, false) ?: value }
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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun PickTile(s: Picks.Shown, pages: Pages, reason: String? = null, near: Boolean = false, from: List<Picks.Shown>? = null) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var said by remember(s) { mutableStateOf<String?>(null) }
    // held: "not for me" offered under it a moment (a tap on it, never shown again, its artist further from you)
    var asking by remember(s) { mutableStateOf(false) }
    LaunchedEffect(asking) { if (asking) { kotlinx.coroutines.delay(4_000); asking = false } }
    LaunchedEffect(s) { if (Fresh.ready) Fresh.seen.shown(Fresh.key(s.pick)) }
    Column(Modifier.width(150.dp).combinedClickable(remember { MutableInteractionSource() }, indication = null,
        onLongClick = { asking = true }) {
        Fresh.tapped(Fresh.key(s.pick))
        // the play build has no YouTube Music: the review is all there is to open
        if (!YouTubeMusic.available) { if (s.pick.link.isNotBlank()) runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.pick.link))) } }
        // never out to the review (2026-10-29: The War on Drugs' "Who's That", a song, said "not streaming
        // yet" and sent you to Pitchfork while YouTube Music had it): a song picked plays; a record opens, and one
        // not there yet plays its song of the same name (its single) where there is one
        // a shelf to play from (a genre page's songs): this one and the ones after it
        else if (from != null && !s.pick.record) scope.launch {
            said = "finding it"
            said = if (Picks.playFrom(from)) null else "not on youtube music yet"
        }
        else scope.launch {
            said = "finding it"
            val a = if (s.pick.record) Picks.record(s.pick) else null
            val song = if (a == null) Picks.song(s.pick.title, s.pick.artist) else null
            said = if (a == null && song == null) "not on youtube music yet" else null
            a?.let { pages.open(Page.Tube(it)) } ?: song?.let { Playback.play(listOf(it), 0) }
        }
    }) {
        // no cover found when the picks were read (forty are asked for a read, and a read is twice a day: #1's INK was
        // half gray): asked for as the tile comes into sight, three at a time, YouTube Music's the last place
        val art by produceState(s.art, s) { value = Picks.artOf(s, s.pick.record) }
        CoverOf(remember(s, art) { if (art == s.art) s.face else s.copy(art = art).face }, s.pick.title, Modifier.fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(8.dp))
        UnderCover(s.pick.title, s.pick.artist, pixel(12.sp, Ink.mid))
        // a third line only when there's something to say: what's happening, or a reason worth it (2026-11-07: less
        // text); its room always kept, so every tile's as tall and the shelf under doesn't move as these scroll by
        Box {
            BasicText("i", style = pixel(12.sp), modifier = Modifier.alpha(0f))
            if (asking) Word("not for me", pixel(12.sp), touch = 6.dp) { asking = false; Fresh.notForMe(ctx, s.pick.artist, Fresh.key(s.pick)) }
            else (said ?: reason)?.let { BasicText(it, style = pixel(12.sp, if (said == null && near) Ink.ink else Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** The artist of the week: from the indie chart's top fifteen, the very famous passed over; their photo dithered, a line, play. */
/**
 * ink weekly as INK's cover (2026-10-29): its four covers in
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
        val tv = onTv()
        @Composable
        fun Covers(modifier: Modifier) = BoxWithConstraints(modifier
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { pages.open(Page.Weekly(w.week)) }),
            contentAlignment = Alignment.BottomStart) {
            Mosaic(w.songs, Modifier.size(headerSide(maxWidth)))
            // its name on its covers, on a black tab in their corner (a sleeve's label: pale covers would swallow it)
            BasicText(w.title.uppercase(), style = headline(52.sp).copy(lineHeight = 50.sp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.background(Ink.black).padding(start = 0.dp, end = 14.dp, top = 8.dp, bottom = 0.dp))
        }
        @Composable
        fun PlayRow(modifier: Modifier) = Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Word("PLAY THE WEEK", headline(20.sp), Modifier.padding(end = 24.dp), touch = 8.dp) { Playback.play(w.songs, 0) }
            Word("shuffle", pixel(14.sp, Ink.mid), Modifier.padding(end = 24.dp), touch = 8.dp) { Playback.play(w.songs.shuffled(), 0) }
            // the week as a DJ's arc, as on its page (the same mix: its progress shows in both)
            MixWord("ink weekly ${w.week}", w.songs, Modifier.padding(end = 24.dp))
            Word("${w.songs.size} songs", pixel(14.sp, Ink.mid), touch = 8.dp) { pages.open(Page.Weekly(w.week)) }
        }
        if (tv) {
            // a TV: the covers at a header's size, and beside them (where was black) the week's note, play the week and
            // its first songs, numbered, each a press to play from (2026-11-10)
            Row(Modifier.padding(horizontal = 20.dp)) {
                BoxWithConstraints(Modifier.weight(1f)) {
                    val side = headerSide(maxWidth)
                    Row {
                        Covers(Modifier.size(side))
                        Spacer(Modifier.width(32.dp))
                        Column(Modifier.weight(1f).height(side)) {
                            if (w.note.isNotEmpty()) BasicText(w.note.lowercase(), style = pixel(13.sp, Ink.ink.copy(alpha = .75f)).copy(lineHeight = 21.sp),
                                maxLines = 4, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(14.dp))
                            PlayRow(Modifier)
                            Spacer(Modifier.height(12.dp))
                            w.songs.take(6).forEachIndexed { n, song ->
                                Row(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null,
                                    onClick = felt { Playback.play(w.songs, n) }).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    BasicText("%02d".format(n + 1), style = pixel(12.sp, Ink.dim), modifier = Modifier.width(30.dp))
                                    BasicText(song.title.uppercase(), style = headline(15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    BasicText("  ${song.artist.lowercase()}", style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            val names = remember(w) { w.songs.map { it.artist.substringBefore(",").trim() }.distinct().joinToString("  ·  ") }
            Marquee(names, !less && Marquees.on.collectAsState().value, Modifier.fillMaxWidth(), size = 64.sp)
        } else {
        Covers(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
        Spacer(Modifier.height(16.dp))
        val names = remember(w) { w.songs.map { it.artist.substringBefore(",").trim() }.distinct().joinToString("  ·  ") }
        Marquee(names, !less && Marquees.on.collectAsState().value, Modifier.fillMaxWidth(), size = 64.sp)
        if (w.note.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            BasicText(w.note.lowercase(), style = pixel(13.sp, Ink.ink.copy(alpha = .75f)).copy(lineHeight = 21.sp), modifier = Modifier.padding(horizontal = 20.dp))
        }
        Spacer(Modifier.height(14.dp))
        PlayRow(Modifier.padding(horizontal = 20.dp))
        }
        Box(Modifier.padding(horizontal = 20.dp)) { MixProgress("ink weekly ${w.week}") }
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
    Heading("ink radio", "real djs") {
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
    Heading("out friday", "${w.releases.size} records") {
        Word("thread", pixel(13.sp, Ink.mid), touch = 8.dp) {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(w.thread)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
    Spacer(Modifier.height(8.dp))
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Word("all", pixel(13.sp, if (kind == null) Ink.ink else Ink.dim), touch = 8.dp) { kind = null } }
        // each kind by its name alone: the total's said once, under the heading
        items(kinds, key = { it.first }) { (k, _) -> Word(k, pixel(13.sp, if (kind == k) Ink.ink else Ink.dim), touch = 8.dp) { kind = k } }
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
 * Headphone Commute), then the tracks premiered for headphones (Inverted Audio's, Headphone Commute's).
 */
@Composable
private fun Electronic(all: List<Curation.Pick>, pages: Pages) {
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<String?>(null) }
    val records = remember(all) { Curation.fair(all.filter { it.record }) { it.from }.take(24) }
    val tracks = remember(all) { all.filter { !it.record }.take(10) }
    Heading("electronic", said)
    Shelf(records) { p ->
        val art by produceState<String?>(null, p) { value = Picks.art(p.title, p.artist, p.link, true) }
        PickTile(remember(p, art) { Picks.Shown(p.copy(art = art), art, listOf(p.from)) }, pages)
    }
    if (tracks.isNotEmpty()) {
        // named for how they sound, ambient and the edges of it, not for a club (2026-11-09)
        Heading("for headphones", "ambient and the edges of it, premiered this month") {
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
    // listeners a month (2026-10-28), with a photo to show
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
        i.photo?.let { BoxWithConstraints(Modifier.fillMaxWidth()) { Dithered(remember(it) { faceSong(i.name, it) }, Modifier.size(headerSide(maxWidth))) } }
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
    /** ink's own order for the genres (2026-10-28: alphabetical put the odd ones first): the ones you open most still lead. */
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
    // text on a phone: 2026-10-28)
    Heading("genres")
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

/**
 * A genre's page (made over 2026-11-07, Bronson: "a little bare", only YouTube Music's playlists): INK's picks in it
 * first (the critics' and curators' songs and records whose artists Last.fm's listeners tag so: [GenrePicks]), the
 * artists on those lists as words, then YouTube Music's own flavors, each a shelf of its playlists in the dither; a
 * station from all of it. A mood (not a genre), or a genre ink has no tags for, keeps YouTube's alone.
 */
@Composable
fun MoodPage(m: TubeMood, pages: Pages) {
    val ctx = LocalContext.current
    val counts by Moods.counts.collectAsState()
    // kept this run (MoodShelves): come back to the page and it's whole at once, so it opens where you'd scrolled to
    // (read again, it was short a moment and the place was lost, 2026-11-08)
    val shelves by produceState(MoodShelves.kept[m.params], m.params) {
        if (value != null) return@produceState
        val all = withContext(Dispatchers.IO) { runCatching { YouTubeMusic.mood(m.params) }.getOrDefault(emptyList()) }
        val opened = counts.filterValues { it > 0 }.keys.joinToString(" ").lowercase()
        value = all.sortedBy { (t, _) -> Moods.REGIONAL.any { w -> w in t.lowercase() && w !in opened } }.also { if (it.isNotEmpty()) MoodShelves.kept[m.params] = it }
    }
    // the genre's editors' lists (GenreSongs, the full build: Spotify's and Apple Music's public ones, 2026-11-08, in
    // place of Last.fm's tags, which put a folk song on dance & electronic); the critics' records by the lists' artists.
    // The play build, and a genre with no lists: INK's picks by their artists' tags, as before
    val listed = m.genre && GenreSongs.known(m.title)
    val listSongs by produceState(if (listed) GenreSongs.now(ctx, m.title) else emptyList(), m.title) { if (listed) value = GenreSongs.of(ctx, m.title) }
    val ours = !listed && m.genre && Genres.known(m.title)
    val tagged by GenrePicks.tagged.collectAsState()
    val records by Picks.records.collectAsState()
    val songs by Picks.songs.collectAsState()
    LaunchedEffect(ours, records.size + songs.size) { if (ours) GenrePicks.tag(ctx) }
    val picks = remember(ours, tagged, records, songs) { if (!ours) emptyList() else GenrePicks.all().filter { Genres.fits(it.pick.artist, m.title) } }
    val listArtists = remember(listSongs) { listSongs.flatMap { it.pick.artist.split(", ") }.map { Curation.folded(it.trim()) }.filter { it.length >= 2 }.toSet() }
    val pickedSongs = remember(picks, listSongs) { if (listed) listSongs.take(40) else Curation.fair(picks.filter { !it.pick.record }) { it.pick.from }.take(24) }
    val pickedRecords = remember(picks, listArtists, records) {
        if (listed) records.filter { r -> r.pick.artist.split(", ").any { Curation.folded(it.trim()) in listArtists } }.take(16)
        else Curation.fair(picks.filter { it.pick.record }) { it.pick.from }.take(16)
    }
    val artists = remember(picks, listSongs) { (if (listed) listSongs else picks).map { it.pick.artist.substringBefore(", ").trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.take(30) }
    val from = remember(picks, listSongs) { (if (listed) listSongs else picks).flatMap { it.by }.distinct() }
    val scope = rememberCoroutineScope()
    val list = pageList()
    FrostedPage(onClose = { pages.close() }) { frost, top -> LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                BasicText(m.title.uppercase(), style = headline(52.sp).copy(lineHeight = 52.sp))
                Spacer(Modifier.height(6.dp))
                BasicText(
                    listOfNotNull(
                        if (listed) listSongs.size.takeIf { it > 0 }?.let { "$it from ${from.size} editors' ${if (from.size == 1) "list" else "lists"}" }
                        else picks.size.takeIf { it > 0 }?.let { "$it picked by ${from.size} ${if (from.size == 1) "critic or curator" else "critics and curators"}" },
                        shelves?.let { "${it.size} flavors on youtube music" },
                    ).joinToString("   ").ifEmpty { "reading youtube music" },
                    style = pixel(12.sp, Ink.mid),
                )
                Spacer(Modifier.height(12.dp))
                Word("start station", pixel(14.sp), touch = 8.dp) { scope.launch { Moods.station(m) } }
                Spacer(Modifier.height(6.dp))
            }
        }
        // a genre at a tempo, mixed (Mixes): its songs at the tempo chosen, one into the next on the beat
        if (listed && YouTubeMusic.available) item(key = "mix") {
            val mix by Mixes.state.collectAsState()
            val ours = mix?.takeIf { it.title.equals(m.title, true) }
            var bpm by remember(m.title) { mutableStateOf(ours?.bpm ?: 125) }
            Column(Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(22.dp))
                BasicText("MIX IT", style = headline(24.sp))
                Spacer(Modifier.height(6.dp))
                BasicText("a tempo, and its songs at it, each into the next on the beat", style = pixel(12.sp, Ink.mid))
                Spacer(Modifier.height(10.dp))
                Row {
                    for (t in Mixes.TEMPOS)
                        Word("$t", pixel(14.sp, if (bpm == t) Ink.ink else Ink.dim), Modifier.padding(end = 18.dp), touch = 8.dp) { bpm = t }
                }
                Spacer(Modifier.height(12.dp))
                Word(if (ours != null && !ours.done && ours.bpm == bpm) "MIXING" else "START THE MIX", headline(28.sp), touch = 8.dp) { Mixes.genre(ctx, m.title, bpm) }
                ours?.let { st -> Spacer(Modifier.height(14.dp)); MixFinding(st) }
                Spacer(Modifier.height(10.dp))
            }
        }
        if (pickedSongs.isNotEmpty()) item(key = "songs") {
            Heading("picked for ${m.title.lowercase()}", if (listed) from.take(4).joinToString(", ") + if (from.size > 4) " and more" else "" else "the critics' and curators' songs, the newest first")
            // a song tapped plays the shelf from there, so what follows is the genre's too
            ShelfIndexed(pickedSongs) { i, s -> PickTile(s, pages, from = if (listed) pickedSongs.drop(i) else null) }
        }
        if (pickedRecords.isNotEmpty()) item(key = "records") {
            Heading("new records", "the ones the critics wrote about")
            Shelf(pickedRecords) { s -> PickTile(s, pages) }
        }
        if (artists.isNotEmpty()) item(key = "artists") {
            Heading("on their lists")
            // the artists as words, big: each opens their page
            androidx.compose.foundation.layout.FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                artists.forEachIndexed { i, a ->
                    Word(a.uppercase(), headline(if (i < 6) 26.sp else 18.sp, if (i < 6) Ink.ink else Ink.mid), touch = 4.dp) { pages.open(Page.Artist(a)) }
                }
            }
            Spacer(Modifier.height(10.dp))
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
    val all by Discover.mixes.collectAsState()
    if (all.isEmpty()) return
    // in the day's own order, the ones passed over a few days stepping back (Fresh): the front changes day to day
    val mixes = remember(all) {
        val day = Moment.issue().toLong()
        all.sortedByDescending { m -> (if (Fresh.ready) Fresh.seen.fresh("mix:${m.key}") else 1.0) + java.util.Random(day * 131 + m.key.hashCode()).nextDouble() * .6 }
    }
    Label("made for you")
    Shelf(mixes) { m ->
        LaunchedEffect(m.key) { if (Fresh.ready) Fresh.seen.shown("mix:${m.key}") }
        Column(Modifier.width(150.dp).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { Fresh.tapped("mix:${m.key}"); Playback.play(m.songs, 0) })) {
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
    // every tile as tall as the tallest seen (a big one, first in sight): the row is as tall as the tiles in sight,
    // so it shrank once the big three slid away and the shelves under it jumped up (2026-11-03)
    val density = androidx.compose.ui.platform.LocalDensity.current
    var tallest by remember { mutableStateOf(0.dp) }
    Shelf(now + InkMoods.ALL.filter { it !in now }) { m ->
        val big = m in now
        Column(Modifier.width(if (big) 200.dp else 150.dp).heightIn(min = tallest)
            .onSizeChanged { with(density) { it.height.toDp() }.let { h -> if (h > tallest) tallest = h } }.clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { go(m) })) {
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
    Heading("festivals soon")
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
    val (a, b) = f.start.toJavaLocalDate() to f.end.toJavaLocalDate()
    return (if (a.month == b.month) "${m.format(a)} – ${b.dayOfMonth}" else "${m.format(a)} – ${m.format(b)}").lowercase()
}

/**
 * A festival's page, set as its poster is, as the computer's (2026-11-10): where and when small, its name huge, how long
 * till it, PLAY THE LINEUP and TICKETS; the ones on it you play, said; the headliners as photo cards (their photo as it
 * is, who, how many listen, a line about them, play, their page); then the bill in type that shrinks down it, names you
 * play lit, each a tap to their page, coming in one after another.
 */
@Composable
fun FestivalPage(name: String, pages: Pages) {
    val ctx = LocalContext.current
    val f = remember(name) { Fests.all(ctx).firstOrNull { it.name == name } } ?: return
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<String?>(null) }
    val heard by Listened.now.collectAsState()
    // the artists on it you've played
    val yours = remember(f, heard) { f.artists.filter { (heard.most("a:" + it.lowercase()) ?: 0.0) > 0 } }
    // top of the bill: two, three on a long one, all of a short one
    val heads = f.artists.take(when { f.artists.size <= 3 -> f.artists.size; f.artists.size >= 9 -> 3; else -> 2 })
    val bill = f.artists.drop(heads.size)
    fun browse(url: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    val list = pageList()
    FrostedPage(onClose = { pages.close() }) { frost, top -> LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 14.dp, bottom = LocalFoot.current + 40.dp),
    ) {
        // the poster's top: where and when, the name huge; how long till it, the lineup to play, tickets
        item(key = "top") {
            Column {
                BasicText("${f.city} · ${dates(f)} · ${f.artists.size} artists".lowercase(), style = pixel(12.sp, Ink.mid))
                Spacer(Modifier.height(8.dp))
                BasicText(f.name.uppercase(), style = headline(56.sp).copy(lineHeight = 53.sp))
                Spacer(Modifier.height(16.dp))
                BasicText(f.when_().uppercase(), style = headline(32.sp))
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Word("PLAY THE LINEUP", headline(20.sp), touch = 8.dp) {
                        scope.launch { said = "finding their songs"; said = if (Fests.play(f)) null else "couldn't find their songs" }
                    }
                    // where to buy them, the festival's own site (the festivals file says where; plain links)
                    if (f.tickets.isNotBlank()) Word("TICKETS", headline(20.sp, Ink.mid), touch = 8.dp) { browse(f.tickets) }
                }
                if (f.link.isNotBlank()) Word("the lineup, announced", pixel(12.sp, Ink.mid), Modifier.padding(top = 6.dp), touch = 8.dp) { browse(f.link) }
                said?.let { BasicText(it, style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 6.dp)) }
                if (yours.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    BasicText(("you play ${if (yours.size == f.artists.size) "every one of them" else "${yours.size} of them"}: " +
                        yours.take(5).joinToString(", ") + if (yours.size > 5) " and more" else "").lowercase(), style = pixel(13.sp))
                }
                Spacer(Modifier.height(28.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line))
                Spacer(Modifier.height(24.dp))
            }
        }
        // the headliners, big
        items(heads, key = { "h:$it" }) { a -> Headliner(a, a in yours, pages, Modifier.padding(bottom = 28.dp)) }
        // the bill, set as a poster's: biggest first, shrinking down it
        if (bill.isNotEmpty()) item(key = "bill") {
            Column(Modifier.padding(top = 12.dp)) {
                BasicText("THE BILL", style = headline(16.sp, Ink.mid))
                Spacer(Modifier.height(14.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    bill.forEachIndexed { i, a ->
                        val size = when { i < 4 -> 36.sp; i < 10 -> 26.sp; else -> 18.sp }
                        BillName(a, size, i, a in yours) { pages.open(Page.Artist(a)) }
                    }
                }
            }
        }
    } }
}

/** An artist's page read from YouTube Music (kept): their photo, how many listen, what's said of them, their top songs. */
@Composable
private fun rememberArtist(name: String): ArtistInfo? =
    produceState<ArtistInfo?>(null, name) { YouTubeAccount.artist(name, "").collect { if (it != null) value = it } }.value

/** A headliner: their photo as it is (no dither, as the computer's), their name, how many listen, a line about them; play, their page. */
@Composable
private fun Headliner(name: String, played: Boolean, pages: Pages, modifier: Modifier) {
    val info = rememberArtist(name)
    val page = { pages.open(Page.Artist(info?.name ?: name)) }
    // the card and its line as one, the space between headliners after both (it was between the photo and its line)
    Column(modifier.fillMaxWidth()) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val side = maxWidth * .46f
        Row(verticalAlignment = Alignment.Bottom) {
            Box(Modifier.size(side).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = page))) {
                when {
                    info == null -> Box(Modifier.fillMaxSize().background(Ink.black))
                    info.photo != null -> CoverOf(remember(info.photo) { faceSong(name, info.photo, photo = true) }, name, Modifier.fillMaxSize(), px = 480)
                    else -> NameCover(name, side)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                if (played) BasicText("you play them", style = pixel(11.sp))
                BasicText(name.uppercase(), style = headline(26.sp).copy(lineHeight = 26.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                info?.audience?.takeIf { it.isNotEmpty() }?.let { BasicText(it.lowercase().replace("monthly audience", "a month"), style = pixel(11.sp, Ink.mid)) }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    info?.top?.takeIf { it.isNotEmpty() }?.let { top -> Word("play", pixel(13.sp), touch = 8.dp) { Playback.play(top, 0) } }
                    Word("their page", pixel(13.sp, Ink.mid), touch = 8.dp, onClick = page)
                }
            }
        }
    }
    // a line about them under the card, where the phone has its width
    info?.let { i -> remember(i.about) { pullQuote(i.about).first } }?.let {
        BasicText(it.lowercase(), style = pixel(12.sp, Ink.ink.copy(alpha = .75f)).copy(lineHeight = 18.sp), maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp))
    }
    }
}

/** A name on the bill: lit if you play them; rising in, in turn; a tap to their page. */
@Composable
private fun BillName(name: String, size: androidx.compose.ui.unit.TextUnit, order: Int, yours: Boolean, onClick: () -> Unit) {
    val shown = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        if (Moves.less.value) shown.snapTo(1f)
        else { kotlinx.coroutines.delay(order * 35L); shown.animateTo(1f, androidx.compose.animation.core.tween(Motion.ENTER, easing = Motion.expo)) }
    }
    BasicText(name.uppercase(), style = headline(size, if (yours) Ink.ink else Ink.mid).copy(lineHeight = size),
        modifier = Modifier.graphicsLayer { alpha = shown.value; translationY = (1f - shown.value) * Motion.drift.toPx() * .6f }
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick)))
}


/**
 * A mix being found (Mixes): its tempo big, beating at itself (the four squares stepping under it, bar one the
 * brightest), and the songs found dropping in as small covers, the newest first; how far it's read, small. Less motion:
 * still.
 */
@Composable
internal fun MixFinding(st: Mixes.State) {
    val less by Moves.less.collectAsState()
    // a list in order has no tempo of its own: it beats at 120 while its songs are read
    val period = 60_000.0 / (if (st.bpm > 0) st.bpm else 120)
    var t by remember { mutableStateOf(0L) }
    LaunchedEffect(st.bpm, less, st.done) {
        if (less) return@LaunchedEffect
        val t0 = android.os.SystemClock.elapsedRealtime()
        while (true) androidx.compose.runtime.withFrameMillis { t = android.os.SystemClock.elapsedRealtime() - t0 }
    }
    val beats = t / period
    val step = (Math.floor(beats).toLong() % 4).toInt()
    val within = (beats - Math.floor(beats)).toFloat()
    val pulse = if (less) 0f else 1f - within
    Row(verticalAlignment = Alignment.Bottom) {
        BasicText(if (st.bpm > 0) "${st.bpm}" else "MIX", style = headline(64.sp, androidx.compose.ui.graphics.lerp(Ink.mid, Ink.ink, pulse)).copy(lineHeight = 64.sp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.padding(bottom = 10.dp)) {
            androidx.compose.foundation.Canvas(Modifier.width(52.dp).height(7.dp)) {
                val sq = 7.dp.toPx(); val gap = (size.width - 4 * sq) / 3
                for (i in 0 until 4) {
                    val lit = if (less) 0f else if (i == step) 1f - within * .7f else 0f
                    drawRect(androidx.compose.ui.graphics.lerp(if (i == 0) Ink.mid else Ink.dim, Ink.ink, lit),
                        androidx.compose.ui.geometry.Offset(i * (sq + gap), 0f), androidx.compose.ui.geometry.Size(sq, sq))
                }
            }
            Spacer(Modifier.height(6.dp))
            BasicText(if (st.bpm > 0) "BPM" else "IN STEP", style = pixel(12.sp, Ink.mid))
        }
    }
    Spacer(Modifier.height(12.dp))
    // the songs found, the newest coming in at the front
    if (st.songs.isNotEmpty()) {
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), userScrollEnabled = false) {
            items(st.songs.reversed().take(12), key = { it.id }) { s ->
                Box(Modifier.size(44.dp).then(if (less) Modifier else Modifier.animateItem())) { CoverOf(s, s.title, Modifier.fillMaxSize(), px = 160) }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
    BasicText(
        when {
            st.bpm == 0 && st.done -> "${st.found} of ${st.of} in step, slowest to fastest, playing"
            st.bpm == 0 -> if (st.playing) "playing, building the set: ${st.read} of ${st.of} read" else "reading tempos: ${st.read} of ${st.of}"
            st.done && st.found == 0 -> "none at ${st.bpm} bpm here. another tempo?"
            st.done -> "${st.found} songs at ${st.bpm} bpm, mixing"
            st.playing -> "${st.found} found, playing. reading ${st.read} of ${st.of}"
            else -> "listening for ${st.bpm}: ${st.read} of ${st.of} read"
        },
        style = pixel(12.sp, Ink.mid),
    )
}

/** "mix" beside play and shuffle (a list, your loved songs): played in a DJ's order by tempo and key (Mixes.list). */
@Composable
internal fun MixWord(title: String, songs: List<Song>?, modifier: Modifier = Modifier) {
    if (songs.isNullOrEmpty() || songs.size < 3) return
    val ctx = LocalContext.current
    val st by Mixes.state.collectAsState()
    val going = st?.let { it.title == title && it.bpm == 0 && !it.done } == true
    // "mix" opens into its shapes, the last one chosen lit; one tapped starts it (2026-11-10, as Prism's DJ mode offers)
    var choosing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { Mixes.loadShape(ctx) }
    if (going || !choosing) Word(if (going) "mixing" else "mix", pixel(14.sp, if (going) Ink.ink else Ink.mid), modifier, touch = 8.dp) { if (!going) choosing = true }
    else Row(modifier) {
        Mixes.Shape.entries.forEach { sh ->
            Word(sh.word, pixel(14.sp, if (sh == Mixes.shape) Ink.ink else Ink.mid), Modifier.padding(end = 18.dp), touch = 8.dp) {
                choosing = false; Mixes.shape(ctx, sh); Mixes.list(ctx, title, songs, sh)
            }
        }
    }
}

/** Under the words while a list is being mixed: the songs read so far, in step (MixFinding). */
@Composable
internal fun MixProgress(title: String) {
    val st by Mixes.state.collectAsState()
    val s = st?.takeIf { it.title == title && it.bpm == 0 } ?: return
    Column(Modifier.padding(top = 14.dp)) { MixFinding(s) }
}


/** Your own music at a tempo (Mixes.yours): a tempo chosen, your songs at it, each into the next on the beat. */
@Composable
private fun YourMix() {
    val ctx = LocalContext.current
    // everything you've touched (Mixes.yours): shown once there's enough of it
    val songs by Library.songs.collectAsState()
    val liked by YouTubeAccount.liked.collectAsState()
    val listened by Listened.now.collectAsState()
    if (remember(songs.size, liked.size, listened) { Mixes.yoursSize() } < 20) return
    val st by Mixes.state.collectAsState()
    val ours = st?.takeIf { it.title == "your music" }
    var bpm by remember { mutableStateOf(ours?.bpm ?: 120) }
    Heading("mix your music", "what you play, love and keep, at a tempo, each into the next on the beat")
    Column(Modifier.padding(horizontal = 20.dp)) {
        Row {
            for (t in Mixes.TEMPOS)
                Word("$t", pixel(14.sp, if (bpm == t) Ink.ink else Ink.dim), Modifier.padding(end = 18.dp), touch = 8.dp) { bpm = t }
        }
        Spacer(Modifier.height(12.dp))
        Word(if (ours != null && !ours.done && ours.bpm == bpm) "MIXING" else "START THE MIX", headline(28.sp), touch = 8.dp) { Mixes.yours(ctx, bpm) }
        ours?.let { Spacer(Modifier.height(14.dp)); MixFinding(it) }
        Spacer(Modifier.height(24.dp))
    }
}


/** Each genre's and mood's shelves of YouTube Music's lists, kept while ink music runs (the page whole when come back to). */
private object MoodShelves { val kept = java.util.concurrent.ConcurrentHashMap<String, List<Pair<String, List<TubeList>>>>() }


/** YouTube Music's own playlists with your artists, or the critics', in them, not the charts' (CoolLists); each says why. */
@Composable
private fun CoolListsShelf(pages: Pages) {
    val ctx = LocalContext.current
    val lists by CoolLists.lists.collectAsState()
    if (lists.isEmpty()) return
    Heading("playlists", "youtube music's own, the ones with your artists in them")
    Shelf(lists) { l -> Tile(null, l.list.name, l.why, face = l.list.art, dither = true) { pages.open(Page.TubeList(l.list)) } }
    Spacer(Modifier.height(12.dp))
}
