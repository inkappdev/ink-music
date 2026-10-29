package app.ink.music

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The update, at the top of listen, as the phone's: no banner, no badge; the version big, as a record's name is, what's
 * new under it in the changelog's own words (three, and the rest a word away), then UPDATE. Coming down, UPDATE gives
 * way to how much, and a hairline under it fills in ink. Going in, ink music closes and opens again.
 */
@Composable
fun UpdateCard(u: Update) {
    val step by Updates.step.collectAsState()
    val now by Player.now.collectAsState()
    var all by remember(u.version) { mutableStateOf(false) }
    val shown = if (all) u.notes else u.notes.take(3)
    Column(Modifier.widthIn(max = 640.dp).animateContentSize(tween(Motion.ENTER, easing = Motion.expo))) {
        BasicText("new ink music", style = pixel(12.sp, Ink.mid))
        BasicText(u.version, style = headline(56.sp), maxLines = 1, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
        shown.forEach { BasicText(it.lowercase(), style = pixel(13.sp, Ink.mid), modifier = Modifier.padding(bottom = 6.dp)) }
        if (!all && u.notes.size > 3) Word("${u.notes.size - 3} more", pixel(13.sp, Ink.mid), Modifier.offset(x = (-4).dp), touch = 8.dp) { all = true }
        Row(Modifier.padding(top = 12.dp).offset(x = (-4).dp), verticalAlignment = Alignment.CenterVertically) {
            when (val s = step) {
                is Updating.Down -> BasicText("downloading ${(s.part * 100).toInt()}%", style = pixel(13.sp))
                Updating.Installing -> BasicText("installing: ink music opens again in a moment", style = pixel(13.sp))
                is Updating.Failed -> {
                    Word("TRY AGAIN", headline(20.sp), Modifier.padding(end = 22.dp), touch = 8.dp) { Updates.take() }
                    BasicText(s.why, style = pixel(13.sp, Ink.mid))
                }
                Updating.Offered -> {
                    Word("UPDATE", headline(20.sp), Modifier.padding(end = 22.dp), touch = 8.dp) { Updates.take() }
                    Word("not now", pixel(13.sp, Ink.mid), touch = 8.dp) { Updates.notNow() }
                }
            }
        }
        // the hairline, filling as it comes down (full while it goes in)
        val part = when (val s = step) { is Updating.Down -> s.part; Updating.Installing -> 1f; else -> 0f }
        val drawn by animateFloatAsState(part, tween(Motion.ENTER, easing = Motion.expo), label = "update")
        if (step is Updating.Down || step == Updating.Installing) Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(1.dp).background(Ink.line)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(drawn).background(Ink.ink))
        }
        // installing closes ink music, and the music with it: said before, not found out
        if (now?.playing == true && step == Updating.Offered) BasicText("the music stops a moment while it installs",
            style = pixel(13.sp, Ink.mid), modifier = Modifier.padding(top = 10.dp))
    }
}
