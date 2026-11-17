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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
        Door("CHARTS", BARS, Modifier.weight(1f)) { open(Page.ChartsAll) }
        Door("NEW RELEASES", SPARK, Modifier.weight(1f)) { open(Page.NewReleases) }
        Door("MOODS & GENRES", WAVE, Modifier.weight(1f)) { open(Page.AllMoods) }
    }
}

@Composable
private fun Door(name: String, sprite: List<String>, modifier: Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val over by src.collectIsHoveredAsState()
    Box(modifier.widthIn(max = 320.dp).height(76.dp).border(1.dp, if (over) Ink.mid else Ink.line).hoverable(src)
        .clickable(src, indication = null, onClick = felt(then = onClick)).padding(14.dp)) {
        Sprite(sprite, if (over) Ink.ink else Ink.mid, 3.dp, Modifier.align(Alignment.TopStart))
        BasicText(name, style = headline(15.sp, if (over) Ink.ink else Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart))
    }
}

/** New releases: YouTube Music's new records, as a grid (yours, signed in; anyone's, signed out). */
@Composable
fun NewReleasesPage(open: (Page) -> Unit, onClose: () -> Unit) {
    val records by produceState<List<TubeAlbum>?>(ExploreKept.fresh) {
        value = withContext(Dispatchers.IO) {
            runCatching { (TubeAccount.tube() ?: InnerTube()).items("FEmusic_new_releases_albums", 60).mapNotNull(::tubeAlbum) }.getOrDefault(emptyList())
        }.ifEmpty { value.orEmpty() }
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
                    BasicText("records out lately, from youtube music", style = pixel(12.sp, Ink.mid))
                }
            }
            val r = records
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
                    items(xs, key = { label + it.params }) { m -> GenreTile(m.title, (counts[m.title] ?: 0) > 0) { Moods.open(m); open(Page.Mood(m)) } }
                }
            }
        }
    }
}
