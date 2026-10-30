package app.ink.music

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import kotlinx.coroutines.delay

/** The window, for the player's whole screen. */
object Win {
    var state: WindowState? = null
    /** The window's size and place before full screen, put back after (the Mac sometimes left it its smallest). */
    var before: DpSize? = null
    var beforeAt: WindowPosition? = null
    private var was: WindowPlacement = WindowPlacement.Floating
    val full get() = state?.placement == WindowPlacement.Fullscreen

    fun full(on: Boolean) {
        val s = state ?: return
        if (on == full) return   // closing the player when it wasn't full screen: the window left as it is (zoomed stays zoomed)
        if (on) {
            was = s.placement
            if (s.placement == WindowPlacement.Floating) { before = s.size; beforeAt = s.position }
            s.placement = WindowPlacement.Fullscreen
        } else {
            s.placement = was
            // once the Mac's done leaving full screen, the size it had (it can come back at its smallest)
            if (was == WindowPlacement.Floating) javax.swing.Timer(700) {
                before?.takeIf { it.width >= Placed.LEAST.width }?.let { s.size = it }
                beforeAt?.takeIf { it.isSpecified }?.let { s.position = it }
            }.apply { isRepeats = false; start() }
        }
    }
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
    val behind by Looks.on.collectAsState()
    val coverLook by Moving.cover.collectAsState()
    val names by Marquees.on.collectAsState()
    Box(Modifier.fillMaxSize().background(Ink.black).clickable(remember { MutableInteractionSource() }, indication = null) {}) {
        // the background (settings, the look): the cover across the whole window in its effect
        if (!n.song.station && behind != Look.PLAIN) Backdrop(cover, behind)
        // the artist's name, big, along the foot: a way to their page too
        Marquee(said(n).second, n.playing && names, Modifier.align(Alignment.BottomStart).fillMaxWidth()
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
                            // its print beside it, while there's no background (the background takes its place)
                            if (!n.song.station && behind == Look.PLAIN) Dithered(n.song, Modifier.offset(off, off).size(side), px = 1200)
                            Box(Modifier.size(side).clickable(remember { MutableInteractionSource() }, indication = null) { Player.toggle() }) {
                                val density = androidx.compose.ui.platform.LocalDensity.current.density
                                CoverOf(n.song, n.song.album.ifEmpty { n.song.title }, Modifier.fillMaxSize().graphicsLayer {
                                    renderEffect = coverLook?.takeIf { !n.song.station }?.let { Effects.of(it, accent, density, 1f, size.width) }
                                }, px = 1200, moving = n.playing)
                                // the record's moving cover over it, where it has one (settings, the look)
                                if (!n.song.station) MovingCover(n.song.albumArtist.ifBlank { n.song.artist.substringBefore(", ") }, n.song.album, n.song.title,
                                    accent, n.playing, Modifier.fillMaxSize())
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
                                Row(verticalAlignment = Alignment.Top) {
                                    BasicText(title.uppercase(), style = headline(44.sp), maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    Heart(n.song, 3.dp, Modifier.padding(start = 8.dp))
                                }
                                Spacer(Modifier.height(12.dp))
                                if (!n.song.station) Word(by.lowercase(), pixel(14.sp)) { artist?.let { open(Page.Artist(it)) } }
                                if (n.song.album.isNotEmpty()) Word(n.song.album.lowercase(), pixel(14.sp, Ink.mid)) { open(Page.Record(n.song.albumId)) }
                                Spacer(Modifier.height(4.dp))
                                BasicText(listOfNotNull(n.song.year.takeIf { it > 0 }?.toString(), on?.let { "on ${it.name.lowercase()}" }).joinToString("  ·  "),
                                    style = pixel(12.sp, Ink.mid))
                                // an easter egg's word, when one's due (on repeat, a 100th time, a promise kept)
                                EggsNow.line(n.song)?.let { BasicText(it, style = pixel(12.sp, Ink.ink), modifier = Modifier.padding(top = 6.dp)) }
                            }
                        }
                        Spacer(Modifier.height(22.dp))
                        // the controls, the volume beside them
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Controls(n, Modifier.width(320.dp))
                            Spacer(Modifier.width(32.dp))
                            Volume(line = 140.dp)
                        }
                        Spacer(Modifier.height(18.dp))
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line))
                        Spacer(Modifier.height(14.dp))
                        // the inner sleeve (Bronson, 2026-10-29: "a really killer design" for the room beside the cover):
                        // the record, its lyrics, up next, its notes, chosen by a word; stop at the row's end
                        val lines = rememberLyrics(n.song)
                        var shows by remember(n.song.albumId) { mutableStateOf<String?>(null) }
                        val panel = shows ?: if (!lines.isNullOrEmpty()) "lyrics" else if (n.song.station) "up next" else "record"
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            for (w in listOfNotNull("record".takeUnless { n.song.station }, "lyrics", "up next", "notes"))
                                Word(w, pixel(14.sp, if (w == panel) Ink.ink else Ink.dim), Modifier.padding(end = 26.dp), touch = 10.dp) { shows = w }
                            Spacer(Modifier.weight(1f))
                            Word("stop", pixel(14.sp, if (n.playing) Ink.mid else Ink.dim), touch = 10.dp) { Player.stop(); onClose() }
                        }
                        Spacer(Modifier.height(14.dp))
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when (panel) {
                                "record" -> RecordSide(n, open)
                                "lyrics" -> {
                                    val pos by Player.position.collectAsState()
                                    val all = lines
                                    if (all.isNullOrEmpty()) BasicText(lyricsSaid(all), style = pixel(13.sp, Ink.mid))
                                    else LyricsBlock(all, all.indexOfLast { it.at <= pos + Lyrics.LEAD }, Modifier.fillMaxSize(), big = true)
                                }
                                "up next" -> Column {
                                    RadioWord()
                                    QueueList(open, rememberLazyListState(), PaddingValues(bottom = 16.dp), Modifier.fillMaxSize())
                                }
                                else -> Notes(n, heard?.takeIf { it.id == n.song.id }, on?.name)
                            }
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

