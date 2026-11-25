package app.ink.music

import kotlinx.coroutines.launch

import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import dev.chrisbanes.haze.hazeSource
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val ROW = 58.dp   // every row the same height, so a carried one knows the place it's over

/** A page asked for from inside the player (up next's record and artist): the library opens it, the player let down. */
object Opening { val asked = kotlinx.coroutines.flow.MutableStateFlow<Page?>(null) }

/** A queued song's record: the library's, else YouTube Music's by its id ("ya" and its browse id); none for a station. */
internal fun recordOf(s: Song): Page? = when {
    s.station || s.episode || s.album.isBlank() -> null
    Library.albums.value.any { it.id == s.albumId } -> Page.Record(s.albumId)
    s.albumId.startsWith("ya") -> s.albumId.removePrefix("ya").let { id ->
        Page.Tube(TubeAlbum(if (id.startsWith("OLAK5uy_")) "https://music.youtube.com/playlist?list=$id" else "https://music.youtube.com/browse/$id",
            s.album, s.albumArtist.ifEmpty { s.artist }, s.art))
    }
    else -> null
}

/**
 * The queue, as words: played dim, the one playing lit, the rest at 55%. A tap plays from there.
 * Hold one and it lifts: carried, the others slide aside to where it would go (a tick a place),
 * let go it settles there. Held and let go without moving, its record, its artist and "remove" show beside it.
 * Swiped left far enough (the word lit, a tick) it's out (#4), with "undo" at the top a few seconds.
 */
