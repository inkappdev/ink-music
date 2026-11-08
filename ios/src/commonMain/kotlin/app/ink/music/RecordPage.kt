package app.ink.music

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import dev.chrisbanes.haze.hazeSource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The close row's height (the phone's CLOSE_ROW). */
private val CLOSE_ROW = 40.dp

/**
 * A page over the places, as the phone's FrostedPage: black, the close arrow at the top right, pulled down from its top
 * (a list at its start) it comes with the finger, and past a fifth of the screen or flung, it closes. `content` is
 * given how far down its own words start.
 */
@Composable
fun PageOver(onClose: () -> Unit, content: @Composable (Dp) -> Unit) {
    var pull by remember { mutableFloatStateOf(0f) }
    var tall by remember { mutableIntStateOf(1) }
    val close by rememberUpdatedState(onClose)
    val pulling = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (pull <= 0f || available.y >= 0f) return Offset.Zero
                val take = maxOf(available.y, -pull)
                pull += take
                return Offset(0f, take)
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                pull += available.y
                return Offset(0f, available.y)
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pull <= 0f) return Velocity.Zero
                if (pull > tall / 5f || available.y > 1800f) close()
                else animate(pull, 0f, animationSpec = tween(Motion.EXIT, easing = Motion.expo)) { v, _ -> pull = v }
                return available
            }
        }
    }
    val bar = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() }
    Box(Modifier.fillMaxSize().onSizeChanged { tall = it.height }.nestedScroll(pulling).graphicsLayer { translationY = pull }.background(Ink.black)
        .combinedClickable(remember { MutableInteractionSource() }, indication = null) {}) {
        val frost = rememberFrost()
        Box(Modifier.fillMaxSize().hazeSource(frost)) { content(bar + CLOSE_ROW) }
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(Ink.black))
            Row(Modifier.fillMaxWidth().height(CLOSE_ROW).background(Ink.black).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Pixel(CLOSE, "close", Ink.mid, onClick = onClose)
            }
            Box(Modifier.fillMaxWidth().height(BAND).softEdge(frost, towardBottom = false))
        }
    }
}

/** A page's rows arriving in turn as it opens (ink's [arrive]: up and in, 50 ms apart, the first eight). */
@Composable
fun Modifier.cascade(i: Int, opened: Long): Modifier {
    val now = remember { nowMs() }
    if (now - opened > 600) return this
    return this.arrive(i.coerceAtMost(8), Unit)
}

