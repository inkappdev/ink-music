package app.ink.music

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A row of words that steps its words down, 5% at a time, till the row fits its width: never under [least] of
 * their size (12 sp, the least that's read, over the row's own size). At Android's own text and display size the
 * tabs and the player's rows were wider than a phone (they'd been set at a smaller one): the last word went off the
 * screen or to "...". Past [least] the row is as it would have been. With Android's text set larger, [least] is of its
 * default size.
 */
@Composable
fun Fit(modifier: Modifier = Modifier, least: Float = .75f, content: @Composable () -> Unit) {
    val d = LocalDensity.current
    // the floor is 12 sp as Android sets text by default: with its text made larger (1.3x), the row may come down
    // to that rather than end in "..."
    val floor = (least / d.fontScale.coerceAtLeast(1f)).coerceAtMost(1f)
    SubcomposeLayout(modifier) { c ->
        fun at(scale: Float, key: Any) = subcompose(key) {
            CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale * scale)) { content() }
        }
        // measured as wide as it likes: its own width
        var scale = 1f
        var step = 0
        while (scale > floor + .001f) {
            val wide = at(scale, step).map { it.measure(Constraints(maxHeight = c.maxHeight)) }
            if ((wide.maxOfOrNull { it.width } ?: 0) <= c.maxWidth) break
            scale = (scale - .05f).coerceAtLeast(floor); step++
        }
        val placed = at(scale, "fit").map { it.measure(c.copy(minHeight = 0)) }
        layout(c.maxWidth, placed.maxOfOrNull { it.height } ?: 0) { placed.forEach { it.place(0, 0) } }
    }
}

/** How tall the player's cover may be (the player's own measure: so the song's words below it all show). */
val LocalCoverRoom = compositionLocalOf { Dp.Infinity }

/**
 * The player's top row: [start] at the start, [end] at the end, and [middle] dead centre, unless a long word on
 * either side would run into it: then midway between them.
 */
@Composable
fun Centred(start: @Composable () -> Unit, middle: @Composable () -> Unit, end: @Composable () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.ui.layout.Layout(listOf(start, middle, end), modifier) { (s, m, e), c ->
        val loose = c.copy(minWidth = 0, minHeight = 0)
        val ps = s.first().measure(loose)
        val pe = e.first().measure(loose)
        val pm = m.first().measure(loose)
        val h = maxOf(ps.height, pm.height, pe.height)
        // measured unbounded (Fit asking its own width): the three with a little room between
        val w = if (c.hasBoundedWidth) c.maxWidth else ps.width + pm.width + pe.width + 2 * 12.dp.roundToPx()
        layout(w, h) {
            val gapStart = ps.width
            val gapEnd = w - pe.width
            val centre = (w - pm.width) / 2
            val x = if (centre >= gapStart && centre + pm.width <= gapEnd) centre else (gapStart + gapEnd - pm.width) / 2
            ps.place(0, (h - ps.height) / 2)
            pm.place(x, (h - pm.height) / 2)
            pe.place(w - pe.width, (h - pe.height) / 2)
        }
    }
}
