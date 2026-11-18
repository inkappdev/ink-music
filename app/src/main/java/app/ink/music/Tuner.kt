package app.ink.music

import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** One of TUNER's clips: its video, its song and artist, its length (s, 0 not known), the song to love or play (null: found when it's needed). */
data class Tune(
    val id: String, val title: String, val artist: String, val song: Song?, val seconds: Int = 0,
    /** Its source (TunerMix's: yours, fans, related, editors) and what it was found through (an artist, a list). */
    val from: String = "", val tag: String = "",
    /** YouTube has it only as its cover (a "- Topic" art track): its real video looked for, else moving art shown. */
    val still: Boolean = false,
    /** When the video went up (0 not known): an old one says its year. */
    val at: Long = 0L,
)

/** How long a clip plays before it goes round again (a tap on PLAY plays the whole song). */
private const val CLIP = 30_000L


/**
 * TUNER's way in, on INK under the day's picks (2026-11-18, Bronson: "a tiktok scrolling style section where you scroll
 * through music videos and tune your taste"; named by him): a hairline tile, its dial in pixels, its name and what it does.
 */
@Composable
fun TunerDoor(pages: Pages) {
    if (!YouTubeMusic.available) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp).border(1.dp, Ink.line)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { Tuning.end(); pages.open(Page.Tuner) })
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            BasicText("TUNER", style = headline(22.sp))
            BasicText("swipe through what's new, it learns", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 4.dp))
        }
        Dial()
    }
}

/**
 * TUNER (2026-11-18): new music as clips, one to a screen, swiped up for the next. Each starts at its best part
 * (YouTube's most replayed moment; else the chorus by its lyrics; else a third of the way in) and goes round its 30
 * seconds till you move on. LIKE loves the song; NOT FOR ME, never its artist again; PLAY, the whole song in ink's
 * player. Just watching or swiping on says something too (Tuned): watched 20 s, a little nearer; gone within 5 s,
 * a skip. The taste's made again as you leave a clip you said something about, so what's next follows you.
 */
