package app.ink.music

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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

/** New releases: YouTube Music's new records, two across. */
@Composable
fun NewReleasesPage(pages: Pages) {
    val records by produceState<List<TubeAlbum>?>(null) { value = withContext(Dispatchers.IO) { runCatching { YouTubeMusic.newReleases() }.getOrDefault(emptyList()) } }
    val list = pageList()
    FrostedPage(onClose = { pages.close() }) { frost, top -> LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
    ) {
        item {
            BasicText("NEW RELEASES", style = headline(40.sp))
            BasicText("records out lately, from youtube music", style = pixel(12.sp, Ink.mid))
            Spacer(Modifier.height(16.dp))
        }
        val r = records
        if (r == null) item { BasicText("reading", style = pixel(12.sp, Ink.mid)) }
        else items(r.chunked(2)) { row ->
            Row(Modifier.padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
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
            BasicText("youtube music's editors' playlists for each, the ones you open most first", style = pixel(12.sp, Ink.mid))
            Spacer(Modifier.height(8.dp))
        }
        val all = moods
        if (all == null) item { BasicText("reading", style = pixel(12.sp, Ink.mid)) }
        else {
            fun ordered(xs: List<TubeMood>) = xs.sortedWith(compareByDescending<TubeMood> { counts[it.title] ?: 0 }.thenBy { Moods.rank(it.title) })
            listOf("moods" to ordered(all.filter { !it.genre }), "genres" to ordered(all.filter { it.genre })).filter { it.second.isNotEmpty() }.forEach { (label, xs) ->
                item { BasicText(label, style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 14.dp, bottom = 8.dp)) }
                items(xs.chunked(3)) { row ->
                    Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { m -> GenreTile(m.title, (counts[m.title] ?: 0) > 0, Modifier.weight(1f)) { Moods.open(ctx, m); pages.open(Page.Mood(m)) } }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    } }
}
