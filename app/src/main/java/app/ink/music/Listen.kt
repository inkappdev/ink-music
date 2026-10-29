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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    /** When the listen page was first shown this run (its shelves cascade only then). */
    var shownAt = 0L
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
            // each artist's best-known song (their mix starts from it), found once and kept: looked for side by side,
            // written down one at a time
            Seeds.artists.value.filter { it.hit == null }.map { a -> async { a.name to YouTubeMusic.hit(a.name) } }.awaitAll()
                .forEach { (name, hit) -> hit?.let { Seeds.found(name, it) } }
        }
        scope.launch {
            // their records: as they were last time at once, then read again side by side
            if (records.value.isEmpty()) records.value = keptRecords(c)
            val found = Seeds.artists.value.take(8).map { a -> async { YouTubeMusic.records(a.name).take(2) } }.awaitAll().flatten()
            if (found.isNotEmpty()) { records.value = found; keepRecords(c, found) }
        }
    }

    private fun recordsFile(c: Context) = java.io.File(c.filesDir, "listen-records.json")

    private fun keptRecords(c: Context): List<TubeAlbum> = runCatching {
        val a = org.json.JSONArray(recordsFile(c).readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map { TubeAlbum(it.getString("url"), it.getString("title"), it.getString("artist"), it.optString("art").ifEmpty { null }) }
    }.getOrDefault(emptyList())

    private fun keepRecords(c: Context, list: List<TubeAlbum>) = runCatching {
        recordsFile(c).writeText(org.json.JSONArray(list.map { org.json.JSONObject().put("url", it.url).put("title", it.title).put("artist", it.artist).put("art", it.art.orEmpty()) }).toString())
    }

    /**
     * An artist's mix: their song at once, and YouTube Music's radio from it behind it once it's read
     * (a second or two; a speaker takes the whole queue, so it waits for it).
     */
    suspend fun mix(hit: Song) {
        if (Casting.on.value != null) return Playback.play(listOf(hit) + withContext(Dispatchers.IO) { YouTubeMusic.related(hit.id) }, 0)
        Playback.play(listOf(hit), 0)
        Playback.follow(hit, withContext(Dispatchers.IO) { YouTubeMusic.related(hit.id) })
    }

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
 * you played lately), your mixes on youtube music, mixes for you, on repeat, new from your artists,
 * ink radio, the week on indie radio, forgotten favorites, your playlists, your artists and records
 * on youtube music, from artists you love, new on your server, from your records, and radio.
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
    LaunchedEffect(seeds.map { it.name }) { Listen.load(ctx); Weekly.load(ctx) }
    val weekly by Weekly.weeks.collectAsState()
    // your server's records kept on the phone: with no signal they come first, "no data? no problem"
    val kept by Offline.kept.collectAsState()
    val keptRecords = remember(kept, albums) { albums.filter { a -> a.songs.any { it.id in kept } } }
    val online = remember(kept) { Quality.online(ctx) }
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
    val tubeMixes by YouTubeAccount.mixes.collectAsState()
    val fresh by YouTubeAccount.fresh.collectAsState()
    val tubeLists by YouTubeAccount.playlists.collectAsState()
    val uploads by YouTubeAccount.uploads.collectAsState()
    val liked by YouTubeAccount.liked.collectAsState()
    val lovedIds by Loved.ids.collectAsState()
    val lovedTube by Loved.tube.collectAsState()
    val songs by Library.songs.collectAsState()
    val recentSongs = remember(recent, songs) { recent.mapNotNull { Library.song(it.id) } }
    LaunchedEffect(songs.isNotEmpty()) { Discover.start(ctx, songs) }
    // a played song as one to play again: the library's, else a YouTube song this run knows (liked, heard there, loved)
    val known = remember(liked, tubeHeard, lovedTube) { (liked + tubeHeard + lovedTube).associateBy { it.id } }
    fun songOf(id: String) = Library.song(id) ?: known[id] ?: YouTubeMusic.known(id)
    // on repeat: what this phone played most these 30 days, twice or more
    val onRepeat = remember(heard, known, songs) {
        val since = System.currentTimeMillis() - 30L * 86_400_000
        heard.filter { it.at > since }.groupingBy { it.id }.eachCount().filter { it.value >= 2 }
            .entries.sortedByDescending { it.value }.mapNotNull { songOf(it.key) }.take(12)
    }
    // forgotten favorites: songs you love or liked, not heard these three months (here or on youtube music),
    // nor loved this last month; a different handful each day
    val forgotten = remember(heard, tubeHeard, liked, lovedIds, lovedTube, songs) {
        val now = System.currentTimeMillis()
        val lately = heard.filter { it.at > now - 90L * 86_400_000 }.map { it.id }.toSet() + tubeHeard.map { it.id }
        (liked + lovedTube + songs.filter { it.id in lovedIds }).distinctBy { it.id }
            .filter { it.id !in lately && (Loved.lovedAt(it) ?: 0L) < now - 30L * 86_400_000 }
            .shuffled(kotlin.random.Random(now / 86_400_000)).take(15)
    }
    val newest = remember(newestIds, albums) { newestIds.mapNotNull { id -> albums.firstOrNull { it.id == id } } }
    val yours = remember(albums, seed) { Order.of(albums, seed) { it.id }.take(12) }
    val mixes = seeds.filter { it.hit != null }

    // the shelves arrive in turn once, as ink music opens (a tab, not a page: its own time, kept for the run)
    if (Listen.shownAt == 0L) Listen.shownAt = android.os.SystemClock.uptimeMillis()
    androidx.compose.runtime.CompositionLocalProvider(LocalOpened provides Listen.shownAt) {
    LazyColumn(state = state, contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding())) {
        item { Column(Modifier.cascade(0)) {
            Row(Modifier.padding(horizontal = 20.dp).padding(top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    BasicText(Listen.greeting(), style = headline(40.sp), maxLines = 2)
                    BasicText(java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.getDefault()).format(java.util.Date()).lowercase(),
                        style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 6.dp))
                    // private listening on: said here, so it isn't left on unawares
                    if (Private.on(ctx)) BasicText("private listening: nothing kept", style = pixel(12.sp), modifier = Modifier.padding(top = 4.dp))
                }
                Cog { pages.open(Page.Settings) }
            }
        }}
        if (!online && keptRecords.isNotEmpty()) item { Column(Modifier.cascade(1)) { KeptShelf(keptRecords, "no data? no problem", pages) }}
        // ink weekly: this week's songs, picked by hand (a new week Friday nights)
        weekly.firstOrNull()?.let { w ->
            item { Column(Modifier.cascade(2)) { WeeklyBlock(w) { pages.open(Page.Weekly(w.week)) } }}
        }
        // made from your listening (Discover): fresh finds, deep cuts and the rest
        item { Column(Modifier.cascade(3)) { MadeForYouShelf() } }
        if (recentSongs.isNotEmpty() || tubeRecent.isNotEmpty()) item { Column(Modifier.cascade(3)) {
            Label("jump back in")
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // the library's songs only, found first: one skipped inside the row left its gap, pushing the row right
                items(recentSongs) { s ->
                    Tile(s, s.album.ifEmpty { s.title }, s.artist) {
                        val record = albums.firstOrNull { it.id == s.albumId }
                        if (record != null) pages.open(Page.Record(record.id)) else Playback.play(listOf(s), 0)
                    }
                }
                // youtube music's: played from there on, back through what you played before it
                itemsIndexed(tubeRecent) { i, s -> Tile(s, s.title, s.artist) { Playback.play(tubeRecent, i) } }
            }
            Spacer(Modifier.height(10.dp))
        }}
        if (online && keptRecords.isNotEmpty()) item { Column(Modifier.cascade(4)) { KeptShelf(keptRecords, "kept on this phone", pages) }}
        // youtube music's own mixes for you (signed in): supermix, discover, new release, replay
        if (tubeMixes.isNotEmpty()) item { Column(Modifier.cascade(5)) {
            Label("your mixes on youtube music")
            Shelf(tubeMixes) { l -> Tile(null, l.name, l.said.ifEmpty { "mix" }.lowercase(), face = l.art, dither = true) { pages.open(Page.TubeList(l)) } }
        }}
        if (mixes.isNotEmpty()) item { Column(Modifier.cascade(6)) {
            Label("mixes for you")
            Shelf(mixes) { a ->
                Tile(a.hit, a.name, "mix", face = a.face, dither = true) { scope.launch { Listen.mix(a.hit!!) } }
            }
        }}
        item { Column(Modifier.cascade(6)) { BecauseYouLovedShelf() } }
        item { Column(Modifier.cascade(7)) { NewToYouShelf(pages) } }
        if (onRepeat.size >= 3) item { Column(Modifier.cascade(7)) {
            Label("on repeat")
            ShelfIndexed(onRepeat) { i, s -> Tile(s, s.title, s.artist) { Playback.play(onRepeat, i) } }
        }}
        // what your youtube music artists put out this year, newest first (none, no row)
        if (fresh.isNotEmpty()) item { Column(Modifier.cascade(8)) {
            Label("new from your artists")
            Shelf(fresh) { a -> TubeTile(a, Modifier.width(140.dp)) { pages.open(Page.Tube(a)) } }
        }}
        // ink radio: the stations chosen by hand, the stars first
        item { Column(Modifier.cascade(9)) {
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
                StationTile(st.song, on, now?.playing == true, said = if (on) "on now" else st.city) {
                    Playback.play(starred.map { it.song }, i)
                }
            }
        }}
        // the week on indie radio: what KEXP, KCRW and WXPN played most
        if (indie.isNotEmpty()) item { Column(Modifier.cascade(10)) {
            val songs = remember(indie) { indie.mapNotNull { it.song } }
            Label("the week on indie radio")
            ShelfIndexed(indie) { i, e -> Tile(e.song, e.title, e.artist, number = i + 1) { Playback.play(songs, i) } }
        }}
        if (forgotten.size >= 4) item { Column(Modifier.cascade(11)) {
            Label("forgotten favorites")
            ShelfIndexed(forgotten) { i, s -> Tile(s, s.title, s.artist) { Playback.play(forgotten, i) } }
        }}
        // the songs you've uploaded to youtube music yourself (they play signed in)
        if (uploads.isNotEmpty()) item { Column(Modifier.cascade(12)) {
            Label("your uploads on youtube music")
            ShelfIndexed(uploads) { i, s -> Tile(s, s.title, s.artist) { Playback.play(uploads, i) } }
        }}
        if (tubeLists.isNotEmpty()) item { Column(Modifier.cascade(13)) {
            Label("your playlists")
            Shelf(tubeLists) { l -> Tile(null, l.name, "playlist", face = l.art, dither = true) { pages.open(Page.TubeList(l)) } }
        }}
        if (tubeArtists.isNotEmpty()) item { Column(Modifier.cascade(14)) {
            Label("your artists on youtube music")
            Shelf(tubeArtists) { a -> Tile(null, a.name, "artist", face = a.face) { pages.open(Page.TubeArtist(a)) } }
        }}
        if (tubeRecords.isNotEmpty()) item { Column(Modifier.cascade(15)) {
            Label("your records on youtube music")
            Shelf(tubeRecords) { a -> TubeTile(a, Modifier.width(140.dp)) { pages.open(Page.Tube(a)) } }
        }}
        if (records.isNotEmpty()) item { Column(Modifier.cascade(16)) {
            Label("from artists you love")
            Shelf(records) { a -> TubeTile(a, Modifier.width(140.dp)) { pages.open(Page.Tube(a)) } }
        }}
        if (newest.isNotEmpty()) item { Column(Modifier.cascade(17)) {
            Label("new on your server")
            Shelf(newest) { a -> RecordTile(a, Modifier.width(140.dp)) { pages.open(Page.Record(a.id)) } }
        }}
        if (yours.isNotEmpty()) item { Column(Modifier.cascade(18)) {
            Label("from your records")
            Shelf(yours) { a -> RecordTile(a, Modifier.width(140.dp)) { pages.open(Page.Record(a.id)) } }
        }}
        // the genres you like, their stations each
        stations.forEach { (word, list) ->
            item { Column(Modifier.cascade(19)) {
                val now by Playback.now.collectAsState()
                Label("$word radio")
                ShelfIndexed(list.take(12)) { i, s ->
                    val on = now?.song?.id == s.id
                    StationTile(s, on, now?.playing == true, said = if (on) "on now" else word) { Playback.play(list, i) }
                }
            }}
        }
        if (radio.isNotEmpty()) item { Column(Modifier.cascade(20)) {
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
        }}
    }
    }
}