@OptIn(UnstableApi::class)
@Composable
fun TunerPage(pages: Pages) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // this sitting, kept while you step into an artist's page and back (Tuning)
    val sit = remember { Tuning.sitting ?: TunerSitting().also { Tuning.sitting = it } }
    // the clips chosen so far (the pager's), and the pool the next are chosen from as you go (TunerMix)
    val shown = sit.shown
    var watchedNow: () -> Long = { 0L }
    val pool = sit.pool
    var loaded by sit::loaded
    // the first time: how it learns, typed out, before the first clip plays (and again from "how it learns")
    var intro by remember { mutableStateOf(TUNER_HINT !in Hints.done.value && !Device.tv) }
    val mix = sit.mix
    // a fan of them: liked here (now or before), or loved anywhere in ink
    fun fanOf(artist: String): Boolean = mix.isLiked(artist) || Fresh.tuned?.likedArtist(artist) == true ||
        Fresh.taste.value.why(artist) == Taste.Why.Loved
    val pager = rememberPagerState(initialPage = sit.page) { shown.size }
    fun pick(t: Tune): TunerMix.Pick {
        val taste = Fresh.taste.value
        return TunerMix.Pick(t.id, t.artist, t.from, t.tag, maxOf(taste.of(t.artist), .4 * taste.of(t.tag)))
    }
    // chosen up to `upTo` clips, each from the pool as it stands
    fun fill(upTo: Int) {
        while (shown.size < upTo) {
            val p = mix.next(pool.map(::pick), shown.map { it.artist }) ?: break
            val t = pool.first { it.id == p.id }
            pool.remove(t); shown.add(t)
        }
    }
    // what's chosen after the next one put back and chosen again (a like or a not for me changes what's next)
    fun rechoose() {
        val keep = pager.settledPage + 2
        while (shown.size > keep) pool.add(0, shown.removeAt(shown.lastIndex))
        pool.removeAll { !mix.wanted(pick(it)) }
        fill(pager.settledPage + 3)
    }
    fun add(more: List<Tune>) {
        val have = (shown + pool).map { it.id }.toSet()
        pool.addAll(more.filter { it.id !in have }.distinctBy { it.id })
        android.util.Log.d("ink-tuner", "found ${more.size}: ${more.groupingBy { it.from }.eachCount()}, ${more.take(4).joinToString { it.artist }}")
    }
    LaunchedEffect(Unit) {
        // back from an artist's page: the sitting as it was
        if (sit.loaded) return@LaunchedEffect
        add(runCatching { TunerFeed.start(ctx) }.getOrDefault(emptyList()))
        fill(3); loaded = true
        // the fans of whom you play most, as they're found
        val top = topArtists(ctx, 8)
        val have = top.map { Curation.folded(it) }.toSet()
        add(runCatching { TunerFeed.fans(ctx, top, have) }.getOrDefault(emptyList()))
        // then the older and further out: your artists' deep cuts, the decades nearest you, fans of their fans
        add(runCatching { TunerFeed.deep(ctx, top.take(5)) }.getOrDefault(emptyList()))
        add(runCatching { TunerFeed.decades(ctx) }.getOrDefault(emptyList()))
        add(runCatching { TunerFeed.far(ctx, top.take(3), have) }.getOrDefault(emptyList()))
    }
    // what's been found for each clip ahead of it: its streams, and where it starts
    val streams = sit.streams
    val starts = sit.starts
    // why it starts where it does, said over its line: "most replayed", "from the chorus", "a third in"
    val startWhy = sit.startWhy
    // dug: played in full, or its artist's page opened (a strong yes, short of a like)
    val dug = sit.dug
    // what you liked this sitting, for rabbit holes
    val likedNow = sit.likedNow
    val liked = sit.liked
    val player = remember {
        val load = androidx.media3.exoplayer.DefaultLoadControl.Builder().setBufferDurationsMs(8_000, 40_000, 1_000, 2_000).build()
        ExoPlayer.Builder(ctx).setLoadControl(load).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }
    var shape by remember { mutableFloatStateOf(16 / 9f) }
    var drawn by remember { mutableStateOf(false) }
    var at by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    var said by remember { mutableStateOf("") }
    var settledAt by remember { mutableLongStateOf(0L) }
    var waiting by remember { mutableStateOf(false) }
    // whose first frame's been drawn, and which clip the player's been given: a picture shows only once its own clip has
    // drawn (a flag alone was still set from the last clip as the next page's picture was made, and the player painted
    // the old video's last frame into it, 2026-11-18, Bronson)
    var drawnId by remember { mutableStateOf("") }
    var givenId by remember { mutableStateOf("") }
    // the picture's own shape inside the video (black bars baked into it cut off), measured from its frames as it plays
    var seen by remember { mutableFloatStateOf(0f) }
    // the picture: a SurfaceView, its frames straight to the screen, the video's frame rate told to it (2026-11-18,
    // Bronson: "a little choppy"; a TextureView's 24 fps landed unevenly on the screen's refresh: 0 dropped, judder)
    var texture by remember { mutableStateOf<SurfaceView?>(null) }
    DisposableEffect(player) {
        val listen = object : Player.Listener {
            // only the clip in view's own first frame: the last one's, drawn again into the new picture, showed the old
            // video while the new loaded (2026-11-18, Bronson)
            override fun onRenderedFirstFrame() { if (!waiting) return; texture?.let { v -> barsOf(v, shape) { seen = it } }; drawn = true; drawnId = givenId; android.util.Log.d("ink-tuner", "drawn ${android.os.SystemClock.elapsedRealtime() - settledAt} ms after it settled") }
            override fun onVideoSizeChanged(v: VideoSize) { if (v.width > 0 && v.height > 0) shape = (v.width * v.pixelWidthHeightRatio / v.height).coerceIn(1f, 2.4f) }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { playing = playWhenReady }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.d("ink-tuner", "wouldn't play: $error")
                said = "this one wouldn't play. swipe on"
            }
        }
        player.addListener(listen)
        onDispose {
            // where you were, to come back to (an artist's page opened, 2026-11-18, Bronson: "exactly where you were")
            shown.getOrNull(pager.settledPage)?.let { sit.resume(it, player.currentPosition, watchedNow()) }
            player.removeListener(listen); player.release()
        }
    }
    val life = LocalLifecycleOwner.current
    DisposableEffect(life) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) player.pause() }
        life.lifecycle.addObserver(o)
        onDispose { life.lifecycle.removeObserver(o) }
    }

    // a still clip's own music video, where its artist has one of that song (else its own id: the cover)
    val videos = sit.videos
    suspend fun videoOf(t: Tune): String {
        videos[t.id]?.let { return it }
        val v = if (t.still) withContext(Dispatchers.IO) { runCatching { TunerFeed.videoOf(t) }.getOrNull() } ?: t.id else t.id
        videos[t.id] = v
        return v
    }
    suspend fun streamOf(t: Tune): Pair<String, String?>? {
        if (streams.containsKey(t.id)) return streams[t.id]
        val vid = videoOf(t)
        val s = withContext(Dispatchers.IO) { runCatching { TunerFeed.stream(vid, Quality.metered(ctx)) }.getOrNull() }
        streams[t.id] = s
        return s
    }
    // where it starts, found ahead: the most replayed moment, else (its length known) the chorus by its lyrics, else a
    // third in; a clip whose length isn't known finds its chorus once it plays
    suspend fun replayedOf(t: Tune) {
        if (starts.containsKey(t.id)) return
        val vid = videoOf(t)
        withContext(Dispatchers.IO) { TunerFeed.replayed(vid) }?.let { starts[t.id] = it; startWhy[t.id] = "most replayed"; return }
        if (t.seconds <= 0) return
        val length = t.seconds * 1000L
        val chorus = withContext(Dispatchers.IO) {
            runCatching { Lyrics.of(Now(null, t.title, t.artist.substringBefore(" & "), "", length, 0L, 0L, false, false, 0, 0))?.let { TunerFeed.chorus(it) } }.getOrNull()
        }
        starts[t.id] = (chorus ?: (length / 3)).coerceAtMost((length - CLIP).coerceAtLeast(0L))
        startWhy[t.id] = if (chorus != null) "from the chorus" else "a third in"
    }

    val eased by androidx.compose.animation.core.animateFloatAsState(if (seen > 0f) seen else shape, androidx.compose.animation.core.tween(Motion.ENTER, easing = Motion.expo), label = "seen")
    // the clip in view: its stream in the player, from where it starts; the next chosen, and two found ahead
    LaunchedEffect(pager.settledPage, loaded) {
        if (!loaded) return@LaunchedEffect
        fill(pager.settledPage + 3)
        val tunes = shown.toList()
        val t = tunes.getOrNull(pager.settledPage) ?: return@LaunchedEffect
        // how the last clip played: its frame rate and what the decoder dropped (2026-11-18: "a little choppy")
        runCatching {
            val f = player.videoFormat; val d = player.videoDecoderCounters
            if (f != null && d != null) android.util.Log.d("ink-tuner", "played ${f.width}x${f.height} at ${f.frameRate} fps, ${f.codecs}: " +
                "${d.renderedOutputBufferCount} shown, ${d.droppedBufferCount} dropped, ${d.skippedOutputBufferCount} skipped, most in a row ${d.maxConsecutiveDroppedBufferCount}")
        }
        drawn = false; waiting = false; givenId = ""; seen = 0f; said = ""; at = 0L; settledAt = android.os.SystemClock.elapsedRealtime()
        android.util.Log.d("ink-tuner", "now ${t.id}: ${t.artist} - ${t.title} (${t.from}, through ${t.tag})")
        player.stop(); player.clearMediaItems()
        // the new page's picture handed to the player only now, the last clip let go (handed over as it was made, the
        // player painted the old video into it: the flash, 2026-11-18)
        launch {
            var n = 0
            while (texture?.tag != t.id && n++ < 60) delay(16)
            texture?.takeIf { it.tag == t.id }?.let { player.setVideoSurfaceView(it) }
        }
        launch { for (n in 1..2) tunes.getOrNull(pager.settledPage + n)?.let { launch { streamOf(it) }; launch { replayedOf(it) } } }
        val hook = launch { replayedOf(t) }
        val s = withTimeoutOrNull(12_000) { streamOf(t) }
        if (s == null) {
            android.util.Log.d("ink-tuner", "no stream for ${t.id} in ${android.os.SystemClock.elapsedRealtime() - settledAt} ms")
            said = "this one wouldn't load. swipe on"; return@LaunchedEffect
        }
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        val whole = androidx.media3.datasource.ResolvingDataSource.Factory(http) { spec ->
            val size = Regex("[?&]clen=(\\d+)").find(spec.uri.toString())?.groupValues?.get(1)?.toLongOrNull()
            if (spec.length == C.LENGTH_UNSET.toLong() && size != null && size > spec.position) spec.subrange(0, size - spec.position) else spec
        }
        val source = ProgressiveMediaSource.Factory(whole)
        val media = if (s.second == null) source.createMediaSource(MediaItem.fromUri(s.first))
            else MergingMediaSource(source.createMediaSource(MediaItem.fromUri(s.first)), source.createMediaSource(MediaItem.fromUri(s.second!!)))
        hook.join()
        waiting = true; givenId = t.id
        // back from an artist's page: where it was, else where it starts
        player.setMediaSource(media, sit.resumeAt(t.id) ?: starts[t.id] ?: 0L)
        player.prepare(); player.playWhenReady = !intro
        // the music waits under it
        if (Playback.now.value?.playing == true) Playback.toggle()
        // no most replayed moment: the chorus by its lyrics, else a third in, once its length is known
        if (!starts.containsKey(t.id)) {
            var length = 0L
            while (isActive && length <= 0) { delay(200); length = player.duration.takeIf { it > 0 } ?: 0L }
            val chorus = withContext(Dispatchers.IO) {
                runCatching {
                    Lyrics.of(Now(null, t.title, t.artist.substringBefore(" & "), "", length, 0L, 0L, false, false, 0, 0))
                        ?.let { TunerFeed.chorus(it) }
                }.getOrNull()
            }
            val start = (chorus ?: (length / 3)).coerceAtMost((length - CLIP).coerceAtLeast(0L))
            starts[t.id] = start
            startWhy[t.id] = if (chorus != null) "from the chorus" else "a third in"
            player.seekTo(start)
            android.util.Log.d("ink-tuner", "${t.artist} - ${t.title}: from ${start / 1000}s (${if (chorus != null) "chorus" else "a third in"})")
        } else android.util.Log.d("ink-tuner", "${t.artist} - ${t.title}: from ${starts[t.id]!! / 1000}s (${startWhy[t.id]})")
    }
    // round its 30 seconds; how long it's been watched, for what it says as you leave it
    var watched by remember { mutableLongStateOf(0L) }
    watchedNow = { watched }
    LaunchedEffect(pager.settledPage, loaded) {
        sit.page = pager.settledPage
        sit.moved(shown.getOrNull(pager.settledPage)?.id)
        watched = sit.watchedFor(shown.getOrNull(pager.settledPage)?.id)
        val t = shown.getOrNull(pager.settledPage) ?: return@LaunchedEffect
        while (isActive) {
            delay(250)
            if (player.isPlaying) watched += 250
            // the bars measured again a second and a half and four seconds in (a first frame can be dark all over)
            if (drawn && (watched == 1_500L || watched == 4_000L)) texture?.let { v -> barsOf(v, shape) { seen = it } }
            val from = starts[t.id] ?: continue
            at = player.currentPosition
            if (at > from + CLIP || (player.duration > 0 && at >= player.duration - 500)) player.seekTo(from)
        }
    }
    // a clip left: what that said (watched a while, a little nearer; gone at once, a skip), the taste made again
    LaunchedEffect(loaded) {
        if (!loaded) return@LaunchedEffect
        var was = pager.settledPage
        androidx.compose.runtime.snapshotFlow { pager.settledPage }.collect { now ->
            if (now == was) return@collect
            shown.getOrNull(was)?.let { t ->
                val kind = when {
                    liked[t.id] == true -> null
                    // round its clip again (50 s and more), played in full, or its artist's page opened
                    dug[t.id] == true || watched >= 50_000 -> Tuned.Kind.DUG
                    watched >= 20_000 -> Tuned.Kind.KEPT
                    // swiped past at once by a fan (an artist you've liked here or love): "not this one", that song only
                    // (2026-11-18, Bronson: a couple liked, then one swiped past, shouldn't count against them)
                    watched < 5_000 && fanOf(t.artist) -> Tuned.Kind.SEEN
                    watched < 5_000 -> Tuned.Kind.PASSED   // 5 s (2026-11-18, Bronson: 3 was too quick to be fair)
                    else -> Tuned.Kind.SEEN
                }
                if (kind != null) Fresh.tuned?.heard(t.id, t.artist, kind, t.from, t.title)
                when (kind) {
                    Tuned.Kind.DUG -> mix.dug(t.artist, t.tag)
                    Tuned.Kind.KEPT -> mix.kept(t.artist, t.tag)
                    Tuned.Kind.PASSED -> mix.passed(t.artist, t.tag)
                    else -> {}
                }
                if (kind == Tuned.Kind.KEPT || kind == Tuned.Kind.DUG || kind == Tuned.Kind.PASSED || liked[t.id] == true) Fresh.build(ctx)
            }
            was = now
        }
    }

    fun next() = scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
    LaunchedEffect(intro) { if (!intro) player.play() else player.pause() }

    // a like: the song loved, its artist nearer; what's like it found now and chosen from straight away (its up next, its
    // artist's fans' favorites); a second like close to another (one found through the other) opens a rabbit hole there
    // a double tap's heart: how many, and for which clip (one count for them all had it pop again over the next clip)
    var burst by remember { mutableStateOf(0) }
    var burstId by remember { mutableStateOf("") }
    fun like(t: Tune, on: Boolean) {
        liked[t.id] = on
        scope.launch {
            val song = t.song ?: withContext(Dispatchers.IO) { YouTubeMusic.byId(t.id) }
            song?.let { if ((it.id in Loved.ids.value) != on) Loved.toggle(it) }
        }
        if (!on) return
        burstId = t.id; burst++
        Fresh.tuned?.heard(t.id, t.artist, Tuned.Kind.LIKED, t.from, t.title)
        mix.liked(t.artist)
        fun k(s: String) = Curation.folded(s.substringBefore(" & "))
        val near = likedNow.lastOrNull { o -> k(o.artist) == k(t.tag) || k(o.tag) == k(t.artist) || k(o.artist) == k(t.artist) ||
            (o.tag == t.tag && o.from in setOf("fans", "far", "hole", "related", "deep")) }
        likedNow += t
        scope.launch {
            val have = (shown + pool).map { k(it.artist) }.toSet()
            add(runCatching { TunerFeed.related(ctx, t) }.getOrDefault(emptyList()))
            rechoose()
            if (near != null) {
                android.util.Log.d("ink-tuner", "rabbit hole: ${t.artist}, after ${near.artist}")
                add(runCatching { TunerFeed.hole(ctx, t.artist.substringBefore(" & "), have) }.getOrDefault(emptyList()))
            } else add(runCatching { TunerFeed.fans(ctx, listOf(t.artist.substringBefore(" & ")), have) }.getOrDefault(emptyList()))
            rechoose()
        }
    }

    // the dial's station: each clip its own frequency on the FM band, the needle stepping to it as a clip settles
    val station = shown.getOrNull(pager.settledPage)?.let { frequency(it.id) } ?: 98.7f

    FrostedPage(onClose = { Tuning.end(); pages.close() }) { _, top -> Box(Modifier.fillMaxSize()) {
        // the dial's height: its readout and its scale, held still at the top while the clips move under it
        val face = 96.dp
        when {
            !loaded -> Box(Modifier.fillMaxSize(), Alignment.Center) { BasicText("tuning in", style = pixel(13.sp, Ink.mid)) }
            shown.isEmpty() -> Box(Modifier.fillMaxSize().padding(40.dp), Alignment.Center) {
                BasicText("nothing to tune to right now. try again later", style = pixel(13.sp, Ink.mid))
            }
            else -> VerticalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { shown[it].id }) { page ->
                val t = shown[page]
                val here = page == pager.settledPage
                val frame by produceState(frameKept(t.id), t.id) { if (value == null) value = videoFrame(t.id) }
                val accent = frame?.accent ?: Ink.mid
                val taste by Fresh.taste.collectAsState()
                Column(Modifier.fillMaxSize().background(Ink.black).padding(top = top + face)) {
                    // why it's here: the learning, said
                    BasicText(whyOf(t), style = pixel(12.sp, Ink.mid), maxLines = 2, modifier = Modifier.padding(horizontal = 20.dp).padding(top = 10.dp))
                    Spacer(Modifier.weight(1f))
                    // the times over the line, as the player's are: where it is, and why it starts there
                    val from = starts[t.id]
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                        BasicText(if (here && from != null) tunerClock(at) else " ", style = pixel(12.sp, Ink.mid))
                        Spacer(Modifier.weight(1f))
                        BasicText(startWhy[t.id].orEmpty(), style = pixel(12.sp, Ink.mid))
                    }
                    // the progress through its clip, along the picture's top edge in its colour
                    val part = if (here && from != null) ((at - from).toFloat() / CLIP).coerceIn(0f, 1f) else 0f
                    Box(Modifier.fillMaxWidth().height(3.dp).background(Ink.line)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(part).background(accent))
                    }
                    Box(
                        // one frame for every clip (2026-11-18, Bronson: smoother with so many shapes): 16:9, each video
                        // cropped to fill it (a wide film's sides, a 4:3's top and foot), so nothing moves from one to the next
                        Modifier.fillMaxWidth().aspectRatio(16 / 9f).clipToBounds()
                            // a tap pauses; a double tap likes it, a pixel heart popping up in the middle
                            .pointerInput(t.id) {
                                detectTapGestures(
                                    onTap = { if (player.playWhenReady) player.pause() else player.play() },
                                    onDoubleTap = { if (liked[t.id] != true) like(t, true) else { burstId = t.id; burst++ } },
                                )
                            },
                    ) {
                        val on = here && drawnId == t.id
                        // tuning in (2026-11-18, Bronson: the develop in, an x-ray or a static effect): till its picture
                        // comes, the clip's still in x-ray (its negative, in its own colour) under static, as between
                        // stations; its picture fades in over it as the static clears
                        // back from an artist's page: it picks up, its last frame held till the video's there, no tuning in
                        val held = sit.held(t.id)
                        val come by androidx.compose.animation.core.animateFloatAsState(if (on) 1f else 0f,
                            androidx.compose.animation.core.tween(if (on && held == null) 420 else 0, easing = Motion.expo), label = "tune")
                        val xray = remember(accent) { xrayEffect(accent)?.asComposeRenderEffect() }
                        if (held != null) Image(held, null, Modifier.covering(shape, eased), contentScale = ContentScale.FillBounds)
                        else frame?.let { Image(it.image, null, Modifier.fillMaxSize().graphicsLayer { renderEffect = xray }, contentScale = ContentScale.Crop) }
                        // only its cover on YouTube and no video of it found: moving art in its place, the cover's picture hidden
                        var moving by remember(t.id) { mutableStateOf(false) }
                        if (here && t.still && videos[t.id] == t.id) TunerMotion(t.title, t.artist.substringBefore(" & "), accent, Modifier.fillMaxSize()) { moving = it }
                        // the frame's size, for the surface's clip (laid out larger than the frame to crop, a surface isn't
                        // cut by the page's own clipping: its bounds clip it)
                        val box = remember { IntArray(2) }
                        Box(Modifier.fillMaxSize().onSizeChanged { box[0] = it.width; box[1] = it.height })
                        fun clip(v: SurfaceView) {
                            if (v.width <= 0 || box[0] <= 0) return
                            val x = (v.width - box[0]) / 2; val y = (v.height - box[1]) / 2
                            v.clipBounds = android.graphics.Rect(maxOf(0, x), maxOf(0, y), minOf(v.width, x + box[0]), minOf(v.height, y + box[1]))
                        }
                        if (here) AndroidView(
                            factory = {
                                SurfaceView(it).apply {
                                    keepScreenOn = true; tag = t.id; texture = this
                                    addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> clip(v as SurfaceView) }
                                }
                            },
                            update = { v -> v.alpha = if (drawnId == t.id && !moving) come else 0f; clip(v) },
                            onRelease = { v -> player.clearVideoSurfaceView(v) },
                            // its picture's own shape, measured from its frames (black bars in the video itself: a 4:3 film
                            // inside 16:9 showed bars at its sides; YouTube's still hadn't them)
                            modifier = Modifier.covering(shape, eased)
                                .alpha(if (drawnId == t.id && !moving) come else 0f),
                        )
                        if (!moving && held == null) Static(1f - come, Modifier.fillMaxSize())
                        if (here && !playing && on) BasicText("paused", style = pixel(12.sp), modifier = Modifier.align(Alignment.Center))
                        if (here && burstId == t.id) HeartBurst(burst, Modifier.align(Alignment.Center))
                    }
                    Column(Modifier.padding(top = 16.dp)) {
                        // a stamp when you've never played them, the year when it's an old one: discoveries, marked
                        val newToYou = taste.of(t.artist) <= 0.0 && Library.songs.value.none { Curation.folded(it.artist) == Curation.folded(t.artist) }
                        val year = t.at.takeIf { it > 0 && System.currentTimeMillis() - it > 400L * 86_400_000 }
                            ?.let { java.util.Calendar.getInstance().apply { timeInMillis = it }.get(java.util.Calendar.YEAR) }
                        if (newToYou || year != null) Row(Modifier.padding(horizontal = 20.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (newToYou) BasicText("NEW TO YOU", style = pixel(11.sp, Ink.black), modifier = Modifier.background(Ink.ink).padding(horizontal = 5.dp, vertical = 1.dp))
                            if (year != null) BasicText("from $year", style = pixel(12.sp, Ink.mid))
                        }
                        // the name as big as a poster's, on two lines at most and smaller when it's long (it ran as a
                        // marquee first; still reads better, 2026-11-18, Bronson)
                        val size = when { t.title.length > 34 -> 26.sp; t.title.length > 18 -> 34.sp; else -> 44.sp }
                        BasicText(t.title.uppercase(), style = headline(size).copy(lineHeight = size * 0.95f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp).padding(horizontal = 20.dp))
                        Word(t.artist.lowercase(), pixel(14.sp, Ink.mid), Modifier.padding(start = 20.dp, top = 2.dp), touch = 6.dp) {
                            dug[t.id] = true
                            // the frame you were on, kept to show as you come back (no tuning in again: it picks up)
                            val open = { pages.open(Page.Artist(t.artist.substringBefore(" & "))) }
                            val v = texture
                            if (v != null && drawnId == t.id && v.width > 0 && v.holder.surface.isValid) runCatching {
                                val b = android.graphics.Bitmap.createBitmap(v.width / 2, v.height / 2, android.graphics.Bitmap.Config.ARGB_8888)
                                android.view.PixelCopy.request(v, b, { r ->
                                    if (r == android.view.PixelCopy.SUCCESS) sit.hold(t.id, b.asImageBitmap()) else b.recycle()
                                    open()
                                }, android.os.Handler(android.os.Looper.getMainLooper()))
                            }.onFailure { open() } else open()
                        }
                        if (here && said.isNotEmpty()) BasicText(said, style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(horizontal = 20.dp).padding(top = 10.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    // the thumb at the left, the heart at the right, play in the very middle (in a row, it sat
                    // off centre by how much wider the thumb is than the heart, 2026-11-18)
                    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                        // not for me: a pixel thumb down, pressed down as it goes on to the next
                        ThumbDown(Modifier.align(Alignment.CenterStart)) {
                            Fresh.tuned?.heard(t.id, t.artist, Tuned.Kind.NOPE, t.from, t.title)
                            mix.refused(t.artist)
                            rechoose()
                            Fresh.notForMe(ctx, t.artist.substringBefore(" & "), "tune:${t.id}")
                            scope.launch { delay(260); next() }
                        }
                        Word("play", pixel(14.sp), Modifier.align(Alignment.Center), touch = 10.dp) {
                            dug[t.id] = true
                            player.pause()
                            scope.launch {
                                val song = t.song ?: withContext(Dispatchers.IO) { YouTubeMusic.byId(t.id) }
                                song?.let { Playback.play(listOf(it), 0) }
                            }
                        }
                        // like: a pixel heart, filling with red from its foot up
                        val on = liked[t.id] == true
                        FillingHeart(on, Modifier.align(Alignment.CenterEnd)) { like(t, !on) }
                    }
                    Spacer(Modifier.height(LocalFoot.current + 28.dp))
                }
            }
        }
        // the dial, still at the top: the station's frequency, its scale, the needle stepping to each clip
        // (below the close row's soft strip, which had blurred the readout's top)
        if (loaded && shown.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(top = top).height(face).background(Ink.black).padding(start = 20.dp, end = 20.dp, top = 18.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                BasicText("FM ${"%.1f".format(java.util.Locale.US, station)}", style = headline(30.sp))
                Spacer(Modifier.weight(1f))
                if (!intro) Word("how it learns", pixel(12.sp, Ink.mid), touch = 10.dp) { intro = true }
            }
            Scale(station, Modifier.fillMaxWidth().padding(top = 8.dp).height(18.dp))
        }
        if (intro) TunerIntro(top) { intro = false; Hints.did(TUNER_HINT) }
    } }
}

