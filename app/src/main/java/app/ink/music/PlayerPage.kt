package app.ink.music

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.foundation.layout.offset
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import dev.chrisbanes.haze.hazeSource
import kotlin.math.PI
import kotlin.math.atan2

/**
 * The player's small words, a size up from the library's and brighter (Bronson, 2026-10-26, after a
 * tester couldn't read them): they're read over the cover's look, not black. Said things (the artist,
 * the times, the top row) at 75%, not 55%; what's off (shuffle, repeat, sleep, lyrics) at 32%, not 17%.
 */
private val SMALL = 13.sp
private val SAID = Ink.ink.copy(alpha = .75f)
private val OFF = Ink.ink.copy(alpha = .32f)

/** A scrub in progress (`at`, the finger's), shared by the line and the clickwheel. */
private class Scrub { var at by mutableStateOf<Long?>(null) }

/**
 * The player, the whole screen, over the cover in its look (ink's desk clock looks: blur, fluted
 * glass, pixels, dither, or plain). The times over the line, the line ruled along the cover's top
 * edge filling in its colour (drag or tap it, or circle a thumb round the cover, to scrub), the
 * song, back / pause / next / lyrics, and the artist as the giant marquee along the foot. With
 * lyrics on and a song that has synced lines, they take the cover's place and the cover sits
 * small beside the song, still under the line. A new song is the iPod's push. Pull down to close.
 */
@Composable
fun PlayerPage(onClose: () -> Unit, onOpen: (Page) -> Unit = {}, onStop: () -> Unit = onClose) {
    val ctx = LocalContext.current
    val now by Playback.now.collectAsState()
    val n = now
    var dir by remember { mutableIntStateOf(1) }   // the push's way: next in from the right, back from the left
    var queueUp by remember { mutableStateOf(false) }
    // into a playlist, from here (Bronson, 2026-10-29: "an easier way"): your lists over the player, and back
    var addUp by remember { mutableStateOf(false) }
    var added by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(added) { if (added != null) { delay(3_000); added = null } }
    var playOnUp by remember { mutableStateOf(false) }
    var heardUp by remember { mutableStateOf(false) }
    // a lossless song's moment across the cover, while it types (the word at the top waits for it)
    var typing by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(playOnUp) { playOnUp = false }
    val close by rememberUpdatedState(onClose)
    val scrub = remember { Scrub() }
    androidx.activity.compose.BackHandler(queueUp) { queueUp = false }
    androidx.activity.compose.BackHandler(addUp) { addUp = false }
    // pulled down, the player comes with the finger: past a fifth of the screen (or flung) it goes, else it settles
    // back (it had closed on a pull without moving, and a tester found no pull at all)
    val pull = remember { androidx.compose.animation.core.Animatable(0f) }
    val pullScope = androidx.compose.runtime.rememberCoroutineScope()
    Box(
        // read before the layer that moves, so the finger's place is the screen's, not the sliding page's
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                val speed = androidx.compose.ui.input.pointer.util.VelocityTracker()
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
        if (n == null) {
            Column(Modifier.windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)).padding(20.dp)) {
                BasicText("NOTHING PLAYING", style = headline(32.sp))
            }
            return@Box
        }
        // the cover at the screen's width: it's shown nearly that wide, and the look behind is drawn from it
        val wide = LocalContext.current.resources.displayMetrics.widthPixels.coerceIn(640, 1440)
        val cover = rememberCover(n.song, wide)
        val accent by animateColorAsState(cover?.accent ?: Ink.ink, tween(Motion.ENTER), label = "accent")
        // the cover in its look behind it all; what nears the controls' black bar softens (ink's bars)
        val frost = dev.chrisbanes.haze.rememberHazeState()
        // black inside the source: the look and its grain are drawn over black there as on the screen (over
        // nothing, the grain's overlay blend came out lighter, a light strip over the bar)
        Box(Modifier.fillMaxSize().hazeSource(frost).background(Ink.black)) { Backdrop(cover, n) }
        val sing by Lyrics.on.collectAsState()
        val lines = rememberLines(n, sing)
        val lyric = rememberLyric(n, lines)
        // the artist read ahead (their name, here, is a way to their page)
        val artist = artistOf(n)
        LaunchedEffect(artist) { artist?.let { YouTubeAccount.warm(ctx, it) } }
        val look by Looks.on.collectAsState()
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val share = { scope.launch { ShareCard.share(ctx, n, look) }; Unit }
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)),
        ) {
            Fit(least = 12f / 13) {
                // where in the queue (a tap opens it); where it plays, a pixel speaker (as the heart is drawn) in the
                // middle, lit while it's a speaker, dim on the phone, a tap opens every speaker (its name is under the
                // title: "flac on bedroom speaker", a long one here pushed close off the row); close. The look is chosen
                // in settings, the look, now (its three layers there, each its own: Bronson, 2026-10-30)
                Centred(
                    start = { Word(if (n.count > 1) "${n.index + 1} of ${n.count}" else "queue", pixel(SMALL, SAID), touch = 10.dp) { queueUp = true } },
                    middle = { PlayOnSpeaker(n.device.isNotEmpty(), Modifier) { playOnUp = true } },
                    end = { Pixel(CLOSE, "close", SAID, onClick = onClose) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                )
            }
            val words = lyric != null
            AnimatedContent(
                targetState = words, modifier = Modifier.weight(1f),
                transitionSpec = { fadeIn(tween(Motion.ENTER, easing = Motion.expo)) togetherWith fadeOut(tween(Motion.EXIT, easing = Motion.expo)) },
                label = "lyrics",
            ) { withLyrics ->
                Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                    if (withLyrics && lyric != null) {
                        LyricsBlock(lyric, Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp, bottom = 20.dp))
                        // lyrics up: the line a little heavier over the small cover (1 dp read as faint there)
                        Scrubber(n, accent, scrub, line = 2.dp) { HeardWord(n, typing) { heardUp = true } }
                        Song(n, dir, small = true, scrub, onOpen, share, onHeard = { heardUp = true })
                    } else {
                        // the cover as big as the width allows, unless the song's words under it would then run
                        // under the controls (Android's own display size, a short phone): then it gives up just
                        // that much height. What's left over goes above and below, as the weights had it (1 : .4)
                        val body = @Composable { room: androidx.compose.ui.unit.Dp ->
                            androidx.compose.runtime.CompositionLocalProvider(LocalCoverRoom provides room) {
                                Column {
                                    Scrubber(n, accent, scrub) { HeardWord(n, typing) { heardUp = true } }
                                    Song(n, dir, small = false, scrub, onOpen, share, onHeard = { heardUp = true }) { t -> Moment(t) { typing = it } }
                                }
                            }
                        }
                        androidx.compose.ui.layout.SubcomposeLayout(Modifier.fillMaxSize()) { c ->
                            val loose = c.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity)
                            val whole = subcompose("whole") { body(androidx.compose.ui.unit.Dp.Infinity) }.map { it.measure(loose) }
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
                }
            }
            // the controls on black, so their small words read over any look (a tester couldn't read
            // shuffle, repeat, up next and sleep over dither); the art softens into the bar, never fades
            Box(Modifier.fillMaxWidth().height(BAND).softEdge(frost, towardBottom = true))
            Column(Modifier.fillMaxWidth().background(Ink.black).navigationBarsPadding().padding(top = 6.dp)) {
            // what's played with, drawn (as the speaker is), by how often it's pressed: play in the middle, under the
            // thumb, the biggest; back and next either side; shuffle and repeat a size smaller at the ends, lit when on.
            // A play shape says what it does, where "play" and "pause" read as a state to a tester (2026-10-28)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
            ) {
                Pixel(SHUFFLE, if (n.shuffle) "shuffle, on" else "shuffle, off", if (n.shuffle) Ink.ink else OFF) { Playback.shuffle() }
                Pixel(BACK, "back", Ink.ink, cell = 3.dp) { dir = -1; Playback.previous() }
                Pixel(if (n.playing) PAUSE else PLAY, if (n.playing) "pause" else "play", Ink.ink, cell = 3.dp) { Playback.toggle() }
                Pixel(NEXT, "next", Ink.ink, cell = 3.dp) { dir = 1; Playback.next() }
                val repeat = n.repeat
                Pixel(
                    if (repeat == androidx.media3.common.Player.REPEAT_MODE_ONE) REPEAT_ONE else REPEAT,
                    when (repeat) { androidx.media3.common.Player.REPEAT_MODE_ONE -> "repeat one"; androidx.media3.common.Player.REPEAT_MODE_OFF -> "repeat, off"; else -> "repeat, on" },
                    if (repeat != androidx.media3.common.Player.REPEAT_MODE_OFF) Ink.ink else OFF,
                ) { Playback.repeat() }
            }
            Spacer(Modifier.height(8.dp))
            // selection is brightness: lit is on; on, for a song lrclib has no synced lines for, it says so
            // (lit with the cover still there read as broken to a tester)
            val none = sing && lines?.isEmpty() == true
            Modes(n, if (none) "no lyrics" else "lyrics", sing && !none, onQueue = { queueUp = true }, onStop = onStop,
                added = added, onAdd = if (canAddToList(n.song)) ({ addUp = true }) else null)
            Spacer(Modifier.height(12.dp))
            // the artist's name, big, a way to their page too
            Marquee(n.artist, n.moving, Modifier.fillMaxWidth().bleedPastCutout().clickable(remember { MutableInteractionSource() }, indication = null,
                onClick = felt { artistOf(n)?.let { onOpen(Page.Artist(it)) } }), size = 120.sp)
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            queueUp,
            enter = slideInVertically(tween(Motion.ENTER, easing = Motion.expo)) { (it * .06f).toInt() } + fadeIn(tween(Motion.ENTER, easing = Motion.expo)),
            exit = slideOutVertically(tween(Motion.EXIT, easing = Motion.expo)) { (it * .04f).toInt() } + fadeOut(tween(Motion.EXIT, easing = Motion.expo)),
        ) { QueuePage(onClose = { queueUp = false }) }
        androidx.compose.animation.AnimatedVisibility(
            addUp && n.song != null,
            enter = slideInVertically(tween(Motion.ENTER, easing = Motion.expo)) { (it * .06f).toInt() } + fadeIn(tween(Motion.ENTER, easing = Motion.expo)),
            exit = slideOutVertically(tween(Motion.EXIT, easing = Motion.expo)) { (it * .04f).toInt() } + fadeOut(tween(Motion.EXIT, easing = Motion.expo)),
        ) { n.song?.let { song -> AddToList(song, onDone = { name -> addUp = false; added = name }, onClose = { addUp = false }) } }
        androidx.compose.animation.AnimatedVisibility(
            playOnUp,
            enter = slideInVertically(tween(Motion.ENTER, easing = Motion.expo)) { (it * .06f).toInt() } + fadeIn(tween(Motion.ENTER, easing = Motion.expo)),
            exit = slideOutVertically(tween(Motion.EXIT, easing = Motion.expo)) { (it * .04f).toInt() } + fadeOut(tween(Motion.EXIT, easing = Motion.expo)),
        ) { PlayOnPage(onClose = { playOnUp = false }) }
        androidx.compose.animation.AnimatedVisibility(
            heardUp && (n.heard != null || n.device.isNotEmpty()),
            enter = slideInVertically(tween(Motion.ENTER, easing = Motion.expo)) { (it * .06f).toInt() } + fadeIn(tween(Motion.ENTER, easing = Motion.expo)),
            exit = slideOutVertically(tween(Motion.EXIT, easing = Motion.expo)) { (it * .04f).toInt() } + fadeOut(tween(Motion.EXIT, easing = Motion.expo)),
        ) { SignalPath(n, onClose = { heardUp = false }) }
    }
}

