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

    /** What was looked for lately, the latest first (kept as find closes, and as enter plays), eight at most. */
    private val file = java.io.File(Home.dir, "searches.json")
    val recent = MutableStateFlow(runCatching { org.json.JSONArray(file.readText()).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList()))

    fun keep(q: String) {
        val t = q.trim()
        if (t.length < 2) return
        recent.value = (listOf(t) + recent.value.filterNot { it.equals(t, true) }).take(8)
        runCatching { Home.write(file, org.json.JSONArray(recent.value).toString()) }
    }

    fun forget() { recent.value = emptyList(); file.delete() }
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
    // null: YouTube Music didn't answer (said, so its silence isn't taken for nothing found)
    val tube by androidx.compose.runtime.produceState<Pair<List<Song>, List<TubeAlbum>>?>(emptyList<Song>() to emptyList(), q) {
        if (q.length < 2) { value = emptyList<Song>() to emptyList(); return@produceState }
        kotlinx.coroutines.delay(300)
        value = YouTube.search(q)
    }
    val focus = remember { FocusRequester() }
    // what was looked for, kept as find closes
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { Finding.keep(Finding.query) } }
    // what's typed, big, the cursor at the end of what's there (ready to type in: asked once it's laid out)
    val field = @Composable { big: Boolean ->
        LaunchedEffect(asked, big) { runCatching { focus.requestFocus() } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!big) BasicText("find > ", style = pixel(14.sp, Ink.dim))
            var value by remember { mutableStateOf(TextFieldValue(Finding.query, TextRange(Finding.query.length))) }
            // a recent search chosen: in the field, the cursor at its end
            LaunchedEffect(Finding.query) { if (value.text != Finding.query) value = TextFieldValue(Finding.query, TextRange(Finding.query.length)) }
            Box(Modifier.weight(1f)) {
                if (big && value.text.isEmpty()) BasicText("FIND", style = headline(44.sp, Ink.dim))
                BasicTextField(
                    value, { value = it; Finding.query = it.text }, singleLine = true,
                    textStyle = if (big) headline(44.sp) else pixel(14.sp), cursorBrush = SolidColor(Ink.ink),
                    visualTransformation = if (big) Upper else androidx.compose.ui.text.input.VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { k ->
                        // esc leaves find from its field too (the field had kept it)
                        if (k.type == KeyEventType.KeyDown && k.key == Key.Escape) { Nav.back(); true } else
                        if (k.type == KeyEventType.KeyDown && k.key == Key.Enter && foundSongs.isNotEmpty()) { Finding.keep(Finding.query); Player.play(foundSongs, 0); true } else false
                    },
                )
            }
        }
        Spacer(Modifier.height(if (big) 8.dp else 18.dp))
        if (q.length >= 2 && tube == null) BasicText("couldn't reach youtube music", style = pixel(12.sp, Ink.mid))
        else if (q.isNotEmpty() && foundArtists.isEmpty() && foundRecords.isEmpty() && foundSongs.isEmpty() && tube?.first.isNullOrEmpty() && tube?.second.isNullOrEmpty())
            BasicText("nothing called that", style = pixel(12.sp, Ink.mid))
        else if (big) BasicText(if (q.isEmpty()) "your artists, records and songs, and youtube music's; enter plays the songs found" else "enter plays your songs found",
            style = pixel(12.sp, Ink.mid))
        // what you looked for lately, a click to look again
        val recent by Finding.recent.collectAsState()
        if (q.isEmpty() && recent.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicText("recent", style = pixel(12.sp, Ink.dim), modifier = Modifier.padding(top = 3.dp))
                recent.forEach { r -> Word(r.lowercase(), pixel(14.sp, Ink.mid), touch = 4.dp) { Finding.query = r } }
                Word("clear", pixel(12.sp, Ink.dim), Modifier.padding(top = 2.dp), touch = 4.dp) { Finding.forget() }
            }
        }
    }
    val (tubeSongs, tubeRecords) = tube ?: (emptyList<Song>() to emptyList())
    val records = @Composable { rs: List<Album>, ts: List<TubeAlbum> ->
        LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            items(rs, key = { it.id }) { a -> RecordTile(a, Modifier.width(160.dp)) { open(Page.Record(a.id)) } }
            items(ts, key = { "ta" + it.url }) { a -> TubeTile(a, Modifier.width(160.dp)) { open(Page.Tube(a)) } }
        }
        Spacer(Modifier.height(10.dp))
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        // wide: yours in one column, YouTube Music's in the other, under the typing
        if (maxWidth >= 900.dp) androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(start = SIDE, end = SIDE, top = 6.dp)) {
            field(true)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxSize()) {
                val mine = rememberLazyListState()
                LazyColumn(Modifier.weight(1f).fillMaxHeight().feather(mine), mine, contentPadding = PaddingValues(bottom = 32.dp)) {
                    if (foundArtists.isNotEmpty()) {
                        item { Label("artists") }
                        items(foundArtists, key = { "a" + it.name }) { a -> ArtistRow(a) { open(Page.Artist(a.name)) } }
                    }
                    if (foundRecords.isNotEmpty()) item { Label("records"); records(foundRecords, emptyList()) }
                    if (foundSongs.isNotEmpty()) {
                        item { Label("songs") }
                        itemsIndexed(foundSongs, key = { _, s -> "s" + s.id }) { i, s ->
                            SongRow(s, said = "${s.artist}  ·  ${s.album}".lowercase(), open = open) { Player.play(foundSongs, i) }
                        }
                    }
                }
                Spacer(Modifier.width(40.dp))
                val theirs = rememberLazyListState()
                LazyColumn(Modifier.weight(1f).fillMaxHeight().feather(theirs), theirs, contentPadding = PaddingValues(bottom = 32.dp)) {
                    if (tubeRecords.isNotEmpty()) item { Label("records on youtube music"); records(emptyList(), tubeRecords) }
                    if (tubeSongs.isNotEmpty()) {
                        item { Label("songs on youtube music") }
                        itemsIndexed(tubeSongs, key = { _, s -> "t" + s.id }) { i, s -> SongRow(s, said = s.artist.lowercase()) { Player.play(tubeSongs, i) } }
                    }
                }
            }
        } else {
            val list = rememberLazyListState()
            LazyColumn(Modifier.fillMaxSize().feather(list), list, contentPadding = PaddingValues(start = SIDE, end = SIDE, top = 6.dp, bottom = 32.dp)) {
                item { field(false) }
                if (foundArtists.isNotEmpty()) {
                    item { Label("artists") }
                    items(foundArtists, key = { "a" + it.name }) { a -> ArtistRow(a) { open(Page.Artist(a.name)) } }
                }
                if (foundRecords.isNotEmpty()) item { Label("records"); records(foundRecords, emptyList()) }
                // your songs, the first few; then YouTube Music's (below a whole library's worth of songs, it went unseen);
                // then the rest of yours
                val (first, rest) = foundSongs.take(10) to foundSongs.drop(10)
                if (first.isNotEmpty()) {
                    item { Label("songs") }
                    itemsIndexed(first, key = { _, s -> "s" + s.id }) { i, s ->
                        SongRow(s, said = "${s.artist}  ·  ${s.album}".lowercase(), open = open) { Player.play(foundSongs, i) }
                    }
                }
                // what YouTube Music has: its records, then its songs
                if (tubeSongs.isNotEmpty() || tubeRecords.isNotEmpty()) item { Label("on youtube music") }
                if (tubeRecords.isNotEmpty()) item { records(emptyList(), tubeRecords) }
                itemsIndexed(tubeSongs, key = { _, s -> "t" + s.id }) { i, s ->
                    SongRow(s, said = s.artist.lowercase()) { Player.play(tubeSongs, i) }
                }
                if (rest.isNotEmpty()) {
                    item { Label("more of your songs") }
                    itemsIndexed(rest, key = { _, s -> "s" + s.id }) { i, s ->
                        SongRow(s, said = "${s.artist}  ·  ${s.album}".lowercase(), open = open) { Player.play(foundSongs, i + first.size) }
                    }
                }
            }
        }
    }
}