/** A clip's station on the FM band (87.9 to 107.9, odd tenths, as American stations are), from its id: the same every time. */
private fun frequency(id: String): Float = 87.9f + (((id.hashCode() and 0x7fffffff) % 101) * 2) / 10f

/** Why a clip's here, as a line (its source and what it was found through). */
private fun whyOf(t: Tune): String {
    val tag = t.tag.lowercase()
    return when (t.from) {
        "yours" -> "new from ${t.artist.lowercase()}, one of yours"
        "fans" -> "fans of $tag also like them"
        "related" -> "because you liked $tag"
        "editors" -> "youtube's editors: $tag"
        "deep" -> "a deep cut from $tag"
        "decades" -> tag
        "far" -> "two steps out from $tag"
        "hole" -> "down the rabbit hole from $tag"
        "sessions" -> tag
        else -> ""
    }
}

private fun tunerClock(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

/**
 * The dial's scale (2026-11-18, Bronson's pick, after a mock of three): ticks across the FM band in pixels (a long one
 * each megahertz, a short one between), a line under them, and the needle, ink, which steps a cell at a time to
 * `station` when it changes, as a hand turns a dial.
 */
@Composable
private fun Scale(station: Float, modifier: Modifier = Modifier) {
    val less by Moves.less.collectAsState()
    val needle = remember { androidx.compose.animation.core.Animatable(station) }
    LaunchedEffect(station) {
        if (less) needle.snapTo(station) else needle.animateTo(station, androidx.compose.animation.core.tween(520, easing = Motion.expo))
    }
    androidx.compose.foundation.Canvas(modifier) {
        val px = 3.dp.toPx()
        val cols = (size.width / px).toInt()
        val rows = (size.height / px).toInt()
        fun cell(c: Int, r: Int, color: androidx.compose.ui.graphics.Color) =
            drawRect(color, androidx.compose.ui.geometry.Offset(c * px, r * px), androidx.compose.ui.geometry.Size(px, px))
        // 87.5 to 108.5 across the width: a long tick each whole megahertz, a short one each half
        fun colOf(f: Float) = ((f - 87.5f) / 21f * (cols - 1)).toInt().coerceIn(0, cols - 1)
        var f = 88f
        while (f <= 108f) {
            val c = colOf(f)
            val whole = f % 1f == 0f
            for (r in (if (whole) 1 else 3) until rows - 2) cell(c, r, if (whole) Ink.mid else Ink.dim)
            f += .5f
        }
        for (c in 0 until cols) cell(c, rows - 1, Ink.dim)
        // the needle on whole cells, so it steps
        val at = colOf(needle.value)
        for (r in 0 until rows) cell(at, r, Ink.ink)
    }
}

/**
 * Static, as between stations: cells of grey noise across the picture, new every few frames, at `strength` (gone at 0);
 * none on a slower phone (MOTION: less), the x-ray still under it showing alone.
 */
@Composable
private fun Static(strength: Float, modifier: Modifier = Modifier) {
    if (strength <= .01f) return
    val less by Moves.less.collectAsState()
    if (less) return
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (isActive) { delay(70); tick++ } }
    androidx.compose.foundation.Canvas(modifier) {
        val px = 4.dp.toPx()
        val rnd = kotlin.random.Random(tick)
        val cols = (size.width / px).toInt() + 1
        val rows = (size.height / px).toInt() + 1
        for (r in 0 until rows) for (c in 0 until cols) {
            val v = rnd.nextFloat()
            if (v > .55f) drawRect(Ink.ink.copy(alpha = (v - .55f) * .9f * strength), androidx.compose.ui.geometry.Offset(c * px, r * px), androidx.compose.ui.geometry.Size(px, px))
        }
    }
}

