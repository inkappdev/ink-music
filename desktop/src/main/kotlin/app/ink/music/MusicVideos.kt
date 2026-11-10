package app.ink.music

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jetbrains.skia.Image as SkImage
import java.io.File

/**
 * New music videos from the artists you play (2026-11-07): about four a week from your top thirty, so a shelf on
 * listen, not a page of its own. Found once a day ([TubeVideos]: each artist's channel, its newest uploads, the real
 * videos picked out by title), kept on disk, watched here with their sound.
 */
object MusicVideos {
    /** A video: its id, its song's title and artist (tidied: "(Official Video)" and the artist's name out), when it went up. */
    data class Clip(val id: String, val title: String, val artist: String, val at: Long)

    private val _clips = MutableStateFlow<List<Clip>>(emptyList())
    val clips: StateFlow<List<Clip>> = _clips
    private val file = File(Home.cache, "videos.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false
    // each artist's channels, found once (none: asked again in a week)
    private val channels = java.util.concurrent.ConcurrentHashMap<String, Pair<List<TubeVideos.Channel>, Long>>()
    // an artist's videos, for their page: read once a run
    private val byArtist = java.util.concurrent.ConcurrentHashMap<String, List<Clip>>()

    /** An artist's music videos, newest first, of any age (a dozen at most): their page's shelf. */
    suspend fun of(name: String, browseId: String = ""): List<Clip> = withContext(Dispatchers.IO) {
        byArtist[name.lowercase()]?.let { return@withContext it }
        val now = System.currentTimeMillis()
        val theirs = channelsFor(name, browseId, now)
        val videos = theirs.flatMap { ch -> TubeVideos.uploads(ch.id).filter { TubeVideos.isVideo(it, name, ch) } }
            .distinctBy { it.id }.sortedBy { it.days }.take(12).map { clip(it, name, now) }
        videos.also { if (theirs.isNotEmpty()) byArtist[name.lowercase()] = it }
    }

    private suspend fun channelsFor(name: String, browseId: String, now: Long): List<TubeVideos.Channel> {
        channels[name.lowercase()]?.takeIf { it.first.isNotEmpty() || now - it.second < 7 * 86_400_000L }?.let { return it.first }
        val id = browseId.takeIf { it.startsWith("UC") }
            ?: withTimeoutOrNull(10_000) { Artists.of(name, "").firstOrNull { it != null } }?.id.orEmpty()
        return (if (id.startsWith("UC")) TubeVideos.channelsOf(name, id) else emptyList()).also { channels[name.lowercase()] = it to now }
    }

    private fun clip(u: TubeVideos.Upload, name: String, now: Long): Clip {
        val (title, _) = Pipe.tidy(u.title, name)
        // "Motive", in its quotes: without them
        return Clip(u.id, title.trim().removeSurrounding("\"").removeSurrounding("\u201c", "\u201d").ifEmpty { u.title }, name, now - u.days * 86_400_000L)
    }

    fun start() {
        if (started) return
        started = true
        scope.launch {
            val was = runCatching { JSONObject(file.readText()) }.getOrNull()
            was?.let { read(it) }
            // read again a day on
            if (was == null || System.currentTimeMillis() - was.optLong("at") > 20 * 3_600_000L) find()
        }
    }

    /** How long ago, as a person says it. */
    fun ago(at: Long): String {
        val d = ((System.currentTimeMillis() - at) / 86_400_000).toInt()
        return when { d < 1 -> "today"; d == 1 -> "yesterday"; d < 7 -> "$d days ago"; d < 14 -> "a week ago"; d < 60 -> "${d / 7} weeks ago"; d < 365 -> "${d / 30} months ago"; d < 730 -> "a year ago"; else -> "${d / 365} years ago" }
    }

    // whom you follow, then whom you've played most these three months
    private fun artists(): List<String> {
        val since = System.currentTimeMillis() - 90L * 86_400_000
        val played = History.read().filter { it.at >= since && it.artist.isNotBlank() }
            .groupingBy { it.artist.substringBefore(", ").trim() }.eachCount()
            .filterKeys { it != "unknown" && !it.equals("various artists", true) }
            .entries.sortedByDescending { it.value }.map { it.key }
        return (Follows.all.value.map { it.name } + played).distinctBy { it.lowercase() }.take(30)
    }

    private suspend fun find() {
        val now = System.currentTimeMillis()
        val found = ArrayList<Clip>()
        for (name in artists()) {
            if (!scope.isActive) return
            val theirChannels = channelsFor(name, "", now)
            val theirs = theirChannels.flatMap { ch -> TubeVideos.uploads(ch.id).filter { it.days <= 35 && TubeVideos.isVideo(it, name, ch) } }
                .distinctBy { it.id }.sortedBy { it.days }
            // an album's week: its videos, the newest two (the rest are its visualizers' kin)
            found += theirs.take(2).map { clip(it, name, now) }
            Log.d("videos: $name, ${theirChannels.joinToString { it.name }}: ${theirs.size}")
        }
        val clips = found.sortedByDescending { it.at }.take(16)
        _clips.value = clips
        runCatching { Home.write(file, write(now, clips).toString()) }
    }

    private fun read(o: JSONObject) {
        _clips.value = o.optJSONArray("clips")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
            .map { Clip(it.getString("id"), it.getString("title"), it.getString("artist"), it.getLong("when")) }
        o.optJSONObject("channels")?.let { c ->
            for (k in c.keys()) c.getJSONObject(k).let { j ->
                val list = j.optJSONArray("list")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
                    .map { TubeVideos.Channel(it.getString("id"), it.optString("name"), it.optBoolean("own")) }
                channels[k] = list to j.optLong("at")
            }
        }
    }

    private fun write(now: Long, clips: List<Clip>) = JSONObject().put("at", now)
        .put("clips", JSONArray(clips.map { JSONObject().put("id", it.id).put("title", it.title).put("artist", it.artist).put("when", it.at) }))
        .put("channels", JSONObject().apply {
            channels.forEach { (k, v) ->
                put(k, JSONObject().put("at", v.second).put("list", JSONArray(v.first.map { JSONObject().put("id", it.id).put("name", it.name).put("own", it.own) })))
            }
        })

    // ---- the pictures: a video's frame, wide as it is (a cover's cut square) ----

    class Frame(val image: ImageBitmap, val accent: Color)
    private val frames = Lru<String, Frame>(64 * 1024 * 1024) { it.image.width * it.image.height * 4 }

    /** A video's frame (YouTube's wide one, else its small one), about 640 across. */
    suspend fun frame(id: String): Frame? = frames.get(id) ?: withContext(Dispatchers.IO) {
        val bytes = listOf("hq720", "mqdefault").firstNotNullOfOrNull { k ->
            runCatching {
                val c = java.net.URL("https://i.ytimg.com/vi/$id/$k.jpg").openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 8_000; c.readTimeout = 12_000
                try { if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null } finally { c.disconnect() }
            }.getOrNull()
        } ?: return@withContext null
        val img = runCatching { trim(SkImage.makeFromEncoded(bytes)) }.getOrNull() ?: return@withContext null
        Frame(img.toComposeImageBitmap(), Covers.accentOf(img)).also { frames.put(id, it) }
    }

    // the black bars in a frame itself (a wide film's, a phone video's), cut off: the picture fills its tile
    private fun trim(img: SkImage): SkImage {
        val b = org.jetbrains.skia.Bitmap.makeFromImage(img)
        fun dark(x: Int, y: Int) = b.getColor(x, y).let { c -> ((c shr 16) and 255) + ((c shr 8) and 255) + (c and 255) < 60 }
        fun rowDark(y: Int) = (0 until b.width step 8).all { dark(it, y) }
        fun colDark(x: Int) = (0 until b.height step 8).all { dark(x, it) }
        var top = 0; var bottom = b.height - 1; var left = 0; var right = b.width - 1
        while (top < b.height / 3 && rowDark(top)) top++
        while (bottom > b.height * 2 / 3 && rowDark(bottom)) bottom--
        while (left < b.width / 3 && colDark(left)) left++
        while (right > b.width * 2 / 3 && colDark(right)) right--
        if (top == 0 && left == 0 && bottom == b.height - 1 && right == b.width - 1) return img
        val out = org.jetbrains.skia.Bitmap()
        if (!b.extractSubset(out, org.jetbrains.skia.IRect.makeLTRB(left, top, right + 1, bottom + 1))) return img
        return SkImage.makeFromBitmap(out)
    }

    /** What it plays from: the picture and its sound, found as it's opened (they expire). */
    fun stream(id: String): Pair<String, String?>? = Pipe.streamInfo(id)?.let { Pipe.video(it) }
}

/** A video watched with its sound: space pauses it (not the music, which waits under it). */
object Watching {
    @Volatile var toggle: (() -> Unit)? = null
    /** The video moved by this much (the arrows, while one's open; not the music under it). */
    @Volatile var seekBy: ((Long) -> Unit)? = null
    /** Full screen on or off (f, a double click, esc to leave). */
    @Volatile var setFull: ((Boolean) -> Unit)? = null
    /** A video full screen, in its own window over the screen (the app's window left as it is). */
    val full = kotlinx.coroutines.flow.MutableStateFlow(false)
}

/** Listen's shelf of them: each frame wide, its song and artist, how long ago. */
@Composable
fun NewVideos(open: (Page) -> Unit) {
    val clips by MusicVideos.clips.collectAsState()
    LaunchedEffect(Unit) { MusicVideos.start() }
    if (clips.isEmpty()) return
    Column {
        Section("NEW VIDEOS", "from the artists you play")
        Shelf(clips.size, 300.dp, cover = 16 / 9f) { each ->
            items(clips, key = { it.id }) { c ->
                Column(Modifier.width(each).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { open(Page.Watch(c, clips)) })) {
                    VideoFrame(c, Modifier.fillMaxWidth().aspectRatio(16 / 9f))
                    Spacer(Modifier.height(8.dp))
                    UnderCover(c.title, "${c.artist}  ·  ${MusicVideos.ago(c.at)}")
                }
            }
        }
    }
}

