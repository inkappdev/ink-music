package app.ink.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/** What the radio tab looks for, asked from elsewhere too (find's "more stations": the tab opened, its words typed). */
object RadioAsk {
    val query = MutableStateFlow("")
    /** Asked again (enter, or find's more): the same words looked for afresh. */
    val asked = MutableStateFlow(0)
    fun look(q: String) { query.value = q; asked.value++; Nav.go("radio") }
}

/** A station as shown: the song that plays it and what's said under it. */
private data class Shown(val song: Song, val said: String)

private fun RadioBrowser.Station.shown() = Shown(Stations.of(this), said)

/**
 * Radio (2026-11-17, a listener on Reddit: "thousands more from radio-browser.info, and yet I can't find a way to add
 * any"): yours (the stations kept) and ink radio (chosen by hand, the stars first), then all of radio-browser to look
 * through. A search for a station by its name, its genre or its place, or a stream's own link pasted to play and keep;
 * genres, places (near you first; a country, then its regions) and an order (most played, trending now, most liked), a
 * genre and a place together; forty at a time, more at the end. A click plays the station and the ones after it; a
 * right click keeps it as yours (or lets it go) or copies its stream's link.
 */
@Composable
fun RadioTab() {
    val ink = remember { Stations.curated().sortedByDescending { it.star } }
    val kept by KeptStations.all.collectAsState()
    val query by RadioAsk.query.collectAsState()
    val asked by RadioAsk.asked.collectAsState()
    var genre by remember { mutableStateOf<RadioBrowser.Tag?>(null) }
    var country by remember { mutableStateOf<RadioBrowser.Country?>(null) }
    var region by remember { mutableStateOf<String?>(null) }
    var order by remember { mutableStateOf(RadioBrowser.Order.PLAYED) }
    var pages by remember(genre, country, region, order) { mutableStateOf(1) }
    val typed = Search.online(query)
    val searching = Search.enough(typed)
    val stream = RadioBrowser.isStream(typed)
    val browsing = !searching && (genre != null || country != null)

    // the places: near you first (the computer's region), then the rest by how many stations
    val countries by produceState(emptyList<RadioBrowser.Country>()) { value = withContext(Dispatchers.IO) { RadioBrowser.countries() } }
    val home = remember(countries) { countries.firstOrNull { it.code.equals(java.util.Locale.getDefault().country, true) } }
    val places = remember(countries, home) { listOfNotNull(home) + countries.filter { it != home }.take(40) }
    val regions by produceState(emptyList<RadioBrowser.State>(), country) {
        value = country?.let { c -> withContext(Dispatchers.IO) { RadioBrowser.states(c.code) } }.orEmpty()
    }
    // a genre and a place, in the order chosen, page after page (ink radio's own of the genre first, anywhere)
    val ask = RadioBrowser.Ask(tag = genre, code = country?.code, state = region, order = order)
    var more by remember { mutableStateOf(false) }
    val browsed by produceState<List<Shown>?>(null, ask, pages, browsing) {
        if (!browsing) { value = null; return@produceState }
        if (pages == 1) { value = null; more = false }
        value = withContext(Dispatchers.IO) {
            val ours = if (genre != null && country == null) ink.filter { c -> genre!!.variants.any { v -> v in c.genre.lowercase() } }.map { Shown(it.song, "${it.city.lowercase()} · ink radio") } else emptyList()
            val b = RadioBrowser.browse(ask, pages, 40, Stations.ours())
            more = b.more
            ours + b.stations.distinctBy { it.id }.map { it.shown() }
        }
    }
    // what's typed, every way (asked once the typing rests, or at once on enter)
    val found by produceState<Pair<List<Song>, RadioBrowser.Found>?>(null, typed, asked) {
        if (!searching || stream) { value = null; return@produceState }
        kotlinx.coroutines.delay(300)
        value = Stations.look(typed)
    }

    // a stream's own link, as a station
    val pastedSong = remember(typed, stream) { if (stream) Stations.of(RadioBrowser.pasted(typed)) else null }
    val grid = pageGrid("radio")
    LazyVerticalGrid(
        GridCells.Adaptive(176.dp), Modifier.fillMaxSize().feather(grid), grid,
        contentPadding = PaddingValues(start = SIDE, end = SIDE, top = 6.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // the tower sending, what's on
        wide("head") { RadioHead() }
        wide("find") { StationField(query) }
        if (!searching) {
            // genres, places, the order: one row each, slid through; lit is chosen, a second click lets it go
            wide("genres") {
                Shelf(gap = 18.dp) {
                    item { Word("ink radio", pixel(13.sp, if (genre == null && country == null) Ink.ink else Ink.dim), touch = 8.dp) { genre = null; country = null; region = null } }
                    RadioBrowser.GENRES.forEach { g ->
                        item { Word(g.word, pixel(13.sp, if (genre == g) Ink.ink else Ink.dim), touch = 8.dp) { genre = if (genre == g) null else g } }
                    }
                }
            }
            wide("places") {
                Shelf(gap = 18.dp) {
                    item { BasicText("in", style = pixel(13.sp, Ink.mid), modifier = Modifier.padding(top = 8.dp)) }
                    item { Word("anywhere", pixel(13.sp, if (country == null) Ink.ink else Ink.dim), touch = 8.dp) { country = null; region = null } }
                    places.forEach { c ->
                        item { Word(if (c == home) "${c.short} (near you)" else c.short, pixel(13.sp, if (country == c) Ink.ink else Ink.dim), touch = 8.dp) {
                            country = if (country == c) null else c; region = null
                        } }
                    }
                }
            }
            if (country != null && regions.isNotEmpty()) wide("regions") {
                Shelf(gap = 18.dp) {
                    item { Word("all of ${country!!.short}", pixel(13.sp, if (region == null) Ink.ink else Ink.dim), touch = 8.dp) { region = null } }
                    regions.forEach { r -> item { Word(r.name.lowercase(), pixel(13.sp, if (region == r.name) Ink.ink else Ink.dim), touch = 8.dp) { region = if (region == r.name) null else r.name } } }
                }
            }
            if (browsing) wide("order") {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    RadioBrowser.Order.entries.forEach { o -> Word(o.word, pixel(13.sp, if (order == o) Ink.ink else Ink.dim), touch = 8.dp) { order = o } }
                }
            }
        }
        when {
            // a stream's own link: played, kept
            stream -> {
                val s = pastedSong!!
                wide("streamsaid") { Label("your link") }
                stations(listOf(Shown(s, "your link")), "u")
                wide("streamwords") {
                    val has = kept.any { it.song.id == s.id }
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Word("PLAY", headline(14.sp), touch = 8.dp) { Player.play(listOf(s)) }
                        Word(if (has) "KEPT" else "KEEP", headline(14.sp, if (has) Ink.ink else Ink.mid), touch = 8.dp) { KeptStations.toggle(s, "your link") }
                    }
                }
            }
            searching -> {
                val f = found
                if (f == null) wide("finding") { BasicText("finding stations", style = pixel(12.sp, Ink.mid)) }
                else {
                    val (mine, theirs) = f
                    if (mine.isEmpty() && theirs.all.isEmpty()) wide("none") { BasicText("no stations called that, of that genre or there", style = pixel(12.sp, Ink.mid)) }
                    if (mine.isNotEmpty()) { wide("l-ink") { Label("ink radio") }; stations(mine.map { s -> Shown(s, ink.firstOrNull { it.song.id == s.id }?.city?.lowercase().orEmpty()) }, "i") }
                    if (theirs.byName.isNotEmpty()) { wide("l-name") { Label("by name") }; stations(theirs.byName.map { it.shown() }, "n") }
                    if (theirs.tagged.isNotEmpty()) { wide("l-tag") { Label("tagged ${typed.lowercase()}") }; stations(theirs.tagged.map { it.shown() }, "t") }
                    if (theirs.placed.isNotEmpty()) { wide("l-place") { Label("in ${theirs.place?.lowercase() ?: typed.lowercase()}") }; stations(theirs.placed.map { it.shown() }, "p") }
                }
            }
            browsing -> {
                val b = browsed
                if (b == null) wide("finding") { BasicText("finding stations", style = pixel(12.sp, Ink.mid)) }
                else if (b.isEmpty()) wide("none") { BasicText("none here yet", style = pixel(12.sp, Ink.mid)) }
                else {
                    stations(b, "b")
                    if (more) wide("more") { Word("more", pixel(14.sp, Ink.mid), touch = 10.dp) { pages++ } }
                }
            }
            else -> {
                // yours, then ink radio
                if (kept.isNotEmpty()) { wide("l-yours") { Label("yours") }; stations(kept.map { Shown(it.song, it.said) }, "k") }
                wide("l-ink") { Label("ink radio") }
                stations(ink.map { Shown(it.song, "${it.city.lowercase()} · ${it.line.lowercase()}") }, "c")
            }
        }
    }
}