/** A double tap's like: a big pixel heart in red pops up in the middle of the picture, swells and rises away. */
@Composable
private fun HeartBurst(count: Int, modifier: Modifier = Modifier) {
    if (count == 0) return
    val a = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(count) { a.snapTo(0f); a.animateTo(1f, androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing)) }
    val t = a.value
    if (t <= 0f || t >= 1f) return
    val scale = if (t < .2f) .6f + t / .2f * .7f else if (t < .35f) 1.3f - (t - .2f) / .15f * .3f else 1f
    val fade = if (t < .7f) 1f else 1f - (t - .7f) / .3f
    Box(modifier.graphicsLayer { scaleX = scale; scaleY = scale; alpha = fade; translationY = -(if (t > .7f) (t - .7f) * 120.dp.toPx() else 0f) }) {
        Sprite(HEART, HeartRed, 9.dp)
    }
}

/**
 * A video `shape` (width over height) whose picture is `seen` (its bars baked in cut off) laid out so the picture covers
 * its place, centred: as big as that takes, the rest cropped by the place.
 */
private fun Modifier.covering(shape: Float, seen: Float = shape) = layout { m, c ->
    val w = c.maxWidth; val h = c.maxHeight
    // the picture's box covering the place, then the whole video round it (wider than the picture by its side bars,
    // taller by its top and foot ones)
    val (pw, ph) = if (seen > w / h.toFloat()) h * seen to h.toFloat() else w.toFloat() to w / seen
    val cw = (if (seen < shape) pw * shape / seen else pw).toInt()
    val ch = (if (seen > shape) ph * seen / shape else ph).toInt()
    val p = m.measure(Constraints.fixed(cw, ch))
    layout(w, h) { p.place((w - cw) / 2, (h - ch) / 2) }
}