/**
 * The player's second row, smaller: up next, lyrics, into a playlist, stop (sleep gone: Bronson, 2026-10-29).
 */
@Composable
private fun Modes(n: Now, lyrics: String, lyricsOn: Boolean, onQueue: () -> Unit, onStop: () -> Unit, added: String? = null, onAdd: (() -> Unit)? = null) {
    val style = 14.sp
    // the words with no drawing everyone reads, stop last, away from next (it ends everything and closes the player)
    Fit(least = 12f / 14) { Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Word("up next", pixel(style, SAID), touch = 10.dp, onClick = onQueue)
        Word(lyrics, pixel(style, if (lyricsOn) Ink.ink else OFF), touch = 10.dp) { Lyrics.toggle() }
        // into one of your playlists (lit a moment once it's in)
        if (onAdd != null) Word(if (added != null) "added" else "playlist", pixel(style, if (added != null) Ink.ink else SAID), touch = 10.dp, onClick = onAdd)
        // stopped, the player goes too (the mini player's gone with it: nothing left to come back down to)
        Word("stop", pixel(style, if (n.playing) SAID else OFF), touch = 10.dp) { Playback.stop(); onStop() }
    } }
}


/**
 * The song under the line: the cover (its clickwheel: circle a thumb round it to scrub, tap to
 * play / pause) and its words. Big, the cover across the width and the words under it; small (the
 * lyrics up), the cover at 96 dp (72, the mini player's, read as too small under the lines) with the words beside it. A new song pushes in.
 */
