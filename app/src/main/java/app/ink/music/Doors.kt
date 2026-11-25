package app.ink.music

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Charts: three bars rising. */
private val BARS = listOf("......#", "....#.#", "....#.#", "..#.#.#", "..#.#.#", "#.#.#.#")
/** New releases: a sparkle, a small one beside it. */
private val SPARK = listOf("...#....#.", "...#...###", "..###...#.", "#######...", "..###.....", "...#......", "...#......")
/** Moods & genres: a wave. */
private val WAVE = listOf("..##.....", ".#..#....", "#....#..#", "......##.")

/**
 * Three ways in from listen, small (2026-11-17, Bronson: "a shelf of icons? charts, new releases, and one other thing";
 * the charts' shelf on listen was too much; the computer's): each a hairline tile, its sprite in pixels and its name.
 */
@Composable
fun Doors(pages: Pages) {
    if (!YouTubeMusic.available) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Door("CHARTS", BARS, Modifier.weight(1f)) { pages.open(Page.ChartsAll) }
        Door("NEW", SPARK, Modifier.weight(1f)) { pages.open(Page.NewReleases) }
        Door("MOODS", WAVE, Modifier.weight(1f)) { pages.open(Page.AllMoods) }
    }
}

@Composable
private fun Door(name: String, sprite: List<String>, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.height(72.dp).border(1.dp, Ink.line)
        .clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick)).padding(12.dp)) {
        Sprite(sprite, Ink.mid, 3.dp, Modifier.align(Alignment.TopStart))
        BasicText(name, style = headline(14.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart))
    }
}

/**
 * New releases, ink's (2026-11-18, Bronson: YouTube Music's list alone "has some shit in there"): new from your artists,
 * the critics' records, out this Friday, each a shelf; then the rest of what's out, YouTube Music's, cleaned (NewOut:
 * records and EPs only, no deluxe, live or holiday padding, nothing AI made (MadeByAi), nothing you said isn't for you),
 * your kind of artist first.
 */
@Composable
fun NewReleasesPage(pages: Pages) {
    val ctx = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) { Picks.start(ctx); Picks.startFriday(); Picks.startElectronic() }
    // the editors' lists' records; YouTube Music's own new records feed only when those can't be read
    val also by produceState<Pair<List<String>, List<TubeAlbum>>?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching { YouTubeMusic.editorsNew(Fresh.taste.value::of) }.getOrNull()?.takeIf { it.second.isNotEmpty() }
                ?: (emptyList<String>() to runCatching { YouTubeMusic.newReleases() }.getOrDefault(emptyList()))
        }
    }
    val youtube = also?.second
    val critics = Picks.records.collectAsState().value.unblocked { it.pick.title to it.pick.artist }
    val friday by Picks.friday.collectAsState()
    val songs by Picks.songs.collectAsState()
    val electronic by Picks.electronic.collectAsState()
    val freshReleases by Picks.fresh.collectAsState()
    val listened by Listened.now.collectAsState()
    val library by Library.songs.collectAsState()
    val brought by Fresh.brought.collectAsState()
    val taste by Fresh.taste.collectAsState()
    val yours = remember(critics, songs, friday, electronic, freshReleases, listened, library, brought) { Picks.yours(listened, library, brought) }
        .unblocked { it.pick.title to it.pick.artist }
    val week = remember(friday) { friday?.releases.orEmpty() }.unblocked { it.title to it.artist }
    val refused = remember { Fresh.turnedDown(ctx) }
    val rest = remember(youtube, taste) {
        youtube?.filterNot { Curation.folded(it.artist) in refused }?.let { NewOut.ordered(it, { a -> a.artist }, taste::of) }
    }?.unblocked { it.title to it.artist }
    val list = pageList()
    FrostedPage(onClose = { pages.close() }) { frost, top -> LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                BasicText("NEW RELEASES", style = headline(40.sp))
                BasicText("records out lately: yours, the critics', this friday's, then the rest", style = pixel(12.sp, Ink.mid))
                Spacer(Modifier.height(8.dp))
            }
        }
        if (yours.isNotEmpty()) item(key = "yours") {
            Heading("new from your artists")
            Shelf(yours) { s -> PickTile(s, pages) }
        }
        if (critics.isNotEmpty()) item(key = "critics") {
            Heading("picked by critics")
            Shelf(critics.take(24)) { s -> PickTile(s, pages, near = taste.of(s.pick.artist) >= .3) }
        }
        if (week.isNotEmpty()) item(key = "friday") {
            Heading("out this friday")
            Shelf(week) { r ->
                val art by produceState<String?>(null, r) { value = Picks.art(r) }
                PickTile(remember(r, art) { Picks.shown(r, art) }, pages)
            }
        }
        item(key = "rest") { Heading("also out", also?.first?.takeIf { it.isNotEmpty() }?.let { "from youtube music's editors: ${it.take(3).joinToString(", ")}" } ?: "youtube music's, the singles and reissues left out") }
        val r = rest
        if (r == null) item { BasicText("reading", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(horizontal = 20.dp)) }
        else items(r.chunked(2)) { row ->
            Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { a -> TubeTile(a, Modifier.weight(1f)) { pages.open(Page.Tube(a)) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    } }
}