/** A thumb down, 10 by 10: its outline (the cuff, the hand, its knuckles, the thumb), and filled. */
private val THUMB_DOWN = listOf(
    "###.######",
    "#.#......#",
    "#.#.....##",
    "#.#......#",
    "#.#.....##",
    "#.#......#",
    "###...####",
    "...#..#...",
    "....#.#...",
    "....##....",
)
private val THUMB_DOWN_FILLED = listOf(
    "###.######",
    "###.######",
    "###.######",
    "###.######",
    "###.######",
    "###.######",
    "###.######",
    "...####...",
    "....###...",
    "....##....",
)

/** The heart's cells at the edge (beside one that isn't, or the sprite's side): its outline. */
private fun outline(rows: List<String>): List<String> = rows.mapIndexed { r, row ->
    row.mapIndexed { c, ch ->
        fun on(rr: Int, cc: Int) = rows.getOrNull(rr)?.getOrNull(cc) == '#'
        if (ch == '#' && !(on(r - 1, c) && on(r + 1, c) && on(r, c - 1) && on(r, c + 1))) '#' else '.'
    }.joinToString("")
}

/**
 * TUNER's like (2026-11-18, Bronson: "the pixel heart that fills in red with an animation"): its outline at 70% while
 * not liked; liked, red fills it a row at a time from the foot up (ease-out, a third of a second) as it swells a
 * little and settles. Unliked, it empties at once.
 */