private fun plainName(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

/**
 * A record's page, as the phone's: its cover across, its name, whose, its year, songs and minutes; play and shuffle; its
 * songs as words, a tap plays from there. Your server's record made whole from YouTube Music's where it has more (your
 * songs in their places, the rest YouTube's, a step dimmer).
 */
@Composable
fun Record(a: Album, onClose: () -> Unit) {
    val opened = remember(a.id) { nowMs() }
    val mine by produceState<List<Song>?>(null, a.id) {
        value = runCatching { if (a.youtube) Here.off { Tube.songs(a) } else Server.songs(a.id) }.getOrDefault(emptyList())
    }
    val whole by produceState(mine.orEmpty(), mine) {
        val yours = mine ?: return@produceState
        value = yours
        if (a.youtube || yours.isEmpty()) return@produceState
        val tracks = Here.off { runCatching { Tube.find(a.title, a.artist, true)?.let { Tube.songs(it) } }.getOrNull() } ?: return@produceState
        if (tracks.size <= yours.size) return@produceState
        val left = yours.toMutableList()
        val placed = tracks.map { t -> left.firstOrNull { plainName(it.title) == plainName(t.title) }?.also { left.remove(it) } ?: t }
        value = placed + left
    }
    val yours = remember(mine) { mine.orEmpty().map { it.id }.toSet() }
    val now by Sound.now.collectAsState()
    PageOver(onClose) { top ->
        LazyColumn(Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 14.dp, bottom = (if (now != null) 120.dp else 30.dp) + 24.dp)) {
            item {
                Column(Modifier.cascade(0, opened)) {
                    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Cover(a.cover, 1200, a.title, Modifier.size(minOf(maxWidth, 560.dp)))
                    }
                    Spacer(Modifier.height(18.dp))
                    BasicText(a.title.uppercase(), style = headline(36.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    // whose: their page
                    Word(a.artist.lowercase(), pixel(13.sp, Ink.mid), Modifier.padding(vertical = 6.dp)) {
                        Pages.open(Page.Artist(a.artist.substringBefore(", ")))
                    }
                    Spacer(Modifier.height(4.dp))
                    BasicText(
                        if (mine == null) "reading it" else listOfNotNull(a.year.takeIf { it > 0 }?.toString(), "${whole.size} ${if (whole.size == 1) "song" else "songs"}",
                            "${yours.size} yours".takeIf { whole.size > yours.size && yours.isNotEmpty() && !a.youtube },
                            whole.sumOf { it.duration }.takeIf { it > 0 }?.let { "${(it / 60_000).coerceAtLeast(1)} min" }).joinToString("   "),
                        style = pixel(12.sp, Ink.mid),
                    )
                    Spacer(Modifier.height(16.dp))
                    if (whole.isNotEmpty()) Row {
                        Word("play", pixel(14.sp), Modifier.padding(end = 26.dp), touch = 8.dp) { Sound.play(whole, 0) }
                        Word("shuffle", pixel(14.sp, Ink.mid), Modifier.padding(end = 26.dp), touch = 8.dp) { Sound.play(whole.shuffled(), 0) }
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }
            itemsIndexed(whole, key = { i, s -> s.id + i }) { i, s ->
                Box(Modifier.cascade(i + 1, opened)) {
                    val number = if (whole.size > yours.size) "${i + 1}" else s.track.takeIf { it > 0 }?.toString() ?: "${i + 1}"
                    SongRow(s, number = number, yours = a.youtube || s.id in yours) { Sound.play(whole, i) }
                }
            }
        }
    }
}

/** How far a song is swiped before it's play next (right) or add to queue (left). */
private val SWIPE = 72.dp

/**
 * A song in a list, as the phone's: its number (on a record), its name, whose, how long; the one playing lit, the rest
 * a step back while something plays, one you don't have a step dimmer. Swiped right it plays next, left it goes to the
 * queue's end; held, more: play next, add to queue, love.
 */
@Composable
fun SongRow(s: Song, number: String? = null, yours: Boolean = true, onClick: () -> Unit) {
    val now by Sound.now.collectAsState()
    val on = now?.song?.id == s.id
    var open by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val tap = felt(then = onClick)
    val scope = rememberCoroutineScope()
    val slid = remember { Animatable(0f) }
    var armed by remember { mutableIntStateOf(0) }
    var done by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(done) { if (done != null) { delay(1_400); done = null } }
    val reach = with(LocalDensity.current) { SWIPE.toPx() }
    Box(Modifier.fillMaxWidth()) {
        if (slid.value != 0f) Row(Modifier.matchParentSize(), verticalAlignment = Alignment.CenterVertically) {
            if (slid.value > 0) BasicText("play next", style = pixel(13.sp, if (armed == 1) Ink.ink else Ink.dim))
            Spacer(Modifier.weight(1f))
            if (slid.value < 0) BasicText("add to queue", style = pixel(13.sp, if (armed == -1) Ink.ink else Ink.dim))
        }
        Column(
            Modifier.fillMaxWidth().offset { IntOffset(slid.value.roundToInt(), 0) }.background(Ink.black)
                .combinedClickable(remember { MutableInteractionSource() }, indication = null, hapticFeedbackEnabled = false,
                    onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); open = !open },
                    onClick = { if (open) open = false else tap() })
                .pointerInput(s.id) {
                    var pulled = 0f
                    fun letGo(act: Boolean) {
                        if (act) when (armed) {
                            1 -> { Sound.playNext(s); done = "next" }
                            -1 -> { Sound.add(s); done = "queued" }
                        }
                        armed = 0; pulled = 0f
                        scope.launch { slid.animateTo(0f, tween(Motion.EXIT, easing = Motion.expo)) }
                    }
                    detectHorizontalDragGestures(onDragEnd = { letGo(true) }, onDragCancel = { letGo(false) }) { change, dx ->
                        change.consume()
                        pulled += dx
                        val past = (kotlin.math.abs(pulled) - reach).coerceAtLeast(0f)
                        scope.launch { slid.snapTo(kotlin.math.sign(pulled) * (kotlin.math.abs(pulled) - past * .7f)) }
                        val at = when { pulled > reach -> 1; pulled < -reach -> -1; else -> 0 }
                        if (at != armed) { armed = at; if (at != 0) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick) }
                    }
                }.padding(vertical = 9.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                number?.let { BasicText(it, style = pixel(12.sp, if (on) Ink.ink else Ink.dim), modifier = Modifier.width(30.dp).padding(bottom = 3.dp)) }
                Column(Modifier.weight(1f)) {
                    BasicText(s.title.uppercase(), style = headline(19.sp, when { on -> Ink.ink; !yours -> if (now == null) Ink.mid else Ink.dim; now == null -> Ink.ink; else -> Ink.mid }),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (number == null) BasicText(s.artist.lowercase(), style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                done?.let { BasicText(it, style = pixel(12.sp), modifier = Modifier.padding(start = 12.dp, bottom = 3.dp)) }
                    ?: if (s.duration > 0) BasicText(clock(s.duration), style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(start = 12.dp, bottom = 3.dp)) else Unit
            }
            if (open) FlowRow(Modifier.padding(start = if (number != null) 30.dp else 0.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                Word("play next", pixel(13.sp), touch = 8.dp) { open = false; Sound.playNext(s) }
                Word("add to queue", pixel(13.sp), touch = 8.dp) { open = false; Sound.add(s) }
                val loved by Loved.ids.collectAsState()
                if (s.kind == "youtube" || s.kind == "server") Word(if (s.id in loved) "unlove" else "love", pixel(13.sp), touch = 8.dp) { open = false; Loved.toggle(s) }
            }
        }
    }
}
