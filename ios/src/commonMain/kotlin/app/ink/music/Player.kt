package app.ink.music

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2

/*
 * The player, as the phone's PlayerPage (2026-11-07: the iPhone the same as the phone): over the cover in its look,
 * where it is in the queue, a pixel speaker, the close arrow; the times over the line ruled along the cover's top edge
 * filling in its colour (drag or tap it, or circle a thumb round the cover, to scrub); the song; the pixel controls on
 * black; up next, lyrics, stop; the artist as the giant marquee along the foot. A new song is the iPod's push. Pull
 * down to close.
 */

/** The player's small words, a size up from the library's and brighter: they're read over the cover's look, not black. */
private val SMALL = 13.sp
private val SAID = Ink.ink.copy(alpha = .75f)
private val OFF = Ink.ink.copy(alpha = .32f)
/** The soft strip over a black bar (the phone's BAND). */
val BAND = 16.dp

/** A scrub in progress (`at`, the finger's), shared by the line and the clickwheel. */
private class Scrub { var at by mutableStateOf<Long?>(null) }

/** Where it is now, read between the player's half-second ticks (a playing song's clock runs on). */
private fun Now.positionNow(at: Long, since: Long): Long = if (playing) (position + nowMs() - since).coerceAtMost(if (duration > 0) duration else Long.MAX_VALUE) else position

@Composable
fun Player(n: Now, onClose: () -> Unit) {
    var dir by remember { mutableIntStateOf(1) }   // the push's way: next in from the right, back from the left
    val close by rememberUpdatedState(onClose)
    val scrub = remember { Scrub() }
    val pull = remember { Animatable(0f) }
    val pullScope = rememberCoroutineScope()
    var queueUp by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                val speed = VelocityTracker()
                detectVerticalDragGestures(
                    onDragStart = { speed.resetTracking() },
                    onDragEnd = {
                        val flung = speed.calculateVelocity().y > 1_800f
                        if (pull.value > size.height / 5f || (flung && pull.value > 0f)) close()
                        else pullScope.launch { pull.animateTo(0f, tween(Motion.EXIT, easing = Motion.expo)) }
                    },
                    onDragCancel = { pullScope.launch { pull.animateTo(0f, tween(Motion.EXIT, easing = Motion.expo)) } },
                ) { c, dy ->
                    c.consume()
                    speed.addPosition(c.uptimeMillis, c.position)
                    pullScope.launch { pull.snapTo((pull.value + dy).coerceAtLeast(0f)) }
                }
            }
            .graphicsLayer { translationY = pull.value }
            .background(Ink.black)
            .clickable(remember { MutableInteractionSource() }, indication = null) {},   // nothing under it takes a tap
    ) {
        val cover = rememberCover(n.song.cover, 1200)
        val accent by animateColorAsState(cover?.accent ?: Ink.ink, tween(Motion.ENTER), label = "accent")
        Box(Modifier.fillMaxSize()) { Backdrop(cover, n) }
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Fit(least = 12f / 13) {
                Centred(
                    start = { Word(if (n.count > 1) "${n.index + 1} of ${n.count}" else "queue", pixel(SMALL, SAID), touch = 10.dp) { queueUp = true } },
                    middle = { Box(Modifier.padding(10.dp)) { Sprite(SPEAKER, SAID, 2.dp) } },
                    end = { Pixel(CLOSE, "close", SAID, onClick = onClose) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp)) {
                // the cover as big as the width allows, unless the song's words under it would run under the controls:
                // then it gives up just that much. What's left over goes above and below (1 : .4)
                val body = @Composable { room: Dp ->
                    Column {
                        Scrubber(n, accent, scrub)
                        Song(n, dir, scrub, room, onBack = { dir = -1 }, onNext = { dir = 1 })
                    }
                }
                SubcomposeLayout(Modifier.fillMaxSize()) { c ->
                    val loose = c.copy(minHeight = 0, maxHeight = Constraints.Infinity)
                    val whole = subcompose("whole") { body(Dp.Infinity) }.map { it.measure(loose) }
                    val over = whole.sumOf { it.height } - c.maxHeight
                    val placed = if (over <= 0) whole else {
                        val side = minOf(c.maxWidth.toDp(), 420.dp)
                        val room = (side - over.toDp() - 4.dp).coerceAtLeast(140.dp)
                        subcompose("fit") { body(room) }.map { it.measure(loose) }
                    }
                    val h = placed.sumOf { it.height }
                    val top = ((c.maxHeight - h).coerceAtLeast(0) / 1.4f).toInt()
                    layout(c.maxWidth, c.maxHeight) { var y = top; placed.forEach { it.place(0, y); y += it.height } }
                }
            }
            // the controls on black, so their small words read over any look
            Column(Modifier.fillMaxWidth().background(Ink.black).windowInsetsPadding(WindowInsets.navigationBars).padding(top = 6.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Pixel(SHUFFLE, if (n.shuffle) "shuffle, on" else "shuffle, off", if (n.shuffle) Ink.ink else OFF) { Sound.shuffle() }
                    Pixel(BACK, "back", Ink.ink, cell = 3.dp) { dir = -1; Sound.previous() }
                    Pixel(if (n.playing) PAUSE else PLAY, if (n.playing) "pause" else "play", Ink.ink, cell = 3.dp) { Sound.toggle() }
                    Pixel(NEXT, "next", Ink.ink, cell = 3.dp) { dir = 1; Sound.next() }
                    Pixel(if (n.repeat == Now.REPEAT_ONE) REPEAT_ONE else REPEAT, "repeat", if (n.repeat != Now.REPEAT_OFF) Ink.ink else OFF) { Sound.repeat() }
                }
                Spacer(Modifier.height(8.dp))
                Fit(least = 12f / 14) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
                        Word("up next", pixel(14.sp, SAID), touch = 10.dp) { queueUp = true }
                        Word("lyrics", pixel(14.sp, OFF), touch = 10.dp) {}
                        Word("stop", pixel(14.sp, if (n.playing) SAID else OFF), touch = 10.dp) { Sound.stop(); onClose() }
                    }
                }
                Spacer(Modifier.height(12.dp))
                // the artist's name, big
                Marquee(n.song.artist, n.playing, Modifier.fillMaxWidth(), size = 120.sp)
            }
        }
        AnimatedVisibility(
            queueUp,
            enter = slideInVertically(tween(Motion.ENTER, easing = Motion.expo)) { (it * .06f).toInt() } + fadeIn(tween(Motion.ENTER, easing = Motion.expo)),
            exit = slideOutVertically(tween(Motion.EXIT, easing = Motion.expo)) { (it * .04f).toInt() } + fadeOut(tween(Motion.EXIT, easing = Motion.expo)),
        ) { QueuePage(n) { queueUp = false } }
    }
}

