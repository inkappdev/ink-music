package app.ink.music

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Radio, a place of its own (the Mac's, here; 2026-10-29): the tower sending across the top ([RadioTower], its rings in
 * the colour of the station on), what's on and tune in anywhere. Then (2026-11-17, a listener on Reddit: "thousands more
 * from radio-browser.info, and yet I can't find a way to add any"; the computer's): a search for a station by its name,
 * its genre or its place, or a stream's own link pasted; genres, places (near you first; a country, then its regions) and
 * an order (most played, trending now, most liked); yours (kept, a long press) and ink radio's when none is chosen;
 * forty at a time, more at the end.
 */
@Composable
fun RadioPlace(pages: Pages, pad: PaddingValues, grid: LazyGridState) {
    val ctx = LocalContext.current
    val ours = remember { Stations.curated(ctx).sortedByDescending { it.star } }
    LaunchedEffect(Unit) { KeptStations.load(ctx) }
    val kept by KeptStations.all.collectAsState()
    val query by RadioAsk.query.collectAsState()
    val asked by RadioAsk.asked.collectAsState()
    var genre by remember { mutableStateOf<RadioBrowser.Tag?>(null) }
    var country by remember { mutableStateOf<RadioBrowser.Country?>(null) }
    var region by remember { mutableStateOf<String?>(null) }
    var order by remember { mutableStateOf(RadioBrowser.Order.PLAYED) }
    var pagesRead by remember(genre, country, region, order) { mutableIntStateOf(1) }
    val typed = Search.online(query)
    val searching = Search.enough(typed)
    val stream = RadioBrowser.isStream(typed)
    val browsing = !searching && (genre != null || country != null)
    val countries by produceState(emptyList<RadioBrowser.Country>()) { value = withContext(Dispatchers.IO) { RadioBrowser.countries() } }
    val home = remember(countries) { countries.firstOrNull { it.code.equals(java.util.Locale.getDefault().country, true) } }
    val places = remember(countries, home) { listOfNotNull(home) + countries.filter { it != home }.take(40) }
    val regions by produceState(emptyList<RadioBrowser.State>(), country) {
        value = country?.let { c -> withContext(Dispatchers.IO) { RadioBrowser.states(c.code) } }.orEmpty()
    }
    val ask = RadioBrowser.Ask(tag = genre, code = country?.code, state = region, order = order)
    var more by remember { mutableStateOf(false) }
    val browsed by produceState<List<Pair<Song, String>>?>(null, ask, pagesRead, browsing) {
        if (!browsing) { value = null; return@produceState }
        if (pagesRead == 1) { value = null; more = false }
        value = withContext(Dispatchers.IO) {
            val g = genre
            val mine = if (g != null && country == null) ours.filter { c -> g.variants.any { v -> v in c.genre.lowercase() } }.map { it.song to it.city } else emptyList()
            val b = RadioBrowser.browse(ask, pagesRead, 40, Stations.ours(ctx))
            more = b.more
            mine + b.stations.distinctBy { it.id }.map { Stations.of(it) to it.said }
        }
    }
    val found by produceState<Pair<List<Song>, RadioBrowser.Found>?>(null, typed, asked) {
        if (!searching || stream) { value = null; return@produceState }
        delay(300)
        value = Stations.look(ctx, typed)
    }
    val pastedSong = remember(typed, stream) { if (stream) Stations.of(RadioBrowser.pasted(typed)) else null }
    // NTS's infinite mixtapes: as kept at once, then read again once a day
    val mixtapes by produceState(remember { Stations.mixtapes(ctx) }) { value = Stations.freshMixtapes(ctx).ifEmpty { value } }
    val now by Playback.now.collectAsState()
    LazyVerticalGrid(GridCells.Adaptive(150.dp), state = grid, contentPadding = pad,
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "head") { RadioHead(ours) }
        item(span = { GridItemSpan(maxLineSpan) }, key = "find") { StationField(query) }
        if (!searching) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "kinds") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    item { Word("ink radio", pixel(13.sp, if (genre == null && country == null) Ink.ink else Ink.dim), touch = 8.dp) { genre = null; country = null; region = null } }
                    items(RadioBrowser.GENRES, key = { it.word }) { g -> Word(g.word, pixel(13.sp, if (genre == g) Ink.ink else Ink.dim), touch = 8.dp) { genre = if (genre == g) null else g } }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "places") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    item { BasicText("in", style = pixel(13.sp, Ink.mid)) }
                    item { Word("anywhere", pixel(13.sp, if (country == null) Ink.ink else Ink.dim), touch = 8.dp) { country = null; region = null } }
                    items(places, key = { it.code }) { c ->
                        Word(if (c == home) "${c.short} (near you)" else c.short, pixel(13.sp, if (country == c) Ink.ink else Ink.dim), touch = 8.dp) { country = if (country == c) null else c; region = null }
                    }
                }
            }
            val inCountry = country
            if (inCountry != null && regions.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "regions") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    item { Word("all of ${inCountry.short}", pixel(13.sp, if (region == null) Ink.ink else Ink.dim), touch = 8.dp) { region = null } }
                    items(regions, key = { it.name }) { r -> Word(r.name.lowercase(), pixel(13.sp, if (region == r.name) Ink.ink else Ink.dim), touch = 8.dp) { region = if (region == r.name) null else r.name } }
                }
            }
            if (browsing) item(span = { GridItemSpan(maxLineSpan) }, key = "order") {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    RadioBrowser.Order.entries.forEach { o -> Word(o.word, pixel(13.sp, if (order == o) Ink.ink else Ink.dim), touch = 8.dp) { order = o } }
                }
            }
        }
        fun label(key: String, text: String) = item(span = { GridItemSpan(maxLineSpan) }, key = key) { BasicText(text, style = pixel(12.sp, Ink.mid)) }
        fun tiles(shown: List<Pair<Song, String>>, part: String) = itemsIndexed(shown, key = { _, (s, _) -> part + s.id }) { i, (s, where) ->
            val on = now?.song?.id == s.id
            val has = kept.any { it.song.id == s.id }
            KeepableStation(s, on, now?.playing == true, if (on) "on now" else listOfNotNull(where.ifEmpty { null }, "kept".takeIf { has }).joinToString(" · "),
                onKeep = { KeptStations.toggle(ctx, s, where) }) { Playback.play(shown.drop(i).map { it.first }, 0) }
        }
        when {
            stream && pastedSong != null -> {
                label("l-link", "your link")
                tiles(listOf(pastedSong to "your link"), "u")
                item(span = { GridItemSpan(maxLineSpan) }, key = "linkwords") {
                    val has = kept.any { it.song.id == pastedSong.id }
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Word("PLAY", headline(14.sp), touch = 8.dp) { Playback.play(listOf(pastedSong), 0) }
                        Word(if (has) "KEPT" else "KEEP", headline(14.sp, if (has) Ink.ink else Ink.mid), touch = 8.dp) { KeptStations.toggle(ctx, pastedSong, "your link") }
                    }
                }
            }
            searching -> {
                val f = found
                if (f == null) label("finding", "finding stations")
                else {
                    val (mine, theirs) = f
                    if (mine.isEmpty() && theirs.all.isEmpty()) label("none", "no stations called that, of that genre or there")
                    if (mine.isNotEmpty()) { label("l-ink", "ink radio"); tiles(mine.map { s -> s to (ours.firstOrNull { it.song.id == s.id }?.city ?: "") }, "i") }
                    if (theirs.byName.isNotEmpty()) { label("l-name", "by name"); tiles(theirs.byName.map { Stations.of(it) to it.said }, "n") }
                    if (theirs.tagged.isNotEmpty()) { label("l-tag", "tagged ${typed.lowercase()}"); tiles(theirs.tagged.map { Stations.of(it) to it.said }, "t") }
                    if (theirs.placed.isNotEmpty()) { label("l-place", "in ${theirs.place?.lowercase() ?: typed.lowercase()}"); tiles(theirs.placed.map { Stations.of(it) to it.said }, "p") }
                }
            }
            browsing -> {
                val b = browsed
                if (b == null) label("finding", "finding stations")
                else if (b.isEmpty()) label("none", "none here yet")
                else {
                    tiles(b, "b")
                    if (more) item(span = { GridItemSpan(maxLineSpan) }, key = "more") { Word("more", pixel(14.sp, Ink.mid), touch = 10.dp) { pagesRead++ } }
                }
            }
            else -> {
                if (kept.isNotEmpty()) { label("l-yours", "yours · a long press lets one go"); tiles(kept.map { it.song to it.said }, "k") }
                else label("l-hint", "a long press on any station keeps it here as yours")
                label("l-ink", "ink radio")
                tiles(ours.map { it.song to it.city }, "c")
                if (mixtapes.isNotEmpty()) { label("l-nts", "nts mixtapes · endless, themed, from nts"); tiles(mixtapes, "m") }
            }
        }
    }
}