@Composable
private fun Song(n: Now, dir: Int, small: Boolean, scrub: Scrub, onOpen: (Page) -> Unit, onShare: () -> Unit, onHeard: () -> Unit = {}, over: @Composable (Now) -> Unit = {}) {
    AnimatedContent(
        targetState = n,
        // a station's new song pushes in too
        contentKey = { it.song?.takeUnless { s -> s.station }?.id ?: (it.song?.id.orEmpty() + it.title + it.artist) },
        transitionSpec = {
            ContentTransform(
                slideInHorizontally(tween(Motion.ENTER, easing = Motion.expo)) { it / 3 * dir } + fadeIn(tween(Motion.ENTER, easing = Motion.expo)),
                slideOutHorizontally(tween(Motion.EXIT, easing = Motion.expo)) { -it / 3 * dir } + fadeOut(tween(Motion.EXIT)),
            )
        },
        label = "push",
    ) { t ->
        val live by rememberUpdatedState(t)
        if (small) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Sleeve(t, 96.dp, Modifier.clickwheel({ live }, scrub))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.Top) {
                    FadeText(t.title.uppercase(), headline(20.sp), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Heart(t.song, 2.dp)
                }
                Spacer(Modifier.height(4.dp))
                Row {
                    FadeText(t.artist.lowercase(), pixel(SMALL, SAID), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).clickable(remember { MutableInteractionSource() }, indication = null,
                            onClick = felt { artistOf(t)?.let { onOpen(Page.Artist(it)) } }))
                    Word("share", pixel(SMALL, SAID), Modifier.padding(start = 12.dp), onClick = onShare)
                }
            }
        } else Column {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val side = minOf(maxWidth, 420.dp, LocalCoverRoom.current)
                Sleeve(t, side, Modifier.clickwheel({ live }, scrub))
                Box(Modifier.size(side)) { over(t) }
            }
            Spacer(Modifier.height(20.dp))
            // the title, and beside it the desktop's heart
            Row(verticalAlignment = androidx.compose.ui.Alignment.Top) {
                FadeText(t.title.uppercase(), headline(32.sp), maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Heart(t.song, 3.dp, Modifier.padding(start = 8.dp))
            }
            Spacer(Modifier.height(8.dp))
            // an easter egg's word, when one's due (on repeat, a 100th time, a promise kept)
            EggsNow.line(t.song)?.let { BasicText(it, style = pixel(SMALL, Ink.ink)); Spacer(Modifier.height(4.dp)) }
            // on a speaker: what reaches it ("flac 24/96 on dining room speaker"); a tap, its signal path
            if (t.device.isNotEmpty()) {
                Word(listOf(t.quality, "on ${t.device.lowercase()}").filter { it.isNotBlank() }.joinToString(" "),
                    pixel(SMALL, SAID), onClick = onHeard)
                Spacer(Modifier.height(4.dp))
            }
            // the artist and the record, each a way to its page
            Row(Modifier.fillMaxWidth()) {
              Row(Modifier.weight(1f)) {
                val song = t.song
                FadeText(t.artist.lowercase(), pixel(SMALL, SAID), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).clickable(remember { MutableInteractionSource() }, indication = null,
                        onClick = felt { artistOf(t)?.let { onOpen(Page.Artist(it)) } }))
                if (t.album.isNotBlank()) {
                    Spacer(Modifier.width(14.dp))
                    FadeText(t.album.lowercase(), pixel(SMALL, SAID), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).clickable(remember { MutableInteractionSource() }, indication = null,
                            onClick = felt { song?.takeUnless { it.station }?.let { onOpen(Page.Record(it.albumId)) } }))
                }
              }
                // the song as a poster, for a story or a message
                Word("share", pixel(SMALL, SAID), Modifier.padding(start = 12.dp), onClick = onShare)
            }
        }
    }
}

@Composable
private fun Sleeve(t: Now, side: Dp, modifier: Modifier) {
    val c = rememberCover(t.song, LocalContext.current.resources.displayMetrics.widthPixels.coerceIn(640, 1440))
    // the cover in its own effect (settings, the look: the cover's): dither, pixels, fluted glass, drawn at its own size
    val look by Moving.cover.collectAsState()
    val density = LocalDensity.current.density
    val px = side.value * density
    val drawn by produceState<ImageBitmap?>(null, c, look, px) {
        val l = look
        value = if (c != null && l != null) withContext(Dispatchers.Default) { Looks.draw(c.image.asAndroidBitmap(), l, c.accent, px, density)?.asImageBitmap() } else null
    }
    Box(modifier.size(side)) {
        if (c != null) Image(drawn ?: c.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            filterQuality = if (drawn != null && look?.crisp == true) FilterQuality.None else FilterQuality.Low)
        else if (t.song?.station == true) StationCover(t.song, Modifier.fillMaxSize(), moving = t.moving)
        else NameCover(t.album.ifEmpty { t.title }, side)
        // the record's own moving sleeve over it, where it has one (settings: moving covers)
        if (t.song?.station != true) MovingCover(t.artist, t.album, t.title, c?.accent ?: Ink.ink, t.moving, Modifier.fillMaxSize())
    }
}

/**
 * The cover in its look across the whole page, drawn off the main thread once a cover and look;
 * the one up stays until the next is ready, then fades in. The grain over it (not dither's).
 */
@Composable
private fun Backdrop(cover: Cover?, n: Now) {
    val look by Looks.on.collectAsState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current.density
        // the square cover cropped to fill the page: shown at the page's longer side
        val shown = maxOf(maxWidth, maxHeight).value * density
        val drawn by produceState<Pair<ImageBitmap, Look>?>(null, cover, look, shown) {
            if (cover == null || look == Look.PLAIN) { value = null; return@produceState }
            withContext(Dispatchers.Default) { Looks.draw(cover.image.asAndroidBitmap(), look, cover.accent, shown, density) }
                ?.let { value = it.asImageBitmap() to look }
        }
        // a canvas playing over it: the still one not drawn under it (its look came through the canvas)
        val canvasOn by Moving.canvasOn.collectAsState()
        val canvasLook by Moving.canvas.collectAsState()
        // (a new song resets it; a look or setting changed doesn't: its clip plays on, and its first frame isn't seen
        // again till it loops, the still one showing through meanwhile)
        var canvasUp by remember(n.title, n.artist) { mutableStateOf(false) }
        val canvasShown = canvasUp && canvasOn
        drawn?.takeIf { !canvasShown }?.let { (img, shape) ->
            val fade = remember(img) { Animatable(0f) }
            LaunchedEffect(img) { fade.animateTo(shape.alpha, tween(600)) }
            Image(
                img, null, Modifier.fillMaxSize().graphicsLayer { alpha = fade.value },
                contentScale = ContentScale.Crop,
                filterQuality = if (shape.crisp) FilterQuality.None else FilterQuality.Low,
            )
        }
        // the song's spotify canvas over it, moving, in the same look (signed in to spotify: settings, the look)
        if (n.song?.station != true) CanvasBehind(n.title, n.artist, cover?.accent ?: Ink.ink, n.moving, Modifier.fillMaxSize()) { canvasUp = true }
        // the grain over a still look, or a canvas in one (not dither's, nor a canvas as it is)
        if (if (canvasShown) canvasLook != null && canvasLook != Look.DITHER else drawn?.second?.let { it != Look.DITHER } == true) Grain()
    }
}

/** ink's fine grain over the art, so the blur reads as a colour field, not a picture. */
@Composable
private fun Grain() {
    val img = ImageBitmap.imageResource(R.drawable.grain)
    val brush = remember(img) { ShaderBrush(ImageShader(img, TileMode.Repeated, TileMode.Repeated)) }
    Canvas(Modifier.fillMaxSize()) { drawRect(brush, alpha = .18f, blendMode = BlendMode.Overlay) }
}

/**
 * The times, then the 1 px line filling with the song in the cover's colour, ruled straight onto
 * the cover's top edge. Drag along it, or tap, to scrub: the times light while the finger is
 * down, the song jumps there on letting go.
 */
