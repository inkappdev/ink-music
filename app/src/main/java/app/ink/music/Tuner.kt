package app.ink.music

import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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

/** One of TUNER's clips: its video, its song and artist, its length (s, 0 not known), the song to love or play (null: found when it's needed). */
data class Tune(
    val id: String, val title: String, val artist: String, val song: Song?, val seconds: Int = 0,
    /** Its source (TunerMix's: yours, fans, related, editors) and what it was found through (an artist, a list). */
    val from: String = "", val tag: String = "",
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
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { pages.open(Page.Tuner) })
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
 * player. Just watching or swiping on says something too (Tuned): watched 20 s, a little nearer; gone within 3 s,
 * a skip. The taste's made again as you leave a clip you said something about, so what's next follows you.
 */
@OptIn(UnstableApi::class)
@Composable
fun TunerPage(pages: Pages) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // the clips chosen so far (the pager's), and the pool the next are chosen from as you go (TunerMix)
    val shown = remember { androidx.compose.runtime.mutableStateListOf<Tune>() }
    val pool = remember { androidx.compose.runtime.mutableStateListOf<Tune>() }
    var loaded by remember { mutableStateOf(false) }
    val mix = remember { TunerMix(Fresh.tuned?.shares(TunerMix.START) ?: TunerMix.START) }
    val pager = rememberPagerState { shown.size }
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
        add(runCatching { TunerFeed.start(ctx) }.getOrDefault(emptyList()))
        fill(3); loaded = true
        // the fans of whom you play most, as they're found
        val top = topArtists(ctx, 8)
        add(runCatching { TunerFeed.fans(ctx, top, top.map { Curation.folded(it) }.toSet()) }.getOrDefault(emptyList()))
        fill(pager.settledPage + 3)
    }
    // what's been found for each clip ahead of it: its streams, and where it starts
    val streams = remember { mutableStateMapOf<String, Pair<String, String?>?>() }
    val starts = remember { mutableStateMapOf<String, Long>() }
    val liked = remember { mutableStateMapOf<String, Boolean>() }
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
    var texture by remember { mutableStateOf<TextureView?>(null) }
    DisposableEffect(player) {
        val listen = object : Player.Listener {
            // only the clip in view's own first frame: the last one's, drawn again into the new picture, showed the old
            // video while the new loaded (2026-11-18, Bronson)
            override fun onRenderedFirstFrame() { if (!waiting) return; texture?.let { v -> barsOf(v, shape)?.let { seen = it } }; drawn = true; drawnId = givenId; android.util.Log.d("ink-tuner", "drawn ${android.os.SystemClock.elapsedRealtime() - settledAt} ms after it settled") }
            override fun onVideoSizeChanged(v: VideoSize) { if (v.width > 0 && v.height > 0) shape = (v.width * v.pixelWidthHeightRatio / v.height).coerceIn(1f, 2.4f) }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { playing = playWhenReady }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.d("ink-tuner", "wouldn't play: $error")
                said = "this one wouldn't play. swipe on"
            }
        }
        player.addListener(listen)
        onDispose {
            player.removeListener(listen); player.release()
            // the clip in view as it closed: shown, so not shown again
            shown.getOrNull(pager.settledPage)?.let { Fresh.tuned?.heard(it.id, it.artist, Tuned.Kind.SEEN, it.from) }
        }
    }
    val life = LocalLifecycleOwner.current
    DisposableEffect(life) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) player.pause() }
        life.lifecycle.addObserver(o)
        onDispose { life.lifecycle.removeObserver(o) }
    }

    suspend fun streamOf(t: Tune): Pair<String, String?>? {
        if (streams.containsKey(t.id)) return streams[t.id]
        val s = withContext(Dispatchers.IO) { runCatching { TunerFeed.stream(t.id, Quality.metered(ctx)) }.getOrNull() }
        streams[t.id] = s
        return s
    }
    // where it starts, found ahead: the most replayed moment, else (its length known) the chorus by its lyrics, else a
    // third in; a clip whose length isn't known finds its chorus once it plays
    suspend fun replayedOf(t: Tune) {
        if (starts.containsKey(t.id)) return
        withContext(Dispatchers.IO) { TunerFeed.replayed(t.id) }?.let { starts[t.id] = it; return }
        if (t.seconds <= 0) return
        val length = t.seconds * 1000L
        val chorus = withContext(Dispatchers.IO) {
            runCatching { Lyrics.of(Now(null, t.title, t.artist.substringBefore(" & "), "", length, 0L, 0L, false, false, 0, 0))?.let { TunerFeed.chorus(it) } }.getOrNull()
        }
        starts[t.id] = (chorus ?: (length / 3)).coerceAtMost((length - CLIP).coerceAtLeast(0L))
    }

    val eased by androidx.compose.animation.core.animateFloatAsState(if (seen > 0f) seen else shape, androidx.compose.animation.core.tween(Motion.ENTER, easing = Motion.expo), label = "seen")
    // the clip in view: its stream in the player, from where it starts; the next chosen, and two found ahead
    LaunchedEffect(pager.settledPage, loaded) {
        if (!loaded) return@LaunchedEffect
        fill(pager.settledPage + 3)
        val tunes = shown.toList()
        val t = tunes.getOrNull(pager.settledPage) ?: return@LaunchedEffect
        drawn = false; waiting = false; givenId = ""; seen = 0f; said = ""; at = 0L; settledAt = android.os.SystemClock.elapsedRealtime()
        android.util.Log.d("ink-tuner", "now ${t.id}: ${t.artist} - ${t.title} (${t.from}, through ${t.tag})")
        player.stop(); player.clearMediaItems()
        launch { for (n in 1..2) tunes.getOrNull(pager.settledPage + n)?.let { launch { streamOf(it) }; launch { replayedOf(it) } } }
        val hook = launch { replayedOf(t) }
        val s = streamOf(t)
        if (s == null) { said = "youtube wouldn't play this one. swipe on"; return@LaunchedEffect }
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
        player.setMediaSource(media, starts[t.id] ?: 0L)
        player.prepare(); player.playWhenReady = true
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
            player.seekTo(start)
            android.util.Log.d("ink-tuner", "${t.artist} - ${t.title}: from ${start / 1000}s (${if (chorus != null) "chorus" else "a third in"})")
        } else android.util.Log.d("ink-tuner", "${t.artist} - ${t.title}: from ${starts[t.id]!! / 1000}s (most replayed)")
    }
    // round its 30 seconds; how long it's been watched, for what it says as you leave it
    var watched by remember { mutableLongStateOf(0L) }
    LaunchedEffect(pager.settledPage, loaded) {
        watched = 0L
        val t = shown.getOrNull(pager.settledPage) ?: return@LaunchedEffect
        while (isActive) {
            delay(250)
            if (player.isPlaying) watched += 250
            // the bars measured again a second and a half and four seconds in (a first frame can be dark all over)
            if (drawn && (watched == 1_500L || watched == 4_000L)) texture?.let { v -> barsOf(v, shape)?.let { seen = it } }
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
                    watched >= 20_000 -> Tuned.Kind.KEPT
                    watched < 3_000 -> Tuned.Kind.PASSED
                    else -> Tuned.Kind.SEEN
                }
                if (kind != null) Fresh.tuned?.heard(t.id, t.artist, kind, t.from)
                when (kind) {
                    Tuned.Kind.KEPT -> mix.kept(t.artist, t.tag)
                    Tuned.Kind.PASSED -> mix.passed(t.artist, t.tag)
                    else -> {}
                }
                if (kind == Tuned.Kind.KEPT || kind == Tuned.Kind.PASSED || liked[t.id] == true) Fresh.build(ctx)
            }
            was = now
        }
    }

    fun next() = scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }

    FrostedPage(onClose = { pages.close() }) { _, top ->
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
                Column(Modifier.fillMaxSize().background(Ink.black).padding(top = top), verticalArrangement = Arrangement.Center) {
                    // the progress through its clip, along the picture's top edge in its colour
                    val from = starts[t.id]
                    val part = if (here && from != null) ((at - from).toFloat() / CLIP).coerceIn(0f, 1f) else 0f
                    Box(Modifier.fillMaxWidth().height(2.dp)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(part).background(accent))
                    }
                    Box(
                        // one frame for every clip (2026-11-18, Bronson: smoother with so many shapes): 16:9, each video
                        // cropped to fill it (a wide film's sides, a 4:3's top and foot), so nothing moves from one to the next
                        Modifier.fillMaxWidth().aspectRatio(16 / 9f).clipToBounds()
                            .clickable(remember { MutableInteractionSource() }, indication = null) { if (player.playWhenReady) player.pause() else player.play() },
                    ) {
                        frame?.let { Image(it.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        if (here) AndroidView(
                            factory = { TextureView(it).apply { keepScreenOn = true; player.setVideoTextureView(this); texture = this } },
                            // its picture's own shape, measured from its frames (black bars in the video itself: a 4:3 film
                            // inside 16:9 showed bars at its sides; YouTube's still hadn't them)
                            modifier = Modifier.covering(shape, eased)
                                .alpha(if (drawnId == t.id) 1f else 0f),
                        )
                        if (here && !playing) BasicText("paused", style = pixel(12.sp), modifier = Modifier.align(Alignment.Center))
                    }
                    Column(Modifier.padding(horizontal = 20.dp).padding(top = 18.dp)) {
                        BasicText(t.title.uppercase(), style = headline(26.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Word(t.artist.lowercase(), pixel(14.sp, Ink.mid), Modifier.padding(top = 6.dp), touch = 6.dp) {
                            pages.open(Page.Artist(t.artist.substringBefore(" & ")))
                        }
                        if (here && said.isNotEmpty()) BasicText(said, style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 10.dp))
                        Row(Modifier.fillMaxWidth().padding(top = 26.dp), verticalAlignment = Alignment.CenterVertically) {
                            // not for me: a pixel thumb down, pressed down as it goes on to the next
                            ThumbDown(Modifier.padding(end = 8.dp)) {
                                Fresh.tuned?.heard(t.id, t.artist, Tuned.Kind.NOPE, t.from)
                                mix.refused(t.artist)
                                rechoose()
                                Fresh.notForMe(ctx, t.artist.substringBefore(" & "), "tune:${t.id}")
                                scope.launch { delay(260); next() }
                            }
                            Spacer(Modifier.weight(1f))
                            Word("play", pixel(14.sp), touch = 10.dp) {
                                player.pause()
                                scope.launch {
                                    val song = t.song ?: withContext(Dispatchers.IO) { YouTubeMusic.byId(t.id) }
                                    song?.let { Playback.play(listOf(it), 0) }
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            // like: a pixel heart, filling with red from its foot up
                            val on = liked[t.id] == true
                            FillingHeart(on) {
                                liked[t.id] = !on
                                scope.launch {
                                    val song = t.song ?: withContext(Dispatchers.IO) { YouTubeMusic.byId(t.id) }
                                    song?.let { if ((it.id in Loved.ids.value) == on) Loved.toggle(it) }
                                }
                                if (!on) {
                                    Fresh.tuned?.heard(t.id, t.artist, Tuned.Kind.LIKED, t.from)
                                    mix.liked(t.artist)
                                    // what's like it, found now and chosen from straight away: its up next, its artist's fans' favorites
                                    scope.launch {
                                        add(runCatching { TunerFeed.related(ctx, t) }.getOrDefault(emptyList()))
                                        rechoose()
                                        val who = t.artist.substringBefore(" & ")
                                        add(runCatching { TunerFeed.fans(ctx, listOf(who), (shown + pool).map { Curation.folded(it.artist) }.toSet()) }.getOrDefault(emptyList()))
                                        rechoose()
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(LocalFoot.current + 40.dp))
                }
            }
        }
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
private fun FillingHeart(on: Boolean, onClick: () -> Unit) {
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
        Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick)).padding(10.dp)
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
private fun barsOf(v: TextureView, shape: Float): Float? = runCatching {
    val w = 160; val h = (w / shape).toInt().coerceAtLeast(40)
    val b = v.getBitmap(w, h) ?: return null
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
