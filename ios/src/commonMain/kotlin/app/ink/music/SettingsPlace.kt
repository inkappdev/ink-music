package app.ink.music

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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

/** A settings section, as the phone's: its headline, a dim line, then what's chosen there. */
@Composable
private fun Section(name: String, line: String, order: Int, opened: Long, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().cascade(order, opened).padding(bottom = 40.dp)) {
        BasicText(name.uppercase(), style = headline(24.sp))
        Spacer(Modifier.height(6.dp))
        BasicText(line, style = pixel(12.sp, Ink.mid))
        Spacer(Modifier.height(10.dp))
        content()
    }
}

/**
 * Settings, as the phone's: INK MUSIC big, then five words, each opening its page, how things stand said dim under
 * it: your music (where it comes from), the look, sound, listening, about. `part` is one of those pages.
 */
@Composable
fun SettingsPage(part: String?, onClose: () -> Unit) {
    val opened = remember(part) { nowMs() }
    val title = when (part) { "music" -> "YOUR MUSIC"; "look" -> "THE LOOK"; "sound" -> "SOUND"; "listening" -> "LISTENING"; "about" -> "ABOUT"; else -> "INK MUSIC" }
    val now by Sound.now.collectAsState()
    PageOver(onClose) { top ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top, bottom = (if (now != null) 120.dp else 30.dp) + 40.dp)) {
            item {
                BasicText(title, style = headline(52.sp), modifier = Modifier.cascade(0, opened))
                if (part == null) {
                    BasicText("version ${appVersion()}", style = pixel(12.sp, Ink.mid))
                    Spacer(Modifier.height(34.dp))
                    SettingsIndex(opened)
                } else Spacer(Modifier.height(30.dp))
                when (part) {
                    "music" -> MusicPart(opened)
                    "look" -> LookPart(opened)
                    "sound" -> SoundPart(opened)
                    "listening" -> ListeningPart(opened)
                    "about" -> AboutPart(opened)
                }
            }
        }
    }
}

@Composable
private fun SettingsIndex(opened: Long) {
    val account by Server.account.collectAsState()
    val who by Tube.who.collectAsState()
    val peers by Together.peers.collectAsState()
    val look by Looks.on.collectAsState()
    val radio by Sound.radio.collectAsState()
    val lyrics by Lyrics.on.collectAsState()
    val lb by ListenBrainz.account.collectAsState()
    @Composable
    fun Entry(word: String, said: String, part: String, order: Int) {
        Column(Modifier.fillMaxWidth().cascade(order, opened)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { Pages.open(Page.SettingsPart(part)) }).padding(vertical = 12.dp)) {
            BasicText(word, style = headline(40.sp))
            Spacer(Modifier.height(4.dp))
            BasicText(said, style = pixel(12.sp, Ink.mid), maxLines = 2)
        }
    }
    Entry("YOUR MUSIC", listOfNotNull(
        account?.let { it.base.substringAfter("://").substringBefore('/').lowercase() },
        if (who != null) "youtube music, signed in" else "youtube music",
        peers.firstOrNull()?.let { "paired with " + it.name.removePrefix("ink music on ").lowercase() },
    ).joinToString(" · "), "music", 1)
    Entry("THE LOOK", look.word, "look", 2)
    Entry("SOUND", listOfNotNull("youtube music's best aac", if (radio) "radio after the queue" else null).joinToString(" · "), "sound", 3)
    Entry("LISTENING", listOfNotNull(if (lyrics) "lyrics on" else "lyrics off", if (lb != null) "listenbrainz on" else null).joinToString(" · "), "listening", 4)
    Entry("ABOUT", "version ${appVersion()} · what leaves the iphone · free and open", "about", 5)
}

