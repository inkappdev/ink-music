package app.ink.music

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/** Charts: three bars rising. */
private val BARS = listOf("......#", "....#.#", "....#.#", "..#.#.#", "..#.#.#", "#.#.#.#")
/** New releases: a sparkle, a small one beside it. */
private val SPARK = listOf("...#....#.", "...#...###", "..###...#.", "#######...", "..###.....", "...#......", "...#......")
/** Moods & genres: a wave. */
private val WAVE = listOf("..##.....", ".#..#....", "#....#..#", "......##.")

/**
 * Three ways in from listen, small (2026-11-17, Bronson: "a shelf of icons? charts, new releases, and one other thing";
 * the charts' shelf on listen was too much): each a hairline tile as INK's genres are, its sprite in pixels and its name.
 */
@Composable
fun Doors(open: (Page) -> Unit) {
    if (Local.on) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        // side by side, each 260 dp at most; a narrow window shares the row between them (each given a third, they
        // had stood a third apart, 2026-11-17)
        Door("CHARTS", BARS, Modifier.weight(1f, fill = false)) { open(Page.ChartsAll) }
        Door("NEW RELEASES", SPARK, Modifier.weight(1f, fill = false)) { open(Page.NewReleases) }
        Door("MOODS & GENRES", WAVE, Modifier.weight(1f, fill = false)) { open(Page.AllMoods) }
    }
}

@Composable
private fun Door(name: String, sprite: List<String>, modifier: Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val over by src.collectIsHoveredAsState()
    Box(modifier.widthIn(max = 260.dp).fillMaxWidth().height(76.dp).border(1.dp, if (over) Ink.mid else Ink.line).hoverable(src)
        .clickable(src, indication = null, onClick = felt(then = onClick)).padding(14.dp)) {
        Sprite(sprite, if (over) Ink.ink else Ink.mid, 3.dp, Modifier.align(Alignment.TopStart))
        BasicText(name, style = headline(15.sp, if (over) Ink.ink else Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart))
    }
}

/** New releases' last shelf from YouTube Music's editors' new-music lists (EditorsNew), read together at most every six hours, without what AI made. Blocking. */
private fun editorsNew(near: (String) -> Double): Pair<List<String>, List<TubeAlbum>> = runCatching {
    val read = editorsRead.takeIf { System.currentTimeMillis() - editorsAt < 6 * 3_600_000L }
        ?: kotlinx.coroutines.runBlocking(Dispatchers.IO) { EditorsNew.LISTS.map { (name, id) -> async { name to EditorsNew.read(id) } }.map { it.await() } }
            .also { r -> if (r.any { it.second.isNotEmpty() }) { editorsRead = r; editorsAt = System.currentTimeMillis() } }
    val shelf = EditorsNew.shelf(read, near)
    val records = shelf.records.map { TubeAlbum("${InnerTube.BASE}/browse/${it.albumId}", it.album.orEmpty(), it.artists.joinToString(" & "), it.art) }
    shelf.lists to Fresh.ai.without(records, { it.artist }, InnerTube()::onWikipedia)
}.getOrDefault(emptyList<String>() to emptyList())
@Volatile private var editorsRead: List<Pair<String, List<InnerTube.Track>>>? = null
@Volatile private var editorsAt = 0L

/**
 * New releases, ink's (2026-11-18, Bronson: YouTube Music's list alone "has some shit in there"): new from your artists,
 * the critics' records, out this Friday, each a shelf; then the rest of what's out, YouTube Music's (yours signed in,
 * anyone's signed out), cleaned (NewOut: records and EPs only, no deluxe, live or holiday padding, nothing AI made (MadeByAi), nothing you said
 * isn't for you), your kind of artist first.
 */
