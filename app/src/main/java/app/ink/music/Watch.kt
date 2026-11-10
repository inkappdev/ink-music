package app.ink.music

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** A music video's frame (YouTube's wide one, its baked-in black bars cut off) and its colour, for the shelf and the page. */
class VideoFrame(val image: ImageBitmap, val accent: Color)

private val frames = android.util.LruCache<String, VideoFrame>(24)

private suspend fun frameOf(id: String): VideoFrame? = frames.get(id) ?: withContext(Dispatchers.IO) {
    val bytes = listOf("hq720", "mqdefault").firstNotNullOfOrNull { k ->
        runCatching {
            val c = java.net.URL("https://i.ytimg.com/vi/$id/$k.jpg").openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 8_000; c.readTimeout = 12_000
            try { if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null } finally { c.disconnect() }
        }.getOrNull()
    } ?: return@withContext null
    val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let(::trim) ?: return@withContext null
    VideoFrame(b.asImageBitmap(), Covers.accentOf(b)).also { frames.put(id, it) }
}

// the black bars in a frame itself (a wide film's, a phone video's), cut off: the picture fills its tile
private fun trim(b: Bitmap): Bitmap {
    fun dark(x: Int, y: Int) = b.getPixel(x, y).let { c -> ((c shr 16) and 255) + ((c shr 8) and 255) + (c and 255) < 60 }
    fun rowDark(y: Int) = (0 until b.width step 8).all { dark(it, y) }
    fun colDark(x: Int) = (0 until b.height step 8).all { dark(x, it) }
    var top = 0; var bottom = b.height - 1; var left = 0; var right = b.width - 1
    while (top < b.height / 3 && rowDark(top)) top++
    while (bottom > b.height * 2 / 3 && rowDark(bottom)) bottom--
    while (left < b.width / 3 && colDark(left)) left++
    while (right > b.width * 2 / 3 && colDark(right)) right--
    if (top == 0 && left == 0 && bottom == b.height - 1 && right == b.width - 1) return b
    return Bitmap.createBitmap(b, left, top, right - left + 1, bottom - top + 1)
}

/** How long ago, as a person says it. */
fun agoOf(at: Long): String {
    val d = ((System.currentTimeMillis() - at) / 86_400_000).toInt()
    return when { d < 1 -> "today"; d == 1 -> "yesterday"; d < 7 -> "$d days ago"; d < 14 -> "a week ago"; d < 60 -> "${d / 7} weeks ago"; d < 365 -> "${d / 30} months ago"; d < 730 -> "a year ago"; else -> "${d / 365} years ago" }
}

/** Listen's shelf of new videos from your artists: each frame wide, its song, the artist and how long ago. */
@Composable
fun NewVideosShelf(clips: List<MusicVideos.Clip>, pages: Pages) {
    Heading("new videos", "from the artists you play")
    Shelf(clips) { c -> VideoTile(c, "${c.artist}  ·  ${agoOf(c.at)}") { pages.open(Page.Watch(c, clips)) } }
}

/** Listen's shelf of live DJ sets (LiveSets): each frame wide, the DJ, the channel and how long ago. */
@Composable
fun LiveSetsShelf(clips: List<MusicVideos.Clip>, pages: Pages) {
    Heading("live dj sets", "humano studios, hör berlin, the lot radio, boiler room, kiosk radio")
    Shelf(clips) { c ->
        Column(Modifier.width(240.dp)) {
            VideoTile(c, "${c.artist}  ·  ${agoOf(c.at)}") { pages.open(Page.Watch(c, clips)) }
            // watch it, or only listen: the sound in the music player, so the phone can be locked (2026-11-10)
            Row(Modifier.padding(top = 2.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(22.dp)) {
                Word("watch", pixel(13.sp), touch = 8.dp) { pages.open(Page.Watch(c, clips)) }
                Word("listen", pixel(13.sp), touch = 8.dp) { LiveSets.listen(c) }
            }
        }
    }
}

/** An artist's music videos on their page, newest first: each frame wide, its song and how long ago. */
@Composable
fun ArtistVideos(name: String, browseId: String, pages: Pages) {
    val videos by produceState(emptyList<MusicVideos.Clip>(), name, browseId) {
        value = runCatching { MusicVideos.of(name, browseId) }.getOrDefault(emptyList())
    }
    if (videos.isEmpty()) return
    Label("videos")
    Shelf(videos) { c -> VideoTile(c, agoOf(c.at)) { pages.open(Page.Watch(c, videos)) } }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun VideoTile(c: MusicVideos.Clip, said: String, onClick: () -> Unit) {
    Column(Modifier.width(240.dp).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick))) {
        ClipFrame(c, Modifier.fillMaxWidth().aspectRatio(16 / 9f))
        Spacer(Modifier.height(8.dp))
        UnderCover(c.title, said, pixel(12.sp, Ink.mid))
    }
}