@Composable
fun QueuePage(onClose: () -> Unit) {
    val queue by Playback.queue.collectAsState()
    val now by Playback.now.collectAsState()
    val playing = now?.index ?: -1
    val finding by Playback.finding.collectAsState()
    val haptic = LocalHapticFeedback.current
    val rowPx = with(LocalDensity.current) { ROW.toPx() }
    var lifted by remember { mutableStateOf<Int?>(null) }
    var drag by remember { mutableFloatStateOf(0f) }
    var options by remember { mutableStateOf<Int?>(null) }
    // the last one swiped out, to put back (its place and the song) for a few seconds
    var undo by remember { mutableStateOf<Pair<Int, Song>?>(null) }
    LaunchedEffect(undo) { if (undo != null) { delay(4_000); undo = null } }
    val reach = with(LocalDensity.current) { 96.dp.toPx() }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { if (playing > 0) list.scrollToItem((playing - 1).coerceAtLeast(0)) }
    // where the carried row would land
    val target = lifted?.let { (it + (drag / rowPx).roundToInt()).coerceIn(0, (queue.size - 1).coerceAtLeast(0)) }
    var lastTarget by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(target) {
        if (target != null && lastTarget != null && target != lastTarget) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        lastTarget = target
    }

    Column(
        Modifier.fillMaxSize().background(Ink.black)
            .noFocus().clickable(remember { MutableInteractionSource() }, indication = null) { options = null }
            .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)).navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                BasicText("UP NEXT", style = headline(40.sp))
                Spacer(Modifier.height(6.dp))
                // how to move one, till one has been moved (Hints)
                val hinted by Hints.done.collectAsState()
                BasicText(
                    if (queue.isEmpty()) "nothing queued" else "${queue.size} songs" + if (Hints.QUEUE in hinted) "" else "   hold one to move it",
                    style = pixel(12.sp, Ink.mid),
                )
                // radio: lit, the queue's end brings more like it
                val on = now?.radio != false
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Word("similar", pixel(13.sp, if (on) Ink.ink else Ink.dim), touch = 8.dp) { Playback.radio(!on) }
                    // one just swiped out: back where it was
                    undo?.let { (at, song) ->
                        Word("undo", pixel(13.sp), Modifier.padding(start = 22.dp), touch = 8.dp) { undo = null; Playback.insert(song, at) }
                    }
                }
                // a DJ set playing: its tracks as they're heard, the newest first; a tap puts one next (SetHeard)
                val heardInSet by SetHeard.heard.collectAsState()
                if (SetHeard.isSet(now) && heardInSet.isNotEmpty()) {
                    val scope = androidx.compose.runtime.rememberCoroutineScope()
                    var said by remember { mutableStateOf<String?>(null) }
                    androidx.compose.runtime.LaunchedEffect(said) { if (said != null) { kotlinx.coroutines.delay(1_600); said = null } }
                    BasicText(said ?: "heard in this set", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
                    heardInSet.asReversed().take(8).forEachIndexed { i, t ->
                        Word("${t.at / 60_000}:${"%02d".format((t.at / 1000) % 60)}  ${t.artist.lowercase()} - ${t.title.lowercase()}", pixel(13.sp, if (i == 0) Ink.ink else Ink.mid), touch = 6.dp) {
                            scope.launch {
                                val song = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { YouTubeMusic.find(t.title, t.artist) }
                                if (song != null) { Playback.playNext(song); said = "next: ${t.title.lowercase()}" } else said = "not found"
                            }
                        }
                    }
                }
            }
            CloseArrow(onClick = onClose)
        }
        Spacer(Modifier.height(14.dp))
        // the list softens just under the heading as it goes behind it
        val soft = dev.chrisbanes.haze.rememberHazeState()
        Box(Modifier.fillMaxSize()) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().hazeSource(soft), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp)) {
            // no keys: one song can be queued twice
            itemsIndexed(queue) { i, q ->
                val l = lifted
                // the others step aside for the carried one, easing there
                val shift = when {
                    l == null || target == null || i == l -> 0f
                    i in (l + 1)..target -> -rowPx
                    i in target until l -> rowPx
                    else -> 0f
                }
                val eased by animateFloatAsState(shift, tween(Motion.ENTER, easing = Motion.expo), label = "make room")
                // swiped left: the row goes with the finger, "remove" under it lit once far enough
                val slid = remember { androidx.compose.animation.core.Animatable(0f) }
                var armed by remember { mutableStateOf(false) }
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                val tone = when {
                    l != null && i != l -> Ink.dim
                    i == playing -> Ink.ink
                    i < playing -> Ink.dim
                    else -> Ink.mid
                }
                Box(Modifier.fillMaxWidth().height(ROW).zIndex(if (i == l) 1f else 0f).graphicsLayer { translationY = if (i == l) drag else eased }) {
                if (slid.value < 0f) Row(Modifier.matchParentSize(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    BasicText("remove", style = pixel(13.sp, if (armed) Ink.ink else Ink.dim))
                }
                Row(
                    Modifier.fillMaxWidth().height(ROW)
                        .graphicsLayer { translationX = slid.value }.background(Ink.black)
                        .pointerInput(i, queue.size) {
                            var pulled = 0f
                            fun letGo(act: Boolean) {
                                val song = q.song
                                if (act && armed && song != null && !Playback.onSpeaker) scope.launch {
                                    // off to the left, then out: the rows under it close up
                                    slid.animateTo(-size.width.toFloat(), tween(Motion.EXIT, easing = Motion.expo))
                                    Playback.remove(i); undo = i to song
                                    slid.snapTo(0f)
                                } else scope.launch { slid.animateTo(0f, tween(Motion.EXIT, easing = Motion.expo)) }
                                armed = false; pulled = 0f
                            }
                            detectHorizontalDragGestures(onDragEnd = { letGo(true) }, onDragCancel = { letGo(false) }) { c, dx ->
                                c.consume()
                                pulled = (pulled + dx).coerceAtMost(0f)
                                val past = (-pulled - reach).coerceAtLeast(0f)
                                scope.launch { slid.snapTo(pulled + past * .7f) }
                                val now2 = -pulled > reach
                                if (now2 != armed) { armed = now2; if (now2) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick) }
                            }
                        }
                        .pointerInput(i, queue.size) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    options = null; lifted = i; drag = 0f
                                },
                                onDragEnd = {
                                    // where it lands, from what's live now: the gesture keeps what it was given as it began,
                                    // and `target` then was none (it went back where it was, 2026-11-16, as the computer's)
                                    val to = (i + kotlin.math.round(drag / rowPx).toInt()).coerceIn(0, (queue.size - 1).coerceAtLeast(0))
                                    if (to != i) { Playback.move(i, to); Hints.did(Hints.QUEUE) }
                                    else if (kotlin.math.abs(drag) < rowPx / 3) options = i   // held still: its options
                                    lifted = null; drag = 0f
                                },
                                onDragCancel = { lifted = null; drag = 0f },
                            ) { c, d -> c.consume(); drag += d.y }
                        }
                        .clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { options = null; Playback.jump(i) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        // the song playing: its name its progress, filling from the left in its cover's colour (the
                        // computer's since 2026-11-09; a station, no length, stays plain)
                        val n = now
                        if (i == playing && l == null && n != null && n.duration > 0) PlayingTitle(q.title.uppercase(), tone, n)
                        else BasicText(q.title.uppercase(), style = headline(18.sp, tone), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        BasicText(q.artist.lowercase(), style = pixel(12.sp, if (tone == Ink.ink) Ink.mid else Ink.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (options == i) {
                        // its record and its artist, the library's pages over the player let down (#4)
                        val song = q.song
                        val record = song?.let(::recordOf)
                        if (record != null) { Spacer(Modifier.width(12.dp)); Word("record", pixel(13.sp, Ink.mid), touch = 10.dp) { options = null; Opening.asked.value = record } }
                        if (song != null && !song.station && !song.episode && song.artist.isNotBlank())
                            { Spacer(Modifier.width(12.dp)); Word("artist", pixel(13.sp, Ink.mid), touch = 10.dp) { options = null; Opening.asked.value = Page.Artist(song.albumArtist.ifBlank { song.artist }) } }
                        Spacer(Modifier.width(12.dp))
                        Word("remove", pixel(13.sp), touch = 10.dp) { options = null; Playback.remove(i) }
                    } else if (i == playing) {
                        Spacer(Modifier.width(12.dp))
                        BasicText("playing", style = pixel(12.sp, Ink.mid))
                    }
                }
                }
            }
            finding?.let { a -> item { FindingRows(a) } }
        }
        Box(Modifier.fillMaxWidth().height(BAND).softEdge(soft, towardBottom = false))
        }
    }
}

