package app.ink.music

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Close, wherever there's one: the down chevron, as the phone's (PlayerPage's CLOSE). */
private val CLOSE = listOf("##...##", ".##.##.", "..###..", "...#...")

/** The player's controls as pixels, the phone's: play and pause nine high, back and next seven (at 3 dp). */
val BACK = listOf("#...#", "#..##", "#.###", "#####", "#.###", "#..##", "#...#")
val PLAY = listOf("#....", "##...", "###..", "####.", "#####", "####.", "###..", "##...", "#....")
val PAUSE = listOf("##.##", "##.##", "##.##", "##.##", "##.##", "##.##", "##.##", "##.##", "##.##")
/** The volume's knob: a pixel pill, 4 wide, 7 tall. */
private val KNOB = listOf(".##.", "####", "####", "####", "####", "####", ".##.")
/** Open (the player, the whole window): close's chevron turned up. */
val OPEN_UP = listOf("...#...", "..###..", ".##.##.", "##...##")

/**
 * A cover that opens the player: under the mouse a pixel up arrow on a black tab at its foot, and the hand, so it's
 * plain a click makes it the whole window (Bronson, 2026-10-29).
 */
@Composable
fun Opens(modifier: Modifier, onOpen: () -> Unit, content: @Composable () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val over by src.collectIsHoveredAsState()
    Box(modifier.hoverable(src).pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon(java.awt.Cursor(java.awt.Cursor.HAND_CURSOR)))
        .clickable(src, indication = null, onClick = felt(then = onOpen))) {
        content()
        if (over) Box(Modifier.align(Alignment.BottomCenter).background(Ink.black).padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 4.dp)) {
            androidx.compose.foundation.Canvas(Modifier.size(14.dp, 8.dp)) {
                val cell = size.width / 7
                OPEN_UP.forEachIndexed { r, row -> row.forEachIndexed { c, ch ->
                    if (ch == '#') drawRect(Ink.ink, androidx.compose.ui.geometry.Offset(c * cell, r * cell), androidx.compose.ui.geometry.Size(cell, cell))
                } }
            }
        }
    }
}

val NEXT = listOf("#...#", "##..#", "###.#", "#####", "###.#", "##..#", "#...#")
/** Shuffle and repeat (at 2 dp): two crossing ways; a loop, a lit pixel in it for repeat one. */
private val SHUFFLE = listOf("........#..", "##....#####", "..#..#..#..", "...##......", "..#..#..#..", "##....#####", "........#..")
private val REPEAT = listOf("..#........", ".##########", "#.#.......#", "#.........#", "#.......#.#", "##########.", "........#..")
private val REPEAT_ONE = listOf("..#........", ".##########", "#.#.......#", "#....#....#", "#.......#.#", "##########.", "........#..")
/** The pixel speaker (the phone's): a cabinet, its tweeter over its woofer. */
val SPEAKER = listOf(
    "##########", "#........#", "#...##...#", "#...##...#", "#........#", "#..####..#", "#.#....#.#",
    "#.#.##.#.#", "#.#.##.#.#", "#.#....#.#", "#..####..#", "#........#", "##########",
)
/** Volume's: a small speaker, sounding (or muted, a cross where its sound would be). */
private val LOUD = listOf("...#.....", "..##..#..", "####...#.", "####.#.#.", "####...#.", "..##..#..", "...#.....")
private val MUTED = listOf("...#.....", "..##.....", "####.#.#.", "####..#..", "####.#.#.", "..##.....", "...#.....")
/** Find: a lens and its handle. */
val SEARCH = listOf(".###...", "#...#..", "#...#..", "#...#..", ".####..", ".....#.", "......#")
/** The whole screen: four corners out; and back in. */
val FULL = listOf("###.###", "#.....#", "#.....#", ".......", "#.....#", "#.....#", "###.###")
val UNFULL = listOf("#.....#", "##...##", ".......", ".......", ".......", "##...##", "#.....#")

/** Tuck the now column away (out to the right), and bring it back. */
val TUCK = listOf("#...", "##..", ".##.", "..##", ".##.", "##..", "#...")
val UNTUCK = listOf("...#", "..##", ".##.", "##..", ".##.", "..##", "...#")

/** The off things' brightness among the controls (shuffle, repeat off). */
private val OFF get() = Ink.dim