@Composable
private fun Scrubber(n: Now, accent: Color, scrub: Scrub, line: androidx.compose.ui.unit.Dp = 1.dp, middle: @Composable () -> Unit = {}) {
    // where the middle word sits: a tap there is the word's, not a seek
    var hole by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val margin = with(androidx.compose.ui.platform.LocalDensity.current) { 12.dp.toPx() }
    var pos by remember { mutableLongStateOf(n.positionNow()) }
    LaunchedEffect(n) {
        while (true) { pos = n.positionNow(); if (!n.moving) break; delay(250) }
    }
    val shown = scrub.at ?: pos
    val frac = if (n.duration > 0) (shown.toFloat() / n.duration).coerceIn(0f, 1f) else 0f
    val times by animateColorAsState(if (scrub.at != null) Ink.ink else SAID, tween(Motion.FADE), label = "times")
    val duration by rememberUpdatedState(n.duration)
    fun where(x: Float, width: Int) = ((x / width).coerceIn(0f, 1f) * duration).toLong()
    Column(
        Modifier
            .pointerInput(Unit) {
                detectTapGestures(onPress = { o ->
                    if (duration <= 0 || hole?.contains(o) == true) return@detectTapGestures
                    scrub.at = where(o.x, size.width)
                    if (tryAwaitRelease()) scrub.at?.let(Playback::seek)
                    scrub.at = null
                })
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> if (duration > 0) scrub.at = where(o.x, size.width) },
                    onDragEnd = { scrub.at?.let(Playback::seek); scrub.at = null },
                    onDragCancel = { scrub.at = null },
                ) { c, _ -> c.consume(); if (duration > 0) scrub.at = where(c.position.x, size.width) }
            },
    ) {
        // the times at the ends, and dead centre between them what's heard
        Box(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            BasicText(clock(shown), style = pixel(SMALL, times), modifier = Modifier.align(androidx.compose.ui.Alignment.CenterStart))
            Box(Modifier.align(androidx.compose.ui.Alignment.Center)
                .onGloballyPositioned { hole = it.boundsInParent().inflate(margin) }) { middle() }
            BasicText(if (n.duration > 0) "-" + clock(n.duration - shown) else "", style = pixel(SMALL, times),
                modifier = Modifier.align(androidx.compose.ui.Alignment.CenterEnd))
        }
        // the line sits on the cover's top edge: nothing between them
        Box(Modifier.fillMaxWidth().height(line).background(Ink.line)) {
            Box(Modifier.fillMaxWidth(frac).height(line).background(accent))
        }
    }
}

/**
 * The iPod's clickwheel, with the cover as its hub (ink's): circle a thumb round it to scrub,
 * clockwise forward, a tick every 15 degrees moving the song 5 s; letting go seeks there. It turns
 * only once the thumb is really circling (its own way bending a sixth of a turn, round the centre),
 * so a straight swipe never scrubs, wherever it crosses the cover: a flick sideways is the next song (to the left) or the
 * one before (to the right), as the push goes. A tap is the centre button: play / pause.
 */
private fun Modifier.clickwheel(now: () -> Now, scrub: Scrub): Modifier = composed {
    val view = LocalView.current
    val density = LocalDensity.current
    val hub = with(density) { 18.dp.toPx() }      // too near the centre, the angle jumps
    val flick = with(density) { 56.dp.toPx() }    // how far sideways a flick goes
    val stride = with(density) { 6.dp.toPx() }    // the thumb's way, read every 6 dp (finer is jitter)
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            val t = now()
            val wheel = t.duration > 0
            val centre = Offset(size.width / 2f, size.height / 2f)
            val from = t.positionNow()
            var last: Float? = null
            var turned = 0f
            var engaged: Float? = null   // how far it had turned when it began to scrub
            var ticks = 0
            var travel = 0f
            var at = down.position
            var mark = down.position     // where the thumb's way was last read
            var way: Float? = null
            var bent = 0f                // how far its way has turned (a straight swipe's stays near 0)
            var flipped = false
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
                val a = atan2(v.y, v.x)   // screen y runs down, so clockwise is positive
                last?.let { turned += wrap(a - it) }
                last = a
                // three turns backwards: 1-bit mode, over (an easter egg), once a gesture
                if (!flipped && turned <= -6 * PI.toFloat()) { flipped = true; OneBit.on.value = !OneBit.on.value; view.performHapticFeedback(HapticFeedbackConstants.CONFIRM) }
                // circling: its way bending, round the centre the same way it goes
                if (engaged == null && kotlin.math.abs(bent) >= WHEEL_ENGAGE && bent * turned > 0) engaged = turned
                val base = engaged ?: continue
                val k = ((turned - base) / WHEEL_STEP).toInt()
                if (k != ticks) {
                    ticks = k
                    scrub.at = (from + k * WHEEL_SEEK).coerceIn(0L, t.duration)
                    view.performHapticFeedback(TICK)
                }
            }
            val moved = at - down.position
            when {
                scrub.at != null -> { scrub.at?.let(Playback::seek); scrub.at = null }
                engaged == null && kotlin.math.abs(moved.x) > flick && kotlin.math.abs(moved.x) > kotlin.math.abs(moved.y) * 1.5f -> {
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    if (moved.x < 0) Playback.next() else Playback.previous()
                }
                travel < viewConfiguration.touchSlop -> Playback.toggle()
            }
        }
    }
}

/** An angle's change, the short way round (-π to π). */
private fun wrap(d: Float): Float = when {
    d > PI -> d - (2 * PI).toFloat()
    d < -PI -> d + (2 * PI).toFloat()
    else -> d
}

private val WHEEL_STEP = (PI / 12).toFloat()   // 15 degrees: 24 ticks a turn
private const val WHEEL_SEEK = 5_000L          // so a turn is two minutes
private val WHEEL_ENGAGE = (PI / 3).toFloat()  // a sixth of a turn before it scrubs
private val TICK = if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
else HapticFeedbackConstants.CLOCK_TICK

/** The pixel speaker: a cabinet, its tweeter over its woofer. */
private val SPEAKER = listOf(
    "##########",
    "#........#",
    "#...##...#",
    "#...##...#",
    "#........#",
    "#..####..#",
    "#.#....#.#",
    "#.#.##.#.#",
    "#.#.##.#.#",
    "#.#....#.#",
    "#..####..#",
    "#........#",
    "##########",
)

/** The player's controls as pixels, on the speaker's grid. Play and pause nine high, back and next seven (at 3 dp). */
private val BACK = listOf("#...#", "#..##", "#.###", "#####", "#.###", "#..##", "#...#")
private val PLAY = listOf("#....", "##...", "###..", "####.", "#####", "####.", "###..", "##...", "#....")
private val PAUSE = listOf("##.##", "##.##", "##.##", "##.##", "##.##", "##.##", "##.##", "##.##", "##.##")
private val NEXT = listOf("#...#", "##..#", "###.#", "#####", "###.#", "##..#", "#...#")
/** Shuffle and repeat (at 2 dp): two crossing ways; a loop, a lit pixel in it for repeat one. */
private val SHUFFLE = listOf("........#..", "##....#####", "..#..#..#..", "...##......", "..#..#..#..", "##....#####", "........#..")
private val REPEAT = listOf("..#........", ".##########", "#.#.......#", "#.........#", "#.......#.#", "##########.", "........#..")
private val REPEAT_ONE = listOf("..#........", ".##########", "#.#.......#", "#....#....#", "#.......#.#", "##########.", "........#..")
/** close: the player goes down, as it's pulled. */
private val CLOSE = listOf("##...##", ".##.##.", "..###..", "...#...")