@Composable
private fun MusicPart(opened: Long) {
    val account by Server.account.collectAsState()
    val peers by Together.peers.collectAsState()
    val said by Together.said.collectAsState()
    val who by Tube.who.collectAsState()
    val scope = rememberCoroutineScope()
    var syncing by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    Section("your server", if (account == null) "navidrome, or any subsonic server: your whole library, streamed from home. it comes across from your computer"
        else "your whole library, streamed from home", 1, opened) {
        account?.let { a ->
            // signed in: where and who; a hold signs out
            Column(Modifier.combinedClickable(remember { MutableInteractionSource() }, indication = null,
                onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); Server.signOut() }, onClick = {})) {
                BasicText(a.base.substringAfter("://"), style = pixel(13.sp))
                BasicText(a.user, style = pixel(12.sp, Ink.mid))
                Spacer(Modifier.height(4.dp))
                BasicText("hold signs out", style = pixel(12.sp, Ink.mid))
            }
        }
    }
    Section("youtube music", who?.let { listOf(it.name, it.handle).filter { s -> s.isNotEmpty() }.joinToString("   ").lowercase() }
        ?: if (peers.isEmpty()) "pair a computer signed in to youtube music, and it comes across" else "comes across from your computer at the next sync", 2, opened) {
        if (who != null) Column(Modifier.combinedClickable(remember { MutableInteractionSource() }, indication = null,
            onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); Tube.signOut() }, onClick = {})) {
            BasicText("your mixes, liked songs and playlists on listen; premium's 256k", style = pixel(12.sp, Ink.mid))
            Spacer(Modifier.height(4.dp))
            BasicText("hold signs out here", style = pixel(12.sp, Ink.mid))
        }
    }
    Section("your computer", if (peers.isEmpty()) "paired, the two keep in step on your wifi: what you've heard, your podcasts, your sign-ins, what's playing"
        else said ?: "in step on this wifi", 3, opened) {
        peers.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    BasicText(p.name.removePrefix("ink music on ").lowercase(), style = pixel(13.sp))
                    if (p.seen > 0) BasicText("last in step " + Moment.since(p.seen), style = pixel(12.sp, Ink.mid))
                }
                Word("forget", pixel(13.sp, Ink.mid), touch = 8.dp) { Together.forget(p.id) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
            if (peers.isNotEmpty()) Word(if (syncing) "keeping in step" else "in step now", pixel(14.sp, if (syncing) Ink.mid else Ink.ink), touch = 8.dp) {
                if (syncing) return@Word
                syncing = true
                scope.launch { Together.now(); syncing = false }
            }
            Word("pair a computer >", pixel(14.sp, if (peers.isEmpty()) Ink.ink else Ink.mid), touch = 8.dp) { Pages.pairing = true }
        }
    }
}

@Composable
private fun LookPart(opened: Long) {
    val look by Looks.on.collectAsState()
    Section("behind the player", "the cover in an effect, across the whole player", 1, opened) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (l in Look.entries) Word(l.word, pixel(14.sp, if (l == look) Ink.ink else Ink.dim), touch = 8.dp) { Looks.set(l) }
        }
    }
}

@Composable
private fun SoundPart(opened: Long) {
    val radio by Sound.radio.collectAsState()
    Section("quality", "youtube music's best aac (256k with premium, signed in); your server's songs as they are, untouched", 1, opened) {}
    Section("radio", "when the queue runs out, more like it: youtube music's radio from the last song", 2, opened) {
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Word("on", pixel(14.sp, if (radio) Ink.ink else Ink.dim), touch = 8.dp) { Sound.radio(true) }
            Word("off", pixel(14.sp, if (!radio) Ink.ink else Ink.dim), touch = 8.dp) { Sound.radio(false) }
        }
    }
}

@Composable
private fun ListeningPart(opened: Long) {
    val lyrics by Lyrics.on.collectAsState()
    val lb by ListenBrainz.account.collectAsState()
    Section("lyrics", "the words over the player, line by line as they're sung: lrclib's, else youtube music's", 1, opened) {
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Word("on", pixel(14.sp, if (lyrics) Ink.ink else Ink.dim), touch = 8.dp) { if (!lyrics) Lyrics.toggle() }
            Word("off", pixel(14.sp, if (!lyrics) Ink.ink else Ink.dim), touch = 8.dp) { if (lyrics) Lyrics.toggle() }
        }
    }
    Section("listenbrainz", lb?.let { "what you play goes to listenbrainz, as ${it.user.lowercase()}" }
        ?: "connect it on your computer and it comes across: what you play here is told there too", 2, opened) {
        if (lb != null) Word("disconnect here", pixel(14.sp, Ink.mid), touch = 8.dp) { ListenBrainz.disconnect(Here.dir) }
    }
}

@Composable
private fun AboutPart(opened: Long) {
    Column(Modifier.cascade(1, opened)) {
        BasicText("WHAT LEAVES THE IPHONE", style = headline(20.sp))
        Spacer(Modifier.height(6.dp))
        BasicText(
            "nothing of yours, to anyone but your own server and your computer. a song's artist and title go to lrclib for its lyrics " +
                "(and to youtube music when lrclib has none); what you search for goes to youtube music (and, signed in there, your sign-in, " +
                "to read your library, and the youtube songs you play, for your history there); with listenbrainz connected, what you play goes there. " +
                "the critics' picks come from their own feeds, podcasts from theirs. no accounts of ours, no tracking, no ads.",
            style = pixel(12.sp, Ink.mid),
        )
        Spacer(Modifier.height(26.dp))
        BasicText("FREE, AND OPEN", style = headline(20.sp))
        Spacer(Modifier.height(6.dp))
        BasicText("gpl-3.0. the fonts are archivo black and silkscreen (sil open font license). lyrics from lrclib.net.", style = pixel(12.sp, Ink.mid))
        Spacer(Modifier.height(26.dp))
    }
    Column(Modifier.cascade(2, opened)) {
        BasicText("FROM THE MAKERS OF INK", style = headline(20.sp))
        Spacer(Modifier.height(6.dp))
        BasicText("ink is a launcher for people who listen: your phone as words, your music on your home screen.", style = pixel(12.sp, Ink.mid))
        Spacer(Modifier.height(8.dp))
        Word("inkhome.app", pixel(13.sp), touch = 8.dp) { openLink("https://inkhome.app") }
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