/** What's typed, shown in capitals (the headline's way), the text itself as typed. */
private val Upper = androidx.compose.ui.text.input.VisualTransformation { t ->
    androidx.compose.ui.text.input.TransformedText(androidx.compose.ui.text.AnnotatedString(t.text.uppercase().take(t.text.length).padEnd(t.text.length)),
        androidx.compose.ui.text.input.OffsetMapping.Identity)
}

@Composable
fun Label(text: String) {
    BasicText(text, style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
}

/**
 * A playlist YouTube Music made (its mixes and its own playlists: an "RD..." list, or YouTube Music's name on it), not
 * a record's: its stock art stands apart from album art in the dither, as the phone shows them.
 */
val TubeAlbum.branded: Boolean get() = Regex("[?&]list=RD").containsMatchIn(url) || artist.equals("youtube music", ignoreCase = true)

/** A YouTube Music record as a tile: its cover (from its link), name and artist; YouTube's own playlists in the dither. */
@Composable
fun TubeTile(a: TubeAlbum, modifier: Modifier, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Column(modifier.clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = felt(then = onClick))) {
        if (a.branded && a.art != null) Dithered(face(a), Modifier.fillMaxWidth().aspectRatio(1f), px = 400)
        else CoverOf(face(a), a.title, Modifier.fillMaxWidth().aspectRatio(1f), px = 400)
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
                if (a.branded && a.art != null) Dithered(face(a), Modifier.size(320.dp), px = 640)
                else CoverOf(face(a), a.title, Modifier.size(320.dp))
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