/** Whose page the song's artist opens: the record's artist, else the song's (none for a station). */
private fun artistOf(n: Now): String? = n.song?.takeUnless { it.station }?.let { it.albumArtist.ifBlank { it.artist } }?.takeIf { it.isNotBlank() }

/** Close, wherever there's one: the down chevron (the player's; a word there before). */
@Composable
fun CloseArrow(colour: androidx.compose.ui.graphics.Color = Ink.mid, modifier: Modifier = Modifier, onClick: () -> Unit) =
    Pixel(CLOSE, "close", colour, modifier, onClick = onClick)

/** A pixel control: dimmer while pressed (as a word is), felt, room round it to touch, its name for a screen reader. */
@Composable
internal fun Pixel(
    rows: List<String>, name: String, colour: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier,
    cell: androidx.compose.ui.unit.Dp = 2.dp, inner: Modifier = Modifier, onClick: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val c by androidx.compose.animation.animateColorAsState(if (pressed) Ink.mid else colour, tween(Motion.FADE), label = name)
    Box(
        modifier.semantics { contentDescription = name; role = androidx.compose.ui.semantics.Role.Button }
            .clickable(src, indication = null, onClick = felt(then = onClick)).padding(horizontal = cell * 4, vertical = cell * 6),
    ) { Sprite(rows, c, cell, inner) }
}

/** "play on" as a pixel speaker: lit in ink while a speaker plays, at the row's words' dimness on the phone. */
@Composable
private fun PlayOnSpeaker(casting: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colour by androidx.compose.animation.animateColorAsState(if (casting) Ink.ink else SAID, tween(Motion.ENTER, easing = Motion.expo), label = "speaker")
    Box(modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick)).padding(10.dp)) {
        Sprite(SPEAKER, colour, 2.dp)
    }
}

/** A song's synced lines and where it is in them (-1 before the first; a blank one is a gap). */
data class Lyric(val lines: List<Line>, val index: Int)

/** The song's synced lines while lyrics are on: empty for a song lrclib has none for; null while looking, or off. */
@Composable
fun rememberLines(n: Now?, on: Boolean): List<Line>? {
    val lines by produceState<List<Line>?>(null, n?.artist, n?.title, on) {
        value = null
        // a station's song: live, its place unknown, so no lines to follow
        if (n == null || !on || n.song?.station == true) return@produceState
        while (true) {
            value = try { Lyrics.of(n) } catch (e: java.io.IOException) { null }
            if (value != null) break
            delay(60_000)   // lrclib couldn't be reached: ask again in a minute
        }
    }
    return lines
}

/** Where the song is in its synced lines; null for a song without them (or not yet found), or off. */
@Composable
fun rememberLyric(n: Now?, lines: List<Line>?): Lyric? {
    val track by rememberUpdatedState(n)
    val pos by produceState(n?.positionNow() ?: 0L, n) {
        while (true) {
            value = track?.positionNow() ?: 0L
            if (track?.moving != true) break
            delay(100)
        }
    }
    val all = lines
    if (all.isNullOrEmpty() || n == null) return null
    return Lyric(all, all.indexOfLast { it.at <= pos + Lyrics.LEAD })
}

/**
 * The lines, as on ink's home: the one being sung big, its words lighting dim to ink one after
 * another; the one before small and dim above, the next ones dim below. A new line lifts the
 * block a drift and fades it in (never sideways, nothing scrolls).
 */
@Composable
private fun LyricsBlock(l: Lyric, modifier: Modifier) {
    val drift = with(LocalDensity.current) { Motion.drift.roundToPx() }
    // the lines to come run out of room at the foot: feathered, never cut
    BoxWithConstraints(modifier.clipToBounds().feather({ 0f }, { 1f })) {
        // by the room both ways: tall alone grew the sung line past the width (cut off mid-word)
        val k = minOf(maxHeight / 330.dp, maxWidth / 380.dp).coerceIn(.6f, 1.15f)
        AnimatedContent(
            targetState = l.index,
            transitionSpec = {
                (slideInVertically(tween(Motion.ENTER, easing = Motion.expo)) { drift } + fadeIn(tween(Motion.ENTER, easing = Motion.expo))) togetherWith
                    (slideOutVertically(tween(Motion.EXIT, easing = Motion.expo)) { -drift } + fadeOut(tween(Motion.EXIT, easing = Motion.expo)))
            },
            label = "line",
        ) { i ->
            val lines = l.lines
            val sung = lines.getOrNull(i)?.takeIf { it.words.isNotBlank() }
            val before = (i - 1 downTo 0).asSequence().map { lines[it] }.firstOrNull { it.words.isNotBlank() }?.takeIf { sung != null }
            val after = (i + 1 until lines.size).asSequence().map { lines[it] }.filter { it.words.isNotBlank() }.take(3).toList()
            Column(Modifier.fillMaxWidth()) {
                before?.let {
                    BasicText(it.words.uppercase(), style = headline(20.sp * k, Ink.dim), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(16.dp * k))
                }
                sung?.let {
                    FadeText(it.words.uppercase(), headline(44.sp * k), maxLines = 4, overflow = TextOverflow.Ellipsis, from = Ink.dim)
                    Spacer(Modifier.height(18.dp * k))
                }
                after.forEach {
                    BasicText(it.words.uppercase(), style = headline(24.sp * k, Ink.dim), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(12.dp * k))
                }
            }
        }
    }
}

/**
 * A lossless song's moment (Bronson's pick, 2026-10-26): as it starts, "HI-RES" or "LOSSLESS" types itself
 * across the cover, its depth and rate under it, holds a moment, and goes; the word at the player's top
 * says it after. Once a song, only at its start, never for lossy (the word alone says that).
 */
