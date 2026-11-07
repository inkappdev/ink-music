package app.ink.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** radio: ink radio's stations, each with its city and what it's playing now (where it says), the one on lit. */
@Composable
fun RadioPlace(top: Dp, foot: Dp) {
    val now by Sound.now.collectAsState()
    val stations = remember { Radio.stations() }
    val onAir by produceState(emptyMap<String, Curation.Aired>()) {
        while (true) { value = Here.off { Curation.onAir() }.associateBy { "s" + it.station }; delay(60_000) }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top, bottom = foot)) {
        items(stations, key = { it.song.id }) { st ->
            val lit = now?.song?.id == st.song.id
            Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null) { Sound.play(listOf(st.song), 0) }
                .padding(vertical = 10.dp)) {
                BasicText(st.song.title.uppercase(), style = headline(24.sp, if (lit || now == null) Ink.ink else Ink.ink.copy(alpha = .92f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText(listOf(st.city, st.genre.substringBefore(",")).filter { it.isNotEmpty() }.joinToString("   "), style = pixel(12.sp, Ink.mid))
                val aired = onAir[st.song.id]
                val line = if (aired != null) "now: ${aired.title} — ${aired.artist}" else st.line
                if (line.isNotEmpty()) BasicText(line.lowercase(), style = pixel(12.sp, Ink.mid), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
