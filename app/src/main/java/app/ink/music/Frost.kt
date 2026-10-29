package app.ink.music

import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur

/** How tall the mini player is (0 when nothing plays): lists leave that much room at their foot to scroll under it. */
val LocalFoot = compositionLocalOf { 0.dp }

/**
 * The soft edge (Bronson's call, 2026-10-25): the bars stay black; only what's about to go behind
 * one goes ever so slightly out of focus as it nears it, in a thin strip beside the bar, sharp at
 * the strip's far side, [EDGE] of blur where it meets the bar, feathered (2026-10-27: "barely notice it"):
 * it comes on along [FEATHER], hardly at all till the last of the strip. No tint, nothing painted: the
 * content itself, softened. `towardBottom`: the bar is below the strip (the mini player); else above
 * (the tab row, the status bar). Black under the strip: the softened copy is drawn over the content itself, and a
 * dim word (35%) showed through it twice, brighter, cut off sharp at the strip's edge (2026-10-29, up next).
 */
/** The blur where content meets a bar (4 dp till 2026-10-27, when Bronson wanted to barely notice it). */
private val EDGE = 1.5.dp

/** How the blur comes on across the strip: cubed, so it's nearly nothing till the bar. */
private val FEATHER = Easing { it * it * it }

@Composable
fun Modifier.softEdge(state: HazeState, towardBottom: Boolean): Modifier {
    // moving less (a slower phone): no blur at all, the bar's edge as it is
    if (Moves.less.collectAsState().value) return this
    var height by remember { mutableIntStateOf(0) }
    val style = remember(height, towardBottom) {
        val h = height.toFloat().coerceAtLeast(1f)
        HazeBlurStyle {
            blurRadius(EDGE)
            noiseFactor(0f)
            progressive(
                if (towardBottom) HazeProgressive.verticalGradient(easing = FEATHER, startY = 0f, startIntensity = 0f, endY = h, endIntensity = 1f)
                else HazeProgressive.verticalGradient(easing = { 1f - FEATHER.transform(1f - it) }, startY = 0f, startIntensity = 1f, endY = h, endIntensity = 0f),
            )
        }
    }
    return this.onSizeChanged { height = it.height }.background(Ink.black).hazeBlur(HazeInput.Sources(state), style)
}

/** The soft edge sideways: what slides along a row toward what stands at its end (the tabs, toward "find"). */
@Composable
fun Modifier.softEdgeEnd(state: HazeState): Modifier {
    if (Moves.less.collectAsState().value) return this
    var width by remember { mutableIntStateOf(0) }
    val style = remember(width) {
        HazeBlurStyle {
            blurRadius(EDGE)
            noiseFactor(0f)
            progressive(HazeProgressive.horizontalGradient(easing = FEATHER, startX = 0f, startIntensity = 0f, endX = width.toFloat().coerceAtLeast(1f), endIntensity = 1f))
        }
    }
    return this.onSizeChanged { width = it.width }.background(Ink.black).hazeBlur(HazeInput.Sources(state), style)
}