/** A station's tile: a tap plays, a long press keeps it as yours or lets it go (felt). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun KeepableStation(s: Song, on: Boolean, moving: Boolean, said: String, onKeep: () -> Unit, onClick: () -> Unit) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Column(Modifier.combinedClickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null,
        onLongClick = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); onKeep() }, onClick = onClick)) {
        StationCover(s, Modifier.fillMaxWidth().aspectRatio(1f), moving = on && moving)
        Spacer(Modifier.height(8.dp))
        BasicText(s.title.uppercase(), style = headline(15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicText(said.lowercase(), style = pixel(12.sp, if (on) Ink.ink else Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "find a station >" and what's typed: a name, a genre, a place, or a stream's link; the keyboard's search looks again. */
@Composable
private fun StationField(query: String) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText("find a station > ", style = pixel(14.sp, Ink.mid))
            androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                if (query.isEmpty()) BasicText("name, genre, place, link", style = pixel(14.sp, Ink.dim), maxLines = 1)
                androidx.compose.foundation.text.BasicTextField(
                    query, { RadioAsk.query.value = it }, singleLine = true, textStyle = pixel(14.sp), cursorBrush = androidx.compose.ui.graphics.SolidColor(Ink.ink),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { RadioAsk.asked.value++ }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) Word("clear", pixel(12.sp, Ink.mid), touch = 10.dp) { RadioAsk.query.value = "" }
        }
        Spacer(Modifier.height(8.dp))
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line))
    }
}

