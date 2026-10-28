package app.ink.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow

/** Asked to find (⌘F, from anywhere): the find tab, its field ready to type in. */
object Finding {
    val asked = MutableStateFlow(0)
    /** What was typed, kept while the app runs (back from a result, it's there). */
    var query by mutableStateOf("")
}

/**
 * Find, as the phone's: what's typed, among the library's artists, records and songs (a word's start
 * first, then a word inside, then anywhere). Enter plays the songs found.
 */
@Composable
fun FindTab(open: (Page) -> Unit) {
    val albums by Library.albums.collectAsState()
    val songs by Library.songs.collectAsState()
    val asked by Finding.asked.collectAsState()
    val q = Finding.query.trim().lowercase()
    fun rank(s: String): Int? { val l = s.lowercase(); return when { l.startsWith(q) -> 0; l.contains(" $q") -> 1; l.contains(q) -> 2; else -> null } }
    val artists = remember(albums, songs) { artistsOf(albums, songs) }
    val foundArtists = remember(q, artists) { if (q.isEmpty()) emptyList() else artists.mapNotNull { a -> rank(a.name)?.let { it to a } }.sortedBy { it.first }.map { it.second }.take(8) }
    val foundRecords = remember(q, albums) { if (q.isEmpty()) emptyList() else albums.mapNotNull { a -> listOfNotNull(rank(a.title), rank(a.artist)?.plus(1)).minOrNull()?.let { it to a } }.sortedBy { it.first }.map { it.second }.take(16) }
    val foundSongs = remember(q, songs) { if (q.isEmpty()) emptyList() else songs.mapNotNull { s -> listOfNotNull(rank(s.title), rank(s.artist)?.plus(1), rank(s.album)?.plus(2)).minOrNull()?.let { it to s } }.sortedBy { it.first }.map { it.second }.take(60) }
    // YouTube Music too, once the typing rests
    val tube by androidx.compose.runtime.produceState(emptyList<Song>() to emptyList<TubeAlbum>(), q) {
        if (q.length < 2) { value = emptyList<Song>() to emptyList(); return@produceState }
        kotlinx.coroutines.delay(300)
        value = YouTube.search(q)
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(asked) { runCatching { focus.requestFocus() } }
    val list = rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().feather(list), list, contentPadding = PaddingValues(start = SIDE, end = SIDE, top = 6.dp, bottom = 32.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText("find > ", style = pixel(14.sp, Ink.dim))
                // the cursor at the end of what's there
                var field by remember { mutableStateOf(TextFieldValue(Finding.query, TextRange(Finding.query.length))) }
                BasicTextField(
                    field, { field = it; Finding.query = it.text }, singleLine = true,
                    textStyle = pixel(14.sp), cursorBrush = SolidColor(Ink.ink),
                    modifier = Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent { k ->
                        if (k.type == KeyEventType.KeyDown && k.key == Key.Enter && foundSongs.isNotEmpty()) { Player.play(foundSongs, 0); true } else false
                    },
                )
            }
            Spacer(Modifier.height(18.dp))
            if (q.isNotEmpty() && foundArtists.isEmpty() && foundRecords.isEmpty() && foundSongs.isEmpty() && tube.first.isEmpty() && tube.second.isEmpty())
                BasicText("nothing called that", style = pixel(12.sp, Ink.mid))
        }
        if (foundArtists.isNotEmpty()) {
            item { Label("artists") }
            items(foundArtists, key = { "a" + it.name }) { a -> ArtistRow(a) { open(Page.Artist(a.name)) } }
        }
        if (foundRecords.isNotEmpty()) item {
            Label("records")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                items(foundRecords, key = { it.id }) { a -> RecordTile(a, Modifier.width(160.dp)) { open(Page.Record(a.id)) } }
            }
            Spacer(Modifier.height(10.dp))
        }
        if (foundSongs.isNotEmpty()) {
            item { Label("songs") }
            itemsIndexed(foundSongs, key = { _, s -> "s" + s.id }) { i, s ->
                SongRow(s, said = "${s.artist}  ·  ${s.album}".lowercase(), open = open) { Player.play(foundSongs, i) }
            }
        }
        // what YouTube Music has: its records, then its songs
        val (tubeSongs, tubeRecords) = tube
        if (tubeSongs.isNotEmpty() || tubeRecords.isNotEmpty()) item { Label("on youtube music") }
        if (tubeRecords.isNotEmpty()) item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                items(tubeRecords, key = { "ta" + it.url }) { a -> TubeTile(a, Modifier.width(160.dp)) { open(Page.Tube(a)) } }
            }
            Spacer(Modifier.height(10.dp))
        }
        itemsIndexed(tubeSongs, key = { _, s -> "t" + s.id }) { i, s ->
            SongRow(s, said = s.artist.lowercase()) { Player.play(tubeSongs, i) }
        }
    }
}

@Composable
fun Label(text: String) {
    BasicText(text, style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
}

/** A YouTube Music record as a tile: its cover (from its link), name and artist. */
@Composable
fun TubeTile(a: TubeAlbum, modifier: Modifier, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Column(modifier.clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = felt(then = onClick))) {
        CoverOf(face(a), a.title, Modifier.fillMaxWidth().aspectRatio(1f), px = 400)
        Spacer(Modifier.height(8.dp))
        UnderCover(a.title, a.artist)
    }
}

/** Enough of a song to show a YouTube record's cover by. */
fun face(a: TubeAlbum) = Song(id = "yface", path = "", title = a.title, artist = a.artist, album = a.title,
    albumId = "ya" + a.url.hashCode(), albumArtist = a.artist, duration = 0, disc = 1, track = 0, year = 0, art = a.art)

/** A YouTube Music record: its songs read from YouTube Music, played from there. */
@Composable
fun TubeRecordPage(a: TubeAlbum, open: (Page) -> Unit, onClose: () -> Unit) {
    val songs by androidx.compose.runtime.produceState<List<Song>?>(null, a.url) { value = YouTube.album(a) }
    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
        Close(onClose)
        Row(Modifier.fillMaxSize().padding(start = SIDE, end = SIDE, top = 16.dp)) {
            androidx.compose.foundation.layout.Column(Modifier.width(320.dp)) {
                CoverOf(face(a), a.title, Modifier.size(320.dp))
                Spacer(Modifier.height(16.dp))
                BasicText(a.title.uppercase(), style = headline(30.sp), maxLines = 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                BasicText(a.artist.lowercase(), style = pixel(14.sp))
                Spacer(Modifier.height(4.dp))
                BasicText(songs?.let { s -> listOf("${s.size} ${if (s.size == 1) "song" else "songs"}", clock(s.sumOf { it.duration }), "youtube music").joinToString("  ·  ") } ?: "reading",
                    style = pixel(12.sp, Ink.mid))
                Spacer(Modifier.height(16.dp))
                songs?.takeIf { it.isNotEmpty() }?.let { s -> Word("play", pixel(14.sp), touch = 8.dp) { Player.play(s) } }
            }
            Spacer(Modifier.width(40.dp))
            val list = rememberLazyListState()
            val s = songs.orEmpty()
            LazyColumn(Modifier.weight(1f).fillMaxHeight().feather(list), list, contentPadding = PaddingValues(bottom = 32.dp)) {
                itemsIndexed(s, key = { _, x -> x.id }) { i, x ->
                    SongRow(x, number = "${i + 1}", said = x.artist.takeIf { it != a.artist }?.lowercase()) { Player.play(s, i) }
                }
            }
        }
    }
}
