package app.ink.music

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import dev.chrisbanes.haze.hazeSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * What the listen page shows, read once a run (and again when your artists change): your
 * artists' best-known songs (each a mix's start) and records, the week on indie radio, the
 * server's newest records, and radio. Each shelf shows once it has something.
 */
object Listen {
    val indie = MutableStateFlow<List<IndieChart.Entry>>(emptyList())
    val records = MutableStateFlow<List<TubeAlbum>>(emptyList())
    val newest = MutableStateFlow<List<String>>(emptyList())
    val radio = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    /** The genres you like, each with its stations (the play build's home has no YouTube to fill it). */
    val stations = MutableStateFlow<List<Pair<String, List<Song>>>>(emptyList())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loaded = false
    private var forArtists: List<String> = emptyList()

    fun load(c: Context) {
        val artists = Seeds.artists.value.map { it.name }
        if (loaded && artists == forArtists) return
        val first = !loaded
        loaded = true; forArtists = artists
        if (first) {
            scope.launch { newest.value = Server.newest(12) }
            scope.launch { indie.value = IndieChart.week(c) }
        }
        // the genres you like first, then the rest
        val liked = Seeds.genres.value
        radio.value = Stations.genres.sortedBy { (word, _) -> if (word in liked) 0 else 1 }
        scope.launch {
            val shown = liked.ifEmpty { if (YouTubeMusic.available) emptyList() else listOf("lofi", "jazz") }.take(3)
            stations.value = shown.mapNotNull { word -> Stations.genres.firstOrNull { it.first == word }?.let { (w, tag) -> w to Stations.top(c, tag) } }
                .filter { it.second.isNotEmpty() }
        }
        scope.launch {
            // each artist's best-known song (their mix starts from it), found once and kept
            Seeds.artists.value.filter { it.hit == null }.forEach { a -> YouTubeMusic.hit(a.name)?.let { Seeds.found(a.name, it) } }
        }
        scope.launch {
            records.value = Seeds.artists.value.take(8).flatMap { YouTubeMusic.records(it.name).take(2) }
        }
    }

    /** An artist's mix: their song, then YouTube Music's radio from it. */
    suspend fun mix(hit: Song): List<Song> = withContext(Dispatchers.IO) { listOf(hit) + YouTubeMusic.related(hit.id) }

    fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "GOOD MORNING"
        in 12..16 -> "GOOD AFTERNOON"
        in 17..21 -> "GOOD EVENING"
        else -> "LATE NIGHT"
    }
}

/**
 * The listen page, the library's first: something to play the moment ink music opens, whatever
 * the phone holds. The greeting and the cog; then, each once it has something: jump back in (what
 * you played lately), mixes for you, ink radio, the week on indie radio,
 * from artists you love, new on your server, from your records, and radio.
 */