@Composable
private fun VideoFrame(c: MusicVideos.Clip, modifier: Modifier) {
    val f by produceState<MusicVideos.Frame?>(null, c.id) { value = MusicVideos.frame(c.id) }
    BoxWithConstraints(modifier) {
        f?.let { Image(it.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } ?: NameCover(c.title, maxWidth)
    }
}

/**
 * A video watched: as big as the window lets it, the line along its top edge in its colour (the times over it), its
 * song and artist under it, pause and the next one. The music pauses for it, and it for the music.
 */
@Composable
fun WatchPage(c: MusicVideos.Clip, list: List<MusicVideos.Clip>, open: (Page) -> Unit, onClose: () -> Unit) {
    val newest by MusicVideos.clips.collectAsState()
    // the videos it was opened among (the shelf's, an artist's): next is the next of them
    val clips = list.ifEmpty { newest }
    val frame by produceState<MusicVideos.Frame?>(null, c.id) { value = MusicVideos.frame(c.id) }
    val accent = frame?.accent ?: Ink.mid
    var said by remember(c.id) { mutableStateOf("finding it") }
    val stream by produceState<Pair<String, String?>?>(null, c.id) {
        value = withContext(Dispatchers.IO) { MusicVideos.stream(c.id) }
        if (value == null) said = "youtube wouldn't play this one"
    }
    val volume by Player.volume.collectAsState()
    val video = remember(stream) {
        stream?.let { (pic, snd) ->
            runCatching { Video(pic, 1280, 720, sound = Video.Sound(snd, Player.volume.value, Player.deviceNow())) }
                .onFailure { Log.d("watch: $it"); said = "it wouldn't start" }.getOrNull()
        }
    }
    var paused by remember(video) { mutableStateOf(false) }
    var at by remember(video) { mutableLongStateOf(0L) }
    var length by remember(video) { mutableLongStateOf(0L) }
    val now by Player.now.collectAsState()
    DisposableEffect(video) {
        val v = video
        if (v != null) {
            // the music waits under it
            if (Player.now.value?.playing == true) Player.toggle()
            Watching.toggle = { paused = !paused; v.pause(paused) }
        }
        onDispose { Watching.toggle = null; v?.close() }
    }
    // the music played again (it was paused for this): this pauses
    var hushed by remember(video) { mutableStateOf(false) }
    LaunchedEffect(now?.playing, video) {
        if (video == null) return@LaunchedEffect
        if (now?.playing != true) hushed = true
        else if (hushed && !paused) { paused = true; video.pause(true) }
    }
    LaunchedEffect(volume, video) { video?.volume(volume) }
    LaunchedEffect(video) {
        val v = video ?: return@LaunchedEffect
        while (isActive) {
            at = v.at(); length = v.length()
            if (length > 0 && v.ended()) {
                // played out: the next one, if there is one
                clips.getOrNull(clips.indexOfFirst { it.id == c.id } + 1)?.let { Nav.back(); open(Page.Watch(it, clips)) } ?: break
                break
            }
            delay(250)
        }
    }
    // full screen (2026-11-10): the video in a window of its own over the whole screen, the picture alone on black at its
    // own shape, the controls over it; the app's window left as it was (putting the app itself full screen kept the rail
    // and the bar along the foot round the picture, and is another thing). Leaving the page leaves full screen too
    var full by remember { mutableStateOf(false) }
    LaunchedEffect(full) { Watching.full.value = full }
    DisposableEffect(video) {
        Watching.seekBy = { d -> video?.let { v -> v.seek((v.at() + d).coerceIn(0, (v.length() - 1_000).coerceAtLeast(0))) } }
        Watching.setFull = { full = it }
        onDispose { Watching.seekBy = null; Watching.setFull = null }
    }
    DisposableEffect(Unit) { onDispose { Watching.full.value = false } }
    val shape = frame?.image?.let { (it.width.toFloat() / it.height).coerceIn(1.2f, 2.8f) } ?: (16 / 9f)
    val next = clips.getOrNull(clips.indexOfFirst { it.id == c.id } + 1)
    @Composable
    fun Picture(modifier: Modifier, fit: ContentScale) = VideoSurface(
        video, frame?.image, said, accent, paused, full,
        title = c.title, artist = c.artist, fit = fit, modifier = modifier,
        onToggle = { Watching.toggle?.invoke() }, onSeek = { ms -> video?.seek(ms) }, onFull = { full = !full },
        onNext = next?.let { n -> { Nav.back(); open(Page.Watch(n, clips)) } },
    )
    if (full) androidx.compose.ui.window.Window(
        onCloseRequest = { full = false },
        state = androidx.compose.ui.window.rememberWindowState(placement = androidx.compose.ui.window.WindowPlacement.Fullscreen),
        // with its title bar, which full screen hides: the Mac makes only a titled window full screen (without, it kept
        // its first size in a space of its own)
        title = c.title,
        onKeyEvent = { k ->
            if (k.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@Window false
            when (k.key) {
                androidx.compose.ui.input.key.Key.Escape, androidx.compose.ui.input.key.Key.F -> { full = false; true }
                androidx.compose.ui.input.key.Key.Spacebar -> { Watching.toggle?.invoke(); true }
                androidx.compose.ui.input.key.Key.DirectionRight -> { Watching.seekBy?.invoke(10_000); true }
                androidx.compose.ui.input.key.Key.DirectionLeft -> { Watching.seekBy?.invoke(-10_000); true }
                else -> false
            }
        },
    ) { Picture(Modifier.fillMaxSize().background(Color.Black), ContentScale.Fit) }
    Column(Modifier.fillMaxSize()) {
        Close(onClose)
        BoxWithConstraints(Modifier.fillMaxSize().padding(start = SIDE, end = SIDE, top = 12.dp, bottom = 28.dp)) {
            // its own shape (a wide film's bars baked into YouTube's 16:9 cut off, as its frame's were), as wide as fits
            // with its words under it
            val w = minOf(maxWidth, (maxHeight - 100.dp) * shape)
            Column(Modifier.width(w).align(Alignment.Center)) {
                if (!full) Picture(Modifier.fillMaxWidth().aspectRatio(shape).background(Color.Black), ContentScale.Crop)
                else Box(Modifier.fillMaxWidth().aspectRatio(shape).background(Color.Black)) {
                    Word("playing full screen · back here", pixel(12.sp, Ink.mid), Modifier.align(Alignment.Center), touch = 8.dp) { full = false }
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        BasicText(c.title.uppercase(), style = headline(30.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Row {
                            Word(c.artist.lowercase(), pixel(14.sp)) { open(Page.Artist(c.artist)) }
                            BasicText("  ·  ${MusicVideos.ago(c.at)}", style = pixel(14.sp, Ink.mid))
                        }
                    }
                    // stop: the video gone, back where it was opened from (play, the line, next and full screen on the picture)
                    Word("stop", pixel(14.sp, Ink.mid), touch = 8.dp) { Nav.back() }
                    next?.let { n ->
                        Spacer(Modifier.size(20.dp))
                        Word("next video", pixel(14.sp, Ink.mid), touch = 8.dp) { Nav.back(); open(Page.Watch(n, clips)) }
                    }
                }
            }
        }
    }
}

/**
 * The picture and its controls over it (2026-11-10): the controls along its foot on a shade, the line across them in the
 * video's colour (a click or a drag goes there, the time under the mouse shown), play or pause, the times, its song in
 * full screen, the next video, full screen; shown while the mouse moves over it or it's paused, fading two and a half
 * seconds after it stops (the pointer too, in full screen). A click pauses, a double click is full screen.
 */
@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun VideoSurface(
    video: Video?, still: ImageBitmap?, said: String, accent: Color, paused: Boolean,
    full: Boolean, title: String, artist: String, fit: ContentScale, modifier: Modifier,
    onToggle: () -> Unit, onSeek: (Long) -> Unit, onFull: () -> Unit, onNext: (() -> Unit)?,
) {
    // the frames and the time read here, in the window showing them: full screen is its own window, and the app's,
    // covered by it, is slowed by the Mac to a few frames a second (2026-11-10: 5 fps when read through the page)
    val picture = video?.frame?.collectAsState()?.value
    val ready = video != null
    var at by remember(video) { mutableLongStateOf(0L) }
    var length by remember(video) { mutableLongStateOf(0L) }
    LaunchedEffect(video) {
        val v = video ?: return@LaunchedEffect
        while (true) { at = v.at(); length = v.length(); kotlinx.coroutines.delay(250) }
    }
    var moved by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var over by remember { mutableStateOf(false) }
    var awake by remember { mutableStateOf(true) }
    LaunchedEffect(moved, over, paused) {
        awake = true
        if (!paused) { kotlinx.coroutines.delay(2_500); awake = false }
    }
    val shown = (awake && (over || full)) || paused || !ready
    val fade by androidx.compose.animation.core.animateFloatAsState(if (shown) 1f else 0f, androidx.compose.animation.core.tween(if (shown) Motion.FADE else 600), label = "controls")
    Box(modifier
        .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move) { moved = System.currentTimeMillis(); over = true }
        .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Enter) { over = true }
        .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Exit) { over = false }
        .then(if (full && !shown) Modifier.pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon(java.awt.Toolkit.getDefaultToolkit()
            .createCustomCursor(java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB), java.awt.Point(0, 0), "none"))) else Modifier)
        .pointerInput(ready) { if (ready) detectTapGestures(onDoubleTap = { onFull() }, onTap = { onToggle() }) }) {
        when {
            picture != null -> Image(picture, null, Modifier.fillMaxSize(), contentScale = fit)
            still != null -> Image(still, null, Modifier.fillMaxSize(), contentScale = fit)
        }
        if (picture == null) BasicText(said, style = pixel(12.sp, Ink.ink), modifier = Modifier.align(Alignment.TopStart).padding(16.dp))
        if (!ready) return@Box
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().graphicsLayer { alpha = fade }
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .55f), Color.Black.copy(alpha = .85f))))
            .padding(start = if (full) 40.dp else 18.dp, end = if (full) 40.dp else 18.dp, top = if (full) 80.dp else 48.dp, bottom = if (full) 32.dp else 14.dp)) {
            if (full) {
                BasicText(title.uppercase(), style = headline(44.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText(artist.lowercase(), style = pixel(14.sp, Ink.mid))
                Spacer(Modifier.height(18.dp))
            }
            SeekLine(at, length, accent, onSeek)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pixel(if (paused) PLAY else PAUSE, if (paused) "play" else "pause", Ink.ink, cell = if (full) 3.dp else 2.dp, onClick = onToggle)
                Spacer(Modifier.width(22.dp))
                BasicText("${time(at)} / ${if (length > 0) time(length) else "–"}", style = pixel(12.sp, Ink.mid))
                Spacer(Modifier.weight(1f))
                onNext?.let { Pixel(NEXT, "next video", Ink.ink, cell = if (full) 3.dp else 2.dp, onClick = it); Spacer(Modifier.width(22.dp)) }
                Pixel(if (full) UNFULL else FULL, if (full) "leave full screen" else "full screen", Ink.ink, cell = if (full) 3.dp else 2.dp, onClick = onFull)
            }
        }
    }
}