private fun LazyGridScope.wide(key: String, content: @Composable () -> Unit) = item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }

/** Stations as tiles: a click plays it and the ones after it; a right click keeps it (or lets it go) or copies its link. */
private fun LazyGridScope.stations(shown: List<Shown>, part: String) {
    itemsIndexed(shown, key = { _, x -> part + x.song.id }) { i, x ->
        val kept by KeptStations.all.collectAsState()
        val has = kept.any { it.song.id == x.song.id }
        WithMenu({ listOf(
            listOf("play" to { Player.play(shown.drop(i).map { it.song }) }),
            listOf((if (has) "let go of it" else "keep as yours") to { KeptStations.toggle(x.song, x.said) }, "copy stream link" to { copyText(x.song.path) }),
        ) }) {
            Column(Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { Player.play(shown.drop(i).map { it.song }) })) {
                StationCover(x.song, Modifier.fillMaxWidth().aspectRatio(1f))
                Spacer(Modifier.height(8.dp))
                BasicText(x.song.title.lowercase(), style = pixel(13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText(listOfNotNull(x.said.ifEmpty { null }, "kept".takeIf { has }).joinToString(" · "), style = pixel(12.sp, Ink.mid), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** "find a station >" and what's typed: a name, a genre, a place, or a stream's link; enter looks again, esc clears. */
@Composable
private fun StationField(query: String) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText("find a station > ", style = pixel(14.sp, Ink.mid))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) BasicText("a name, a genre, a place, or a stream's link", style = pixel(14.sp, Ink.dim))
                BasicTextField(
                    query, { RadioAsk.query.value = it }, singleLine = true, textStyle = pixel(14.sp), cursorBrush = SolidColor(Ink.ink),
                    modifier = Modifier.fillMaxWidth().typing().onPreviewKeyEvent { k ->
                        if (k.type != KeyEventType.KeyDown) false
                        else if (k.key == Key.Enter) { RadioAsk.asked.value++; true }
                        else if (k.key == Key.Escape && query.isNotEmpty()) { RadioAsk.query.value = ""; true }
                        else false
                    },
                )
            }
            if (query.isNotEmpty()) Word("clear", pixel(12.sp, Ink.mid), touch = 8.dp) { RadioAsk.query.value = "" }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line))
    }
}