@Composable
private fun Moment(t: Now, onTyping: (Boolean) -> Unit) {
    // on a speaker the phone isn't decoding: what's sent says it ("flac 24/96 on denon", Bronson's ask)
    val h = if (t.device.isNotEmpty()) sent(t.quality, t.song?.id.orEmpty()) else t.heard
    val key = t.song?.id ?: (t.title + t.artist)
    var big by remember(key) { mutableStateOf("") }
    var small by remember(key) { mutableStateOf("") }
    // 0 while it's typed and held; to 1 as it fades where it is, the dim lifting with it
    val leave = remember(key) { androidx.compose.animation.core.Animatable(1f) }
    androidx.compose.runtime.DisposableEffect(key) { onDispose { onTyping(false) } }
    LaunchedEffect(key, h?.lossless) {
        if (h?.lossless != true || t.positionNow() > 8_000 || !Moments.on.value) return@LaunchedEffect
        val a = if (h.hires) "HI-RES" else "LOSSLESS"
        val b = listOfNotNull(h.bits.takeIf { it > 0 }?.let { "$it bit" }, h.rate.takeIf { it > 0 }?.let { "${Heard.khz(it)} khz" }).joinToString(" · ")
        onTyping(true)
        leave.animateTo(0f, tween(Motion.ENTER, easing = Motion.expo))
        delay(100)
        for (i in 1..a.length) { big = a.take(i); delay(70) }
        for (i in 1..b.length) { small = b.take(i); delay(30) }
        delay(1_400)
        // it fades where it is; once it's gone, the word between the times fades in
        leave.animateTo(1f, tween(SETTLE, easing = SETTLING))
        big = ""; small = ""
        onTyping(false)
    }
    Box(Modifier.fillMaxSize().background(Ink.black.copy(alpha = .55f * (1f - leave.value))),
        contentAlignment = androidx.compose.ui.Alignment.Center) {
        Column(Modifier.graphicsLayer { alpha = 1f - leave.value }, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            BasicText(big, style = headline(40.sp))
            Spacer(Modifier.height(10.dp))
            BasicText(small, style = pixel(14.sp))
        }
    }
}

/** What's sent to a speaker, as its quality line says it ("flac 24/96", "flac 16/44.1", "mp3 320"), as a [Heard]. */
private fun sent(quality: String, id: String): Heard? {
    val m = Regex("^(\\w+)(?:\\s+(?:(\\d+)/)?([\\d.]+))?").find(quality.trim().lowercase()) ?: return null
    val codec = m.groupValues[1]
    val bits = m.groupValues[2].toIntOrNull() ?: 0
    val lossless = codec in setOf("flac", "alac", "wav")
    val rate = if (lossless) m.groupValues[3].toFloatOrNull()?.let { (it * 1000).toInt() } ?: 0 else 0
    return Heard(id, codec, bits, rate, 0, if (lossless) 0 else m.groupValues[3].toIntOrNull() ?: 0, "", "", -1, false, null)
}

/** The moment's fade, and the word's fade in after it: slow, gentle at both ends. */
private const val SETTLE = 1_400
private val SETTLING = androidx.compose.animation.core.CubicBezierEasing(.45f, 0f, .2f, 1f)

/**
 * The signal path (Bronson, 2026-10-30, "an audio pipeline of information as nice text"): what reaches your ears, step by
 * step, from where the song came to where it plays, each as the player read it, a hairline between; then plainly
 * whether it's exact, and if not what touched it. On a speaker, the path ends there: the speaker plays it itself.
 */