@Composable
private fun ClipFrame(c: MusicVideos.Clip, modifier: Modifier) {
    val f by produceState<VideoFrame?>(frames.get(c.id), c.id) { if (value == null) value = frameOf(c.id) }
    BoxWithConstraints(modifier) {
        f?.let { Image(it.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } ?: NameCover(c.title, maxWidth)
    }
}

/**
 * A music video watched: across the screen in its own shape (a wide film's bars cut off), the line along its top edge
 * in its colour with the times over it, the song and artist under it, pause and the next one. The music pauses for it
 * and it for the music (each asks for the sound: Android's audio focus); it pauses when the app's left.
 */
@OptIn(UnstableApi::class)
@Composable
fun WatchPage(c: MusicVideos.Clip, list: List<MusicVideos.Clip>, pages: Pages) {
    val ctx = LocalContext.current
    val newest by MusicVideos.clips.collectAsState()
    // the videos it was opened among (the shelf's, an artist's): up next is the rest of them
    val clips = list.ifEmpty { newest }
    val frame by produceState<VideoFrame?>(frames.get(c.id), c.id) { if (value == null) value = frameOf(c.id) }
    val accent = frame?.accent ?: Ink.mid
    var said by remember(c.id) { mutableStateOf("finding it") }
    val stream by produceState<Pair<String, String?>?>(null, c.id) {
        // 1080p on wifi (the screen's 1440 across, 3120 sideways), 720p on someone's data
        value = withContext(Dispatchers.IO) { runCatching { MusicVideos.stream(c.id, if (Quality.metered(ctx)) 720 else 1080) }.getOrNull() }
        android.util.Log.d("ink-youtube", "watch ${c.id}: ${if (value == null) "no stream" else if (value?.second == null) "one stream" else "picture and sound"}")
        if (value == null) said = "youtube wouldn't play this one"
    }
    val player = remember(stream) {
        stream?.let { (pic, snd) ->
            // as the player opens YouTube's songs (a browser's name on them, they never came: a link is for the client that asked)
            val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
            // each read asked for to the file's end (its size is in the link, clen): asked open-ended, googlevideo sends it
            // at about twice its speed, and it stopped to load (as kept songs found: Offline)
            val whole = androidx.media3.datasource.ResolvingDataSource.Factory(http) { spec ->
                val size = Regex("[?&]clen=(\\d+)").find(spec.uri.toString())?.groupValues?.get(1)?.toLongOrNull()
                if (spec.length == C.LENGTH_UNSET.toLong() && size != null && size > spec.position) spec.subrange(0, size - spec.position) else spec
            }
            val source = ProgressiveMediaSource.Factory(whole)
            val media = if (snd == null) source.createMediaSource(MediaItem.fromUri(pic))
                else MergingMediaSource(source.createMediaSource(MediaItem.fromUri(pic)), source.createMediaSource(MediaItem.fromUri(snd)))
            // playing after a second and a half loaded (not two and a half), two minutes kept ahead
            val load = androidx.media3.exoplayer.DefaultLoadControl.Builder().setBufferDurationsMs(30_000, 120_000, 1_500, 3_000).build()
            ExoPlayer.Builder(ctx).setLoadControl(load).build().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                setMediaSource(media); prepare(); playWhenReady = true
            }
        }
    }
    var playing by remember(player) { mutableStateOf(true) }
    var at by remember(player) { mutableLongStateOf(0L) }
    var length by remember(player) { mutableLongStateOf(0L) }
    var shape by remember(player) { mutableFloatStateOf(16 / 9f) }
    // its first frame drawn: YouTube's still under it put away (a still another shape showed round the picture: Japan's)
    var drawn by remember(player) { mutableStateOf(false) }
    val next = clips.getOrNull(clips.indexOfFirst { it.id == c.id } + 1)
    fun watch(n: MusicVideos.Clip, among: List<MusicVideos.Clip> = clips) { pages.back(); pages.open(Page.Watch(n, among)) }
    DisposableEffect(player) {
        val p = player
        val listen = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = p?.playWhenReady == true }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { playing = playWhenReady }
            override fun onRenderedFirstFrame() { drawn = true }
            override fun onVideoSizeChanged(v: VideoSize) { if (v.width > 0 && v.height > 0) shape = v.width * v.pixelWidthHeightRatio / v.height }
            override fun onPlaybackStateChanged(state: Int) {
                // played out: the next one, if there is one
                if (state == Player.STATE_ENDED) next?.let { watch(it) }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.d("ink-youtube", "watch ${c.id}: $error ${error.cause}")
                said = "it wouldn't play: ${error.errorCodeName.lowercase()}"
            }
        }
        if (p != null) {
            p.addListener(listen)
            // the music waits under it (focus would pause it too; a speaker's doesn't hear the phone's focus)
            if (Playback.now.value?.playing == true) Playback.toggle()
        }
        onDispose { p?.removeListener(listen); p?.release() }
    }
    // left (home, the screen off): it pauses
    val life = LocalLifecycleOwner.current
    DisposableEffect(life, player) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) player?.pause() }
        life.lifecycle.addObserver(o)
        onDispose { life.lifecycle.removeObserver(o) }
    }
    LaunchedEffect(player) {
        val p = player ?: return@LaunchedEffect
        while (isActive) { at = p.currentPosition; length = p.duration.takeIf { it > 0 } ?: 0L; delay(250) }
    }
    // the picture's shape: the frame's with its bars cut off (a wide film's baked into the video too), else the video's,
    // whichever's wider, so neither YouTube's still nor a bar shows round it
    val seen = maxOf(frame?.image?.let { (it.width.toFloat() / it.height).coerceIn(1.2f, 2.8f) } ?: shape, shape)
    fun toggle() { player?.let { if (it.playWhenReady) it.pause() else it.play() } }
    // sideways (2026-11-07): the phone follows itself round while a video's open (ink music is upright everywhere
    // else); full screen turns it over whatever the rotation lock says, exit (or back) stands it up again. Kept from one
    // video to the next; put back upright when the last one closes
    val activity = ctx as? android.app.Activity
    val turn = Wide.turn
    LaunchedEffect(turn) {
        activity?.requestedOrientation = when (turn) {
            Wide.Turn.SIDEWAYS -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            Wide.Turn.UPRIGHT -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            Wide.Turn.FOLLOW -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (pages.stack.lastOrNull() !is Page.Watch) {
                Wide.turn = Wide.Turn.FOLLOW
                activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
    }
    if (sideways()) {
        androidx.activity.compose.BackHandler { Wide.turn = Wide.Turn.UPRIGHT }
        // the whole screen: the bars away (a swipe from an edge brings them a moment)
        val window = activity?.window
        DisposableEffect(window) {
            val bars = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
            bars?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            onDispose { bars?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
        }
        // the words over it come with a tap and go a few seconds on, while it plays
        var shown by remember { mutableStateOf(true) }
        LaunchedEffect(shown, playing) { if (shown && playing) { delay(3_000); shown = false } }
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)
            .clickable(remember { MutableInteractionSource() }, indication = null) { shown = !shown }) {
            // the picture as big as the screen lets it, whole: in its frame's shape (bars baked into the video itself cut
            // off, as upright), so the line can touch it
            val w = minOf(maxWidth, maxHeight * seen)
            val h = w / seen
            val picTop = (maxHeight - h) / 2
            Box(Modifier.align(Alignment.Center).requiredSize(w, h).clipToBounds()) {
                if (player != null) AndroidView(
                    factory = { TextureView(it).apply { keepScreenOn = true; player.setVideoTextureView(this) } },
                    modifier = Modifier.align(Alignment.Center).requiredSize(w, w / shape),
                ) else BasicText(said, style = pixel(12.sp, Ink.ink), modifier = Modifier.align(Alignment.Center))
            }
            if (shown) {
                val side = Modifier.windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal))
                // the times, then the line along the picture's top edge (over the picture where it fills the height)
                Column(side.fillMaxWidth().padding(top = (picTop - 32.dp).coerceAtLeast(0.dp), start = 24.dp, end = 24.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        BasicText(time(at), style = pixel(12.sp, Ink.ink), modifier = Modifier.background(Color.Black).padding(horizontal = 4.dp, vertical = 2.dp))
                        Spacer(Modifier.weight(1f))
                        BasicText(if (length > 0) time(length) else "", style = pixel(12.sp, Ink.ink), modifier = Modifier.background(Color.Black).padding(horizontal = 4.dp, vertical = 2.dp))
                    }
                    Box(Modifier.fillMaxWidth().height(14.dp).pointerInput(player, length) {
                        detectTapGestures { o -> if (length > 0) player?.seekTo((o.x / size.width * length).toLong()) }
                    }, contentAlignment = Alignment.BottomStart) {
                        Box(Modifier.fillMaxWidth().height(2.dp).background(Ink.line))
                        Box(Modifier.fillMaxWidth(if (length > 0) (at.toFloat() / length).coerceIn(0f, 1f) else 0f).height(2.dp).background(accent))
                    }
                }
                // its song and the words to steer it, at the foot, each on black (no panel over the picture)
                Row(side.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        BasicText(c.title.uppercase(), style = headline(22.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.background(Color.Black).padding(horizontal = 6.dp, vertical = 2.dp))
                        BasicText(c.artist.lowercase(), style = pixel(12.sp, Ink.mid), modifier = Modifier.background(Color.Black).padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                    Row(Modifier.background(Color.Black).padding(horizontal = 6.dp)) {
                        if (player != null) Word(if (playing) "PAUSE" else "PLAY", headline(16.sp), touch = 8.dp) { toggle() }
                        next?.let { n ->
                            Spacer(Modifier.width(20.dp))
                            Word("NEXT", headline(16.sp, Ink.mid), touch = 8.dp) { watch(n) }
                        }
                        Spacer(Modifier.width(20.dp))
                        Word("EXIT", headline(16.sp, Ink.mid), touch = 8.dp) { Wide.turn = Wide.Turn.UPRIGHT }
                    }
                }
            }
        }
        return
    }
    // more of theirs, under up next (the ones up next already left out)
    val more by produceState(emptyList<MusicVideos.Clip>(), c.artist) { value = runCatching { MusicVideos.of(c.artist) }.getOrDefault(emptyList()) }
    val upNext = remember(clips, c.id) { clips.indexOfFirst { it.id == c.id }.let { i -> if (i < 0) clips else clips.drop(i + 1) + clips.take(i) } }
    val theirs = remember(more, upNext, c.id) { more.filter { m -> m.id != c.id && upNext.none { it.id == m.id } } }
    FrostedPage(onClose = { pages.close() }) { frost, top ->
        LazyColumn(
            Modifier.fillMaxSize().hazeSource(frost).windowInsetsPadding(bars.only(WindowInsetsSides.Horizontal)),
            contentPadding = PaddingValues(top = top + 14.dp, bottom = LocalFoot.current + 24.dp),
        ) {
            item(key = "video") { Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 4.dp)) {
                    BasicText(time(at), style = pixel(12.sp, Ink.mid))
                    Spacer(Modifier.weight(1f))
                    BasicText(if (length > 0) time(length) else "", style = pixel(12.sp, Ink.mid))
                }
                // the line, along its top edge: where it is in its colour; a tap goes there
                Box(Modifier.fillMaxWidth().height(14.dp).pointerInput(player, length) {
                    detectTapGestures { o -> if (length > 0) player?.seekTo((o.x / size.width * length).toLong()) }
                }, contentAlignment = Alignment.BottomStart) {
                    Box(Modifier.fillMaxWidth().height(2.dp).background(Ink.line))
                    Box(Modifier.fillMaxWidth(if (length > 0) (at.toFloat() / length).coerceIn(0f, 1f) else 0f).height(2.dp).background(accent))
                }
                BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(seen).background(Color.Black).clipToBounds()
                    .clickable(remember { MutableInteractionSource() }, indication = null) { toggle() }) {
                    if (!drawn) frame?.let { Image(it.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    // the video in its own shape, as wide as the frame, its bars past the frame's edges cut off
                    if (player != null) AndroidView(
                        factory = { TextureView(it).apply { keepScreenOn = true; player.setVideoTextureView(this) } },
                        modifier = Modifier.align(Alignment.Center).requiredSize(maxWidth, maxWidth / shape),
                    ) else BasicText(said, style = pixel(12.sp, Ink.ink), modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
                }
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Spacer(Modifier.height(18.dp))
                    BasicText(c.title.uppercase(), style = headline(40.sp).copy(lineHeight = 42.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Word(c.artist.lowercase(), pixel(14.sp), touch = 8.dp) { pages.open(Page.Artist(c.artist)) }
                        BasicText("  ·  ${agoOf(c.at)}", style = pixel(14.sp, Ink.mid))
                    }
                    Spacer(Modifier.height(14.dp))
                    Row {
                        if (player != null) Word(if (playing) "PAUSE" else "PLAY", headline(18.sp), touch = 8.dp) { toggle() }
                        Spacer(Modifier.width(24.dp))
                        Word("FULL SCREEN", headline(18.sp, Ink.mid), touch = 8.dp) { Wide.turn = Wide.Turn.SIDEWAYS }
                        // only its sound, from here, in the music player: lock the phone and listen on
                        if (YouTubeMusic.available) {
                            Spacer(Modifier.width(22.dp))
                            Word("LISTEN", headline(18.sp, Ink.mid), touch = 8.dp) { val from = player?.currentPosition ?: 0L; pages.back(); LiveSets.listen(c, from) }
                        }
                    }
                    if (player != null && said.startsWith("it wouldn't")) BasicText(said, style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 12.dp))
                }
                // their name as the player has it: giant, running off both edges while it plays, still while it's paused
                Spacer(Modifier.height(10.dp))
                Marquee(c.artist, playing && Marquees.on.collectAsState().value, Modifier.fillMaxWidth().bleedPastCutout()
                    .clickable(remember { MutableInteractionSource() }, indication = null) { pages.open(Page.Artist(c.artist)) }, size = 120.sp)
            } }
            if (upNext.isNotEmpty()) {
                item(key = "next") { Label("up next") }
                items(upNext, key = { "n" + it.id }) { n -> ClipRow(n) { watch(n) } }
            }
            if (theirs.isNotEmpty()) {
                item(key = "more") { Label("more from ${c.artist.lowercase()}") }
                items(theirs, key = { "m" + it.id }) { m -> ClipRow(m) { watch(m, more) } }
            }
        }
    }
}

/** A video as a row: its frame, its song, the artist and how long ago. */
@Composable
private fun ClipRow(c: MusicVideos.Clip, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick))
        .padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ClipFrame(c, Modifier.width(144.dp).aspectRatio(16 / 9f))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            BasicText(c.title.uppercase(), style = headline(16.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            BasicText("${c.artist}  ·  ${agoOf(c.at)}".lowercase(), style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Which way a video's screen is asked to turn: with the phone, over on its side (full screen), or stood up again. */
object Wide {
    enum class Turn { FOLLOW, SIDEWAYS, UPRIGHT }
    var turn by mutableStateOf(Turn.FOLLOW)
}

private fun time(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }
