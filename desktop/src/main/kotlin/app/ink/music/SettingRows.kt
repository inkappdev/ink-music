package app.ink.music

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
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
 * Settings as rows, the computer's (the phone's SettingRows, 2026-11-11: "its still a lot of reading"): each setting one
 * row, its name and what it's set to (lit); a click opens it in place and closes it again, one open at a time ([Opened]).
 * A setting drawn by its own function keeps its own heading out of a row ([SettingHead]): the row is its heading.
 */
class Opened {
    var name by mutableStateOf<String?>(null)
}

@Composable
fun rememberOpened() = remember { Opened() }

/** Whether what's drawn is inside a row (its heading the row's). */
val LocalInRow = compositionLocalOf { false }

/** A setting's heading, unless it's inside a row. */
@Composable
fun SettingHead(text: String) {
    if (LocalInRow.current) return
    BasicText(text, style = headline(24.sp))
    Spacer(Modifier.height(6.dp))
}

@Composable
fun SettingRow(name: String, said: String, opened: Opened, why: String? = null, content: @Composable () -> Unit) {
    val open = opened.name == name
    val src = remember { MutableInteractionSource() }
    val hover by src.collectIsHoveredAsState()
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(if (hover) Ink.mid else Ink.line))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).hoverable(src)
                .clickable(src, indication = null, onClick = felt { opened.name = if (open) null else name })
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(name, style = headline(18.sp), modifier = Modifier.padding(end = 16.dp))
            // what it's set to: lit while closed (under the pointer a hairline brighter), dim while its words are open
            BasicText(said, style = pixel(13.sp, if (open) Ink.dim else Ink.ink).copy(textAlign = TextAlign.End), maxLines = 2,
                modifier = Modifier.weight(1f))
        }
        AnimatedVisibility(open,
            enter = expandVertically(tween(Motion.ENTER, easing = Motion.expo)) + fadeIn(tween(Motion.ENTER, easing = Motion.expo)),
            exit = shrinkVertically(tween(Motion.EXIT, easing = Motion.expo)) + fadeOut(tween(Motion.EXIT, easing = Motion.expo))) {
            Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
                why?.let { BasicText(it, style = pixel(12.sp, Ink.mid)); Spacer(Modifier.height(10.dp)) }
                CompositionLocalProvider(LocalInRow provides true) { content() }
            }
        }
    }
}

/** The hairline under a part's last row. */
@Composable
fun RowsEnd() = Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line))

/** A row of words to choose from, the chosen one lit. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun Pick(words: List<Pair<String, Boolean>>, pick: (Int) -> Unit) {
    FlowRow {
        words.forEachIndexed { i, (w, lit) ->
            Word(w, pixel(14.sp, if (lit) Ink.ink else Ink.dim), Modifier.padding(end = 26.dp, bottom = 4.dp), touch = 8.dp) { pick(i) }
        }
    }
}

/** A small dim label over a group of words inside an open row. */
@Composable
fun PickLabel(text: String) {
    Spacer(Modifier.height(8.dp))
    BasicText(text, style = pixel(12.sp, Ink.mid))
    Spacer(Modifier.height(4.dp))
}