/** The video's line: where it is in its colour, a square at that place; a click or a drag goes there, the time under the mouse said above it. */
@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun SeekLine(at: Long, length: Long, accent: Color, onSeek: (Long) -> Unit) {
    var hover by remember { mutableStateOf<Float?>(null) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shownAt = dragging ?: (if (length > 0) (at.toFloat() / length).coerceIn(0f, 1f) else 0f)
    BoxWithConstraints(Modifier.fillMaxWidth().height(22.dp)
        .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move) { e -> hover = (e.changes.first().position.x / size.width).coerceIn(0f, 1f) }
        .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Exit) { hover = null }
        .pointerInput(length) {
            detectTapGestures { p -> if (length > 0) onSeek((p.x / size.width * length).toLong()) }
        }
        .pointerInput(length) {
            detectHorizontalDragGestures(
                onDragStart = { p -> dragging = (p.x / size.width).coerceIn(0f, 1f) },
                onDragEnd = { dragging?.let { if (length > 0) onSeek((it * length).toLong()) }; dragging = null },
                onDragCancel = { dragging = null },
            ) { change, _ -> dragging = (change.position.x / size.width).coerceIn(0f, 1f) }
        }
        .pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon.Hand), contentAlignment = Alignment.CenterStart) {
        val thick = if (hover != null || dragging != null) 4.dp else 2.dp
        Box(Modifier.fillMaxWidth().height(thick).background(Color.White.copy(alpha = .25f)))
        Box(Modifier.fillMaxWidth(shownAt).height(thick).background(accent))
        if (hover != null || dragging != null) Box(Modifier.padding(start = (maxWidth * shownAt - 5.dp).coerceAtLeast(0.dp)).size(10.dp).background(accent))
        val h = dragging ?: hover
        if (h != null && length > 0) BasicText(time((h * length).toLong()), style = pixel(11.sp, Ink.ink),
            modifier = Modifier.padding(start = (maxWidth * h - 14.dp).coerceIn(0.dp, maxWidth - 40.dp)).offset(y = (-18).dp))
    }
}

private fun time(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

/** An artist's music videos on their page, newest first: each frame wide, its song and how long ago. */
@Composable
fun ArtistVideos(name: String, browseId: String, open: (Page) -> Unit) {
    val videos by produceState(emptyList<MusicVideos.Clip>(), name, browseId) {
        value = runCatching { MusicVideos.of(name, browseId) }.getOrDefault(emptyList())
    }
    if (videos.isEmpty()) return
    Column {
        Label("videos")
        Shelf(cover = 16 / 9f) {
            items(videos, key = { it.id }) { c ->
                Column(Modifier.width(300.dp).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { open(Page.Watch(c, videos)) })) {
                    VideoFrame(c, Modifier.fillMaxWidth().aspectRatio(16 / 9f))
                    Spacer(Modifier.height(8.dp))
                    UnderCover(c.title, MusicVideos.ago(c.at))
                }
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}