@Composable
fun ListenPage(pages: Pages, pad: PaddingValues, state: LazyListState) {
    val ctx = LocalContext.current
    val seeds by Seeds.artists.collectAsState()
    val indie by Listen.indie.collectAsState()
    val records by Listen.records.collectAsState()
    val newestIds by Listen.newest.collectAsState()
    val radio by Listen.radio.collectAsState()
    val stations by Listen.stations.collectAsState()
    val albums by Library.albums.collectAsState()
    val seed by Order.seed.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(seeds.map { it.name }) { Listen.load(ctx) }
    val heard by androidx.compose.runtime.produceState<List<History.Heard>>(emptyList()) {
        value = withContext(Dispatchers.IO) { History.read(ctx) }
    }
    // what you played lately: a record each (newest first), as songs to play from; then, signed in
    // to youtube music, what you played there (the ones this phone hasn't just said)
    val recent = remember(heard) { heard.asReversed().distinctBy { it.album.ifEmpty { it.id } }.take(10) }
    val tubeHeard by YouTubeAccount.history.collectAsState()
    val tubeRecent = remember(tubeHeard, recent) {
        val said = recent.map { it.id }.toSet()
        tubeHeard.filter { it.id !in said }.distinctBy { it.album.ifEmpty { it.id } }.take((16 - recent.size).coerceAtLeast(6))
    }
    val tubeRecords by YouTubeAccount.records.collectAsState()
    val tubeArtists by YouTubeAccount.artists.collectAsState()
    val newest = remember(newestIds, albums) { newestIds.mapNotNull { id -> albums.firstOrNull { it.id == id } } }
    val yours = remember(albums, seed) { Order.of(albums, seed) { it.id }.take(12) }
    val mixes = seeds.filter { it.hit != null }

    LazyColumn(state = state, contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding())) {
        item {
            Row(Modifier.padding(horizontal = 20.dp).padding(top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    BasicText(Listen.greeting(), style = headline(40.sp), maxLines = 2)
                    BasicText(java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.getDefault()).format(java.util.Date()).lowercase(),
                        style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 6.dp))
                }
                Cog { pages.open(Page.Settings) }
            }
        }
        if (recent.isNotEmpty() || tubeRecent.isNotEmpty()) item {
            Label("jump back in")
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(recent) { h ->
                    val s = Library.song(h.id) ?: return@items
                    Tile(s, s.album.ifEmpty { s.title }, s.artist) {
                        val record = albums.firstOrNull { it.id == s.albumId }
                        if (record != null) pages.open(Page.Record(record.id)) else Playback.play(listOf(s), 0)
                    }
                }
                // youtube music's: played from there on, back through what you played before it
                itemsIndexed(tubeRecent) { i, s -> Tile(s, s.title, s.artist) { Playback.play(tubeRecent, i) } }
            }
            Spacer(Modifier.height(10.dp))
        }
        if (mixes.isNotEmpty()) item {
            Label("mixes for you")
            Shelf(mixes) { a ->
                Tile(a.hit, a.name, "mix", face = a.face) { scope.launch { Playback.play(Listen.mix(a.hit!!), 0) } }
            }
        }
        // ink radio: the stations chosen by hand, the stars first
        item {
            val now by Playback.now.collectAsState()
            val ink = remember { Stations.curated(ctx) }
            Row(verticalAlignment = Alignment.Bottom) {
                Label("ink radio")
                Spacer(Modifier.weight(1f))
                Word("all", pixel(12.sp, Ink.mid), Modifier.padding(end = 16.dp), touch = 8.dp) { pages.open(Page.InkRadio) }
            }
            val starred = ink.filter { it.star }
            ShelfIndexed(starred) { i, st ->
                val on = now?.song?.id == st.song.id
                RadioTile(st.song.title.lowercase(), on, now?.playing == true, said = if (on) "on now" else st.city) {
                    Playback.play(starred.map { it.song }, i)
                }
            }
        }
        // the week on indie radio: what KEXP, KCRW and WXPN played most
        if (indie.isNotEmpty()) item {
            val songs = remember(indie) { indie.mapNotNull { it.song } }
            Label("the week on indie radio")
            ShelfIndexed(indie) { i, e -> Tile(e.song, e.title, e.artist, number = i + 1) { Playback.play(songs, i) } }
        }
        if (tubeArtists.isNotEmpty()) item {
            Label("your artists on youtube music")
            Shelf(tubeArtists) { a -> Tile(null, a.name, "artist", face = a.face) { pages.open(Page.TubeArtist(a)) } }
        }
        if (tubeRecords.isNotEmpty()) item {
            Label("your records on youtube music")
            Shelf(tubeRecords) { a -> TubeTile(a, Modifier.width(140.dp)) { pages.open(Page.Tube(a)) } }
        }
        if (records.isNotEmpty()) item {
            Label("from artists you love")
            Shelf(records) { a -> TubeTile(a, Modifier.width(140.dp)) { pages.open(Page.Tube(a)) } }
        }
        if (newest.isNotEmpty()) item {
            Label("new on your server")
            Shelf(newest) { a -> RecordTile(a, Modifier.width(140.dp)) { pages.open(Page.Record(a.id)) } }
        }
        if (yours.isNotEmpty()) item {
            Label("from your records")
            Shelf(yours) { a -> RecordTile(a, Modifier.width(140.dp)) { pages.open(Page.Record(a.id)) } }
        }
        // the genres you like, their stations each
        stations.forEach { (word, list) ->
            item {
                val now by Playback.now.collectAsState()
                Label("$word radio")
                ShelfIndexed(list.take(12)) { i, s ->
                    val on = now?.song?.id == s.id
                    RadioTile(s.title.lowercase(), on, now?.playing == true, said = if (on) "on now" else word) { Playback.play(list, i) }
                }
            }
        }
        if (radio.isNotEmpty()) item {
            Label("radio")
            val now by Playback.now.collectAsState()
            Shelf(radio) { (word, tag) ->
                val on = now?.song?.let { s -> s.station && Stations.found(tag)?.any { it.id == s.id } == true } == true
                // tapped, its stations play (next is the next station)
                RadioTile(word, on, now?.playing == true) {
                    scope.launch { Stations.top(ctx, tag).takeIf { it.isNotEmpty() }?.let { Playback.play(it, 0) } }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * A kind of radio: no cover, so a word, as ink does where a word works: the genre big in the
 * pixel face on black in a hairline frame, and a little equaliser of pixels that moves while it plays.
 */
@Composable
private fun RadioTile(word: String, on: Boolean, moving: Boolean, said: String? = null, onClick: () -> Unit) {
    Column(Modifier.width(140.dp).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick))) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Ink.black).border(1.dp, if (on) Ink.mid else Ink.line)) {
            BasicText(word, style = pixel(fit(word), if (on) Ink.ink else Ink.mid), maxLines = 4,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.TopStart).padding(14.dp))
            Bars(on && moving, if (on) Ink.ink else Ink.dim, Modifier.align(Alignment.BottomStart).padding(14.dp))
        }
        Spacer(Modifier.height(8.dp))
        BasicText(word.uppercase(), style = headline(15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicText(if (on) "on now" else said ?: "stations", style = pixel(12.sp, Ink.mid), maxLines = 1)
    }
}

