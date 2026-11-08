package app.ink.music

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The songs you love (the phone's Loved): a YouTube song's is its like on YouTube Music (your liked songs read in),
 * a server song's its star there. Kept in loved.json.
 */
object Loved {
    private val file get() = File(Here.dir, "loved.json")
    val ids = MutableStateFlow(runCatching { JSONArray(File(Here.dir, "loved.json").readText()).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } }.getOrDefault(emptySet()))
    private fun keep() { runCatching { Keep.write(file, JSONArray(ids.value.toList()).toString()) } }

    /** Your YouTube Music likes read in (with the library). */
    fun liked(songs: List<Song>) { val more = songs.map { it.id }.toSet(); if (!ids.value.containsAll(more)) { ids.value = ids.value + more; keep() } }

    fun toggle(s: Song) {
        val on = s.id !in ids.value
        ids.value = if (on) ids.value + s.id else ids.value - s.id
        keep()
        Here.work.launch {
            when (s.kind) {
                "youtube" -> runCatching { Tube.api.like(s.id, on) }
                "server" -> Server.star(s.id, on)
            }
        }
    }
}

/** The desktop bar's heart (waybar/heart/make-hearts.py), 7 x 6 on Silkscreen's grid. */
val HEART = listOf(".##.##.", "#######", "#######", ".#####.", "..###..", "...#...")

/** The heart's red, the desktop's: shown only a moment after loving, the one colour that isn't the art. */
private val RED = Color(0xFFE8302A)

/** A song's heart, as the phone's: 35% while not loved; loving it, red two seconds, then ink. A tap loves or unloves. */
@Composable
fun Heart(song: Song?, cell: Dp = 3.dp, modifier: Modifier = Modifier) {
    val loved by Loved.ids.collectAsState()
    val on = song != null && song.id in loved
    var red by remember { mutableStateOf(false) }
    var lovedAt by remember { mutableStateOf(0L) }
    LaunchedEffect(lovedAt) { if (lovedAt > 0) { red = true; delay(2_000); red = false } }
    LaunchedEffect(on) { if (!on) red = false }
    val colour by animateColorAsState(when { red -> RED; on -> Ink.ink; else -> Ink.dim },
        tween(if (red) Motion.FADE else Motion.ENTER, easing = Motion.expo), label = "heart")
    val haptic = LocalHapticFeedback.current
    Box(modifier.clickable(remember { MutableInteractionSource() }, indication = null) {
        val s = song?.takeIf { it.kind == "youtube" || it.kind == "server" } ?: return@clickable
        haptic.performHapticFeedback(if (s.id in loved) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
        if (s.id !in loved) lovedAt = nowMs()
        Loved.toggle(s)
    }.padding(8.dp), contentAlignment = Alignment.Center) { Sprite(HEART, colour, cell) }
}
