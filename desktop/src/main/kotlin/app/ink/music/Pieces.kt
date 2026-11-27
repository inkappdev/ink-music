package app.ink.music

// From ink (the launcher): the marquee, the word you press, the coverless sleeve. Kept as ink has
// them, so the two read as one family.

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

fun clock(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000).toInt()
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}

/** The giant artist running off both edges; still while paused. A new artist drops and rises. */
@Composable
fun Marquee(artist: String, playing: Boolean, modifier: Modifier, size: androidx.compose.ui.unit.TextUnit = 150.sp, color: androidx.compose.ui.graphics.Color = Ink.ink) {
    AnimatedContent(
        targetState = artist.uppercase(),
        transitionSpec = {
            // no size animation: the lane is the same size whatever the name (resizing re-laid
            // the 230 px name out every frame of the change on the onn)
            (slideInVertically(tween(Motion.ENTER, easing = Motion.expo)) { it } togetherWith
                slideOutVertically(tween(Motion.EXIT, easing = Motion.expo)) { it }) using null
        },
        modifier = modifier,
        label = "marquee",
    ) { shown -> Drift(shown, headline(size, color).let { s -> Hand.display?.let { s.copy(fontFamily = it) } ?: s }, playing, Modifier.fillMaxWidth().padding(bottom = 4.dp)) }
}

/**
 * Text wider than the screen, sliding left in a seamless loop (the name, a gap, the name again).
 * Pausing eases it to a stop where it is and playing eases it back up from there: it never
 * jumps to the start or waits at it. Still, it asks for no frames.
 */
@Composable
private fun Drift(text: String, style: TextStyle, moving: Boolean, modifier: Modifier) {
    // as the desktop's (eww/scripts/marquee.py): the name and a gap (0.7 of the type's size, so
    // the repeats read apart) as one tile, repeated across, sliding a tile a loop at a screen
    // width every 22 seconds, endlessly, whether the name fits or not
    val measurer = rememberTextMeasurer()
    // measured once a name (the TV's warm-up measured it already: laying out 230 px type in the
    // frame the name changed was a 57 ms frame on the onn)
    val density = LocalDensity.current
    val key = Drawn.key(text, style, density)
    val laid = remember(key) { Drawn.laid(key) ?: measurer.measure(text, style, maxLines = 1, softWrap = false) }
    val gap = with(LocalDensity.current) { style.fontSize.toPx() * .7f }
    val tile = laid.size.width + gap
    var offset by remember { mutableFloatStateOf(0f) }
    val width = remember { FloatArray(1) }             // the page's width, px, for the speed
    val speed = remember { Animatable(if (moving) 1f else 0f) }

    LaunchedEffect(moving, tile) {
        coroutineScope {
            launch { speed.animateTo(if (moving) 1f else 0f, tween(Motion.ENTER, easing = Motion.expo)) }
            var last = -1L
            while (moving || speed.value > 0f) {
                withFrameNanos { now ->
                    if (last >= 0 && tile > 0f) offset = (offset + (now - last) / 1e9f * width[0] / 22f * speed.value) % tile
                    last = now
                }
            }
        }
    }

    // the name drawn once into a bitmap: type this big is past the glyph cache, so drawn as text
    // every frame each letter was a path again (20 ms a frame on the onn, over a 60 Hz frame);
    // a bitmap slides for nothing, and at fractions of a pixel (filtered), so it never steps
    // Drawn off the main thread (a 230 px name took frames to draw on the onn, a hitch every time
    // the marquee changed), and the last few kept, so a name come back to costs nothing
    val dir = LocalLayoutDirection.current
    // the bitmap with the name and size it was drawn for: only that one is drawn at this tile. A
    // bitmap kept from before a change (the phone turned: 150 sp upright, 84 sp sideways) drawn at
    // the new tile's spacing overlapped itself
    val big = Drawn.tooBig(laid)
    val drawn by produceState(Drawn.get(key)?.let { key to it }, key) {
        if (value?.first == key || big) return@produceState
        value = key to (Drawn.get(key) ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val w = kotlin.math.ceil(laid.size.width.toFloat()).toInt().coerceAtLeast(1)
            val h = laid.size.height.coerceAtLeast(1)
            ImageBitmap(w, h).also { b ->
                CanvasDrawScope().draw(density, dir, androidx.compose.ui.graphics.Canvas(b), Size(w.toFloat(), h.toFloat())) { drawText(laid) }
            }
        }.also { Drawn.put(key, it) })
    }
    // clipped to its page: the copies waiting off the edge mustn't draw over the apps tab. The
    // tiles are drawn once; each frame only moves the layer they're on
    Box(
        modifier.clipToBounds().height(with(density) { laid.size.height.toDp() })
            .onSizeChanged { width[0] = it.width.toFloat() },
    ) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { translationX = -offset }) {
            val b = drawn?.takeIf { it.first == key }?.second
            if (text.isEmpty() || tile <= 0f) return@Canvas
            if (big) {   // too big a bitmap to draw: as text, slower but there
                var x = 0f
                while (x < size.width + tile) { drawText(laid, topLeft = Offset(x, 0f)); x += tile }
                return@Canvas
            }
            if (b == null) return@Canvas
            var x = 0f
            while (x < size.width + tile) {
                drawImage(b, topLeft = Offset(x, 0f))
                x += tile
            }
        }
    }
}