/**
 * A tile's word as big as fits its 112 dp across (a Silkscreen letter is about 0.55 of its size
 * wide), 26 sp at most: "classical" and "electronic" broke mid-word at 26. A station's long name wraps.
 */
private fun fit(word: String) = (112f / (word.substringBefore(' ').length * .55f)).coerceIn(16f, 26f).sp

/** Seven bars of pixels, still at rest, rising and falling while the radio plays. */
@Composable
private fun Bars(moving: Boolean, colour: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    val rest = listOf(2, 4, 3, 5, 2, 4, 3)
    val t = rememberInfiniteTransition(label = "bars")
    val beat by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "beat")
    Row(modifier.height(30.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        rest.forEachIndexed { i, r ->
            val h = if (!moving) r else 1 + ((kotlin.math.sin((beat * 2 + i * .37f) * kotlin.math.PI * 2).toFloat() + 1f) * 2.5f).toInt()
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) { repeat(h) { Box(Modifier.size(5.dp, 3.dp).background(colour)) } }
        }
    }
}

/**
 * ink radio, all of it: each station's name, the line on it, its city and kind; a tap plays from
 * there (next is the next station).
 */
@Composable
fun InkRadioPage(pages: Pages) {
    val ctx = LocalContext.current
    val all = remember { Stations.curated(ctx) }
    val now by Playback.now.collectAsState()
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    FrostedPage(onClose = { pages.back() }) { frost, top -> LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
    ) {
        item {
            BasicText("INK RADIO", style = headline(44.sp))
            Spacer(Modifier.height(8.dp))
            BasicText("${all.size} stations chosen by hand: independent, listener-powered and community radio, from seattle to tbilisi",
                style = pixel(12.sp, Ink.mid))
            Spacer(Modifier.height(16.dp))
            Row {
                Word("play", pixel(14.sp), Modifier.padding(end = 26.dp), touch = 8.dp) { Playback.play(all.map { it.song }, 0) }
                Word("shuffle", pixel(14.sp, Ink.mid), touch = 8.dp) { Playback.play(all.map { it.song }.shuffled(), 0) }
            }
            Spacer(Modifier.height(14.dp))
        }
        itemsIndexed(all, key = { _, st -> st.song.id }) { i, st ->
            val on = now?.song?.id == st.song.id
            Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null,
                onClick = felt { Playback.play(all.map { it.song }, i) }).padding(vertical = 10.dp)) {
                BasicText(st.song.title.uppercase(), style = headline(22.sp, if (on || now == null) Ink.ink else Ink.mid), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (st.line.isNotEmpty()) BasicText(st.line, style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 3.dp))
                BasicText(if (on) "on now" else listOf(st.city, st.genre).filter { it.isNotEmpty() }.joinToString("   "),
                    style = pixel(12.sp, if (on) Ink.ink else Ink.mid), modifier = Modifier.padding(top = 3.dp))
            }
        }
    } }
}

/** A shelf: a row of tiles, sliding sideways, the page's margins at its ends. */
@Composable
private fun <T> Shelf(items: List<T>, tile: @Composable (T) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        items(items) { tile(it) }
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun <T> ShelfIndexed(items: List<T>, tile: @Composable (Int, T) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        itemsIndexed(items) { i, it -> tile(i, it) }
    }
    Spacer(Modifier.height(10.dp))
}

/** A tile: a cover (or an artist's photo), a name big, a word small; numbered on a chart. */
@Composable
private fun Tile(song: Song?, name: String, said: String, face: String? = null, number: Int? = null, onClick: () -> Unit) {
    val shown = remember(song?.id, face) { if (face != null) faceSong(name, face) else song }
    Column(Modifier.width(140.dp).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick))) {
        CoverOf(shown, name, Modifier.fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Top) {
            number?.let { BasicText("$it", style = pixel(12.sp, Ink.mid), modifier = Modifier.width(26.dp).padding(top = 2.dp)) }
            Column {
                BasicText(name.uppercase(), style = headline(15.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                BasicText(said.lowercase(), style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** An artist's photo as a song-shaped face, so it loads and is kept as covers are. */
fun faceSong(name: String, face: String) = Song(
    id = "yface:$name", uri = android.net.Uri.EMPTY, title = name, artist = name, album = name, albumId = "face:$name",
    albumArtist = name, duration = 0, disc = 1, track = 0, year = 0, art = face,
)
