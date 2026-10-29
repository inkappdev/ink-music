package app.ink.music

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import kotlinx.coroutines.delay

/** The window, for the player's whole screen. */
object Win {
    var state: WindowState? = null
    val full get() = state?.placement == WindowPlacement.Fullscreen
    fun full(on: Boolean) { state?.placement = if (on) WindowPlacement.Fullscreen else WindowPlacement.Floating }
}

/**
 * The player, the whole window (the phone's, laid sideways): the cover as big as the window lets it, over a print
 * of itself in the dither set a little down and right; the line along the cover's top edge with the times over it
 * and what's heard between them (a lossless song's moment typed across the cover as it starts); the song beside it,
 * the pixel controls, up next, lyrics, stop, the volume, the lyrics as they're sung; the artist as the giant marquee
 * along the foot. Full screen from the corners at the top.
 */
@Composable
fun PlayerPage(open: (Page) -> Unit, onClose: () -> Unit) {
    val now by Player.now.collectAsState()
    val n = now ?: return Close(onClose)
    val cover = rememberCover(n.song, 1200)
    val accent by animateColorAsState(cover?.accent ?: Ink.ink, tween(Motion.ENTER), label = "accent")
    val heard by Player.heard.collectAsState()
    val on by Casting.on.collectAsState()
    // a lossless song's moment across the cover, while it types (the word between the times waits for it)
    var typing by remember { mutableStateOf(false) }
    var heardUp by remember { mutableStateOf(false) }
    val artist = n.song.takeUnless { it.station }?.let { it.albumArtist.ifBlank { it.artist.substringBefore(", ") } }
    Box(Modifier.fillMaxSize().background(Ink.black).clickable(remember { MutableInteractionSource() }, indication = null) {}) {
        // the artist's name, big, along the foot: a way to their page too
        Marquee(said(n).second, n.playing, Modifier.align(Alignment.BottomStart).fillMaxWidth()
            .clickable(remember { MutableInteractionSource() }, indication = null) { artist?.let { open(Page.Artist(it)) } }, size = 150.sp)
        Column(Modifier.fillMaxSize()) {
            // close; where in the queue, where it plays (the pixel speaker, lit on one), the whole screen
            Row(Modifier.fillMaxWidth().padding(start = SIDE - 8.dp, end = SIDE - 8.dp, top = TOP), verticalAlignment = Alignment.CenterVertically) {
                CloseArrow(onClick = onClose)
                Spacer(Modifier.weight(1f))
                Word(if (n.song.station) "queue" else "${n.index + 1} of ${Player.queue.collectAsState().value.size}", pixel(12.sp, Ink.mid), touch = 10.dp) { open(Page.Queue) }
                Spacer(Modifier.width(12.dp))
                Pixel(SPEAKER, if (on != null) "playing on ${on!!.name}" else "play on", if (on != null) Ink.ink else Ink.mid) { open(Page.PlayOn) }
                Pixel(if (Win.full) UNFULL else FULL, if (Win.full) "leave full screen" else "full screen", Ink.mid) { Win.full(!Win.full) }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(start = SIDE, end = SIDE, top = 8.dp, bottom = 190.dp)) {
                // the print's offset, a twentieth of the cover; the cover as big as that leaves room for
                val side: Dp = minOf((maxHeight - 17.dp - 18.dp) / 1.05f, maxWidth * .5f).coerceAtLeast(120.dp)
                val off = side * .05f
                Row {
                    Column(Modifier.width(side + off)) {
                        Scrubber(n, accent, Modifier.width(side)) {
                            if (on == null) HeardWord(heard?.takeIf { it.id == n.song.id }, typing) { heardUp = true }
                        }
                        Box(Modifier.size(side + off)) {
                            if (!n.song.station) Dithered(n.song, Modifier.offset(off, off).size(side), px = 1200)
                            Box(Modifier.size(side).clickable(remember { MutableInteractionSource() }, indication = null) { Player.toggle() }) {
                                CoverOf(n.song, n.song.album.ifEmpty { n.song.title }, Modifier.fillMaxSize(), px = 1200, moving = n.playing)
                                Moment(n, heard?.takeIf { it.id == n.song.id }) { typing = it }
                            }
                        }
                    }
                    Spacer(Modifier.width(48.dp))
                    Column(Modifier.weight(1f).padding(top = 17.dp)) {
                        val (title, by) = said(n)
                        AnimatedContent(
                            n.song.id + title,
                            transitionSpec = {
                                (slideInVertically(tween(Motion.ENTER, easing = Motion.expo)) { it / 4 } + fadeIn(tween(Motion.ENTER, easing = Motion.expo))) togetherWith
                                    (slideOutVertically(tween(Motion.EXIT, easing = Motion.expo)) { -it / 4 } + fadeOut(tween(Motion.EXIT, easing = Motion.expo)))
                            },
                            label = "song",
                        ) { _ ->
                            Column {
                                BasicText(title.uppercase(), style = headline(44.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(12.dp))
                                if (!n.song.station) Word(by.lowercase(), pixel(14.sp)) { artist?.let { open(Page.Artist(it)) } }
                                if (n.song.album.isNotEmpty()) Word(n.song.album.lowercase(), pixel(14.sp, Ink.mid)) { open(Page.Record(n.song.albumId)) }
                                Spacer(Modifier.height(4.dp))
                                BasicText(listOfNotNull(n.song.year.takeIf { it > 0 }?.toString(), on?.let { "on ${it.name.lowercase()}" }).joinToString("  ·  "),
                                    style = pixel(12.sp, Ink.mid))
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                        Controls(n, Modifier.width(320.dp))
                        Spacer(Modifier.height(10.dp))
                        // the words everyone reads, stop last (it ends everything and closes the player)
                        val sing by Lyrics.on.collectAsState()
                        val lines = rememberLyrics(n.song)
                        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                            Word("up next", pixel(14.sp, Ink.mid), touch = 10.dp) { open(Page.Queue) }
                            Word("lyrics", pixel(14.sp, if (sing) Ink.ink else Ink.dim), touch = 10.dp) { Lyrics.toggle() }
                            Word("stop", pixel(14.sp, if (n.playing) Ink.mid else Ink.dim), touch = 10.dp) { Player.stop(); onClose() }
                        }
                        Spacer(Modifier.height(10.dp))
                        Volume(line = 180.dp)
                        // the song's words, the sung line lit, as it plays
                        val pos by Player.position.collectAsState()
                        lines?.let { all ->
                            Spacer(Modifier.height(24.dp))
                            LyricsBlock(all, all.indexOfLast { it.at <= pos + Lyrics.LEAD }, Modifier.weight(1f).fillMaxWidth())
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            heardUp && heard != null,
            enter = slideInVertically(tween(Motion.ENTER, easing = Motion.expo)) { (it * .06f).toInt() } + fadeIn(tween(Motion.ENTER, easing = Motion.expo)),
            exit = slideOutVertically(tween(Motion.EXIT, easing = Motion.expo)) { (it * .04f).toInt() } + fadeOut(tween(Motion.EXIT, easing = Motion.expo)),
        ) { heard?.let { HeardPage(it) { heardUp = false } } }
    }
}

/**
 * The times, then the 1 px line filling with the song in the cover's colour, ruled straight onto the cover's top
 * edge, what's heard dead centre between the times. Click along the line, or drag, to go there: the times light
 * while the mouse is down, the song jumps there on letting go.
 */
@Composable
fun Scrubber(n: Player.Now, accent: Color, modifier: Modifier = Modifier, middle: @Composable () -> Unit = {}) {
    val pos by Player.position.collectAsState()
    var at by remember { mutableStateOf<Long?>(null) }
    val live = !n.song.station && n.duration > 0
    val shown = at ?: pos
    val times by animateColorAsState(if (at != null) Ink.ink else Ink.mid, tween(Motion.FADE), label = "times")
    fun where(x: Float, width: Int) = ((x / width).coerceIn(0f, 1f) * n.duration).toLong()
    Column(modifier) {
        Box(Modifier.fillMaxWidth()) {
            BasicText(if (n.song.station) "live" else clock(shown), style = pixel(12.sp, times), modifier = Modifier.align(Alignment.CenterStart))
            Box(Modifier.align(Alignment.Center)) { middle() }
            if (live) BasicText("-" + clock((n.duration - shown).coerceAtLeast(0)), style = pixel(12.sp, times), modifier = Modifier.align(Alignment.CenterEnd))
        }
        val frac = if (live) (shown.toFloat() / n.duration).coerceIn(0f, 1f) else 0f
        // a line too thin to aim at: its click reaches up 16 dp over it
        Box(
            Modifier.fillMaxWidth().height(17.dp)
                .pointerInput(n.duration, n.song.id) { detectTapGestures { o -> if (live) Player.seek(where(o.x, size.width)) } }
                .pointerInput(n.duration, n.song.id) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> if (live) at = where(o.x, size.width) },
                        onDragEnd = { at?.let(Player::seek); at = null },
                        onDragCancel = { at = null },
                    ) { c, _ -> c.consume(); if (live) at = where(c.position.x, size.width) }
                },
            contentAlignment = Alignment.BottomStart,
        ) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line)) {
                Box(Modifier.fillMaxWidth(frac).height(1.dp).background(accent))
            }
        }
    }
}

