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
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A newer ink music: its version, what's new in it (the changelog's lines), where its APK is. */
data class Update(val version: String, val notes: List<String>, val apk: String, val name: String, val sums: String?, val size: Long)

/** Where taking an update is: offered, coming down (how much of it), with the phone's installer, or failed (why). */
sealed interface Updating {
    data object Offered : Updating
    data class Down(val part: Float) : Updating
    data object Asking : Updating
    data class Failed(val why: String) : Updating
}

/**
 * The update, at the top of listen: no banner, no badge; the version big, as a record's name is,
 * what's new under it in the changelog's own words (three, and the rest a word away), then UPDATE.
 * Coming down, UPDATE gives way to how much, and a hairline under it fills in ink: the progress
 * line's shape with no album to colour it.
 */
@Composable
fun UpdateCard(u: Update) {
    val ctx = LocalContext.current
    val step by Updates.step.collectAsState()
    val now by Playback.now.collectAsState()
    var all by remember(u.version) { mutableStateOf(false) }
    val shown = if (all) u.notes else u.notes.take(3)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 22.dp).animateContentSize(tween(Motion.ENTER, easing = Motion.expo))) {
        BasicText("new ink music", style = pixel(12.sp, Ink.mid))
        BasicText(u.version, style = headline(44.sp), maxLines = 1, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
        shown.forEach { BasicText(it.lowercase(), style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(bottom = 5.dp)) }
        if (!all && u.notes.size > 3) Word("${u.notes.size - 3} more", pixel(12.sp, Ink.mid), Modifier.offset(x = (-4).dp), touch = 8.dp) { all = true }
        // the words' reach pulled back, so they line up with the version over them
        // 4 dp left, so UPDATE's touch room lines up with the words above; plain words in it take the 4 dp back
        Row(Modifier.padding(top = 10.dp).offset(x = (-4).dp), verticalAlignment = Alignment.CenterVertically) {
            when (val s = step) {
                is Updating.Down -> BasicText("downloading ${(s.part * 100).toInt()}%", style = pixel(12.sp), modifier = Modifier.padding(start = 4.dp))
                Updating.Asking -> BasicText("installing", style = pixel(12.sp), modifier = Modifier.padding(start = 4.dp))
                is Updating.Failed -> {
                    Word("TRY AGAIN", headline(18.sp), Modifier.padding(end = 18.dp), touch = 8.dp) { Updates.take(ctx) }
                    BasicText(s.why, style = pixel(12.sp, Ink.mid))
                }
                Updating.Offered -> {
                    Word("UPDATE", headline(18.sp), Modifier.padding(end = 18.dp), touch = 8.dp) { Updates.take(ctx) }
                    Word("not now", pixel(12.sp, Ink.mid), touch = 8.dp) { Updates.notNow(ctx) }
                }
            }
        }
        // the hairline, filling as it comes down (full while the installer asks)
        val part = when (val s = step) { is Updating.Down -> s.part; Updating.Asking -> 1f; else -> 0f }
        val drawn by animateFloatAsState(part, tween(Motion.ENTER, easing = Motion.expo), label = "update")
        if (step is Updating.Down || step == Updating.Asking) Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(1.dp).background(Ink.line)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(drawn).background(Ink.ink))
        }
        // installing restarts ink music, and the music with it: said before, not found out
        if (now?.playing == true && step == Updating.Offered) BasicText("the music stops a moment while it installs",
            style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 8.dp))
    }
}
