package app.ink.music

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * A row of covers sliding sideways, for a mouse as well as a trackpad (2026-11-09: with a wheel alone there was no way
 * past the edge). Over it, a < and > at its ends turn it a page at a time, each only where there's more that way (always
 * there, lit; the square turns ink under the pointer), level with the middle of the covers (`cover`: a cover's width
 * over its height; a wide video frame is 16 / 9), not the middle of the covers and their words; a
 * trackpad's sideways swipe and shift with the wheel slide it as before. The wheel alone still scrolls the page: a shelf
 * that caught it would stop the page under the pointer.
 */
@Composable
fun Shelf(
    modifier: Modifier = Modifier,
    gap: Dp = 20.dp,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    cover: Float = 1f,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        // a page: the shelf's width less a cover's worth, so the last one seen comes round to the start
        val page = with(density) { (maxWidth * .8f).toPx() }
        LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = verticalAlignment, content = content)
        // the covers' middle: a tile's width (as laid out) over the covers' shape, halved, less half a turn
        val tile = state.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 0
        val at = with(density) { (tile / cover / 2f).toDp() } - 26.dp
        Turn("<", state.canScrollBackward, Modifier.align(Alignment.TopStart).offset(y = at.coerceAtLeast(0.dp))) {
            scope.launch { state.animateScrollBy(-page, tween(Motion.ENTER, easing = Motion.expo)) }
        }
        Turn(">", state.canScrollForward, Modifier.align(Alignment.TopEnd).offset(y = at.coerceAtLeast(0.dp))) {
            scope.launch { state.animateScrollBy(page, tween(Motion.ENTER, easing = Motion.expo)) }
        }
    }
}

/**
 * A shelf of [count] tiles, [tile] wide at least, that reaches across when they'd all fit (2026-11-10: five stations on the
 * air stopped halfway across a wide window): each grown to its share of the width, up to [most]; more than fit, each
 * [tile] wide and sliding, as any shelf. [content] is given the width for each tile.
 */
@Composable
fun Shelf(
    count: Int,
    tile: Dp,
    modifier: Modifier = Modifier,
    gap: Dp = 20.dp,
    most: Dp = tile * 1.5f,
    cover: Float = 1f,
    content: LazyListScope.(Dp) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val share = if (count > 0) (maxWidth - gap * (count - 1)) / count else tile
        val each = if (share >= tile) minOf(share, most) else tile
        Shelf(gap = gap, cover = cover) { content(each) }
    }
}

/**
 * A shelf's turns over something else that slides sideways (2026-11-10: the genres' two rows had no way across with a
 * mouse): < and > at its ends, level with its middle, a page at a time, each only where there's more that way.
 */
@Composable
fun Turned(state: androidx.compose.foundation.gestures.ScrollableState, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val page = with(density) { (maxWidth * .8f).toPx() }
        content()
        Turn("<", state.canScrollBackward, Modifier.align(Alignment.CenterStart)) {
            scope.launch { state.animateScrollBy(-page, tween(Motion.ENTER, easing = Motion.expo)) }
        }
        Turn(">", state.canScrollForward, Modifier.align(Alignment.CenterEnd)) {
            scope.launch { state.animateScrollBy(page, tween(Motion.ENTER, easing = Motion.expo)) }
        }
    }
}

/** One of a shelf's turns: the word in ink on a black square over the covers' edge; under the pointer, black on ink. */
@Composable
private fun Turn(word: String, shown: Boolean, modifier: Modifier, onClick: () -> Unit) {
    AnimatedVisibility(shown, modifier, enter = fadeIn(tween(Motion.FADE)), exit = fadeOut(tween(Motion.EXIT))) {
        val src = remember { MutableInteractionSource() }
        val over by src.collectIsHoveredAsState()
        val back by androidx.compose.animation.animateColorAsState(if (over) Ink.ink else Ink.black, tween(Motion.FADE), label = "turn")
        val fore by androidx.compose.animation.animateColorAsState(if (over) Ink.black else Ink.ink, tween(Motion.FADE), label = "word")
        Box(Modifier.size(52.dp).background(back).hoverable(src)
            .clickable(src, indication = null, onClick = felt(then = onClick)), contentAlignment = Alignment.Center) {
            BasicText(word, style = pixel(22.sp, fore))
        }
    }
}