@Composable
private fun SignalPath(n: Now, onClose: () -> Unit) {
    val h = n.heard
    val ctx = LocalContext.current
    val k = { hz: Int -> "${Heard.khz(hz)} khz" }
    // what's yours to change sits on its step, a word to tap (Bronson's ask): the path and "exact?" follow at once
    var levelOn by remember { mutableStateOf(Playback.levelling(ctx)) }
    var eqState by remember { mutableStateOf(Eq.read(ctx)) }
    var exactOn by remember { mutableStateOf(Exact.on(ctx)) }
    val acts = HashMap<String, Pair<String, () -> Unit>>()
    val steps = mutableListOf<Pair<String, String>>()
    if (h != null) {
        steps += "from" to (when (h.from) {
            "server" -> if (h.made.isEmpty()) "your server, the file as it is" else "your server, made ${h.made} for you"
            "youtube" -> "youtube music, its ${h.made.ifEmpty { "best" }} stream"
            "station" -> "the station's stream"
            else -> "this phone, the file as it is"
        } + (h.quality.takeIf { it >= 0 }?.let { "\n${Quality.words[it]} on ${if (h.metered) "mobile data" else "wifi"}" } ?: ""))
        steps += (if (h.from == "youtube" || h.from == "station") "the stream" else "the file") to listOfNotNull(
            h.codec, h.bits.takeIf { it > 0 }?.let { "$it bit" }, h.rate.takeIf { it > 0 }?.let(k),
            h.channels.takeIf { it > 0 }?.let { when (it) { 1 -> "mono"; 2 -> "stereo"; else -> "$it channels" } },
            h.kbps.takeIf { it > 0 }?.let { "%,d kbps".format(java.util.Locale.ROOT, it) },
        ).joinToString(" · ") + "\n" + (if (h.hires) "hi-res lossless" else if (h.lossless) "lossless" else "lossy")
    }
    // on a speaker: the receiver as it says of itself, read again every few seconds (a Denon's sound mode changes
    // under you); the song as its source has it (the phone's player isn't decoding it)
    val speaker by Casting.on.collectAsState()
    val said by produceState<Receiver.Said?>(null, speaker) {
        val sp = speaker ?: return@produceState
        while (true) { Receiver.read(sp)?.let { value = it }; kotlinx.coroutines.delay(5_000) }
    }
    if (n.device.isNotEmpty()) {
        val song = n.song
        val from = song?.id?.let { when { it.startsWith("n") -> "server"; it.startsWith("y") -> "youtube"; it.startsWith("s") -> "station"; else -> "phone" } }
        if (h == null && from != null) {
            steps += "from" to when (from) { "server" -> "your server"; "youtube" -> "youtube music"; "station" -> "the station"; else -> "this phone" }
            listOfNotNull(song.format.takeIf { it.isNotBlank() }, song.bits.takeIf { it > 0 }?.let { "$it bit" }, song.rate.takeIf { it > 0 }?.let(k))
                .takeIf { it.isNotEmpty() }?.let { steps += "the file" to it.joinToString(" · ") }
        }
        steps += "sent" to "to ${n.device.lowercase()}" + (n.quality.takeIf { it.isNotBlank() }?.let { ", $it" } ?: "") + when (from) {
            "server" -> "\nstreamed from your server"
            "phone" -> "\nserved by this phone"
            "youtube" -> "\nyoutube's stream, found by this phone"
            else -> ""
        }
        val r = said
        val model = speaker?.model?.takeIf { it.isNotBlank() } ?: n.device
        steps += "the receiver" to model.lowercase() + (r?.takes?.takeIf { it.isNotEmpty() }?.let { "\ntakes ${it.joinToString(", ")}" } ?: "")
        // asked, not answered yet: a step the signal waits at, its answer unfolding in its place
        if (r == null && speaker?.kind == "dlna") steps += "asking" to "asking the ${(speaker?.maker ?: "receiver").lowercase().substringBefore(',')}…"
        if (r?.live == true) {
            r.lines.forEach { steps += it }
        } else steps += "the speaker" to "plays it itself: its own decoder, its own dac"
    } else if (h != null) {
        h.decoder.takeIf { it.isNotEmpty() }?.let { d ->
            val who = when {
                d.startsWith("c2.android.") || d.startsWith("OMX.google.") -> "android's ${h.codec} decoder"
                d.startsWith("c2.sec.") || d.startsWith("OMX.SEC.") -> "samsung's ${h.codec} decoder"
                d.contains("ffmpeg", true) || d.contains("lib", true) -> "ink music's own ${h.codec} decoder"
                else -> d.lowercase()
            }
            steps += "decoded" to who + (h.outBits.takeIf { it > 0 }?.let { ", to ${if (it == 32) "32 bit float" else "$it bit"}" } ?: "")
        }
        steps += "even volume" to (if (exactOn) "resting: exact to a usb dac" else h.gain?.let { "%+.1f db".format(java.util.Locale.ROOT, it) + if (it == 0f) "" else ", so it sits with the rest" } ?: "off")
        if (!exactOn) acts["even volume"] = (if (levelOn) "turn off" else "turn on") to { levelOn = !levelOn; Playback.level(levelOn) }
        steps += "equalizer" to (if (exactOn) "resting: exact to a usb dac" else h.eq.ifEmpty { "off" })
        // the equalizer's word only once there's one set (its own page sets it up)
        if (!exactOn && (eqState.on || eqState.levels.any { it != 0 })) acts["equalizer"] = (if (eqState.on) "turn off" else "turn on") to {
            eqState = eqState.copy(on = !eqState.on); Playback.equalize(ctx, eqState)
        }
        if (h.outRate > 0) steps += "to android" to "${if (h.outBits == 32) "32 bit float" else "${h.outBits} bit"} · ${k(h.outRate)}"
        // exact's word with a usb dac in (or exact on, to turn it off): without one it would only rest the rest
        if (Exact.available && (h.kind == "usb" || exactOn)) acts["android"] = (if (exactOn) "exact: off" else "exact to a dac") to { exactOn = !exactOn; Playback.exact(ctx, exactOn) }
        steps += "android" to when {
            h.exact.startsWith("yes") -> "steps aside: bit-perfect to the dac"
            h.mixer > 0 && h.outRate > 0 && h.mixer != h.outRate -> "mixes at ${k(h.mixer)}: ${k(h.outRate)} resampled"
            h.mixer > 0 -> "mixes at ${k(h.mixer)}, with its volume"
            else -> "mixes it, with its volume"
        }
        if (h.device.isNotEmpty()) steps += "your ears" to h.device.lowercase() + when (h.kind) {
            "bluetooth" -> "\nre-encoded for bluetooth on the way (android picks the codec)"
            "usb" -> " · usb"; "wired" -> " · wired"; else -> ""
        }
    }
    // exact? plainly: yes only when every step left it be, else the first thing that touched it
    val verdict = when {
        n.device.isNotEmpty() && said?.live == true -> said!!.touching.let { t ->
            val who = (speaker?.maker ?: "receiver").lowercase().substringBefore(',')
            // yes: the file as it is (nothing made of it on the way), at its own rate, and the receiver doing nothing to it
            val file = sent(n.quality, "")
            val asIs = file?.lossless == true && n.song?.let { song -> song.format.isBlank() || song.format.equals(file.codec, true) } != false &&
                (file.rate == 0 || said!!.lines.none { it.first == "it's getting" } || said!!.lines.first { it.first == "it's getting" }.second.contains(Heard.khz(file.rate)))
            if (t.isEmpty() && asIs && "direct" in said!!.mode) "yes: the file as it is, the $who's ${said!!.mode}"
            else if (t.isEmpty()) "close: the $who plays it as it comes" + (said!!.mode.takeIf { it.isNotEmpty() }?.let { ", in $it" } ?: "")
            else "no: the ${(speaker?.maker ?: "receiver").lowercase().substringBefore(',')}'s " + (if (t.size > 1) t.dropLast(1).joinToString(", ") + " and " + t.last() else t[0]) + " shape it"
        }
        n.device.isNotEmpty() -> "the phone hands it on; what happens next is the speaker's"
        h == null -> ""
        h.exact.startsWith("yes") && h.bits > 16 -> "close: bit-perfect to your dac, but ${h.bits} bit made 16 on the way"
        h.exact.startsWith("yes") -> "yes: ${h.exact.removePrefix("yes ")} to your dac, untouched"
        else -> {
            // everything that touched it, in the order it did
            val touched = listOfNotNull(
                "even volume moves it".takeIf { h.gain != null && h.gain != 0f },
                "the equalizer shapes it".takeIf { h.eq.isNotEmpty() },
                "android resamples ${Heard.khz(h.outRate)} to ${k(h.mixer)}".takeIf { h.mixer > 0 && h.outRate > 0 && h.mixer != h.outRate },
                "bluetooth re-encodes it".takeIf { h.kind == "bluetooth" },
            )
            when {
                touched.isNotEmpty() -> "no: " + touched.joinToString(", ").let { t -> touched.takeIf { it.size > 1 }?.let { t.replaceAfterLast(", ", "").removeSuffix(", ") + " and " + it.last() } ?: t } +
                    if (!h.lossless) "\nand lossy to begin with" else ""
                !h.lossless -> "as good as its stream: lossy to begin with"
                else -> "close: the same rate, but android's mixer and volume still touch it"
            }
        }
    } + (h?.exact?.takeIf { it.isNotEmpty() && !it.startsWith("yes") }?.let { "\nexact to a usb dac: $it" } ?: "")
    Column(
        Modifier.fillMaxSize().background(Ink.black).windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
            .navigationBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp)
            .verticalScroll(androidx.compose.foundation.rememberScrollState()),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            CloseArrow(SAID, onClick = onClose)
        }
        Spacer(Modifier.height(28.dp))
        BasicText("WHAT YOU HEAR", style = headline(44.sp))
        Spacer(Modifier.height(8.dp))
        BasicText(h?.word ?: n.quality, style = pixel(16.sp, if (h?.lossless == true) Ink.ink else SAID))
        Spacer(Modifier.height(28.dp))
        // the signal travelling down it, once as it opens (Bronson's ask): each step lit in turn from dim, the hairline
        // to the next filling with ink as the progress line does; then "exact?". Moving less, all of it at once
        val less by Moves.less.collectAsState()
        val per = 160
        val travel = remember { Animatable(if (less) steps.size.toFloat() else 0f) }
        // on: a receiver's own steps arrive a moment after it opens, and the signal carries on down to them
        LaunchedEffect(steps.size) {
            if (less) travel.snapTo(steps.size.toFloat())
            else travel.animateTo(steps.size.toFloat(), tween((per * (steps.size - travel.value)).toInt().coerceAtLeast(per * 2), easing = androidx.compose.animation.core.FastOutSlowInEasing))
        }
        // the steps unfold (a receiver's answer arriving makes the page taller: eased, not jumped)
        Column(Modifier.animateContentSize(tween(Motion.ENTER * 2, easing = Motion.expo))) {
            steps.forEachIndexed { i, (label, value) ->
                androidx.compose.runtime.key(label) {
                    // brightened on a soft curve as the signal reaches it, never a hard edge
                    val reached = (travel.value - i).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
                    // a step new to the page fades up as it unfolds
                    val appear = remember { Animatable(0f) }
                    LaunchedEffect(Unit) { appear.animateTo(1f, tween(Motion.ENTER * 2, easing = Motion.expo)) }
                    Column(Modifier.graphicsLayer { alpha = appear.value; translationY = (1f - appear.value) * 6.dp.toPx() }) {
                        // a hairline from one step to the next, under the labels, filling as the signal passes down it
                        if (i > 0) Box(Modifier.padding(start = 3.dp).width(1.dp).height(14.dp).background(Ink.line)) {
                            Box(Modifier.fillMaxWidth().fillMaxHeight(((travel.value - i + 1) * 2).coerceIn(0f, 1f)).background(Ink.ink))
                        }
                        val lit = Ink.ink.copy(alpha = (if (label == "asking") .35f else .35f + .65f * reached))
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            BasicText(if (label == "asking") "" else label, style = pixel(12.sp, Ink.mid.copy(alpha = Ink.mid.alpha * (.5f + .5f * reached))), modifier = Modifier.width(120.dp).padding(top = 2.dp))
                            Column(Modifier.weight(1f)) {
                                // a value that changes (the volume, read again) crossfades
                                androidx.compose.animation.Crossfade(value, animationSpec = tween(Motion.ENTER * 2), label = "value") { v ->
                                    BasicText(v, style = pixel(14.sp, lit).copy(lineHeight = 20.sp))
                                }
                                acts[label]?.let { (word, act) -> Word(word, pixel(12.sp, Ink.mid), Modifier.padding(top = 2.dp), touch = 8.dp, onClick = act) }
                            }
                        }
                    }
                }
            }
            val arrived = (travel.value - steps.size + 1).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
            if (verdict.isNotEmpty() && steps.none { it.first == "asking" }) Column(Modifier.graphicsLayer { alpha = arrived }) {
                Spacer(Modifier.height(28.dp))
                BasicText("EXACT?", style = headline(24.sp))
                Spacer(Modifier.height(6.dp))
                androidx.compose.animation.Crossfade(verdict, animationSpec = tween(Motion.ENTER * 2), label = "verdict") { v ->
                    BasicText(v, style = pixel(14.sp).copy(lineHeight = 20.sp))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * What's heard, between the song's times on the cover's top edge ("flac 24/96", lit when lossless; at 75%
 * when lossy); a tap tells all of it. After a lossless song's moment it fades in slowly (Bronson's ask,
 * 2026-10-26: once the moment has faded, as slowly). On a speaker, what's sent there.
 */