/** What's up next: the queue from here, the playing one lit; a tap plays that one. */
@Composable
private fun QueuePage(n: Now, onClose: () -> Unit) {
    val q = remember(n.index, n.count, n.shuffle) { Sound.queue() }
    androidx.compose.foundation.lazy.LazyColumn(
        Modifier.fillMaxSize().background(Ink.black).windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 40.dp),
        state = androidx.compose.foundation.lazy.rememberLazyListState((n.index - 1).coerceAtLeast(0)),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText("UP NEXT", style = headline(40.sp), modifier = Modifier.weight(1f))
                Pixel(CLOSE, "close", Ink.mid, onClick = onClose)
            }
        }
        items(q.size) { i ->
            val s = q[i]
            Named(s.title, s.artist, lit = i == n.index) { if (i != n.index) Sound.play(q, i); onClose() }
        }
    }
}

/**
 * The times, then the 1 px line filling with the song in the cover's colour, ruled straight onto the cover's top edge.
 * Drag along it, or tap, to scrub: the times light and the line grows to 4 dp while the finger is down, the song jumps
 * there on letting go. The finger's room reaches [REACH] above the times.
 */
@Composable
private fun Scrubber(n: Now, accent: Color, scrub: Scrub, line: Dp = 1.dp) {
    var pos by remember { mutableLongStateOf(n.position) }
    LaunchedEffect(n) {
        val since = nowMs()
        while (true) { pos = n.positionNow(0, since); if (!n.playing) break; delay(250) }
    }
    val shown = scrub.at ?: pos
    val frac = if (n.duration > 0) (shown.toFloat() / n.duration).coerceIn(0f, 1f) else 0f
    val times by animateColorAsState(if (scrub.at != null) Ink.ink else SAID, tween(Motion.FADE), label = "times")
    val duration by rememberUpdatedState(n.duration)
    fun where(x: Float, width: Int) = ((x / width).coerceIn(0f, 1f) * duration).toLong().coerceAtMost((duration - 500).coerceAtLeast(0))
    Column(
        Modifier
            .pointerInput(Unit) {
                detectTapGestures(onPress = { o ->
                    if (duration <= 0) return@detectTapGestures
                    scrub.at = where(o.x, size.width)
                    if (tryAwaitRelease()) scrub.at?.let(Sound::seek)
                    scrub.at = null
                })
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> if (duration > 0) scrub.at = where(o.x, size.width) },
                    onDragEnd = { scrub.at?.let(Sound::seek); scrub.at = null },
                    onDragCancel = { scrub.at = null },
                ) { c, _ -> c.consume(); if (duration > 0) scrub.at = where(c.position.x, size.width) }
            }
            .padding(top = REACH),
    ) {
        Box(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            BasicText(clock(shown), style = pixel(SMALL, times), modifier = Modifier.align(Alignment.CenterStart))
            BasicText(quality(n.song), style = pixel(SMALL, SAID), modifier = Modifier.align(Alignment.Center))
            BasicText(if (n.duration > 0) "-" + clock(n.duration - shown) else "", style = pixel(SMALL, times), modifier = Modifier.align(Alignment.CenterEnd))
        }
        val held = scrub.at != null
        val thick by animateDpAsState(if (held) maxOf(line, 4.dp) else line, tween(if (held) Motion.ENTER else Motion.EXIT, easing = Motion.expo), label = "line")
        Box(Modifier.fillMaxWidth().height(line)) {
            Box(Modifier.align(Alignment.BottomStart).wrapContentHeight(Alignment.Bottom, unbounded = true).fillMaxWidth().height(thick).background(Ink.line)) {
                Box(Modifier.fillMaxWidth(frac).height(thick).background(accent))
            }
        }
    }
}