/** The marquee's names as drawn, the last few (a TV's reel comes back round to them). */
private object Drawn {
    /** A name as drawn: its words, size and colour, and the screen's density and font scale. */
    fun key(text: String, style: TextStyle, d: androidx.compose.ui.unit.Density) =
        "$text|${style.fontSize}|${style.color}|${d.density}|${d.fontScale}"

    private val kept = Lru<String, ImageBitmap>(24 * 1024 * 1024) { it.width * it.height * 4 }
    fun get(key: String): ImageBitmap? = kept.get(key)
    /** A long list of artists at 150 sp passes what Android will draw as one bitmap (100 MB) and throws. */
    fun tooBig(l: androidx.compose.ui.text.TextLayoutResult) = l.size.width.toLong() * l.size.height * 4 > 48L * 1024 * 1024
    fun put(key: String, b: ImageBitmap) { kept.put(key, b) }

    // and how each was laid out, so the name it changes to isn't measured in that frame
    private val layouts = Lru<String, androidx.compose.ui.text.TextLayoutResult>(8) { 1 }
    fun laid(key: String): androidx.compose.ui.text.TextLayoutResult? = layouts.get(key)
    fun keepLaid(key: String, l: androidx.compose.ui.text.TextLayoutResult) { layouts.put(key, l) }
}

/**
 * A word you can press: brightness is the feedback (ink, 55% while held). `touch`: its press reaches that far past it (half across).
 * The desktop's: a step brighter under the mouse, too (the phone's and ink's have no mouse to follow).
 */
@Composable
fun Word(text: String, style: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier, touch: Dp = 0.dp, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val over by src.collectIsHoveredAsState()
    val c by animateColorAsState(if (pressed) Ink.mid else if (over) brighter(style.color) else style.color, tween(Motion.FADE), label = "word")
    BasicText(
        text, style = style.copy(color = c), maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.hoverable(src).clickable(src, indication = null, onClick = felt(then = onClick)).padding(vertical = touch, horizontal = touch / 2),
    )
}

/**
 * A record with no cover anywhere: ink draws its own sleeve, the album's name (or the song's)
 * in Archivo at the bottom of the plate, as the recap shows a coverless album by its name.
 */
