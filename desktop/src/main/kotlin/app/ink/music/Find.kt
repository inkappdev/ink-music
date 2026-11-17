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
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
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
 * Find, as the phone's: what's typed, among the library's artists, records and songs, your playlists, radio's stations
 * and all YouTube Music has (its artists, records, playlists and songs). Forgiving ([Search]): accents, "&", words in
 * any order. Enter plays the songs found (yours, else YouTube Music's).
 */
@Composable
fun FindTab(open: (Page) -> Unit) {
    val albums by Library.albums.collectAsState()
    val songs by Library.songs.collectAsState()
    val serverLists by Server.playlists.collectAsState()
    val tubeLists by TubeAccount.playlists.collectAsState()
    val asked by Finding.asked.collectAsState()
    val raw = Finding.query.trim()
    // a link from another service pasted (PastedLink: Spotify, Apple Music, Deezer, YouTube): read, then found here by its
    // words, the song or record it was at the top (2026-11-16: Song.link's free API closed, so read here)
    val pasted = PastedLink.isLink(raw)
    val link by androidx.compose.runtime.produceState<PastedLink.Found?>(null, raw) {
        value = null
        if (!pasted) return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            PastedLink.read(raw)?.let { f -> f.tubeId?.let { id -> YouTube.byId(id)?.let { s -> f.copy(title = s.title, artist = s.artist) } } ?: f }
        }
    }
    // what it found: its song to play, its record to open; "reading" while it's read, "none" where YouTube Music hasn't it
    val linked by androidx.compose.runtime.produceState<Any>("reading", link, pasted) {
        val f = link ?: run { value = if (pasted) "reading" else "none"; return@produceState }
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val artist = f.artist.substringBefore(", ")
            when {
                f.tubeId != null -> f.tubeId?.let(YouTube::byId)
                f.kind == PastedLink.Kind.SONG -> YouTube.find(f.title, artist)
                else -> YouTube.search("$artist ${f.title}")?.albums?.firstOrNull { Pipe.same(it.title, it.artist, f.title, artist) }
            } ?: "none"
        }
    }
    // searched as its words once read (nothing while it's read: a link's letters find nothing)
    val typed = when { link != null && link!!.title.isNotEmpty() -> "${link!!.artist.substringBefore(", ")} ${link!!.title}"; pasted -> ""; else -> raw }
    val q = Search.fold(typed)
    val artists = remember(albums, songs) { artistsOf(albums, songs) }
    val foundArtists = remember(q, artists) { if (q.isEmpty()) emptyList() else artists.mapNotNull { a -> Search.rank(a.name, q)?.let { it to a } }.sortedBy { it.first }.map { it.second }.take(8) }
    val foundRecords = remember(q, albums) { if (q.isEmpty()) emptyList() else albums.mapNotNull { a -> Search.rank(q, a.title, a.artist)?.let { it to a } }.sortedBy { it.first }.map { it.second }.take(16) }
    val foundSongs = remember(q, songs) { if (q.isEmpty()) emptyList() else songs.mapNotNull { s -> Search.rank(q, s.title, s.artist, s.album)?.let { it to s } }.sortedBy { it.first }.map { it.second }.take(60) }
    // your playlists: the server's, and YouTube Music's (signed in)
    val foundLists = remember(q, serverLists) { if (q.isEmpty()) emptyList() else serverLists.mapNotNull { l -> Search.rank(l.name, q)?.let { it to l } }.sortedBy { it.first }.map { it.second }.take(8) }
    val foundTubeLists = remember(q, tubeLists) { if (q.isEmpty()) emptyList() else tubeLists.mapNotNull { l -> Search.rank(l.title, q)?.let { it to l } }.sortedBy { it.first }.map { it.second }.take(8) }
    // asked online as typed (Search.online: the folded words made two titles one, and "愛℃" too short to ask, 2026-11-17),
    // once the typing rests, or at once on enter (asked again, the same words too)
    var forced by remember { mutableStateOf(0) }
    val online = Search.online(typed)
    val seen = remember { intArrayOf(0) }
    val ask by androidx.compose.runtime.produceState<Asked?>(null, online, forced) {
        if (!Search.enough(online)) { value = null; return@produceState }
        if (forced == seen[0]) kotlinx.coroutines.delay(300)
        seen[0] = forced
        value = Asked(online, forced)
    }
    // YouTube Music too; null: it didn't answer (said, so its silence isn't taken for nothing found)
    val tube by androidx.compose.runtime.produceState<TubeFound?>(TubeFound(), ask) {
        value = ask?.let { tubeFound(it.text) } ?: TubeFound()
    }
    // radio's stations by that name (ink radio's own first)
    val stations by androidx.compose.runtime.produceState(emptyList<Song>(), ask) {
        value = ask?.let { Stations.search(it.text) }.orEmpty()
    }
    // youtube.com's own videos (YouTube.videos): songs never put on YouTube Music, apart from its songs; none it already gave
    val tubeVideosAll by androidx.compose.runtime.produceState(emptyList<Song>(), ask) {
        value = ask?.let { tubeVideos(it.text) }.orEmpty()
    }
    // a YouTube video's id in what's typed ("[VDvX1hzGZN4]", as a file downloaded from YouTube is often named): that video
    val tubeId = if (pasted) null else Search.tubeId(raw)
    val byId by androidx.compose.runtime.produceState<Song?>(null, tubeId) {
        value = tubeId?.let { id -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { YouTube.byId(id) } }
    }
    // a stream's own link pasted: played as a station, kept as yours
    val streamed = remember(raw) { if (!pasted && RadioBrowser.isStream(raw)) Stations.of(RadioBrowser.pasted(raw)) else null }
    val found = tube ?: TubeFound()
    val tubeSongs = found.songs.filter { it.id != byId?.id }
    val tubeVideos = remember(tubeVideosAll, tubeSongs, byId) {
        val have = (tubeSongs.flatMap { listOf(it.id, Radio.key(it.title, it.artist)) } + listOfNotNull(byId?.id)).toSet()
        tubeVideosAll.filter { it.id !in have && Radio.key(it.title, it.artist) !in have }.take(12)
    }
    val tubeRecords = found.albums
    // its artists, their faces (any you have already shown as yours); else whose its songs and records are
    // (2026-10-29: Mac DeMarco found, no page of his to open)
    val tubeArtists = remember(q, found, foundArtists) {
        if (q.isEmpty()) emptyList()
        else (found.artists.ifEmpty { (found.songs.flatMap { it.artist.split(", ") } + found.albums.map { it.artist }).map { TubeArtist(it.trim(), null) } })
            .filter { a -> a.name.isNotEmpty() && Search.rank(a.name, q) != null && foundArtists.none { it.name.equals(a.name, true) } }
            .distinctBy { it.name.lowercase() }.take(8)
    }
    val tubePlaylists = found.lists
    val focus = remember { FocusRequester() }
    // enter plays what's found: your songs, else YouTube Music's
    val enter = (linked as? Song)?.let { listOf(it) } ?: foundSongs.ifEmpty { tubeSongs }
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
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).typing().onPreviewKeyEvent { k ->
                        // esc leaves find from its field too (the field had kept it); a second esc with words in it clears them first
                        if (k.type == KeyEventType.KeyDown && k.key == Key.Escape) { if (Finding.query.isNotEmpty()) Finding.query = "" else Nav.back(); true } else
                        // enter looks now (again, the same words too: what was slow or missed asked afresh); shift and
                        // enter plays what's found (2026-11-17, a tester: enter to search played the first song instead)
                        if (k.type == KeyEventType.KeyDown && k.key == Key.Enter) {
                            Finding.keep(Finding.query)
                            if (k.isShiftPressed) { if (enter.isNotEmpty()) Player.play(enter, 0) } else forced++
                            true
                        } else false
                    },
                )
            }
        }
        Spacer(Modifier.height(if (big) 8.dp else 18.dp))
        if (ask != null && tube == null) BasicText("couldn't reach youtube music; enter asks again", style = pixel(12.sp, Ink.mid))
        else if (ask != null && foundArtists.isEmpty() && foundRecords.isEmpty() && foundSongs.isEmpty() && foundLists.isEmpty() && byId == null && streamed == null &&
            foundTubeLists.isEmpty() && stations.isEmpty() && tubeSongs.isEmpty() && tubeVideos.isEmpty() && tubeRecords.isEmpty() && tubeArtists.isEmpty() && tubePlaylists.isEmpty())
            BasicText("nothing called that", style = pixel(12.sp, Ink.mid))
        else if (big) BasicText(
            if (online.isEmpty()) "your artists, records, songs and playlists, radio's stations, and youtube music's; enter looks; shift enter plays the songs found; esc clears"
            else if (foundSongs.isEmpty() && tubeSongs.isNotEmpty()) "enter looks again; shift enter plays youtube music's songs found" else "enter looks again; shift enter plays your songs found",
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
    val records = @Composable { rs: List<Album>, ts: List<TubeAlbum> ->
        Shelf {
            items(rs, key = { it.id }) { a -> RecordTile(a, Modifier.width(160.dp)) { open(Page.Record(a.id)) } }
            items(ts, key = { "ta" + it.url }) { a -> TubeTile(a, Modifier.width(160.dp)) { open(Page.Tube(a)) } }
        }
        Spacer(Modifier.height(10.dp))
    }
    // YouTube Music's artists: their faces in a row, each opening their page
    val faces = @Composable {
        Shelf {
            items(tubeArtists, key = { "f" + it.id.ifEmpty { it.name } }) { a ->
                androidx.compose.foundation.layout.Column(Modifier.width(130.dp).clickable(
                    remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null,
                    onClick = felt { open(Page.Artist(a.name, a.id)) })) {
                    CoverOf(remember(a.face) { a.face?.let { faceSong(a.name, it) } }, a.name, Modifier.fillMaxWidth().aspectRatio(1f), px = 300)
                    Spacer(Modifier.height(8.dp))
                    BasicText(a.name.uppercase(), style = headline(14.sp), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    BasicText("artist", style = pixel(12.sp, Ink.mid))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
    // your playlists found: the server's and YouTube Music's
    val lists = @Composable {
        foundLists.forEach { l -> ListRow(l) { open(Page.List(l.id, l.name)) } }
        foundTubeLists.forEach { l -> ListRow(Server.Playlist(l.url, l.title, 0, true), said = "on youtube music") { open(Page.Tube(l)) } }
    }
    // the pasted link's song or record, at the top
    val fromLink = @Composable {
        streamed?.let { x ->
            Label("a stream's link")
            SongRow(x, said = "play it as a station") { Player.play(listOf(x)) }
            val kept by KeptStations.all.collectAsState()
            val has = kept.any { it.song.id == x.id }
            Word(if (has) "kept in radio" else "keep it in radio", pixel(13.sp, if (has) Ink.ink else Ink.mid), Modifier.padding(bottom = 6.dp), touch = 8.dp) { KeptStations.toggle(x, "your link") }
        }
        // a video's id typed: that video, first
        byId?.let { x ->
            Label("youtube video $tubeId")
            SongRow(x, said = x.artist.lowercase(), open = open) { Player.play(listOf(x)) }
        }
        if (pasted) {
            Label("from your ${link?.service ?: "link"}")
            when (val x = linked) {
                is Song -> SongRow(x, said = x.artist.lowercase(), open = open) { Player.play(listOf(x)) }
                is TubeAlbum -> records(emptyList(), listOf(x))
                else -> BasicText(when {
                    x == "reading" -> "reading the link"
                    link == null -> "couldn't read that link"
                    else -> "${link!!.title.lowercase()} isn't on youtube music; what's like it, below"
                }, style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(bottom = 10.dp))
            }
        }
    }
    // the first three stations, then the radio tab with these words looked for there, every way (2026-11-17)
    val radio = @Composable {
        stations.take(3).forEachIndexed { i, s -> SongRow(s, said = s.artist.lowercase()) { Player.play(stations.drop(i).take(1)) } }
        Word("more stations", pixel(13.sp, Ink.mid), Modifier.padding(top = 6.dp, bottom = 4.dp), touch = 8.dp) { RadioAsk.look(online) }
    }

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        // wide: yours in one column, YouTube Music's in the other, under the typing
        if (maxWidth >= 900.dp) androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(start = SIDE, end = SIDE, top = 6.dp)) {
            field(true)
            Spacer(Modifier.height(10.dp))
            fromLink()
            Row(Modifier.fillMaxSize()) {
                val mine = rememberLazyListState()
                LazyColumn(Modifier.weight(1f).fillMaxHeight().feather(mine), mine, contentPadding = PaddingValues(bottom = 32.dp)) {
                    if (foundArtists.isNotEmpty()) {
                        item { Label("artists") }
                        items(foundArtists, key = { "a" + it.name }) { a -> ArtistRow(a) { open(Page.Artist(a.name)) } }
                    }
                    if (foundRecords.isNotEmpty()) item { Label("records"); records(foundRecords, emptyList()) }
                    if (foundLists.isNotEmpty() || foundTubeLists.isNotEmpty()) item { Label("your playlists"); lists() }
                    if (stations.isNotEmpty()) item { Label("radio"); radio() }
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
                    if (tubeArtists.isNotEmpty()) item { Label("artists on youtube music"); faces() }
                    if (tubeRecords.isNotEmpty()) item { Label("records on youtube music"); records(emptyList(), tubeRecords) }
                    if (tubeSongs.isNotEmpty()) {
                        item { Label("songs on youtube music") }
                        itemsIndexed(tubeSongs, key = { _, s -> "t" + s.id }) { i, s -> SongRow(s, said = s.artist.lowercase(), open = open) { Player.play(tubeSongs, i) } }
                    }
                    if (tubePlaylists.isNotEmpty()) item { Label("playlists on youtube music"); records(emptyList(), tubePlaylists) }
                    if (tubeVideos.isNotEmpty()) {
                        item { Label("from youtube videos") }
                        itemsIndexed(tubeVideos, key = { _, s -> "v" + s.id }) { i, s -> SongRow(s, said = s.artist.lowercase(), open = open) { Player.play(tubeVideos, i) } }
                    }
                }
            }
        } else androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
            // the field stays where it is as the results scroll under it (2026-11-17, a tester: back to the top to type again)
            Box(Modifier.padding(start = SIDE, end = SIDE, top = 6.dp)) { field(false) }
            val list = rememberLazyListState()
            LazyColumn(Modifier.fillMaxSize().feather(list), list, contentPadding = PaddingValues(start = SIDE, end = SIDE, bottom = 32.dp)) {
                if (pasted || byId != null || streamed != null) item { fromLink() }
                if (foundArtists.isNotEmpty()) {
                    item { Label("artists") }
                    items(foundArtists, key = { "a" + it.name }) { a -> ArtistRow(a) { open(Page.Artist(a.name)) } }
                }
                if (foundRecords.isNotEmpty()) item { Label("records"); records(foundRecords, emptyList()) }
                if (foundLists.isNotEmpty() || foundTubeLists.isNotEmpty()) item { Label("your playlists"); lists() }
                // your songs, the first few; then YouTube Music's (below a whole library's worth of songs, it went unseen);
                // then the rest of yours
                val (first, rest) = foundSongs.take(10) to foundSongs.drop(10)
                if (first.isNotEmpty()) {
                    item { Label("songs") }
                    itemsIndexed(first, key = { _, s -> "s" + s.id }) { i, s ->
                        SongRow(s, said = "${s.artist}  ·  ${s.album}".lowercase(), open = open) { Player.play(foundSongs, i) }
                    }
                }
                if (stations.isNotEmpty()) item { Label("radio"); radio() }
                // what YouTube Music has: its artists, records, songs, then playlists
                if (tubeArtists.isNotEmpty()) item { Label("artists on youtube music"); faces() }
                if (tubeRecords.isNotEmpty()) item { Label("records on youtube music"); records(emptyList(), tubeRecords) }
                if (tubeSongs.isNotEmpty()) item { Label("songs on youtube music") }
                itemsIndexed(tubeSongs, key = { _, s -> "t" + s.id }) { i, s ->
                    SongRow(s, said = s.artist.lowercase(), open = open) { Player.play(tubeSongs, i) }
                }
                if (tubePlaylists.isNotEmpty()) item { Label("playlists on youtube music"); records(emptyList(), tubePlaylists) }
                if (tubeVideos.isNotEmpty()) item { Label("from youtube videos") }
                itemsIndexed(tubeVideos, key = { _, s -> "v" + s.id }) { i, s ->
                    SongRow(s, said = s.artist.lowercase(), open = open) { Player.play(tubeVideos, i) }
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

/** What find asked online: the words, and which enter (the same words asked again are a new ask). */
private data class Asked(val text: String, val n: Int)

/**
 * YouTube Music's answer to what's typed, and to its words alone where it has symbols (YouTube can answer a title's
 * symbols with nothing: "ピーナツ ⧸ 可不"), together; best answers first. Null when neither was answered.
 */
private suspend fun tubeFound(text: String): TubeFound? = kotlinx.coroutines.coroutineScope {
    val plain = Search.plain(text).takeIf { it != text && Search.enough(it) }
    val asked = async { YouTube.search(text, all = true) }
    val also = plain?.let { async { YouTube.search(it, all = true) } }
    val a = asked.await(); val b = also?.await()
    if (a == null && b == null) return@coroutineScope null
    TubeFound(
        Search.best(text, (a?.songs.orEmpty() + b?.songs.orEmpty()).distinctBy { it.id }, { it.title }, { it.artist }).take(20),
        Search.best(text, (a?.albums.orEmpty() + b?.albums.orEmpty()).distinctBy { it.url }, { it.title }, { it.artist }).take(12),
        (a?.artists.orEmpty() + b?.artists.orEmpty()).distinctBy { it.name.lowercase() }.take(8),
        (a?.lists.orEmpty() + b?.lists.orEmpty()).distinctBy { it.url }.take(12),
    )
}

/** youtube.com's videos for what's typed and its words alone, together, best answers first. */
private suspend fun tubeVideos(text: String): List<Song> = kotlinx.coroutines.coroutineScope {
    val plain = Search.plain(text).takeIf { it != text && Search.enough(it) }
    val asked = async { runCatching { YouTube.videos(text) }.getOrDefault(emptyList()) }
    val also = plain?.let { async { runCatching { YouTube.videos(it) }.getOrDefault(emptyList()) } }
    Search.best(text, (asked.await() + also?.await().orEmpty()).distinctBy { it.id }, { it.title }, { it.artist })
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
val TubeAlbum.branded: Boolean get() = Curation.tubeBranded(url, artist)

/** A YouTube Music record as a tile: its cover (from its link), name and artist; YouTube's own playlists in the dither. */
@Composable
fun TubeTile(a: TubeAlbum, modifier: Modifier, said: String = a.artist, onClick: () -> Unit) {
    WithMenu({ listOf(listOf("open" to onClick, "play" to { playTube(a); Unit }, "shuffle" to { playTube(a, shuffle = true); Unit }, "play next" to { playTube(a, next = true); Unit }), listOf("keep as your list" to { keepTube(a); Unit }, "copy link" to { copyText(tubeLink(a.url)) })) }) {
    androidx.compose.foundation.layout.Column(modifier.clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = felt(then = onClick))) {
        if (a.branded && a.art != null) Covered(face(a), Modifier.fillMaxWidth().aspectRatio(1f), px = 400)
        else CoverOf(face(a), a.title, Modifier.fillMaxWidth().aspectRatio(1f), px = 400)
        Spacer(Modifier.height(8.dp))
        UnderCover(a.title, said)
    }
    }
}

/** Enough of a song to show a YouTube record's cover by. */
fun face(a: TubeAlbum) = Song(id = "yface", path = "", title = a.title, artist = a.artist, album = a.title,
    albumId = "ya" + a.url.hashCode(), albumArtist = a.artist, duration = 0, disc = 1, track = 0, year = 0, art = a.art)

/** A YouTube Music record: its songs read from YouTube Music, played from there. */
@Composable
fun TubeRecordPage(a: TubeAlbum, open: (Page) -> Unit, onClose: () -> Unit) {
    val playlist = a.playlist
    val covers by ListCovers.on.collectAsState()
    // as last read, at once, if it was ever read here; else the songs as each page comes. Read fresh every time, and
    // the fresh list put in once whole (a list grown or changed since). Its play words wait for it whole
    var whole by remember(a.url) { mutableStateOf(false) }
    val songs by androidx.compose.runtime.produceState<List<Song>?>(null, a.url) {
        val kept = YouTube.kept(a)
        if (kept != null) { value = kept; whole = true }
        val fresh = YouTube.read(a) { so -> if (kept == null && so.isNotEmpty()) value = so }
        value = fresh.ifEmpty { kept ?: emptyList() }
        whole = true
    }
    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
        Close(onClose)
        Row(Modifier.fillMaxSize().padding(start = SIDE, end = SIDE, top = 16.dp)) {
            androidx.compose.foundation.layout.Column(Modifier.width(320.dp)) {
                if (a.branded && a.art != null) Covered(face(a), Modifier.size(320.dp), px = 640)
                else CoverOf(face(a), a.title, Modifier.size(320.dp))
                Spacer(Modifier.height(16.dp))
                BasicText(a.title.uppercase(), style = headline(30.sp), maxLines = 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                // a record's artists, each their own page (2026-11-09: the name was only words here); a playlist's byline
                // (its maker, "auto playlist") stays words
                // a playlist's byline only its count ("130 tracks", said again just under): youtube music in its place
                val by = a.artist.takeUnless { Regex("""^[\d,.]+\s+(tracks?|songs?)$""", RegexOption.IGNORE_CASE).matches(it.trim()) } ?: "youtube music"
                if (playlist || a.branded) BasicText(by.lowercase(), style = pixel(14.sp))
                else Row {
                    val names = Artists.named(a.artist)
                    names.forEachIndexed { i, name ->
                        Word(name.lowercase(), pixel(14.sp), touch = 6.dp) { open(Page.Artist(name)) }
                        if (i < names.lastIndex) BasicText(", ", style = pixel(14.sp, Ink.mid))
                    }
                }
                Spacer(Modifier.height(4.dp))
                BasicText(songs?.let { s -> if (!whole) "${s.size} songs, reading" else listOf("${s.size} ${if (s.size == 1) "song" else "songs"}", clock(s.sumOf { it.duration })).joinToString("  ·  ") } ?: "reading",
                    style = pixel(12.sp, Ink.mid))
                Spacer(Modifier.height(16.dp))
                songs?.takeIf { it.isNotEmpty() && whole }?.let { s ->
                    // a playlist's mix beside play and shuffle: a way to play it too
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PlayShuffle(s)
                        if (playlist) MixWord(a.title, s, Modifier.padding(start = 24.dp), bold = true)
                    }
                    // the rest small under them, one row of four (2026-11-16, Bronson's screenshot: "the top controls of a
                    // playlist need some work it gets a little messy": seven words of three kinds wrapped onto two rows).
                    // Follow and keep offline, then more: the covers, a copy, its link. Closer than elsewhere, so the
                    // three fit the cover's width (24 dp put more on a line of its own)
                    HeadWords(gap = 16.dp) {
                        // a playlist followed: on your lists, itself, fresh each time (not one of yours on YouTube Music:
                        // those are on your lists already)
                        val tubeLists by TubeAccount.playlists.collectAsState()
                        val followed by FollowedLists.lists.collectAsState()
                        val yoursThere = playlist && tubeLists.any { TubeAccount.listId(it) == TubeAccount.listId(a) } &&
                            followed.none { TubeAccount.listId(it) == TubeAccount.listId(a) }
                        if (playlist && !yoursThere) {
                            val on = followed.any { TubeAccount.listId(it) == TubeAccount.listId(a) }
                            Word(if (on) "following" else "follow", pixel(14.sp, if (on) Ink.ink else Ink.mid), touch = 8.dp) { FollowedLists.set(a, !on) }
                        }
                        KeepOffline(s)
                        // a copy as a list of yours, every song, kept here even if whoever made it takes theirs down
                        // (2026-11-12, a tester: a 300-song playlist found and lost when its owner deleted it); a playlist's
                        // songs' covers, if wanted; its link on YouTube Music onto the clipboard (2026-11-10)
                        val here by HereLists.all.collectAsState()
                        val copy = here.firstOrNull { it.name == a.title && it.songs.size == s.size }
                        val covers by ListCovers.on.collectAsState()
                        var copied by remember(a.url) { mutableStateOf(false) }
                        LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(2_000); copied = false } }
                        DropWord(if (copied) "link copied" else "more", lit = copied, size = 14.sp, rows = listOfNotNull(
                            DropRow(if (covers) "covers beside songs: on" else "covers beside songs: off", null, covers) { ListCovers.set(!covers) }.takeIf { playlist },
                            DropRow(if (copy != null) "open your copy" else "keep a copy as your list", null, copy != null) {
                                if (copy != null) open(Page.List(copy.id, copy.name)) else HereLists.make(a.title, s)
                            },
                            DropRow("copy link", null, false) {
                                java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(tubeLink(a.url)), null)
                                copied = true
                            },
                        ))
                    }
                }
                // the mix as it's found: its tempo beating, the songs dropping in
                if (playlist) MixProgress(a.title)

            }
            Spacer(Modifier.width(40.dp))
            val list = pageList()
            val s = songs.orEmpty()
            // the songs in their own box, never past what critics said under them (a record's, not a playlist's)
            androidx.compose.foundation.layout.Column(Modifier.weight(1f).fillMaxHeight()) {
                LazyColumn(Modifier.weight(1f, fill = false).feather(list), list, contentPadding = PaddingValues(bottom = 16.dp)) {
                    itemsIndexed(s, key = { _, x -> x.id }) { i, x ->
                        SongRow(x, number = "${i + 1}", said = x.artist.takeIf { it != a.artist }?.lowercase(), cover = playlist && covers, order = s) { Player.play(s, i) }
                    }
                }
                if (!playlist) Critics(a.title, a.artist, across = true)
            }
        }
    }
}

/** A record's or playlist's YouTube Music link, from however it was found (a link, its list id, its browse id). */
fun tubeLink(url: String): String {
    Regex("[?&]list=([^&]+)").find(url)?.let { return "https://music.youtube.com/playlist?list=" + it.groupValues[1] }
    if (url.startsWith("http")) return url.replace("://www.youtube.com", "://music.youtube.com").replace("://youtube.com", "://music.youtube.com")
    val id = url.removePrefix("VL")
    return if (id.startsWith("PL") || id.startsWith("OLAK") || id.startsWith("RD")) "https://music.youtube.com/playlist?list=$id"
        else "https://music.youtube.com/browse/$id"
}