/** What's heard, between the times (the phone's says "flac 16/44.1"): YouTube's AAC, or the server's. */
private fun quality(s: Song): String = when (s.kind) { "youtube" -> Tube.quality(s.id) ?: ""; "station" -> "live"; else -> "" }

private val REACH = 14.dp

/** The song under the line: the cover (its clickwheel) and its words. A new song pushes in. */
@Composable
private fun Song(n: Now, dir: Int, scrub: Scrub, room: Dp, onBack: () -> Unit, onNext: () -> Unit) {
    AnimatedContent(
        targetState = n,
        contentKey = { it.song.id + it.index },
        transitionSpec = {
            ContentTransform(
                slideInHorizontally(tween(Motion.ENTER, easing = Motion.expo)) { it / 3 * dir } + fadeIn(tween(Motion.ENTER, easing = Motion.expo)),
                slideOutHorizontally(tween(Motion.EXIT, easing = Motion.expo)) { -it / 3 * dir } + fadeOut(tween(Motion.EXIT)),
            )
        },
        label = "push",
    ) { t ->
        val live by rememberUpdatedState(t)
        Column {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val side = minOf(maxWidth, 420.dp, room)
                Sleeve(t, side, Modifier.clickwheel({ live }, scrub, onBack, onNext))
            }
            Spacer(Modifier.height(20.dp))
            SongWords(t)
        }
    }
}

