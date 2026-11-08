package app.ink.music

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A mix as it's found (the phone's MixFinding): its tempo big, beating at itself, four squares stepping beside it (bar
 * one the brightest), the songs found dropping in as small covers, the newest first; how far it's read, small. A list
 * mixed has no tempo of its own: MIX, beating at 120.
 */
@Composable
fun MixFinding(st: TempoMixes.State) {
    val period = 60_000.0 / (if (st.bpm > 0) st.bpm else 120)
    var t by remember { mutableStateOf(0L) }
    LaunchedEffect(st.bpm) {
        val t0 = System.currentTimeMillis()
        while (true) androidx.compose.runtime.withFrameMillis { t = System.currentTimeMillis() - t0 }
    }
    val beats = t / period
    val step = (Math.floor(beats).toLong() % 4).toInt()
    val within = (beats - Math.floor(beats)).toFloat()
    Row(verticalAlignment = Alignment.Bottom) {
        BasicText(if (st.bpm > 0) "${st.bpm}" else "MIX", style = headline(72.sp, lerp(Ink.mid, Ink.ink, 1f - within)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.padding(bottom = 14.dp)) {
            Canvas(Modifier.width(52.dp).height(7.dp)) {
                val sq = 7.dp.toPx(); val gap = (size.width - 4 * sq) / 3
                for (i in 0 until 4) {
                    val lit = if (i == step) 1f - within * .7f else 0f
                    drawRect(lerp(if (i == 0) Ink.mid else Ink.dim, Ink.ink, lit), Offset(i * (sq + gap), 0f), Size(sq, sq))
                }
            }
            Spacer(Modifier.height(6.dp))
            BasicText(if (st.bpm > 0) "BPM" else "IN STEP", style = pixel(12.sp, Ink.mid))
        }
    }
    Spacer(Modifier.height(12.dp))
    if (st.songs.isNotEmpty()) {
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), userScrollEnabled = false) {
            items(st.songs.reversed().take(16), key = { it.id }) { s -> Box(Modifier.size(48.dp).animateItem()) { CoverOf(s, s.title, Modifier.fillMaxSize(), px = 160) } }
        }
        Spacer(Modifier.height(10.dp))
    }
    BasicText(
        when {
            st.bpm == 0 && st.done -> "${st.found} of ${st.of} in step, slowest to fastest, playing"
            st.bpm == 0 -> if (st.playing) "playing, building the set: ${st.read} of ${st.of} read" else "reading tempos: ${st.read} of ${st.of}"
            st.done && st.found == 0 -> "none at ${st.bpm} bpm here. another tempo?"
            st.done -> "${st.found} songs at ${st.bpm} bpm, mixing"
            st.playing -> "${st.found} found, playing. reading ${st.read} of ${st.of}"
            else -> "listening for ${st.bpm}: ${st.read} of ${st.of} read"
        },
        style = pixel(12.sp, Ink.mid),
    )
}

/** A tempo chosen, and a mix begun at it (a genre's MIX IT, your music's): the tempos as words, START THE MIX, the mix as it's found. */
@Composable
fun MixAt(title: String, heading: String, line: String, start: (Int) -> Unit) {
    val st by TempoMixes.state.collectAsState()
    val ours = st?.takeIf { it.title.equals(title, true) && it.bpm > 0 }
    var bpm by remember(title) { mutableStateOf(ours?.bpm ?: 125) }
    Column {
        Section(heading, line)
        Row {
            for (t in TempoMixes.TEMPOS) Word("$t", pixel(14.sp, if (bpm == t) Ink.ink else Ink.dim), Modifier.padding(end = 20.dp), touch = 8.dp) { bpm = t }
        }
        Spacer(Modifier.height(12.dp))
        Word(if (ours != null && !ours.done && ours.bpm == bpm) "MIXING" else "START THE MIX", headline(28.sp), touch = 8.dp) { start(bpm) }
        ours?.let { Spacer(Modifier.height(16.dp)); MixFinding(it) }
    }
}

/** "mix" beside play and shuffle (a list): played as a DJ's arc by tempo and key (TempoMixes.list). */
@Composable
fun MixWord(title: String, songs: List<Song>?, modifier: Modifier = Modifier) {
    if (songs.isNullOrEmpty() || songs.size < 3) return
    val st by TempoMixes.state.collectAsState()
    val going = st?.let { it.title == title && it.bpm == 0 && !it.done } == true
    Word(if (going) "mixing" else "mix", pixel(14.sp, if (going) Ink.ink else Ink.mid), modifier, touch = 8.dp) { TempoMixes.list(title, songs) }
}

/** Under a list's words while it's mixed: the mix as it's found. */
@Composable
fun MixProgress(title: String) {
    val st by TempoMixes.state.collectAsState()
    val s = st?.takeIf { it.title == title && it.bpm == 0 } ?: return
    Column(Modifier.padding(top = 16.dp)) { MixFinding(s) }
}