/**
 * A kind of radio: no cover, so a word, as ink does where a word works: the genre big in the
 * pixel face on black in a hairline frame, and a little equaliser of pixels that moves while it plays.
 */
@Composable
internal fun RadioTile(word: String, on: Boolean, moving: Boolean, said: String? = null, onClick: () -> Unit) {
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

/** A station as a tile: its sleeve (framed, its rings moving while it plays), its name, where or what's on. */
@Composable
internal fun StationTile(station: Song, on: Boolean, moving: Boolean, said: String, onClick: () -> Unit) {
    Column(Modifier.width(140.dp).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick))) {
        StationCover(station, Modifier.fillMaxWidth().aspectRatio(1f), moving = on && moving)
        Spacer(Modifier.height(8.dp))
        BasicText(station.title.uppercase(), style = headline(15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicText(said.lowercase(), style = pixel(12.sp, if (on) Ink.ink else Ink.mid), maxLines = 1)
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
    FrostedPage(onClose = { pages.close() }) { frost, top -> LazyColumn(
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
internal fun <T> Shelf(items: List<T>, tile: @Composable (T) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        items(items) { tile(it) }
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
internal fun <T> ShelfIndexed(items: List<T>, tile: @Composable (Int, T) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        itemsIndexed(items) { i, it -> tile(i, it) }
    }
    Spacer(Modifier.height(10.dp))
}

/** Your server's records kept on the phone, as covers: each opens its page (and plays from the phone). */
@Composable
private fun KeptShelf(records: List<Album>, label: String, pages: Pages) {
    Label(label)
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        items(records, key = { it.id }) { a -> RecordTile(a, Modifier.width(140.dp)) { pages.open(Page.Record(a.id)) } }
    }
    Spacer(Modifier.height(10.dp))
}

/** This week's ink weekly on listen: its mosaic, its name big, its note, play, shuffle; a tap opens the week. */
@Composable
private fun WeeklyBlock(w: Weekly.Week, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onOpen))
        .padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 22.dp)) {
        BasicText(listOf("ink weekly", Weekly.day(w.date)).filter { it.isNotEmpty() }.joinToString("   "), style = pixel(12.sp, Ink.mid))
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            // its name on its covers, on a black tab in their corner (a sleeve's label: pale covers would swallow it)
            Box(Modifier.size(140.dp), contentAlignment = Alignment.BottomStart) {
                Mosaic(w.songs, Modifier.fillMaxSize())
                BasicText(w.title.uppercase(), style = headline(24.sp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.background(Ink.black).padding(start = 6.dp, end = 8.dp, top = 4.dp, bottom = 2.dp))
            }
            if (w.note.isNotEmpty()) {
                Spacer(Modifier.width(16.dp))
                BasicText(w.note, style = pixel(12.sp, Ink.ink.copy(alpha = .75f)).copy(lineHeight = 19.sp), maxLines = 5,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(12.dp))
        Row {
            Word("play", pixel(14.sp), Modifier.padding(end = 26.dp), touch = 8.dp) { Playback.play(w.songs, 0) }
            Word("shuffle", pixel(14.sp, Ink.mid), Modifier.padding(end = 26.dp), touch = 8.dp) { Playback.play(w.songs.shuffled(), 0) }
            Word("${w.songs.size} songs", pixel(14.sp, Ink.mid), touch = 8.dp, onClick = onOpen)
        }
    }
}

/** A tile: a cover (or an artist's photo), a name big, a word small; numbered on a chart. */
@Composable
internal fun Tile(song: Song?, name: String, said: String, face: String? = null, number: Int? = null, dither: Boolean = false, onClick: () -> Unit) {
    val shown = remember(song?.id, face) { if (face != null) faceSong(name, face) else song }
    Column(Modifier.width(140.dp).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick))) {
        // a mix or a playlist: its picture in the dither (YouTube's lettering on it made ink's)
        if (dither && shown?.art != null) Dithered(shown, Modifier.fillMaxWidth().aspectRatio(1f), px = 400)
        else CoverOf(shown, name, Modifier.fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Top) {
            number?.let { BasicText("$it", style = pixel(12.sp, Ink.mid), modifier = Modifier.width(26.dp).padding(top = 2.dp)) }
            UnderCover(name, said, pixel(12.sp, Ink.mid))
        }
    }
}

/** An artist's photo as a song-shaped face, so it loads and is kept as covers are. */
fun faceSong(name: String, face: String) = Song(
    id = "yface:$name", uri = android.net.Uri.EMPTY, title = name, artist = name, album = name, albumId = "face:$name",
    albumArtist = name, duration = 0, disc = 1, track = 0, year = 0, art = face,
)
