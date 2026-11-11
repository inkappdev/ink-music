package app.ink.music

import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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

/**
 * The queue, as words: played dim, the one playing lit, the rest at 55%. A tap plays from there.
 * Hold one and it lifts: carried, the others slide aside to where it would go (a tick a place),
 * let go it settles there. Held and let go without moving, its "remove" shows beside it.
 */
@Composable
fun QueuePage(onClose: () -> Unit) {
    val queue by Playback.queue.collectAsState()
    val now by Playback.now.collectAsState()
    val playing = now?.index ?: -1
    val haptic = LocalHapticFeedback.current
    val rowPx = with(LocalDensity.current) { ROW.toPx() }
    var lifted by remember { mutableStateOf<Int?>(null) }
    var drag by remember { mutableFloatStateOf(0f) }
    var options by remember { mutableStateOf<Int?>(null) }
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
                    Word("radio", pixel(13.sp, if (on) Ink.ink else Ink.dim), touch = 8.dp) { Playback.radio(!on) }
                    BasicText(if (on) "  then more like these" else "  stops at the end", style = pixel(12.sp, Ink.mid))
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
                val tone = when {
                    l != null && i != l -> Ink.dim
                    i == playing -> Ink.ink
                    i < playing -> Ink.dim
                    else -> Ink.mid
                }
                Row(
                    Modifier.fillMaxWidth().height(ROW)
                        .zIndex(if (i == l) 1f else 0f)   // the carried one over the rest
                        .graphicsLayer { translationY = if (i == l) drag else eased }
                        .pointerInput(i, queue.size) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    options = null; lifted = i; drag = 0f
                                },
                                onDragEnd = {
                                    val to = target
                                    if (to != null && to != i) { Playback.move(i, to); Hints.did(Hints.QUEUE) }
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
                        Spacer(Modifier.width(12.dp))
                        Word("remove", pixel(13.sp), touch = 10.dp) { options = null; Playback.remove(i) }
                    } else if (i == playing) {
                        Spacer(Modifier.width(12.dp))
                        BasicText("playing", style = pixel(12.sp, Ink.mid))
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(BAND).softEdge(soft, towardBottom = false))
        }
    }
}

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