/** The cover across the whole window behind the player, in the look's effect, faded in at the look's own strength. */
@Composable
fun Backdrop(cover: Cover?, look: Look) {
    val c = cover ?: return
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val fade = remember(c.image) { Animatable(0f) }
    LaunchedEffect(c.image, look) { fade.animateTo(look.alpha, tween(600)) }
    androidx.compose.foundation.Image(c.image, null, Modifier.fillMaxSize().graphicsLayer {
        alpha = fade.value
        renderEffect = Effects.of(look, c.accent, density, 1f, size.width, big = true)
    }, contentScale = androidx.compose.ui.layout.ContentScale.Crop)
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
    val muted by Player.muted.collectAsState()
    val sound by Player.sound.collectAsState()
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
        "even volume" to (if (!sound.even) "off, as recorded" else h.gain?.let { "%+.1f db".format(java.util.Locale.ROOT, it) } ?: "none in the file"),
        "volume" to "$volume",
        // whether the file's samples reach the output unchanged, and what's in the way if not
        "exact" to when {
            !h.lossless -> "no: a lossy file is never the recording exactly"
            muted || volume < 100 -> "no: volume at ${if (muted) "mute" else volume}"
            sound.even && h.gain != null -> "no: even volume"
            !sound.exclusive -> "no: shared, mixed at the ${if (Home.mac) "mac" else "computer"}'s rate (settings, sound: alone)"
            else -> "yes: the file's samples, at its own rate"
        },
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

/**
 * The record, on the inner sleeve: its songs in order, numbered, this one lit (and marked), each its length; a click
 * plays the record from there. A song whose record isn't here says so, and offers its page.
 */
@Composable
private fun RecordSide(n: Player.Now, open: (Page) -> Unit) {
    val albums by Library.albums.collectAsState()
    val queue by Player.queue.collectAsState()
    val record = remember(albums, n.song.albumId) { albums.firstOrNull { it.id == n.song.albumId }?.songs }
        // a YouTube record played whole: its songs as the queue has them
        ?: queue.filter { it.albumId == n.song.albumId && it.album.isNotEmpty() }.takeIf { it.size > 1 }
    if (record == null) {
        Column {
            BasicText("just this song from ${n.song.album.ifEmpty { "its record" }.lowercase()} is here", style = pixel(13.sp, Ink.mid))
            if (n.song.album.isNotEmpty()) Word("its record", pixel(14.sp), Modifier.padding(top = 10.dp), touch = 8.dp) { open(Page.Record(n.song.albumId)) }
        }
        return
    }
    val list = rememberLazyListState()
    val at = record.indexOfFirst { it.id == n.song.id }
    LaunchedEffect(n.song.id) { if (at > 3) list.animateScrollToItem(at - 3) }
    LazyColumn(Modifier.fillMaxSize().feather(list), list, contentPadding = PaddingValues(bottom = 16.dp)) {
        itemsIndexed(record, key = { _, s -> s.id }) { i, s ->
            val on = s.id == n.song.id
            Row(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { Player.play(record, i) })
                .padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText((if (s.track > 0) s.track else i + 1).toString().padStart(2, '0'), style = headline(18.sp, if (on) Ink.ink else Ink.dim), modifier = Modifier.width(44.dp))
                BasicText(s.title.uppercase(), style = headline(20.sp, if (on) Ink.ink else Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (s.duration > 0) BasicText(clock(s.duration), style = pixel(12.sp, if (on) Ink.ink else Ink.dim), modifier = Modifier.padding(start = 16.dp))
            }
        }
    }
}

/**
 * The song's notes, on the inner sleeve, as a record's liner notes: what it is (format, its moment), its record and
 * year, where it plays, and you with it (how many times, first and last heard, when you loved it).
 */
@Composable
private fun Notes(n: Player.Now, heard: Heard?, speaker: String?) {
    val listened by Listened.now.collectAsState()
    val s = n.song
    val plays = listened.most(s.id)?.toInt() ?: 0
    val history = remember(s.id) { History.read().filter { it.id == s.id || (it.title.equals(s.title, true) && it.artist.equals(s.artist, true)) } }
    val day = java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.ENGLISH)
    val rows = listOfNotNull(
        "what it is" to listOfNotNull(heard?.word ?: s.quality.ifEmpty { if (s.tube) "youtube music" else if (s.station) "radio" else null },
            heard?.from?.let { when (it) { "computer" -> "from this computer"; "server" -> "from your server"; "youtube" -> "from youtube music"; else -> "on the air" } }, heard?.let { if (it.hires) "hi-res" else if (it.lossless) "lossless" else null }).joinToString(" · ").ifEmpty { "a song" },
        s.album.takeIf { it.isNotEmpty() }?.let { "from" to (it + (s.year.takeIf { y -> y > 0 }?.let { y -> ", $y" } ?: "")) },
        speaker?.let { "playing on" to it },
        "you've played it" to when (plays) { 0 -> "not yet, till this one's done"; 1 -> "once"; else -> "$plays times" },
        history.firstOrNull()?.let { "first heard" to day.format(java.util.Date(it.at)) },
        history.lastOrNull()?.takeIf { history.size > 1 }?.let { "last heard" to day.format(java.util.Date(it.at)) },
        Loved.lovedAt(s)?.let { "loved" to day.format(java.util.Date(it)) },
    )
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        rows.forEach { (k, v) ->
            Column {
                BasicText(k, style = pixel(12.sp, Ink.dim))
                BasicText(v.uppercase(), style = headline(20.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * The phone's playing, the whole window (Bronson, 2026-10-29): as this computer's own player looks (the cover over its
 * dither print, the line and the times, the artist as the giant marquee), where it plays said ("playing on <phone> ·
 * on <speaker>"); back, play, next steering it there; continue here, big, taking it over; its lyrics as it sings them
 * there, or its notes.
 */
@Composable
fun RemotePlayerPage(o: Continue.Offer, open: (Page) -> Unit, onClose: () -> Unit) {
    val cover = rememberCover(o.song, 1200)
    val accent by animateColorAsState(cover?.accent ?: Ink.ink, tween(Motion.ENTER), label = "accent")
    val pos by androidx.compose.runtime.produceState(o.positionNow(), o) { while (true) { value = o.positionNow(); kotlinx.coroutines.delay(250) } }
    val artist = o.song.takeUnless { it.station }?.let { it.albumArtist.ifBlank { it.artist.substringBefore(", ") } }
    Box(Modifier.fillMaxSize().background(Ink.black).clickable(remember { MutableInteractionSource() }, indication = null) {}) {
        Marquee(o.song.artist, o.playing && Marquees.on.collectAsState().value, Modifier.align(Alignment.BottomStart).fillMaxWidth()
            .clickable(remember { MutableInteractionSource() }, indication = null) { artist?.let { open(Page.Artist(it)) } }, size = 150.sp)
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = SIDE - 8.dp, end = SIDE - 8.dp, top = TOP), verticalAlignment = Alignment.CenterVertically) {
                CloseArrow(onClick = onClose)
                Spacer(Modifier.weight(1f))
                Pixel(if (Win.full) UNFULL else FULL, if (Win.full) "leave full screen" else "full screen", Ink.mid) { Win.full(!Win.full) }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(start = SIDE, end = SIDE, top = 8.dp, bottom = 190.dp)) {
                val side: Dp = minOf((maxHeight - 17.dp - 18.dp) / 1.05f, maxWidth * .5f).coerceAtLeast(120.dp)
                val off = side * .05f
                Row {
                    Column(Modifier.width(side + off)) {
                        // the times, the line in the cover's colour, filling as it plays there
                        val length = o.song.duration
                        Row(Modifier.width(side).padding(bottom = 6.dp)) {
                            BasicText(if (o.song.station) "live" else clock(pos), style = pixel(12.sp, Ink.mid))
                            Spacer(Modifier.weight(1f))
                            if (length > 0 && !o.song.station) BasicText("-" + clock((length - pos).coerceAtLeast(0L)), style = pixel(12.sp, Ink.mid))
                        }
                        Box(Modifier.width(side).height(1.dp).background(Ink.line)) {
                            if (length > 0 && !o.song.station) Box(Modifier.fillMaxWidth((pos.toFloat() / length).coerceIn(0f, 1f)).height(1.dp).background(accent))
                        }
                        Box(Modifier.size(side + off)) {
                            if (!o.song.station) Dithered(o.song, Modifier.offset(off, off).size(side), px = 1200)
                            CoverOf(o.song, o.song.album.ifEmpty { o.song.title }, Modifier.size(side), px = 1200, moving = o.playing)
                        }
                    }
                    Spacer(Modifier.width(48.dp))
                    Column(Modifier.weight(1f).padding(top = 17.dp)) {
                        BasicText((if (o.playing) "playing now on " else "paused on ") + o.from + (if (o.device.isNotEmpty()) " · on ${o.device.lowercase()}" else ""),
                            style = pixel(12.sp, Ink.ink))
                        Spacer(Modifier.height(10.dp))
                        BasicText(o.song.title.uppercase(), style = headline(44.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(12.dp))
                        if (!o.song.station) Word(o.song.artist.lowercase(), pixel(14.sp)) { artist?.let { open(Page.Artist(it)) } }
                        if (o.song.album.isNotEmpty()) BasicText(o.song.album.lowercase(), style = pixel(14.sp, Ink.mid))
                        Spacer(Modifier.height(22.dp))
                        // steering it there, then taking it over
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                            Pixel(BACK, "back, there", Ink.mid, cell = 3.dp) { SyncHub.steer(o.peer, "previous") }
                            Pixel(if (o.playing) PAUSE else PLAY, if (o.playing) "pause, there" else "play, there", Ink.ink, cell = 4.dp) {
                                SyncHub.steer(o.peer, if (o.playing) "pause" else "play")
                            }
                            Pixel(NEXT, "next, there", Ink.mid, cell = 3.dp) { SyncHub.steer(o.peer, "next") }
                            Spacer(Modifier.width(12.dp))
                            Word("CONTINUE HERE", headline(26.sp), touch = 10.dp) { Continue.take(o) }
                        }
                        Spacer(Modifier.height(18.dp))
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line))
                        Spacer(Modifier.height(14.dp))
                        val lines = rememberLyrics(o.song)
                        var shows by remember(o.song.id) { mutableStateOf<String?>(null) }
                        val panel = shows ?: if (!lines.isNullOrEmpty()) "lyrics" else "notes"
                        Row {
                            for (w in listOf("lyrics", "notes"))
                                Word(w, pixel(14.sp, if (w == panel) Ink.ink else Ink.dim), Modifier.padding(end = 26.dp), touch = 10.dp) { shows = w }
                        }
                        Spacer(Modifier.height(14.dp))
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            if (panel == "lyrics") {
                                val all = lines
                                if (all.isNullOrEmpty()) BasicText(lyricsSaid(all), style = pixel(13.sp, Ink.mid))
                                else LyricsBlock(all, all.indexOfLast { it.at <= pos + Lyrics.LEAD }, Modifier.fillMaxSize(), big = true)
                            } else {
                                val n = remember(o.song.id) { Player.Now(o.song, 0, o.playing, o.song.duration) }
                                Notes(n, null, o.device.ifEmpty { null } ?: o.from)
                            }
                        }
                    }
                }
            }
        }
    }
}
