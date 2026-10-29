package app.ink.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Radio: ink radio (the stations chosen by hand, the stars first; each its sleeve, its city and the line on it),
 * and a genre's stations from radio-browser, the genre chosen in a row of words (lit is chosen). A click plays the
 * station, the ones after it next.
 */
@Composable
fun RadioTab() {
    val ink = remember { Stations.curated().sortedByDescending { it.star } }
    var genre by remember { mutableStateOf<String?>(null) }
    val theirs by produceState<List<Song>?>(null, genre) {
        value = null
        genre?.let { g -> Stations.genres.firstOrNull { it.first == g }?.let { (_, tag) -> value = Stations.top(tag) } }
    }
    val shown = if (genre == null) ink.map { it.song to "${it.city.lowercase()} · ${it.line.lowercase()}" }
        else theirs.orEmpty().map { it to it.artist.lowercase() }
    val grid = rememberLazyGridState()
    LazyVerticalGrid(
        GridCells.Adaptive(176.dp), Modifier.fillMaxSize().feather(grid), grid,
        contentPadding = PaddingValues(start = SIDE, end = SIDE, top = 6.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // the tower sending, what's on
        item(span = { GridItemSpan(maxLineSpan) }) { RadioHead() }
        item(span = { GridItemSpan(maxLineSpan) }) {
            // one row, slid through (it wrapped onto a second line)
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                item { Word("ink radio", pixel(13.sp, if (genre == null) Ink.ink else Ink.dim), touch = 8.dp) { genre = null } }
                Stations.genres.forEach { (word, _) ->
                    item { Word(word, pixel(13.sp, if (genre == word) Ink.ink else Ink.dim), touch = 8.dp) { genre = word } }
                }
            }
        }
        if (genre != null && theirs == null) item(span = { GridItemSpan(maxLineSpan) }) { BasicText("finding stations", style = pixel(12.sp, Ink.mid)) }
        itemsIndexed(shown, key = { _, (s, _) -> s.id }) { i, (s, line) ->
            Column(Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { Player.play(shown.drop(i).map { it.first }) })) {
                StationCover(s, Modifier.fillMaxWidth().aspectRatio(1f))
                Spacer(Modifier.height(8.dp))
                BasicText(line, style = pixel(12.sp, Ink.mid), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
