package app.ink.music

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
 * the strip's far side, a few dp of blur where it meets the bar. No tint, nothing painted: the
 * content itself, softened. `towardBottom`: the bar is below the strip (the mini player); else above
 * (the tab row, the status bar).
 */
@Composable
fun Modifier.softEdge(state: HazeState, towardBottom: Boolean): Modifier {
    var height by remember { mutableIntStateOf(0) }
    val style = remember(height, towardBottom) {
        val h = height.toFloat().coerceAtLeast(1f)
        HazeBlurStyle {
            blurRadius(4.dp)
            noiseFactor(0f)
            progressive(
                if (towardBottom) HazeProgressive.verticalGradient(startY = 0f, startIntensity = 0f, endY = h, endIntensity = 1f)
                else HazeProgressive.verticalGradient(startY = 0f, startIntensity = 1f, endY = h, endIntensity = 0f),
            )
        }
    }
    return this.onSizeChanged { height = it.height }.hazeBlur(HazeInput.Sources(state), style)
}

/** The soft edge sideways: what slides along a row toward what stands at its end (the tabs, toward "find"). */
@Composable
fun Modifier.softEdgeEnd(state: HazeState): Modifier {
    var width by remember { mutableIntStateOf(0) }
    val style = remember(width) {
        HazeBlurStyle {
            blurRadius(4.dp)
            noiseFactor(0f)
            progressive(HazeProgressive.horizontalGradient(startX = 0f, startIntensity = 0f, endX = width.toFloat().coerceAtLeast(1f), endIntensity = 1f))
        }
    }
    return this.onSizeChanged { width = it.width }.hazeBlur(HazeInput.Sources(state), style)
}
