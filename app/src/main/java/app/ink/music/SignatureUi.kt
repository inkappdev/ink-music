package app.ink.music

import androidx.compose.foundation.clickable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Your signature on listen (2026-11-25, #16): in a hairline frame, its name big and PLAY bigger; how familiar (yours, both, new) and a mood (any,
 * or one of the three for now) as words, the chosen lit; while it plays, its bars move and it says how to steer it.
 * Just your music, or the play build: no moods (their songs are found on YouTube Music).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SignatureCard() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { YourSignature.load(ctx) }
    val d by YourSignature.dials.collectAsState()
    val starting by YourSignature.starting.collectAsState()
    val first by YourSignature.first.collectAsState()
    val queue by Playback.queue.collectAsState()
    val now by Playback.now.collectAsState()
    val on = first != null && queue.firstOrNull()?.song?.id == first
    val playing = on && now?.playing == true
    // the three for the hour (and the one chosen); "more" shows all twelve in place
    var all by remember { mutableStateOf(false) }
    val moods = remember(d.mood, all) { if (all) InkMoods.ALL else (InkMoods.now() + InkMoods.ALL.filter { it.key == d.mood }).distinctBy { it.key } }
    fun go() { if (!on) YourSignature.start(ctx) else Playback.toggle() }
    // in a hairline frame, as listen's doors and radio's tiles are: the one thing up here that plays at a tap
    Column(Modifier.padding(horizontal = 20.dp).padding(top = 6.dp, bottom = 18.dp).fillMaxWidth().border(1.dp, Ink.line).padding(16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            BasicText("SIGNATURE", style = headline(30.sp), modifier = Modifier.weight(1f))
            Bars(playing, if (on) Ink.ink else Ink.dim, Modifier.padding(top = 2.dp))
        }
        BasicText(when {
            starting -> "gathering your songs"
            playing -> "skip to steer it, love one for more like it"
            on -> "paused where you left it"
            else -> "your taste, written out. it never ends"
        }, style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            SignatureMix.Familiar.entries.forEach { f ->
                Word(f.word, pixel(14.sp, if (d.familiar == f) Ink.ink else Ink.dim), touch = 8.dp) { YourSignature.turn(ctx, d.copy(familiar = f)) }
            }
        }
        if (!JustYours.on && YouTubeMusic.available) {
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Word("any mood", pixel(14.sp, if (d.mood.isEmpty()) Ink.ink else Ink.dim), touch = 8.dp) { YourSignature.turn(ctx, d.copy(mood = "")) }
                moods.forEach { m ->
                    Word(m.name, pixel(14.sp, if (d.mood == m.key) Ink.ink else Ink.dim), touch = 8.dp) {
                        YourSignature.turn(ctx, d.copy(mood = if (d.mood == m.key) "" else m.key))
                    }
                }
                Word(if (all) "fewer" else "more", pixel(14.sp, Ink.mid), touch = 8.dp) { all = !all }
            }
        }
        // the big word: play, pause, or finding while its songs are gathered
        Spacer(Modifier.height(6.dp))
        Word(when { starting -> "FINDING"; playing -> "PAUSE"; else -> "PLAY" }, headline(34.sp, if (starting) Ink.dim else Ink.ink), touch = 8.dp) { if (!starting) go() }
    }
}
