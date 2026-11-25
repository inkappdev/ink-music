package app.ink.music

import androidx.compose.foundation.clickable
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
 * Your signature on listen (2026-11-25, #16): its name big, a tap plays it; how familiar (yours, both, new) and a mood (any,
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
    val moods = remember(d.mood) { (InkMoods.now() + InkMoods.ALL.filter { it.key == d.mood }).distinctBy { it.key } }
    Column(Modifier.padding(horizontal = 20.dp).padding(top = 6.dp, bottom = 18.dp)) {
        Row(Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { if (!on) YourSignature.start(ctx) else if (now?.playing != true) Playback.toggle() }),
            verticalAlignment = Alignment.Bottom) {
            BasicText("SIGNATURE", style = headline(30.sp), modifier = Modifier.weight(1f))
            Bars(on && now?.playing == true, if (on) Ink.ink else Ink.dim, Modifier.padding(bottom = 6.dp))
        }
        BasicText(when {
            starting -> "gathering your songs"
            on && now?.playing == true -> "playing. skip to steer it, love one for more like it"
            on -> "paused. tap to go on"
            else -> "made from you, never runs out. tap to play"
        }, style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(10.dp))
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
            }
        }
    }
}