/** The song's words under its cover: the title (a long one sliding along its line) and the heart, the artist and record, share. */
@Composable
private fun SongWords(t: Now) {
    Column {
        Row(verticalAlignment = Alignment.Top) {
            val slides = t.playing
            FadeText(t.song.title.uppercase(), headline(32.sp), maxLines = 1, softWrap = false,
                overflow = if (slides) TextOverflow.Clip else TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).then(if (slides) Modifier.basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = 2_000, initialDelayMillis = 2_000) else Modifier))
            Heart(t.song, 3.dp, Modifier.padding(start = 8.dp))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            Row(Modifier.weight(1f)) {
                FadeText(t.song.artist.lowercase(), pixel(SMALL, SAID), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (t.song.album.isNotBlank() && t.song.kind != "episode") {
                    Spacer(Modifier.width(14.dp))
                    FadeText(t.song.album.lowercase(), pixel(SMALL, SAID), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
            }
        }
    }
}

@Composable
private fun Sleeve(t: Now, side: Dp, modifier: Modifier) {
    val c = rememberCover(t.song.cover, 1200)
    val late by produceState(false, t.song.albumId) { delay(1_500); value = true }
    Box(modifier.size(side)) {
        if (c != null) Image(c.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.Low)
        else if (late || t.song.cover == null) NameCover(t.song.album.ifEmpty { t.song.title }, side)
    }
}

/** The cover in its look across the whole page, drawn off the main thread once a cover and look; the one up stays till the next fades in. */
@Composable
private fun Backdrop(cover: Art?, n: Now) {
    val look by Looks.on.collectAsState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current.density
        val shown = maxOf(maxWidth, maxHeight).value * density
        val drawn by produceState<Pair<androidx.compose.ui.graphics.ImageBitmap, Look>?>(null, cover, look, shown) {
            if (cover == null || look == Look.PLAIN) { value = null; return@produceState }
            Here.off { Looks.draw(cover.image, look, cover.accent, shown, density) }?.let { value = it to look }
        }
        drawn?.let { (img, shape) ->
            val fade = remember(img) { Animatable(0f) }
            LaunchedEffect(img) { fade.animateTo(shape.alpha, tween(600)) }
            Image(img, null, Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }, contentScale = ContentScale.Crop,
                filterQuality = if (shape.crisp) FilterQuality.None else FilterQuality.Low)
        }
    }
}

/**
 * The iPod's clickwheel, with the cover as its hub: circle a thumb round it to scrub, clockwise forward, a tick every 15
 * degrees moving the song 5 s; letting go seeks there. It turns only once the thumb is really circling, so a straight
 * swipe never scrubs: a flick sideways is the next song (to the left) or the one before (to the right). A tap: play / pause.
 */
private fun Modifier.clickwheel(now: () -> Now, scrub: Scrub, onBack: () -> Unit, onNext: () -> Unit): Modifier = composed {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val hub = with(density) { 18.dp.toPx() }
    val flick = with(density) { 56.dp.toPx() }
    val stride = with(density) { 6.dp.toPx() }
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            val t = now()
            val wheel = t.duration > 0
            val centre = Offset(size.width / 2f, size.height / 2f)
            val from = t.position
            var last: Float? = null
            var turned = 0f
            var engaged: Float? = null
            var ticks = 0
            var travel = 0f
            var at = down.position
            var mark = down.position
            var way: Float? = null
            var bent = 0f
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                change.consume()
                at = change.position
                travel += (change.position - change.previousPosition).getDistance()
                if (!wheel) continue
                val step = change.position - mark
                if (step.getDistance() >= stride) {
                    val w = atan2(step.y, step.x)
                    way?.let { bent += wrap(w - it) }
                    way = w; mark = change.position
                }
                val v = change.position - centre
                if (v.getDistance() < hub) { last = null; continue }
                val a = atan2(v.y, v.x)
                last?.let { turned += wrap(a - it) }
                last = a
                if (engaged == null && kotlin.math.abs(bent) >= WHEEL_ENGAGE && bent * turned > 0) engaged = turned
                val base = engaged ?: continue
                val k = ((turned - base) / WHEEL_STEP).toInt()
                if (k != ticks) {
                    ticks = k
                    scrub.at = (from + k * WHEEL_SEEK).coerceIn(0L, t.duration)
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                }
            }
            val moved = at - down.position
            when {
                scrub.at != null -> { scrub.at?.let(Sound::seek); scrub.at = null }
                engaged == null && kotlin.math.abs(moved.x) > flick && kotlin.math.abs(moved.x) > kotlin.math.abs(moved.y) * 1.5f -> {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    if (moved.x < 0) { onNext(); Sound.next() } else { onBack(); Sound.previous() }
                }
                travel < viewConfiguration.touchSlop -> Sound.toggle()
            }
        }
    }
}