@Composable
private fun HeardWord(n: Now, typing: Boolean, onOpen: () -> Unit) {
    // on a speaker, what's sent ("flac 24/96"): up here too, a tap to its path (Bronson: easier than the line under the title)
    val h = if (n.device.isNotEmpty()) sent(n.quality, n.song?.id.orEmpty()) else n.heard
    var last by remember { mutableStateOf(h) }
    if (h != null) last = h
    androidx.compose.animation.AnimatedVisibility(h != null && !typing,
        enter = fadeIn(tween(SETTLE, easing = SETTLING)), exit = fadeOut(tween(Motion.EXIT, easing = Motion.expo))) {
        last?.let { Word(it.word, pixel(SMALL, if (it.lossless) Ink.ink else SAID), onClick = onOpen) }
    }
}

/** The hi-res moment, on unless turned off (settings); the word between the times shows either way. */
object Moments {
    private val _on = kotlinx.coroutines.flow.MutableStateFlow(true)
    val on: kotlinx.coroutines.flow.StateFlow<Boolean> = _on
    private fun prefs(c: android.content.Context) = c.getSharedPreferences("player", android.content.Context.MODE_PRIVATE)
    fun start(c: android.content.Context) { _on.value = prefs(c).getBoolean("moment", true) }
    fun set(c: android.content.Context, on: Boolean) { _on.value = on; prefs(c).edit().putBoolean("moment", on).apply() }
}

/**
 * The player in small, for settings, the look: what plays now (else a word to play something), drawn by the player's
 * own pieces (its backdrop, canvas, cover, moving cover) at a phone's full size and scaled down, so it's what the
 * player shows; each word chosen there shows here at once. Centred under the page's title.
 */
@Composable
fun PlayerPreview(which: Int, modifier: Modifier = Modifier) {
    val now by Playback.now.collectAsState()
    // one of the example songs (Showcase); till it's found, and in the play build (no YouTube), what plays now
    val pick = Showcase.picks[which.coerceIn(0, Showcase.picks.size - 1)]
    val shown by produceState(Showcase.found[pick.title], pick) {
        if (value == null && YouTubeMusic.available)
            value = withContext(Dispatchers.IO) { runCatching { YouTubeMusic.find(pick.title, pick.artist) }.getOrNull() }?.also { Showcase.found[pick.title] = it }
    }
    // its moving covers and canvas keep moving here though the music's paused: a preview shows what they do
    val n = shown?.let { s -> Now(s, s.title, s.artist, s.album.ifBlank { pick.album }, s.duration, 0, 0, playing = true, moving = true, index = 0, count = 1) }
        ?: now?.copy(moving = true)
    BoxWithConstraints(modifier, contentAlignment = Alignment.TopCenter) {
        val full = LocalContext.current.resources.displayMetrics.let { (it.widthPixels / it.density).dp to (it.heightPixels / it.density).dp }
        // only its top shown: the cover and the title (under them are the player's own controls, the same whatever's chosen)
        val top = full.second * .8f
        val k = minOf(maxWidth / full.first, maxHeight / top, 1f)
        val d = LocalDensity.current
        Box(Modifier.size(full.first * k, top * k).clipToBounds().border(1.dp, Ink.line)) {
            if (n == null) BasicText("play something, and see it here", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(12.dp))
            // drawn small from the start, a smaller density, not a full one scaled down: a moving cover's shader
            // inside a scaled layer stopped moving (and its dots and squares come out in proportion)
            else androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides androidx.compose.ui.unit.Density(d.density * k, d.fontScale)) {
                Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(full.first, full.second)) {
                    val cover = rememberCover(n.song, LocalContext.current.resources.displayMetrics.widthPixels.coerceIn(640, 1440))
                    Box(Modifier.fillMaxSize().background(Ink.black)) { Backdrop(cover, n) }
                    Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 110.dp)) {
                        val side = minOf(full.first - 40.dp, 420.dp)
                        Sleeve(n, side, Modifier)
                        Spacer(Modifier.height(20.dp))
                        BasicText(n.title.uppercase(), style = headline(32.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                        BasicText(n.artist.lowercase(), style = pixel(SMALL, SAID), maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * The look's example songs: Fontaines D.C.'s Starburster (Bronson's pick, 2026-10-30: Romance's moving heart, a canvas
 * behind it), and Tame Impala's The Less I Know the Better (Currents' moving cover, and no canvas: the still look
 * behind it shows, which a canvas covers; "behind it" changed nothing in sight with Starburster alone). The record named
 * too: YouTube's search can find a song without it, and the moving cover is the record's.
 */
object Showcase {
    class Pick(val title: String, val artist: String, val album: String, val said: String)
    val picks = listOf(
        Pick("Starburster", "Fontaines D.C.", "Romance", "fontaines d.c., starburster: a moving cover, a canvas behind it"),
        Pick("The Less I Know the Better", "Tame Impala", "Currents", "tame impala, the less i know the better: no canvas, so the look behind it shows"),
    )
    val found = java.util.concurrent.ConcurrentHashMap<String, Song>()
}