/** Moods & genres, all of them: the moods, then the genres (the ones you open most first), three across. */
@Composable
fun AllMoodsPage(pages: Pages) {
    val ctx = LocalContext.current
    val moods by produceState(Moods.kept(ctx)) { value = Moods.read(ctx) ?: value }
    val counts by Moods.counts.collectAsState()
    val list = pageList()
    FrostedPage(onClose = { pages.close() }) { frost, top -> LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
    ) {
        item {
            BasicText("MOODS & GENRES", style = headline(40.sp))
            BasicText("the moods are ink's own, each played as your frequency: your taste, in that mood", style = pixel(12.sp, Ink.mid))
            Spacer(Modifier.height(8.dp))
        }
        // ink's moods (InkMoods), not youtube music's editors' playlists (2026-11-25, Bronson didn't like those): a tap plays
        // your frequency in that mood, a third of it the mood's songs by the artists nearest you
        item { BasicText("moods", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 14.dp, bottom = 8.dp)) }
        items(InkMoods.ALL.chunked(2)) { row ->
            Row(Modifier.padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { m -> InkMoodTile(m, Modifier.weight(1f)) { YourFrequency.startIn(ctx, m.key); pages.close() } }
                repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        val all = moods
        if (all == null) item { BasicText("reading", style = pixel(12.sp, Ink.mid)) }
        else {
            fun ordered(xs: List<TubeMood>) = xs.sortedWith(compareByDescending<TubeMood> { counts[it.title] ?: 0 }.thenBy { Moods.rank(it.title) })
            listOf("genres" to ordered(all.filter { it.genre })).filter { it.second.isNotEmpty() }.forEach { (label, xs) ->
                item { BasicText(label, style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 14.dp, bottom = 8.dp)) }
                items(xs.chunked(3)) { row ->
                    val size = genreSize(all.map { it.title })
                    Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { m -> GenreTile(m, (counts[m.title] ?: 0) > 0, size, Modifier.weight(1f)) { Moods.open(ctx, m); pages.open(Page.Mood(m)) } }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    } }
}

/** One of ink's moods as a tile: its week's cover (Moodboard's) with its name on a black tab, its line under. */
@Composable
private fun InkMoodTile(m: InkMoods.Mood, modifier: Modifier, onClick: () -> Unit) {
    val ctx = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) { Moodboard.start(ctx) }
    val kept by Moodboard.kept.collectAsState()
    Column(modifier.clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = felt(then = onClick))) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).border(1.dp, Ink.line), contentAlignment = Alignment.BottomStart) {
            kept[m.key]?.art?.let { art -> Covered(remember(art) { faceSong("mood " + m.key, art) }, Modifier.fillMaxSize(), px = 400) }
            BasicText(m.name.uppercase(), style = headline(20.sp), maxLines = 2,
                modifier = Modifier.background(Ink.black).padding(start = 6.dp, end = 8.dp, top = 4.dp, bottom = 2.dp))
        }
        Spacer(Modifier.height(6.dp))
        BasicText(m.line, style = pixel(12.sp, Ink.mid), maxLines = 2)
    }
}
