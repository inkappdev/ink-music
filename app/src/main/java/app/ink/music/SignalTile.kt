package app.ink.music

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One of NTS's mixtapes as a signal tile (2026-11-24, Bronson's pick): black in a hairline, NTS's picture for it small in
 * the dither at its top left (the only color on it), its name big in the middle, and along its foot a line of pixel
 * bars in the picture's color: moving while it's the station on, low and still at 35% otherwise (still under less
 * motion). Under it, ∞ and its line. A tap plays it, and the ones after it, as stations (felt).
 */
@Composable
fun SignalTile(station: Song, said: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val now by Playback.now.collectAsState()
    val less by Moves.less.collectAsState()
    val on = now?.song?.id == station.id
    val playing = on && now?.playing == true
    val art = remember(station.id) { Stations.mixtapeArt(station.id)?.let { faceSong(station.title, it) } }
    val accent = rememberCover(art, 200)?.accent ?: Ink.ink
    Column(modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick))) {
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f).background(Ink.black).border(1.dp, if (on) Ink.mid else Ink.line)) {
            val side = maxWidth
            val inset = side / 14
            art?.let { Covered(it, Modifier.padding(start = inset, top = inset).size(side / 3), px = 200) }
            // its name as big as fits, its longest word whole on a line (as a station's sleeve sizes its own)
            val name = station.title.removePrefix("NTS ").uppercase()
            val longest = name.split(' ').maxOfOrNull { it.length } ?: 1
            val size = minOf(side.value / 7f, (side - inset * 2).value * .9f / (longest * .74f))
            BasicText(name, style = headline(size.sp).copy(lineHeight = (size * 1.02f).sp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = inset, end = inset, top = side / 10))
            Wave(station.id.hashCode(), playing && !less, when { playing -> accent; on -> accent.copy(alpha = .7f); else -> accent.copy(alpha = .35f) },
                Modifier.align(Alignment.BottomStart).padding(start = inset, end = inset, bottom = inset).fillMaxWidth().height(side / 6))
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Infinity(if (on) Ink.ink else Ink.mid)
            BasicText(said.lowercase(), style = pixel(12.sp, if (on) Ink.ink else Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The tile's line: square pixel bars from the foot up, as [SignalLine] says, redrawn each frame only while [moving]. */
@Composable
private fun Wave(seed: Int, moving: Boolean, colour: Color, modifier: Modifier) {
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(moving) {
        if (!moving) return@LaunchedEffect
        val start = withFrameMillis { it } - (t * 1000).toLong()
        while (true) withFrameMillis { t = (it - start) / 1000f }
    }
    val cellDp = LocalDensity.current.run { 2.dp.toPx() }
    Canvas(modifier) {
        val cell = kotlin.math.max(1f, kotlin.math.round(cellDp))
        val n = (size.width / (cell * 2)).toInt().coerceAtLeast(1)
        val rows = (size.height / cell).toInt().coerceAtLeast(1)
        for (i in 0 until n) {
            val level = if (moving) SignalLine.moving(i, n, seed, t) else SignalLine.still(i, n, seed)
            val h = (level * rows).toInt().coerceIn(1, rows) * cell
            drawRect(colour, Offset(i * cell * 2, size.height - h), Size(cell, h))
        }
    }
}

/** ∞ in pixels, a Silkscreen letter's height at 12 sp (the font has none). */
@Composable
private fun Infinity(colour: Color) {
    val dot = LocalDensity.current.run { 12.sp.toDp() } / 8
    Canvas(Modifier.size(dot * 7, dot * 5)) {
        val d = size.width / 7
        SignalLine.INFINITY.forEachIndexed { y, row -> row.forEachIndexed { x, c -> if (c == '#') drawRect(colour, Offset(x * d, y * d), Size(d, d)) } }
    }
}
