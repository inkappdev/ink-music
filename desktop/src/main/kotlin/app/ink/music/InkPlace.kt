package app.ink.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * INK's own place on the rail: what we make, all of it. Ink weekly, every volume, the latest first (what we're
 * listening to at ink, picked each Friday by people), each its songs as covers and play the week; then ink radio, the
 * stations we keep, our favorites first.
 */
@Composable
fun InkPage(open: (Page) -> Unit) {
    val weeks by Weekly.weeks.collectAsState()
    LaunchedEffect(Unit) { Weekly.load() }
    val stations = remember { Stations.curated().sortedByDescending { it.star } }
    val now by Player.now.collectAsState()
    val grid = rememberLazyGridState()
    LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize().feather(grid), grid,
        contentPadding = PaddingValues(start = SIDE, end = SIDE, top = 14.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                BasicText("INK WEEKLY", style = headline(30.sp))
                BasicText(if (weeks.isEmpty()) "reading this week's" else "what we're listening to at ink, picked by people, a new volume each friday",
                    style = pixel(12.sp, Ink.mid))
            }
        }
        weeks.forEach { w ->
            item(span = { GridItemSpan(maxLineSpan) }, key = "w${w.week}") {
                Column {
                    Label("vol ${w.week}" + Weekly.day(w.date).takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty())
                    Row(verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            BasicText(w.title.uppercase(), style = headline(26.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (w.note.isNotEmpty()) BasicText(w.note.lowercase(), style = pixel(12.sp, Ink.mid), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Word("play the week", pixel(14.sp), touch = 8.dp) { Player.play(w.songs) }
                    }
                    Spacer(Modifier.height(12.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        itemsIndexed(w.songs, key = { _, s -> s.id }) { i, s ->
                            Column(Modifier.width(150.dp).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { Player.play(w.songs, i) })) {
                                CoverOf(s, s.title, Modifier.fillMaxWidth().aspectRatio(1f), px = 300)
                                Spacer(Modifier.height(8.dp))
                                UnderCover(s.title, s.artist)
                            }
                        }
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(top = 18.dp)) {
                BasicText("INK RADIO", style = headline(30.sp))
                BasicText("the stations we keep, our favorites first: next is the next station", style = pixel(12.sp, Ink.mid))
            }
        }
        itemsIndexed(stations, key = { _, c -> c.song.id }) { i, c ->
            val on = now?.song?.id == c.song.id
            Column(Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { Player.play(stations.drop(i).map { it.song }) })) {
                StationCover(c.song, Modifier.fillMaxWidth().aspectRatio(1f), moving = on && now?.playing == true)
                Spacer(Modifier.height(8.dp))
                BasicText(c.song.title.uppercase(), style = headline(14.sp, if (on) Ink.ink else Ink.ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
                BasicText((if (on) "on now" else c.city).lowercase(), style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
