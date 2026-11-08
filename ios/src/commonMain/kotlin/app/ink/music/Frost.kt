package app.ink.music

import androidx.compose.animation.core.Easing
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.rememberHazeState

/** What the places draw, for the bars beside them to soften (the mini player's strip reads it). */
val LocalFrost = staticCompositionLocalOf<HazeState?> { null }

/** The blur where content meets a bar, as the phone's: barely noticed. */
private val EDGE = 1.5.dp

/** How the blur comes on across the strip: cubed, so it's nearly nothing till the bar. */
private val FEATHER = Easing { it * it * it }

/**
 * The soft edge, as the phone's (Frost.kt there): the bars stay black; what's about to go behind one goes ever so
 * slightly out of focus in a thin strip ([BAND]) beside it, sharp at the strip's far side. No tint, nothing painted.
 * `towardBottom`: the bar is below the strip (the mini player); else above.
 */
@Composable
fun Modifier.softEdge(state: HazeState, towardBottom: Boolean): Modifier {
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

/** A page's own frost, lent to the mini player while the page is on top. */
@Composable
fun rememberFrost(): HazeState {
    val f = rememberHazeState()
    DisposableEffect(f) {
        Pages.frost = f
        onDispose { if (Pages.frost === f) Pages.frost = null }
    }
    return f
}
