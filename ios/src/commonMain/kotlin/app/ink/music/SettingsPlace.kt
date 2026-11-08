package app.ink.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** A settings section: its headline, a dim line, then what's chosen there. */
@Composable
private fun Section(name: String, line: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 34.dp)) {
        BasicText(name.uppercase(), style = headline(26.sp))
        BasicText(line, style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        content()
    }
}

/** settings: your server, your computer (pairing, in step), and who made it. */
@Composable
fun SettingsPlace(top: Dp, foot: Dp) {
    val account by Server.account.collectAsState()
    val peers by Together.peers.collectAsState()
    val said by Together.said.collectAsState()
    val who by Tube.who.collectAsState()
    val scope = rememberCoroutineScope()
    var syncing by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top, bottom = foot)) {
        item {
            Section("your server", account?.let { it.base.removePrefix("http://").removePrefix("https://") + ", as " + it.user } ?: "none") {
                Word("sign out", pixel(14.sp, Ink.mid)) { Server.signOut() }
            }
            Section("your computer", if (peers.isEmpty()) "paired, the two keep in step on your wifi: what you've heard, your podcasts, what's playing"
                else said ?: "in step on this wifi") {
                peers.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            BasicText(p.name.removePrefix("ink music on ").uppercase(), style = headline(18.sp))
                            if (p.seen > 0) BasicText("last in step " + Moment.since(p.seen), style = pixel(12.sp, Ink.mid))
                        }
                        Word("forget", pixel(13.sp, Ink.mid)) { Together.forget(p.id) }
                    }
                }
                if (peers.isNotEmpty()) Word(if (syncing) "keeping in step" else "in step now", pixel(14.sp, if (syncing) Ink.mid else Ink.ink)) {
                    if (syncing) return@Word
                    syncing = true
                    scope.launch { Together.now(); syncing = false }
                }
                Word("pair a computer", pixel(14.sp, if (peers.isEmpty()) Ink.ink else Ink.mid)) { Pages.pairing = true }
            }
            Section("youtube music", who?.let { "signed in as ${it.name.lowercase()}" + if (it.handle.isNotEmpty()) ", ${it.handle.lowercase()}" else "" }
                ?: if (peers.isEmpty()) "pair a computer signed in to youtube music, and it comes across" else "comes across from your computer at the next sync") {
                if (who != null) Word("sign out here", pixel(14.sp, Ink.mid)) { Tube.signOut() }
            }
            Section("the look", "behind the player: the cover in an effect") {
                val look by Looks.on.collectAsState()
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(22.dp)) {
                    for (l in Look.entries) Word(l.word, pixel(14.sp, if (l == look) Ink.ink else Ink.dim), touch = 8.dp) { Looks.set(l) }
                }
            }
            Section("ink music", "free, open source, for your iphone") {
                BasicText("made by ink", style = pixel(12.sp, Ink.mid))
            }
        }
    }
}

/**
 * Pairing with the computer: ink music there waits (settings, your phone, pair your phone) and shows a code; here it's
 * found on the wifi (or its address typed), and its code typed.
 */
@Composable
fun Pairing(onClose: () -> Unit) {
    val found = remember { mutableStateListOf<Pair<String, String>>() }
    var picked by remember { mutableStateOf<Pair<String, String>?>(null) }
    var code by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf("") }
    var said by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        val stop = browse(Handover.SERVICE) { n, at -> if (found.none { it.first == n }) found += n to at }
        onDispose { stop() }
    }
    Column(Modifier.fillMaxSize().background(Ink.black).windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 40.dp)) {
        Word("back", pixel(14.sp, Ink.mid)) { onClose() }
        BasicText("PAIR A COMPUTER", style = headline(40.sp), modifier = Modifier.padding(top = 10.dp))
        BasicText("on the computer: ink music, settings, your phone, pair your phone. it shows a code, and on the same wifi it's listed here",
            style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
        val p = picked
        if (p == null) {
            if (found.isEmpty()) BasicText("looking on this wifi", style = pixel(13.sp, Ink.mid))
            found.forEach { c -> Word(c.first.removePrefix("ink music on ").uppercase(), headline(28.sp)) { picked = c; said = null } }
            Spacer(Modifier.height(28.dp))
            Field("or its address, under the code", typed, { typed = it.trim() }, type = KeyboardType.Uri)
            Word("NEXT", headline(28.sp, if (':' in typed) Ink.ink else Ink.mid)) {
                if (':' in typed) { picked = typed.substringBeforeLast(':') to typed; said = null }
            }
        } else {
            BasicText(p.first.removePrefix("ink music on ").uppercase(), style = headline(28.sp))
            Spacer(Modifier.height(14.dp))
            Field("the code on its screen", code, { code = it.filter(Char::isDigit).take(6) }, type = KeyboardType.Number)
            Word(if (sending) "PAIRING" else "PAIR", headline(40.sp, if (sending || code.length < 6) Ink.mid else Ink.ink)) {
                if (sending || code.length < 6) return@Word
                sending = true; said = null
                scope.launch {
                    val r = Together.pair(p.first, p.second, code)
                    said = r; sending = false
                    if (r.startsWith("paired")) onClose()
                }
            }
            Word("another computer", pixel(13.sp, Ink.mid)) { picked = null; code = ""; said = null }
        }
        said?.let { BasicText(it, style = pixel(13.sp), modifier = Modifier.padding(top = 14.dp)) }
    }
}