@Composable
private fun FillingHeart(on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val fill = remember { androidx.compose.animation.core.Animatable(if (on) 1f else 0f) }
    val swell = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(on) {
        if (on) {
            launch { fill.animateTo(1f, androidx.compose.animation.core.tween(340, easing = Motion.expo)) }
            swell.animateTo(1.18f, androidx.compose.animation.core.tween(140, easing = Motion.expo))
            swell.animateTo(1f, androidx.compose.animation.core.tween(260, easing = Motion.expo))
        } else fill.snapTo(0f)
    }
    val edge = remember { outline(HEART) }
    Box(
        modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick)).padding(10.dp)
            .graphicsLayer { scaleX = swell.value; scaleY = swell.value },
    ) {
        androidx.compose.foundation.Canvas(Modifier.width(4.dp * 7).height(4.dp * 6)) {
            val px = 4.dp.toPx()
            val rows = HEART.size
            // rows filled from the foot: the bottom row first
            val shown = fill.value * rows
            for (r in 0 until rows) for (c in 0 until 7) {
                val filled = HEART[r][c] == '#' && (rows - r) <= kotlin.math.ceil(shown.toDouble()).toInt() && fill.value > 0f
                val color = when {
                    filled -> HeartRed
                    edge[r][c] == '#' -> Ink.mid
                    else -> continue
                }
                drawRect(color, androidx.compose.ui.geometry.Offset(c * px, r * px), androidx.compose.ui.geometry.Size(px, px))
            }
        }
    }
}