@Composable
fun NewReleasesPage(open: (Page) -> Unit, onClose: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { Picks.start(); Picks.startFriday(); Picks.startElectronic() }
    // the editors' lists' records (EditorsNew); YouTube Music's own new records feed only when those can't be read
    val also by produceState<Pair<List<String>, List<TubeAlbum>>?>(null) {
        value = withContext(Dispatchers.IO) { editorsNew(Fresh.taste.value::of) }.takeIf { it.second.isNotEmpty() } ?: (emptyList<String>() to withContext(Dispatchers.IO) {
            runCatching {
                (TubeAccount.tube() ?: InnerTube()).items("FEmusic_new_releases_albums", 80)
                    .filter { NewOut.kept(it.title, it.subtitle.substringBefore(" • ")) }.mapNotNull(::tubeAlbum)
            }.getOrDefault(emptyList())
                .ifEmpty { ExploreKept.fresh.orEmpty().filter { NewOut.kept(it.title, it.kind.ifEmpty { "album" }) } }
                .let { xs -> Fresh.ai.without(xs, { it.artist }, InnerTube()::onWikipedia) }
        })
    }
    val youtube = also?.second
    val critics = Picks.records.shown { it.pick.title to it.pick.artist }
    val songs = Picks.songs.shown { it.pick.title to it.pick.artist }
    val friday by Picks.friday.collectAsState()
    val electronic by Picks.electronic.collectAsState()
    val freshReleases by Picks.fresh.collectAsState()
    val heard by Listened.now.collectAsState()
    val library by Library.songs.collectAsState()
    val brought by Fresh.brought.collectAsState()
    val taste by Fresh.taste.collectAsState()
    val blocked by Blocked.kept.collectAsState()
    val yours = remember(critics, songs, friday, electronic, freshReleases, heard, library, brought, blocked) {
        Picks.yours(heard, library, brought).filterNot { Blocked.hides(it.pick.title, it.pick.artist) }
    }
    val week = remember(friday, blocked) { friday?.releases.orEmpty().filterNot { Blocked.hides(it.title, it.artist) } }
    val rest = remember(youtube, taste, blocked) {
        val refused = Fresh.refused().map { Curation.folded(it) }.toSet()
        youtube?.filterNot { Curation.folded(it.artist) in refused || Blocked.hides(it.title, it.artist) }?.let { NewOut.ordered(it, { a -> a.artist }, taste::of) }
    }
    Column(Modifier.fillMaxSize()) {
        Close(onClose)
        val grid = pageGrid("new")
        LazyVerticalGrid(
            GridCells.Adaptive(176.dp), Modifier.fillMaxSize().feather(grid), grid,
            contentPadding = PaddingValues(start = SIDE, end = SIDE, top = 6.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "head") {
                Column {
                    BasicText("NEW RELEASES", style = headline(56.sp))
                    BasicText("records out lately: yours, the critics', this friday's, then the rest", style = pixel(12.sp, Ink.mid))
                }
            }
            fun shelf(key: String, title: String, line: String, list: List<Picks.Shown>) {
                if (list.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = key) {
                    Column(Modifier.padding(top = 12.dp)) {
                        Section(title, line)
                        Shelf {
                            rowItems(list, key = { key + it.pick.from + it.pick.artist + it.pick.title }) { s ->
                                val art by produceState(s.art, s) { value = Picks.artOf(s, s.pick.record) }
                                Box(Modifier.width(176.dp)) { PickTile(remember(s, art) { s.copy(pick = s.pick.copy(art = art), art = art) }, open) }
                            }
                        }
                    }
                }
            }
            shelf("yours", "NEW FROM YOUR ARTISTS", "out this week or picked by critics, by artists you play", yours)
            shelf("critics", "PICKED BY CRITICS", "the records the critics wrote up lately", critics.take(24))
            if (week.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "friday") {
                Column(Modifier.padding(top = 12.dp)) {
                    Section("OUT THIS FRIDAY", "every record out this week, from r/indieheads")
                    Shelf {
                        rowItems(week, key = { "fri" + it.artist + it.title }) { r ->
                            val art by produceState<String?>(null, r) { value = Picks.art(r) }
                            Box(Modifier.width(176.dp)) { PickTile(remember(r, art) { Picks.shown(r, art) }, open) }
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "rest") {
                Box(Modifier.padding(top = 12.dp)) { Section("ALSO OUT", also?.first?.takeIf { it.isNotEmpty() }?.let { "from youtube music's editors: ${it.take(3).joinToString(", ")}" } ?: "youtube music's, the singles and reissues left out") }
            }
            val r = rest
            if (r == null) item(span = { GridItemSpan(maxLineSpan) }) { BasicText("reading", style = pixel(12.sp, Ink.mid)) }
            else items(r, key = { it.url }) { a -> TubeTile(a, Modifier.fillMaxWidth()) { open(Page.Tube(a)) } }
        }
    }
}

/** Moods & genres, all of them: the moods, then the genres (the ones you open most first), each its own page. */
@Composable
fun AllMoodsPage(open: (Page) -> Unit, onClose: () -> Unit) {
    val moods by produceState<List<InnerTube.Mood>?>(Moods.kept()) { value = Moods.read() ?: value }
    val counts by Moods.counts.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Close(onClose)
        val grid = pageGrid("moods")
        LazyVerticalGrid(
            GridCells.Adaptive(186.dp), Modifier.fillMaxSize().feather(grid), grid,
            contentPadding = PaddingValues(start = SIDE, end = SIDE, top = 6.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "head") {
                Column(Modifier.padding(bottom = 10.dp)) {
                    BasicText("MOODS & GENRES", style = headline(56.sp))
                    BasicText("youtube music's editors' playlists for each, the ones you open most first", style = pixel(12.sp, Ink.mid))
                }
            }
            val all = moods
            if (all == null) item(span = { GridItemSpan(maxLineSpan) }) { BasicText("reading", style = pixel(12.sp, Ink.mid)) }
            else {
                fun ordered(xs: List<InnerTube.Mood>) = xs.sortedWith(compareByDescending<InnerTube.Mood> { counts[it.title] ?: 0 }.thenBy { Moods.rank(it.title) })
                listOf("moods" to ordered(all.filter { !it.genre }), "genres" to ordered(all.filter { it.genre })).filter { it.second.isNotEmpty() }.forEach { (label, xs) ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "l-$label") { Label(label) }
                    items(xs, key = { label + it.params }) { m -> GenreTile(m.title, (counts[m.title] ?: 0) > 0, m.params) { Moods.open(m); open(Page.Mood(m)) } }
                }
            }
        }
    }
}
