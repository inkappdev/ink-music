package app.ink.music

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Pull down to find, as ink's launcher does (2026-11-09): a tab pulled down past the top of its list follows the
 * finger at half its travel; past `reach` a light tick says letting go will open find, and back above it another;
 * let go past it and find opens, else it springs back. Only the finger's own drag pulls: a fling that reaches the top
 * stops there.
 */
class Pull(val reach: Float) {
    var px by mutableFloatStateOf(0f)
    val past get() = px >= reach

    /** Follow the finger at half its travel (resistance), never above zero. */
    fun drag(dy: Float) { px = (px + dy / 2).coerceAtLeast(0f) }

    suspend fun release() = animate(px, 0f, animationSpec = tween(Motion.ENTER, easing = Motion.expo)) { v, _ -> px = v }
}

/** A pull, and the nested scroll that feeds it from whatever list is under the finger (`find`: what letting go opens). */
@Composable
fun rememberPull(find: () -> Unit): Pair<Pull, NestedScrollConnection> {
    val reach = with(LocalDensity.current) { 84.dp.toPx() }
    val pull = remember { Pull(reach) }
    val open by rememberUpdatedState(find)
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    // the tick as it crosses, each way (the first value, at rest, says nothing)
    LaunchedEffect(pull) {
        snapshotFlow { pull.past }.distinctUntilChanged().drop(1).collect {
            haptic.performHapticFeedback(if (it) HapticFeedbackType.GestureThresholdActivate else HapticFeedbackType.SegmentFrequentTick)
        }
    }
    val connection = remember(pull) {
        object : NestedScrollConnection {
            // pulled, a drag back up gives back the pull before the list moves
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (pull.px <= 0f || available.y >= 0f) return Offset.Zero
                val used = maxOf(available.y, -pull.px)
                pull.drag(used * 2)
                return Offset(0f, used)
            }

            // what the list couldn't use going down (it's at its top): the pull's
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                pull.drag(available.y)
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pull.px <= 0f) return Velocity.Zero
                if (pull.past) { haptic.performHapticFeedback(HapticFeedbackType.Confirm); open() }
                scope.launch { pull.release() }
                return available
            }
        }
    }
    return pull to connection
}

/** "pull to find" coming in with the pull, lit once letting go would open it. */
@Composable
fun PullHint(pull: Pull, modifier: Modifier = Modifier) {
    val c by animateColorAsState(if (pull.past) Ink.ink else Ink.mid, tween(Motion.FADE), label = "pull")
    BasicText(
        if (pull.past) "let go to find" else "pull to find", style = pixel(12.sp, c),
        modifier = modifier.padding(horizontal = 20.dp, vertical = 14.dp)
            .graphicsLayer { alpha = (pull.px / pull.reach).coerceIn(0f, 1f); translationY = pull.px / 3 - 12.dp.toPx() * (1 - (pull.px / pull.reach).coerceIn(0f, 1f)) },
    )
}
