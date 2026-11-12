package app.ink.music

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Settings as rows (2026-11-11, Bronson: "its still a lot of reading"): each setting one row, its name and what it's set
 * to (lit); a tap opens it in place, a line of why and its words, and closes it again. One open at a time on a page
 * ([Opened]); a hairline between rows, one after the last ([RowsEnd]). A heading, a paragraph and the words for every
 * setting made a page of ten settings ten paragraphs; most visits only need what each is set to.
 */
class Opened {
    var name by mutableStateOf<String?>(null)
}

@Composable
fun rememberOpened() = remember { Opened() }

@Composable
fun SettingRow(name: String, said: String, opened: Opened, why: String? = null, content: @Composable () -> Unit) {
    val open = opened.name == name
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).tvLit()
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { opened.name = if (open) null else name })
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(name, style = headline(18.sp), modifier = Modifier.padding(end = 16.dp))
            // what it's set to: lit while closed (that's what's read), dim while its words are open under it
            BasicText(said, style = pixel(13.sp, if (open) Ink.dim else Ink.ink).copy(textAlign = TextAlign.End), maxLines = 2,
                modifier = Modifier.weight(1f))
        }
        AnimatedVisibility(open,
            enter = expandVertically(tween(Motion.ENTER, easing = Motion.expo)) + fadeIn(tween(Motion.ENTER, easing = Motion.expo)),
            exit = shrinkVertically(tween(Motion.EXIT, easing = Motion.expo)) + fadeOut(tween(Motion.EXIT, easing = Motion.expo))) {
            Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
                why?.let { BasicText(it, style = pixel(12.sp, Ink.mid)); Spacer(Modifier.height(10.dp)) }
                content()
            }
        }
    }
}

/** The hairline under a page's last row. */
@Composable
fun RowsEnd() = Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line))

/** A row of words to choose from, the chosen one lit. */
@Composable
fun Pick(words: List<Pair<String, Boolean>>, pick: (Int) -> Unit) {
    FlowRow {
        words.forEachIndexed { i, (w, lit) ->
            Word(w, pixel(14.sp, if (lit) Ink.ink else Ink.dim), Modifier.padding(end = 22.dp, bottom = 4.dp), touch = 8.dp) { pick(i) }
        }
    }
}

/** A small dim label over a group of words inside an open row ("on wifi", "on mobile data"). */
@Composable
fun PickLabel(text: String) {
    Spacer(Modifier.height(8.dp))
    BasicText(text, style = pixel(12.sp, Ink.mid))
    Spacer(Modifier.height(4.dp))
}