/** The tower sending (in the colour of the station on), and under it what's on, or how many stations ink keeps; tune in anywhere. */
@Composable
private fun RadioHead(ours: List<Stations.Curated>) {
    val now by Playback.now.collectAsState()
    val station = now?.song?.takeIf { it.station }
    val moving = station != null && now?.playing == true && !Moves.less.collectAsState().value
    Column {
        BoxWithConstraints(Modifier.fillMaxWidth().height(240.dp)) {
            val density = LocalDensity.current.density
            val w = (maxWidth.value / 3).toInt().coerceAtLeast(40); val h = 80
            val dot = station?.let { StationSleeve.colorOf(it.id) } ?: QUIET
            val frames by produceState<List<ImageBitmap>?>(null, w, h, moving, dot) {
                value = withContext(Dispatchers.Default) {
                    (0 until if (moving) RadioTower.FRAMES else 1).map {
                        Bitmap.createBitmap(RadioTower.pixels(w, h, .5f, it, Ink.ink.toArgb(), dot), w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
                    }
                }
            }
            var frame by remember { mutableIntStateOf(0) }
            LaunchedEffect(moving, frames) {
                val count = frames?.size ?: return@LaunchedEffect
                while (moving && count > 1) { delay(220); frame = (frame + 1) % count }
            }
            // three taps on the tower: lightning off its mast, and a station by chance (an easter egg)
            var taps by remember { mutableIntStateOf(0) }
            var lastTap by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
            var struck by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
            var flash by remember { mutableStateOf(false) }
            val bolt by produceState<ImageBitmap?>(null, w, h, dot) {
                value = withContext(Dispatchers.Default) { Bitmap.createBitmap(RadioTower.pixels(w, h, .5f, 0, Ink.ink.toArgb(), dot, bolts = true), w, h, Bitmap.Config.ARGB_8888).asImageBitmap() }
            }
            LaunchedEffect(struck) {
                if (struck == 0L) return@LaunchedEffect
                repeat(5) { flash = true; delay(90); flash = false; delay(70) }
                ours.filter { it.song.id != station?.id }.randomOrNull()?.let { Playback.play(listOf(it.song), 0) }
            }
            val tower = Modifier.fillMaxSize().clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {
                val t = System.currentTimeMillis()
                taps = if (t - lastTap < 500) taps + 1 else 1
                lastTap = t
                if (taps >= 3) { taps = 0; struck = t }
            }
            (if (flash) bolt else frames?.let { f -> f[frame % f.size] })?.let { Image(it, null, tower, contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.None) }
        }
        Spacer(Modifier.height(14.dp))
        if (station != null) {
            BasicText(if (now?.playing == true) "ON THE AIR" else "TUNED TO", style = headline(14.sp, Ink.mid))
            BasicText(station.title.uppercase(), style = headline(40.sp).copy(lineHeight = 40.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            val n = now
            val playing = n?.takeIf { it.title.isNotEmpty() && !it.title.equals(station.title, true) }?.let { listOf(it.title, it.artist).filter { s -> s.isNotEmpty() }.joinToString(" · ") }
            BasicText((playing ?: station.artist).lowercase(), style = pixel(13.sp, Ink.mid), maxLines = 2, overflow = TextOverflow.Ellipsis)
        } else {
            BasicText("${ours.size} stations ink keeps, real djs from seattle to accra, and every genre's", style = pixel(13.sp, Ink.mid))
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Word("TUNE IN ANYWHERE", headline(20.sp), Modifier.padding(end = 24.dp), touch = 8.dp) {
                ours.filter { it.song.id != station?.id }.randomOrNull()?.let { Playback.play(listOf(it.song), 0) }
            }
            if (station != null) Word(if (now?.playing == true) "pause" else "play", pixel(14.sp, Ink.mid), touch = 8.dp) { Playback.toggle() }
        }
    }
}

/** Ink at 55% over black, opaque: the tower's rings with no station on. */
private val QUIET: Int = Ink.ink.toArgb().let { c ->
    fun k(v: Int) = (v * .55f).toInt()
    (255 shl 24) or (k(c shr 16 and 255) shl 16) or (k(c shr 8 and 255) shl 8) or k(c and 255)
}
