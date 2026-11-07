package app.ink.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * listen: what plays on your computer (continue here), new episodes of the shows you follow, and your server's
 * shelves; a record never twice on the page (the first shelf it's on keeps it).
 */
@Composable
fun ListenPlace(top: Dp, foot: Dp) {
    val offer by Together.theirs.collectAsState()
    val changed by Together.changed.collectAsState()
    val shelves by produceState<List<Pair<String, List<Album>>>?>(null, changed) {
        val seen = HashSet<String>()
        value = listOf("new on your server" to "newest", "lately" to "recent", "on repeat" to "frequent", "loved" to "starred", "rediscover" to "random")
            .map { (name, type) -> name to runCatching { Server.shelf(type, 24) }.getOrDefault(emptyList()).filter { seen.add(it.id) } }
            .filter { it.second.isNotEmpty() }
    }
    val who by Tube.who.collectAsState()
    val tube by produceState<Tube.Library?>(Tube.library.value, who, changed) { value = Here.off { Tube.readLibrary() } }
    val episodes by produceState<List<Pair<Podcasts.Show, Podcasts.Episode>>>(emptyList(), changed) { value = Here.off { Podcasts.fresh(n = 8) } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top - 26.dp, bottom = foot)) {
        offer?.let { o ->
            item {
                Label("on ${o.from}")
                val s = o.songs[o.index]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Cover(s.cover, 200, s.album.ifEmpty { s.title }, Modifier.padding(end = 14.dp).size(64.dp))
                    Column(Modifier.weight(1f)) {
                        BasicText(s.title.uppercase(), style = headline(18.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        BasicText(s.artist.lowercase(), style = pixel(12.sp, Ink.mid), maxLines = 1)
                    }
                }
                Word("CONTINUE HERE", headline(24.sp), Modifier.padding(top = 6.dp)) { Together.take(o) }
            }
        }
        if (episodes.isNotEmpty()) item {
            Label("new episodes")
            episodes.take(4).forEach { (show, e) -> Named(e.title, show.title) { Sound.play(listOf(episodeSong(show, e)), 0) } }
        }
        tube?.let { lib ->
            if (lib.mixes.isNotEmpty()) item(key = "mixes") {
                Label("your mixes")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(lib.mixes, key = { it.id }) { a -> RecordTile(a, Modifier.width(140.dp)) } }
            }
            if (lib.liked.isNotEmpty()) item(key = "liked") {
                Label("liked on youtube")
                lib.liked.take(4).forEachIndexed { i, s -> Named(s.title, s.artist) { Sound.play(lib.liked, i) } }
                Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                    Word("play all ${lib.liked.size}", pixel(14.sp)) { Sound.play(lib.liked, 0) }
                    Word("shuffle", pixel(14.sp, Ink.mid)) { Sound.play(lib.liked.shuffled(), 0) }
                }
            }
            if (lib.lists.isNotEmpty()) item(key = "lists") {
                Label("your playlists")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(lib.lists, key = { it.id }) { a -> RecordTile(a, Modifier.width(140.dp)) } }
            }
        }
        if (shelves == null) item { Label("reading your server") }
        shelves.orEmpty().forEach { (name, albums) ->
            item(key = name) {
                Label(name)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(albums, key = { it.id }) { a -> RecordTile(a, Modifier.width(140.dp)) }
                }
            }
        }
    }
}
