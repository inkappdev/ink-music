package app.ink.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/** find: your server's records and songs first, then YouTube Music's, as you type. */
@Composable
fun FindPlace(top: Dp, foot: Dp) {
    val now by Sound.now.collectAsState()
    var q by rememberSaveable { mutableStateOf("") }
    var mine by remember { mutableStateOf<Pair<List<Album>, List<Song>>?>(null) }
    var tube by remember { mutableStateOf<Pair<List<Album>, List<Song>>?>(null) }
    LaunchedEffect(q) {
        if (q.trim().length < 2) { mine = null; tube = null; return@LaunchedEffect }
        delay(350)
        coroutineScope {
            val a = async { runCatching { Server.search(q.trim(), 12, 20) }.getOrNull() }
            val b = async {
                Here.off {
                    runCatching {
                        Tube.api.searchItems(q.trim(), InnerTube.Kind.RECORD, 12).map(Tube::record) to Tube.api.searchSongs(q.trim(), 20).map { Tube.song(it) }
                    }.getOrNull()
                }
            }
            mine = a.await(); tube = b.await()
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top, bottom = foot)) {
        item {
            Column(Modifier.fillMaxWidth()) {
                BasicTextField(q, { q = it }, singleLine = true, textStyle = headline(22.sp), cursorBrush = SolidColor(Ink.ink), modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner -> Box { if (q.isEmpty()) BasicText("a song, a record, someone", style = pixel(14.sp, Ink.dim)); inner() } })
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line))
            }
        }
        for ((source, found) in listOf("yours" to mine, "youtube music" to tube)) {
            val (records, songs) = found ?: continue
            if (records.isEmpty() && songs.isEmpty()) continue
            if (records.isNotEmpty()) item(key = "$source records") {
                Label("$source: records")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(records, key = { it.id }) { a -> RecordTile(a, Modifier.width(130.dp)) }
                }
            }
            if (songs.isNotEmpty()) {
                item(key = "$source songs") { Label("$source: songs") }
                items(songs.take(12), key = { source + it.id }) { s ->
                    Named(s.title, "${s.artist}   ${clock(s.duration)}", lit = now?.song?.id == s.id) { Sound.play(listOf(s), 0) }
                }
            }
        }
        if (q.trim().length >= 2 && mine == null && tube == null) item { Label("looking") }
    }
}
