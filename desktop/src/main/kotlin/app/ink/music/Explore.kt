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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Explore: what isn't yours yet. On the left the week on indie radio (the phone's indie chart: what KEXP, KCRW and
 * WXPN played most), drawn as a chart, the place big; on the right YouTube Music's new records, then its home's
 * shelves (as it shows them to anyone signed out). Side by side in a wide window, one above the other in a narrow one.
 */
@Composable
fun ExplorePage(open: (Page) -> Unit) {
    val chart by produceState<List<IndieChart.Entry>?>(null) { value = IndieChart.week() }
    val fresh by produceState<List<TubeAlbum>?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { InnerTube().items("FEmusic_new_releases_albums", 24).mapNotNull(::tubeAlbum) }.getOrDefault(emptyList()) }
    }
    val shelves by produceState<List<Pair<String, List<TubeAlbum>>>?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching { InnerTube().home(12).map { it.title to it.items.mapNotNull(::tubeAlbum) }.filter { it.first.isNotEmpty() && it.second.size >= 3 } }
                .getOrDefault(emptyList())
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 860.dp
        val chartList = @Composable { m: Modifier ->
            val list = rememberLazyListState()
            val c = chart
            LazyColumn(m.feather(list), list, contentPadding = PaddingValues(top = 14.dp, bottom = 32.dp)) {
                item {
                    BasicText("THE INDIE CHART", style = headline(30.sp))
                    BasicText("this week on kexp, kcrw and wxpn, the songs they played most", style = pixel(12.sp, Ink.mid))
                    Spacer(Modifier.height(10.dp))
                    if (c == null) BasicText("counting this week's plays", style = pixel(12.sp, Ink.mid))
                }
                val songs = c.orEmpty().mapNotNull { it.song }
                itemsIndexed(c.orEmpty(), key = { i, e -> "$i" + e.title }) { i, e ->
                    val s = e.song ?: return@itemsIndexed
                    // a chart: the place big beside each
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicText("${i + 1}", style = headline(30.sp), modifier = Modifier.width(56.dp))
                        Box(Modifier.weight(1f)) {
                            SongRow(s, said = (s.artist + "  ·  " + e.stations.joinToString(" ")).lowercase(), open = open) {
                                Player.play(songs, songs.indexOf(s).coerceAtLeast(0))
                            }
                        }
                    }
                }
            }
        }
        val records = @Composable { m: Modifier ->
            val grid = rememberLazyGridState()
            LazyVerticalGrid(GridCells.Adaptive(150.dp), m.feather(grid), grid, contentPadding = PaddingValues(top = 14.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        BasicText("NEW RECORDS", style = headline(30.sp))
                        BasicText(if (fresh == null) "reading youtube music" else "this week on youtube music", style = pixel(12.sp, Ink.mid))
                    }
                }
                items(fresh.orEmpty(), key = { "n" + it.url }) { a -> TubeTile(a, Modifier) { open(Page.Tube(a)) } }
                shelves.orEmpty().forEach { (title, items) ->
                    item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.height(1.dp)); Label(title.lowercase()) }
                    items(items.take(12), key = { title + it.url }) { a -> TubeTile(a, Modifier) { open(Page.Tube(a)) } }
                }
            }
        }
        if (wide) Row(Modifier.fillMaxSize().padding(start = SIDE, end = SIDE), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            chartList(Modifier.weight(1f).fillMaxHeight())
            records(Modifier.weight(1.1f).fillMaxHeight())
        } else Column(Modifier.fillMaxSize().padding(start = SIDE, end = SIDE)) {
            chartList(Modifier.weight(1f))
            records(Modifier.weight(1f))
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