/**
 * Radio finding more for the queue's end: rows under its last song developing in the dither from black as covers do
 * (the brightest dots first), each a beat behind the one above, then going back to black and again; still, half come,
 * when ink music moves less. The songs found take their place as they land. (2026-11-20: the queue's end, waiting,
 * read as broken.)
 */
@Composable
private fun FindingRows(artist: String) {
    val less by Moves.less.collectAsState()
    var t by remember { mutableFloatStateOf(0f) }
    if (!less) LaunchedEffect(Unit) { val from = withFrameNanos { it }; while (true) withFrameNanos { t = (it - from) / 1e9f } }
    BasicText("finding more like ${artist.lowercase()}", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
    repeat(3) { r ->
        Canvas(Modifier.fillMaxWidth().height(ROW)) {
            val level = if (less) .6f else {
                // a round of 2.4 s: in over 55% (ease-out-expo), held, out over the last 30%
                val p = (((t - r * .22f) % CYCLE) + CYCLE) % CYCLE / CYCLE
                when {
                    p < .55f -> Motion.expo.transform(p / .55f)
                    p < .7f -> 1f
                    else -> 1f - Motion.expo.transform((p - .7f) / .3f)
                }
            }
            val cell = 3.dp.toPx()
            val dot = Size(cell * .7f, cell * .7f)
            // the title's line and the artist's under it, as the rows above set them
            val titleTop = 12.dp.toPx(); val titleH = 14.dp.toPx()
            val artistTop = titleTop + titleH + 8.dp.toPx(); val artistH = 7.dp.toPx()
            fun block(top: Float, h: Float, w: Float, salt: Int) {
                var y = 0
                while (y * cell < h) {
                    var x = 0
                    while (x * cell < w) {
                        // each dot's own moment to come in: the brightest first
                        // (mixed, so they come scattered: the plain product came in diagonal stripes)
                        var k = (x * 73856093) xor (y * 19349663) xor ((r * 4 + salt) * 83492791)
                        k = k xor (k ushr 16); k *= 0x45d9f3b; k = k xor (k ushr 16)
                        val at = (k and 0xffff) / 65536f
                        if (at < level) drawRect(if (at < level * .3f) Ink.mid else Ink.dim, Offset(x * cell, top + y * cell), dot)
                        x++
                    }
                    y++
                }
            }
            block(titleTop, titleH, size.width * TITLE_W[r], 1)
            block(artistTop, artistH, size.width * ARTIST_W[r], 2)
        }
    }
}
private const val CYCLE = 2.4f
private val TITLE_W = floatArrayOf(.62f, .46f, .7f)
private val ARTIST_W = floatArrayOf(.3f, .38f, .24f)

/**
 * Up next on another device (opened from its now playing): its queue as [QueuePage] draws this phone's, the song
 * playing there lit, the ones before dim. Only looked at: that device keeps its own order.
 */
@Composable
fun RemoteQueuePage(r: Everywhere.Remote, onClose: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onClose)
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { if (r.index > 0) list.scrollToItem((r.index - 1).coerceAtLeast(0)) }
    Column(
        Modifier.fillMaxSize().background(Ink.black).noFocus().clickable(remember { MutableInteractionSource() }, indication = null) {}
            .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)).navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                BasicText("UP NEXT", style = headline(40.sp))
                Spacer(Modifier.height(6.dp))
                BasicText("${r.songs.size} songs   on ${r.where.removePrefix("ink music on ")}", style = pixel(12.sp, Ink.mid))
            }
            CloseArrow(onClick = onClose)
        }
        Spacer(Modifier.height(14.dp))
        val soft = dev.chrisbanes.haze.rememberHazeState()
        Box(Modifier.fillMaxSize()) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize().hazeSource(soft), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp)) {
                itemsIndexed(r.songs) { i, q ->
                    val tone = when {
                        i == r.index -> Ink.ink
                        i < r.index -> Ink.dim
                        else -> Ink.mid
                    }
                    Row(Modifier.fillMaxWidth().height(ROW), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            // the song playing there filling with its cover's color, as this phone's own queue does
                            if (i == r.index && r.length > 0 && !q.station) FilledTitle(q.title.uppercase(), tone, q, r.length, r.playing, r) { r.positionNow() }
                            else BasicText(q.title.uppercase(), style = headline(18.sp, tone), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            BasicText(q.artist.lowercase(), style = pixel(12.sp, if (tone == Ink.ink) Ink.mid else Ink.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (i == r.index) {
                            Spacer(Modifier.width(12.dp))
                            BasicText(if (r.playing) "playing" else "paused", style = pixel(12.sp, Ink.mid))
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(BAND).softEdge(soft, towardBottom = false))
        }
    }
}

/**
 * The playing song's name as its progress (as the computer's queue): the letters in `base`, and over them the same
 * letters in its cover's colour, the progress line's, cut off where the song's at, moving on smoothly between looks.
 */
@Composable
private fun PlayingTitle(title: String, base: androidx.compose.ui.graphics.Color, n: Now) =
    FilledTitle(title, base, n.song, n.duration, n.moving, n) { n.positionNow() }

/**
 * A title filled as its song plays: `at` where it is now, asked four times a second while it `moves`; `key` anew
 * when what's playing is told again. This phone's queue ([PlayingTitle]) and another device's ([RemoteQueuePage]).
 */
@Composable
private fun FilledTitle(title: String, base: androidx.compose.ui.graphics.Color, song: Song?, length: Long, moves: Boolean, key: Any?, at: () -> Long) {
    var pos by remember { mutableLongStateOf(at()) }
    // asked every frame while it moves (2026-11-11: four times a second, each a short slide: it moved in steps); with
    // less motion, once a second
    val less by Moves.less.collectAsState()
    LaunchedEffect(key, less) {
        while (true) { pos = at(); if (!moves) break; if (less) delay(1_000) else androidx.compose.runtime.withFrameMillis { } }
    }
    val k = if (length > 0) (pos.toFloat() / length).coerceIn(0f, 1f) else 0f
    val accent = rememberCover(song, 300)?.accent ?: Ink.ink
    val c by androidx.compose.animation.animateColorAsState(accent, tween(Motion.ENTER * 2, easing = Motion.expo), label = "fill")
    Box {
        BasicText(title, style = headline(18.sp, base), maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicText(title, style = headline(18.sp, c), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.drawWithContent { clipRect(right = size.width * k) { this@drawWithContent.drawContent() } })
    }
}