private fun wrap(d: Float): Float = when {
    d > PI -> d - (2 * PI).toFloat()
    d < -PI -> d + (2 * PI).toFloat()
    else -> d
}

private val WHEEL_STEP = (PI / 12).toFloat()
private const val WHEEL_SEEK = 5_000L
private val WHEEL_ENGAGE = (PI / 3).toFloat()

/** The pixel speaker: a cabinet, its tweeter over its woofer. */
private val SPEAKER = listOf("##########", "#........#", "#...##...#", "#...##...#", "#........#", "#..####..#", "#.#....#.#", "#.#.##.#.#",
    "#.#.##.#.#", "#.#....#.#", "#..####..#", "#........#", "##########")
/** The player's controls as pixels, on the speaker's grid. Play and pause nine high, back and next seven (at 3 dp). */
private val BACK = listOf("#...#", "#..##", "#.###", "#####", "#.###", "#..##", "#...#")
private val PLAY = listOf("#....", "##...", "###..", "####.", "#####", "####.", "###..", "##...", "#....")
private val PAUSE = listOf("##.##", "##.##", "##.##", "##.##", "##.##", "##.##", "##.##", "##.##", "##.##")
private val NEXT = listOf("#...#", "##..#", "###.#", "#####", "###.#", "##..#", "#...#")
private val SHUFFLE = listOf("........#..", "##....#####", "..#..#..#..", "...##......", "..#..#..#..", "##....#####", "........#..")
private val REPEAT = listOf("..#........", ".##########", "#.#.......#", "#.........#", "#.......#.#", "##########.", "........#..")
private val REPEAT_ONE = listOf("..#........", ".##########", "#.#.......#", "#....#....#", "#.......#.#", "##########.", "........#..")
/** close: the player goes down, as it's pulled. Also the menu's chevron. */
val CLOSE = listOf("##...##", ".##.##.", "..###..", "...#...")

/** A pixel control: dimmer while pressed (as a word is), felt, room round it to touch. */
@Composable
fun Pixel(rows: List<String>, name: String, colour: Color, modifier: Modifier = Modifier, cell: Dp = 2.dp, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val c by animateColorAsState(if (pressed) Ink.mid else colour, tween(Motion.FADE), label = name)
    Box(modifier.clickable(src, indication = null, onClick = felt(then = onClick)).padding(horizontal = cell * 4, vertical = cell * 6)) { Sprite(rows, c, cell) }
}

/** At the foot (the phone's mini player): the line in the cover's colour, the cover, the song, pause; a tap opens the player. */
@Composable
fun MiniPlayer(n: Now, onOpen: () -> Unit) {
    val cover = rememberCover(n.song.cover, 400)
    var pos by remember { mutableLongStateOf(n.position) }
    LaunchedEffect(n) { val since = nowMs(); while (true) { pos = n.positionNow(0, since); if (!n.playing) break; delay(500) } }
    val frac = if (n.duration > 0) (pos.toFloat() / n.duration).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth().background(Ink.black).windowInsetsPadding(WindowInsets.navigationBars).padding(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line)) { Box(Modifier.fillMaxWidth(frac).height(1.dp).background(cover?.accent ?: Ink.ink)) }
        Row(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onOpen)),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(72.dp)) {
                if (cover != null) Image(cover.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else NameCover(n.song.album.ifEmpty { n.song.title }, 72.dp)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                BasicText(n.song.title.uppercase(), style = headline(20.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                BasicText(n.song.artist.lowercase(), style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Word(if (n.playing) "pause" else "play", pixel(14.sp), Modifier.padding(start = 14.dp), touch = 12.dp) { Sound.toggle() }
        }
    }
}