/** TUNER's not for me: a pixel thumb down, its outline at 70%; pressed, it fills with ink and drops a little, and comes back. */
@Composable
private fun ThumbDown(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val drop = remember { androidx.compose.animation.core.Animatable(0f) }
    var pressed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Box(
        modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt {
            pressed = true
            onClick()
            scope.launch {
                drop.animateTo(1f, androidx.compose.animation.core.tween(140, easing = Motion.expo))
                drop.animateTo(0f, androidx.compose.animation.core.tween(260, easing = Motion.expo))
                pressed = false
            }
        }).padding(10.dp).graphicsLayer { translationY = drop.value * 6.dp.toPx() },
    ) { Sprite(if (pressed) THUMB_DOWN_FILLED else THUMB_DOWN, if (pressed) Ink.ink else Ink.mid, 3.dp) }
}

/**
 * TUNER's sign (2026-11-18, Bronson: the first, a few blocks, "isn't very good"): a radio's tuning scale in pixels, 17
 * cells across, a long tick every fourth and a short one between, a line under them, and its needle, ink, stepping a
 * cell at a time across the scale and back, resting a moment where it lands, as if it were finding a station.
 */
@Composable
private fun Dial() {
    val cols = 17
    var needle by remember { mutableStateOf(5) }
    // still on a slower phone (MOTION: less)
    val less by Moves.less.collectAsState()
    LaunchedEffect(less) {
        if (less) return@LaunchedEffect
        val stops = listOf(5, 11, 3, 14, 8)
        var i = 0
        while (isActive) {
            delay(1_600)
            val to = stops[++i % stops.size]
            while (needle != to) { needle += if (to > needle) 1 else -1; delay(70) }
        }
    }
    androidx.compose.foundation.Canvas(Modifier.width(3.dp * cols).height(3.dp * 7)) {
        val px = 3.dp.toPx()
        fun cell(c: Int, r: Int, color: androidx.compose.ui.graphics.Color) =
            drawRect(color, androidx.compose.ui.geometry.Offset(c * px, r * px), androidx.compose.ui.geometry.Size(px, px))
        for (c in 0 until cols) {
            if (c % 4 == 0) for (r in 1..3) cell(c, r, Ink.mid)
            else if (c % 2 == 0) for (r in 2..3) cell(c, r, Ink.dim)
            cell(c, 5, Ink.dim)
        }
        for (r in 0..6) cell(needle, r, Ink.ink)
    }
}

/**
 * The picture's shape inside a video (`shape`) playing in `v`, its black bars baked in cut off: from a small copy of the
 * frame, the dark columns at its sides and rows at its top and foot. Null when there are none, or they don't match on
 * both sides (a dark frame, not bars).
 */
private fun barsOf(v: SurfaceView, shape: Float, found: (Float) -> Unit) {
    if (android.os.Build.VERSION.SDK_INT < 24 || !v.holder.surface.isValid) return
    val w = 160; val h = (w / shape).toInt().coerceAtLeast(40)
    val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    // a copy of the surface's frame, scaled to the small bitmap (a surface can't be read as a view is)
    runCatching {
        android.view.PixelCopy.request(v, b, { r -> if (r == android.view.PixelCopy.SUCCESS) barsIn(b, shape)?.let(found) else b.recycle() },
            android.os.Handler(android.os.Looper.getMainLooper()))
    }.onFailure { b.recycle() }
}

private fun barsIn(b: android.graphics.Bitmap, shape: Float): Float? = runCatching {
    val w = b.width; val h = b.height
    fun dark(x: Int, y: Int) = b.getPixel(x, y).let { c -> ((c shr 16) and 255) + ((c shr 8) and 255) + (c and 255) < 54 }
    fun colDark(x: Int) = (0 until h step 3).all { dark(x, it) }
    fun rowDark(y: Int) = (0 until w step 3).all { dark(it, y) }
    var left = 0; var right = w - 1; var top = 0; var bottom = h - 1
    while (left < w / 3 && colDark(left)) left++
    while (right > w * 2 / 3 && colDark(right)) right--
    while (top < h / 3 && rowDark(top)) top++
    while (bottom > h * 2 / 3 && rowDark(bottom)) bottom--
    b.recycle()
    val sides = left > 2 && kotlin.math.abs(left - (w - 1 - right)) <= 3
    val ends = top > 2 && kotlin.math.abs(top - (h - 1 - bottom)) <= 3
    if (!sides && !ends) return null
    val pw = if (sides) (right - left + 1).toFloat() else w.toFloat()
    val ph = if (ends) (bottom - top + 1).toFloat() else h.toFloat()
    // in the video's own shape (the copy's pixels aren't square when its height was rounded)
    (pw / ph) * (shape / (w.toFloat() / h))
}.getOrNull()

