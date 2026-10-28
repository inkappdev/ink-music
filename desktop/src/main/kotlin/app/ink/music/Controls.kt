package app.ink.music

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Close, wherever there's one: the down chevron, as the phone's (PlayerPage's CLOSE). */
private val CLOSE = listOf("##...##", ".##.##.", "..###..", "...#...")

@Composable
fun CloseArrow(modifier: Modifier = Modifier, onClick: () -> Unit) = Pixel(CLOSE, "close", Ink.mid, modifier, onClick = onClick)

/** A pixel control: a step brighter under the mouse, dimmer while pressed (as a word is), its name for a screen reader. */
@Composable
fun Pixel(rows: List<String>, name: String, colour: Color, modifier: Modifier = Modifier, cell: Dp = 2.dp, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val over by src.collectIsHoveredAsState()
    val c by animateColorAsState(if (pressed) Ink.mid else if (over) brighter(colour) else colour, tween(Motion.FADE), label = name)
    Box(
        modifier.semantics { contentDescription = name; role = androidx.compose.ui.semantics.Role.Button }
            .hoverable(src).clickable(src, indication = null, onClick = felt(then = onClick)).padding(horizontal = cell * 4, vertical = cell * 6),
    ) { Sprite(rows, c, cell) }
}

/** One step up ink's brightness: off (35%) to 70%, 70% to ink. */
fun brighter(c: Color): Color = when {
    c.alpha < Ink.mid.alpha - .01f -> c.copy(alpha = Ink.mid.alpha)
    else -> c.copy(alpha = 1f)
}
