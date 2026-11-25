package app.ink.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What an extension said last, in a few words, and how long ago ("found the last song · 2 min ago"). The phone's. */
internal fun extensionSaid(x: YourExtensions.Said): String {
    val why = x.why
    val words = when {
        x.found -> "found the last song"
        why.startsWith("unreachable") -> "couldn't be reached"
        why.contains("encrypted") -> "had the last song only encrypted"
        why.contains("not in its search") -> "didn't have the last song"
        Regex("http (\\d+)").find(why) != null -> "said no (${Regex("http (\\d+)").find(why)!!.groupValues[1]})"
        else -> why.substringAfter("(").substringBefore(")").ifBlank { why }
    }
    val min = ((System.currentTimeMillis() - x.at) / 60_000).toInt()
    return words + " · " + when { min < 1 -> "just now"; min < 60 -> "$min min ago"; min < 1440 -> "${min / 60} h ago"; else -> "${min / 1440} d ago" }
}

/**
 * Extensions, the phone's setting on the computer: each address, its name, on or off; a click on one changes it (and
 * takes it away, the word under the fields: a hold isn't the computer's). A new one, or one whose address changed,
 * starts off: nothing of a song goes to it till it's turned on.
 */
@Composable
fun ExtensionsSetting() {
    val sources by Extensions.sources.collectAsState()
    var editing by remember { mutableStateOf<String?>(null) }   // the address being changed, or null: adding
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var said by remember { mutableStateOf<String?>(null) }
    // what each said last (the player writes it), read again while this is open
    val last by Extensions.said.collectAsState()
    val lossless by Extensions.lossless.collectAsState()
    LaunchedEffect(Unit) { while (true) { Extensions.refreshSaid(); kotlinx.coroutines.delay(5_000) } }
    fun leave() { editing = null; url = ""; name = ""; said = null }
    fun go() {
        if (url.isBlank()) return
        val old = editing
        val ok = if (old == null) Extensions.add(url, name) else Extensions.edit(old, url, name)
        if (ok) leave()
        else said = if (Extensions.clean(url) == null) "that isn't an http or https address" else "that address is here already"
    }
    sources.forEach { s ->
        Column(Modifier.padding(bottom = 12.dp)) {
            Column(Modifier.clickable(remember { MutableInteractionSource() }, indication = null,
                onClick = felt { editing = s.url; url = s.url; name = s.name; said = null })) {
                val host = Regex("^https?://(?:[^/?#@]*@)?([^/?#:]+)").find(s.url)?.groupValues?.get(1).orEmpty()
                BasicText(s.name.ifBlank { host }.lowercase(), style = pixel(14.sp, if (editing == s.url) Ink.ink else Ink.mid))
                // a lossless one: what it uses (2026-11-24: ~35 MB a five-minute song)
                if (s.url in lossless) BasicText("lossless · about a gb an hour", style = pixel(12.sp, Ink.mid))
                if (s.on) last[s.url]?.let { BasicText(extensionSaid(it), style = pixel(12.sp, if (it.found) Ink.ink else Ink.mid)) }
            }
            Spacer(Modifier.height(4.dp))
            Pick(listOf("on" to s.on, "off" to !s.on)) { Extensions.turn(s.url, it == 0) }
        }
    }
    BasicText(if (sources.isEmpty()) "paste an addon's link"
        else "click to change", style = pixel(12.sp, Ink.mid))
    Spacer(Modifier.height(14.dp))
    Field("address", url, { url = it; said = null }, "https://", onEnter = ::go)
    Field("name", name, { name = it }, "", onEnter = ::go)
    said?.let { BasicText(it, style = pixel(12.sp, Ink.ink)); Spacer(Modifier.height(10.dp)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Word(if (editing == null) "ADD" else "SAVE", headline(40.sp, if (url.isBlank()) Ink.mid else Ink.ink), Modifier.padding(end = 26.dp), touch = 10.dp) { go() }
        editing?.let { e ->
            Word("leave it", pixel(14.sp, Ink.mid), Modifier.padding(end = 26.dp), touch = 8.dp) { leave() }
            Word("take it away", pixel(14.sp, Ink.mid), touch = 8.dp) { Extensions.remove(e); leave() }
        }
    }
}