/** Whom you've played most these three months (TUNER's fans also like starts from them). */
private suspend fun topArtists(c: android.content.Context, n: Int): List<String> = withContext(Dispatchers.IO) {
    val since = System.currentTimeMillis() - 90L * 86_400_000
    History.read(c).filter { it.at >= since && it.artist.isNotBlank() }
        .groupingBy { it.artist.substringBefore(", ").trim() }.eachCount()
        .filterKeys { it != "unknown" && !it.equals("various artists", true) }
        .entries.sortedByDescending { it.value }.take(n).map { it.key }
}

private const val TUNER_HINT = "tuner"

/**
 * How TUNER learns, the first time it's opened (2026-11-18, Bronson: "type out some tips and rules of how it learns"):
 * its name, then a line at a time typed out as ink's hints are (the heart and the thumb beside theirs), and TUNE IN,
 * which starts the first clip. Said once; "how it learns" at its top says it again.
 */
@Composable
private fun TunerIntro(top: androidx.compose.ui.unit.Dp, onStart: () -> Unit) {
    val lines = listOf(
        null to "swipe up for the next one. each starts at its best part",
        HEART to "double tap or the heart: love it, and more like it comes next",
        THUMB_DOWN to "not for you. that artist won't come back",
        null to "watch 20 seconds: a little more like it",
        null to "swipe on within 5: a little less",
        null to "anything in between: no harm done",
        null to "play: the whole song, in your player",
        null to "like two close together and it goes down the rabbit hole",
    )
    Column(
        Modifier.fillMaxSize().background(Ink.black)
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .padding(start = 28.dp, end = 28.dp, top = top + 24.dp, bottom = LocalFoot.current + 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        BasicText("TUNER", style = headline(40.sp))
        Typed("how it learns what you like", pixel(13.sp, Ink.mid), Modifier.padding(top = 6.dp), wait = 300, lines = 1)
        lines.forEachIndexed { i, (sprite, text) ->
            Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(40.dp)) {
                    if (sprite === HEART) Sprite(HEART, HeartRed, 3.dp) else if (sprite != null) Sprite(sprite, Ink.mid, 3.dp)
                }
                Typed(text, pixel(13.sp), wait = 900L + i * 1_300L, lines = 2)
            }
        }
        Word("TUNE IN", headline(28.sp), Modifier.padding(top = 40.dp), touch = 12.dp) { onStart() }
    }
}

/** "found on tuner, nov 18": an artist page's line when you took to them on TUNER (null when you didn't). */
fun foundOnTuner(artist: String): String? = Fresh.tuned?.found(artist)?.let {
    "found on tuner, " + java.text.SimpleDateFormat("MMM d", java.util.Locale.US).format(java.util.Date(it)).lowercase()
}

/**
 * Listen's shelf of what you liked on TUNER (2026-11-18, Bronson: "how and where can we make this all reflect?"), newest
 * first: each its video's frame across, its song and artist; a tap plays the song in ink's player.
 */
@Composable
fun FoundOnTuner(likes: List<Tuned.Heard>, pages: Pages) {
    val scope = rememberCoroutineScope()
    Heading("found on tuner", "what you liked there")
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(likes.size, key = { likes[it].id }) { i ->
            val h = likes[i]
            Column(
                Modifier.width(220.dp).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt {
                    scope.launch { withContext(Dispatchers.IO) { YouTubeMusic.byId(h.id) }?.let { Playback.play(listOf(it), 0) } }
                }),
            ) {
                ClipFrame(MusicVideos.Clip(h.id, h.title, h.artist, h.at), Modifier.fillMaxWidth().aspectRatio(16 / 9f))
                BasicText(h.title.uppercase(), style = headline(14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                BasicText(h.artist.lowercase(), style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * A sitting of TUNER (2026-11-18, Bronson: back from an artist's page, "exactly where you were in that video"): the
 * clips chosen and the pool, what this sitting has learned, what's been found for each clip, which you were on and
 * where in it. Kept in memory while you step away into the app; a new one from INK's door or when TUNER is closed.
 */
class TunerSitting {
    val shown = androidx.compose.runtime.mutableStateListOf<Tune>()
    val pool = androidx.compose.runtime.mutableStateListOf<Tune>()
    var loaded by mutableStateOf(false)
    val mix = TunerMix(Fresh.tuned?.shares(TunerMix.START) ?: TunerMix.START)
    val streams = mutableStateMapOf<String, Pair<String, String?>?>()
    val starts = mutableStateMapOf<String, Long>()
    val startWhy = mutableStateMapOf<String, String>()
    val dug = mutableStateMapOf<String, Boolean>()
    val likedNow = mutableListOf<Tune>()
    val liked = mutableStateMapOf<String, Boolean>()
    val videos = mutableStateMapOf<String, String>()
    var page = 0
    // the frame you were on as you stepped away (for its clip), shown as you come back till the video's there
    private var heldFrame: Pair<String, androidx.compose.ui.graphics.ImageBitmap>? by mutableStateOf(null)
    fun hold(id: String, frame: androidx.compose.ui.graphics.ImageBitmap) { heldFrame = id to frame }
    fun held(id: String) = heldFrame?.takeIf { it.first == id }?.second
    /** Moved on to another clip: the held frame let go. */
    fun moved(id: String?) { if (heldFrame != null && heldFrame?.first != id) heldFrame = null }
    private var left: Tune? = null
    private var leftAt = 0L
    private var leftWatched = 0L

    /** Stepped away on `t`, at `at` ms, `watched` ms of it watched. */
    fun resume(t: Tune, at: Long, watched: Long) { left = t; leftAt = at; leftWatched = watched }
    /** Where `id` was when you stepped away (once), or null. */
    fun resumeAt(id: String): Long? = if (left?.id == id && leftAt > 0) leftAt.also { leftAt = 0 } else null
    /** How much of `id` you'd watched before you stepped away (0 for any other). */
    fun watchedFor(id: String?): Long = if (id != null && left?.id == id) leftWatched else 0L
    /** The clip in view when the sitting ends: shown, so not shown again for a month. */
    fun end() { left?.let { Fresh.tuned?.heard(it.id, it.artist, Tuned.Kind.SEEN, it.from, it.title) } }
}

/** TUNER's sitting, kept across the app's pages while it lasts. */
object Tuning {
    var sitting: TunerSitting? = null
    /** The sitting over: closed, or TUNER opened anew. */
    fun end() { sitting?.end(); sitting = null }
}