/**
 * What's heard, between the song's times ("flac 24/96", lit when lossless; at 70% when lossy); a click tells all of
 * it. After a lossless song's moment it fades in slowly. Not on a speaker.
 */
@Composable
fun HeardWord(h: Heard?, typing: Boolean, onOpen: () -> Unit) {
    var last by remember { mutableStateOf(h) }
    if (h != null) last = h
    AnimatedVisibility(h != null && !typing, enter = fadeIn(tween(SETTLE, easing = SETTLING)), exit = fadeOut(tween(Motion.EXIT, easing = Motion.expo))) {
        last?.let { Word(it.word, pixel(12.sp, if (it.lossless) Ink.ink else Ink.mid), onClick = onOpen) }
    }
}

/**
 * A lossless song's moment (the phone's): as it starts, "HI-RES" or "LOSSLESS" types itself across the cover, its
 * depth and rate under it, holds a moment, and goes; the word between the times says it after. Once a song, only at
 * its start, never for lossy (the word alone says that).
 */
@Composable
private fun Moment(n: Player.Now, h: Heard?, onTyping: (Boolean) -> Unit) {
    val key = n.song.id
    var big by remember(key) { mutableStateOf("") }
    var small by remember(key) { mutableStateOf("") }
    // 0 while it's typed and held; to 1 as it fades where it is, the dim lifting with it
    val leave = remember(key) { Animatable(1f) }
    DisposableEffect(key) { onDispose { onTyping(false) } }
    LaunchedEffect(key, h?.lossless) {
        if (h?.lossless != true || Player.position.value > 8_000) return@LaunchedEffect
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
    Box(Modifier.fillMaxSize().background(Ink.black.copy(alpha = .55f * (1f - leave.value))), contentAlignment = Alignment.Center) {
        Column(Modifier.graphicsLayer { alpha = 1f - leave.value }, horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(big, style = headline(56.sp))
            Spacer(Modifier.height(12.dp))
            BasicText(small, style = pixel(16.sp))
        }
    }
}

/** The moment's fade, and the word's fade in after it: slow, gentle at both ends. */
private const val SETTLE = 1_400
private val SETTLING = CubicBezierEasing(.45f, 0f, .2f, 1f)

/** All of what reaches your ears: the stream's format, where it came from, the volume and even volume. */
@Composable
private fun HeardPage(h: Heard, onClose: () -> Unit) {
    val volume by Player.volume.collectAsState()
    val lines = listOfNotNull(
        "format" to (h.codec + if (h.hires) ", hi-res lossless" else if (h.lossless) ", lossless" else ", lossy"),
        h.bits.takeIf { it > 0 }?.let { "bit depth" to "$it bit" },
        h.rate.takeIf { it > 0 }?.let { "sample rate" to "${Heard.khz(it)} khz" },
        h.channels.takeIf { it > 0 }?.let { "channels" to when (it) { 1 -> "mono"; 2 -> "stereo"; else -> "$it" } },
        h.kbps.takeIf { it > 0 }?.let { "bitrate" to "$it kbps" },
        "from" to when (h.from) {
            "server" -> "your server, the file as it is"
            "youtube" -> "youtube music, its best stream"
            "station" -> "the station's stream"
            else -> "this ${if (Home.mac) "mac" else "computer"}, the file as it is"
        },
        "even volume" to (h.gain?.let { "%+.1f db".format(java.util.Locale.ROOT, it) } ?: "none in the file"),
        "volume" to "$volume",
    )
    Column(Modifier.fillMaxSize().background(Ink.black).clickable(remember { MutableInteractionSource() }, indication = null) {}) {
        Close(onClose)
        Column(Modifier.padding(start = SIDE, end = SIDE, top = 16.dp)) {
            BasicText("WHAT YOU HEAR", style = headline(64.sp))
            Spacer(Modifier.height(8.dp))
            BasicText(h.word, style = pixel(16.sp, if (h.lossless) Ink.ink else Ink.mid))
            Spacer(Modifier.height(32.dp))
            lines.forEach { (label, value) ->
                Row(Modifier.padding(vertical = 7.dp)) {
                    BasicText(label, style = pixel(12.sp, Ink.mid), modifier = Modifier.width(160.dp).padding(top = 2.dp))
                    BasicText(value, style = pixel(14.sp))
                }
            }
        }
    }
}