/**
 * What's played with, drawn (the phone's row): play in the middle, the biggest; back and next either side; shuffle
 * and repeat a size smaller at the ends, lit when on. `small`: back, play and next alone (the foot bar).
 */
@Composable
fun Controls(n: Player.Now, modifier: Modifier = Modifier, small: Boolean = false) {
    val shuffle by Player.shuffle.collectAsState()
    val repeat by Player.repeat.collectAsState()
    val cell = if (small) 2.dp else 3.dp
    Row(modifier, horizontalArrangement = if (small) Arrangement.spacedBy(4.dp) else Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        if (!small) Pixel(SHUFFLE, if (shuffle) "shuffle, on" else "shuffle, off", if (shuffle) Ink.ink else OFF) { Player.shuffle() }
        Pixel(BACK, "back", Ink.ink, cell = cell) { Player.previous() }
        Pixel(if (n.playing) PAUSE else PLAY, if (n.playing) "pause" else "play", Ink.ink, cell = cell) { Player.toggle() }
        Pixel(NEXT, "next", Ink.ink, cell = cell) { Player.next() }
        if (!small) Pixel(
            if (repeat == 2) REPEAT_ONE else REPEAT,
            when (repeat) { 2 -> "repeat one"; 0 -> "repeat, off"; else -> "repeat, on" },
            if (repeat != 0) Ink.ink else OFF,
        ) { Player.repeat() }
    }
}

/**
 * Volume: the small speaker (a click mutes), and a line filling to the level as the song's line fills (click or
 * drag along it), the level beside it. On a speaker, the speaker's own. ↑ and ↓ step it too.
 */
@Composable
fun Volume(modifier: Modifier = Modifier, line: Dp = 120.dp) {
    val mine by Player.volume.collectAsState()
    val muted by Player.muted.collectAsState()
    val speaker by Casting.volume.collectAsState()
    val on by Casting.on.collectAsState()
    val level = if (on != null) ((speaker ?: 0f) * 100).toInt() else mine
    var dragging by remember { mutableStateOf<Int?>(null) }
    val shown = dragging ?: level
    val quiet = on == null && muted
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Pixel(if (quiet || shown == 0) MUTED else LOUD, if (quiet) "unmute" else "mute", if (quiet) OFF else Ink.mid) {
            if (on == null) Player.mute() else Player.volume(0)
        }
        val src = remember { MutableInteractionSource() }
        val over by src.collectIsHoveredAsState()
        Box(
            Modifier.width(line).height(17.dp).hoverable(src)
                .pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon(java.awt.Cursor(java.awt.Cursor.HAND_CURSOR)))
                .pointerInput(Unit) { detectTapGestures { Player.volume((it.x / size.width * 100).toInt()) } }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = (it.x / size.width * 100).toInt().coerceIn(0, 100) },
                        onDragEnd = { dragging?.let(Player::volume); dragging = null },
                        onDragCancel = { dragging = null },
                    ) { c, _ -> c.consume(); dragging = (c.position.x / size.width * 100).toInt().coerceIn(0, 100) }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line)) {
                Box(Modifier.fillMaxWidth(if (quiet) 0f else shown / 100f).height(1.dp).background(if (dragging != null) Ink.ink else Ink.mid))
            }
            // the knob to take hold of (Bronson, 2026-10-29: "an easy thing to grab"), on Silkscreen's grid like the
            // controls: a pixel pill at the line's end, a step brighter under the mouse, ink while dragged
            val at = if (quiet) 0f else shown / 100f
            val knob = if (dragging != null) Ink.ink else if (over) brighter(Ink.mid) else Ink.mid
            androidx.compose.foundation.Canvas(Modifier.offset(x = line * at - 4.dp).size(8.dp, 14.dp)) {
                val cell = size.width / 4
                KNOB.forEachIndexed { r, row -> row.forEachIndexed { c, ch ->
                    if (ch == '#') drawRect(knob, androidx.compose.ui.geometry.Offset(c * cell, r * cell), androidx.compose.ui.geometry.Size(cell, cell))
                } }
            }
        }
        Spacer(Modifier.width(10.dp))
        BasicText(if (quiet) "muted" else "$shown", style = pixel(12.sp, if (dragging != null) Ink.ink else Ink.mid))
    }
}

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