@Composable
fun NameCover(name: String, side: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(side).background(Ink.plate).padding(side / 11), contentAlignment = Alignment.BottomStart) {
        BasicText(
            name.uppercase(), style = headline((side.value / 7f).sp, Ink.mid),
            maxLines = 4, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The feather: where content runs behind something (a list under the mini player, under the tab
 * row), it fades out over `topSize` / `bottomSize` instead of ending on a cut. Its own opacity
 * masked (ink tv's list over the marquee), never a colour painted over it. `top` / `bottom` say
 * how much of each edge's fade is on (0 to 1): only where something is behind, so a list at its
 * top doesn't fade its first row.
 */
fun Modifier.feather(
    top: () -> Float, bottom: () -> Float,
    // a short fade (2026-10-25: subtler than ink tv's): it softens the edge, not a band
    topSize: Dp = 20.dp, bottomSize: Dp = 32.dp,
): Modifier = this
    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val h = size.height
        if (h <= 0f) return@drawWithContent
        val t = (topSize.toPx() * top()).coerceIn(0f, h / 2) / h
        val b = (bottomSize.toPx() * bottom()).coerceIn(0f, h / 2) / h
        if (t <= 0f && b <= 0f) return@drawWithContent
        val stops = buildList {
            add(0f to if (t > 0f) androidx.compose.ui.graphics.Color.Transparent else androidx.compose.ui.graphics.Color.Black)
            if (t > 0f) add(t to androidx.compose.ui.graphics.Color.Black)
            if (b > 0f) add(1f - b to androidx.compose.ui.graphics.Color.Black)
            add(1f to if (b > 0f) androidx.compose.ui.graphics.Color.Transparent else androidx.compose.ui.graphics.Color.Black)
        }.toTypedArray()
        drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(*stops), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
    }

/** A scrolling list's feather: each edge on while there's more of the list that way, easing on and off. */
@Composable
fun Modifier.feather(state: androidx.compose.foundation.gestures.ScrollableState, topSize: Dp = 20.dp, bottomSize: Dp = 32.dp): Modifier {
    val top by androidx.compose.animation.core.animateFloatAsState(if (state.canScrollBackward) 1f else 0f, tween(Motion.FADE), label = "top")
    val bottom by androidx.compose.animation.core.animateFloatAsState(if (state.canScrollForward) 1f else 0f, tween(Motion.FADE), label = "bottom")
    return feather({ top }, { bottom }, topSize, bottomSize)
}

// a list's, a grid's or a column's feather comes with its scrollbar (every page's long lists at once)
@Composable
fun Modifier.feather(state: androidx.compose.foundation.lazy.LazyListState, topSize: Dp = 20.dp, bottomSize: Dp = 32.dp): Modifier =
    scrollbar(androidx.compose.foundation.rememberScrollbarAdapter(state)).feather(state as androidx.compose.foundation.gestures.ScrollableState, topSize, bottomSize)

@Composable
fun Modifier.feather(state: androidx.compose.foundation.lazy.grid.LazyGridState, topSize: Dp = 20.dp, bottomSize: Dp = 32.dp): Modifier =
    scrollbar(androidx.compose.foundation.rememberScrollbarAdapter(state)).feather(state as androidx.compose.foundation.gestures.ScrollableState, topSize, bottomSize)

@Composable
fun Modifier.feather(state: androidx.compose.foundation.ScrollState, topSize: Dp = 20.dp, bottomSize: Dp = 32.dp): Modifier =
    scrollbar(androidx.compose.foundation.rememberScrollbarAdapter(state)).feather(state as androidx.compose.foundation.gestures.ScrollableState, topSize, bottomSize)

/**
 * A scrollbar at the right edge (2026-11-15, a tester: "scrolling relies entirely on the mouse wheel ... inconvenient
 * with a large number of songs"): a 2 dp line, there only while there's more than fits; brighter under the mouse,
 * ink while held. Drag it, or press anywhere along the edge to go there. Drawn over the feather, never faded by it.
 * In a gutter of its own at the right (2026-11-16, Bronson's screenshot: drawn over the list, it ran through the songs'
 * lengths): what's listed stops 10 dp short of the edge, the line in that.
 */
@Composable
fun Modifier.scrollbar(adapter: androidx.compose.foundation.v2.ScrollbarAdapter): Modifier {
    var over by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val edge = with(density) { 14.dp.toPx() }
    val least = with(density) { 32.dp.toPx() }
    // the thumb: where it starts and how tall, in pixels (null: all of it fits)
    fun thumb(h: Float): Pair<Float, Float>? {
        val content = adapter.contentSize; val view = adapter.viewportSize
        if (content <= view + 1 || h <= 0f) return null
        val len = (h * view / content).toFloat().coerceIn(least.coerceAtMost(h), h)
        val at = (adapter.scrollOffset / (content - view)).toFloat().coerceIn(0f, 1f) * (h - len)
        return at to len
    }
    return this
        .pointerInput(adapter) {
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    val c = e.changes.first()
                    val inEdge = c.position.x >= size.width - edge && thumb(size.height.toFloat()) != null
                    if (e.type == androidx.compose.ui.input.pointer.PointerEventType.Move || e.type == androidx.compose.ui.input.pointer.PointerEventType.Enter) over = inEdge
                    if (e.type == androidx.compose.ui.input.pointer.PointerEventType.Exit) over = false
                    if (e.type != androidx.compose.ui.input.pointer.PointerEventType.Press || !inEdge || !e.buttons.isPrimaryPressed) continue
                    // held: the thumb follows the pointer (pressed off the thumb, it centres there first)
                    val h = size.height.toFloat()
                    val (at, len) = thumb(h) ?: continue
                    val grab = if (c.position.y in at..at + len) c.position.y - at else len / 2
                    fun go(y: Float) {
                        val room = h - len
                        if (room <= 0f) return
                        val f = ((y - grab) / room).coerceIn(0f, 1f)
                        scope.launch { adapter.scrollTo(f * (adapter.contentSize - adapter.viewportSize)) }
                    }
                    held = true
                    go(c.position.y)
                    c.consume()
                    while (true) {
                        val m = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        val mc = m.changes.first()
                        mc.consume()
                        if (!mc.pressed) break
                        go(mc.position.y)
                    }
                    held = false
                }
            }
        }
        .drawWithContent {
            drawContent()
            val (at, len) = thumb(size.height) ?: return@drawWithContent
            val w = 2.dp.toPx()
            drawRect(if (held) Ink.ink else if (over) Ink.mid else Ink.dim,
                androidx.compose.ui.geometry.Offset(size.width - w - 4.dp.toPx(), at), androidx.compose.ui.geometry.Size(w, len))
        }
        .padding(end = SCROLLBAR_GUTTER)
}

/** The room kept at a scrolling list's right for its scrollbar. */
val SCROLLBAR_GUTTER = 10.dp
