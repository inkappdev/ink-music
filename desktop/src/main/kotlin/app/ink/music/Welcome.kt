package app.ink.music

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File

/**
 * The computer's first open (2026-11-12, Bronson: "a cool welcome screen"; the phone's Welcome its model): INK MUSIC,
 * then the phone's QR code, since a phone paired brings everything over (its sign-ins, server, listening, loves) and
 * that's the whole of it; without one, where your music is, then whom you like (followed: their mixes lead listen).
 * As few words as can be. Shown once, and never to someone who's used ink music here before.
 */
object Welcome {
    private val said = File(Home.dir, "welcomed")
    val showing = kotlinx.coroutines.flow.MutableStateFlow(
        !said.exists() && !File(Home.dir, "heard.jsonl").exists() && !File(Home.dir, "sync.json").exists())

    fun finish() {
        showing.value = false
        runCatching { said.writeText("") }
        // the rail's pairing waits for a later open, not straight after the phone was offered here (RailPair)
        runCatching { File(Home.dir, "rail-pair-shown").writeText("") }
        TubeAccount.stop()
    }
}

private enum class Step { HELLO, PHONE, MUSIC, LIKE }

@Composable
fun WelcomePage() {
    var step by remember { mutableStateOf(Step.HELLO) }
    LaunchedEffect(Unit) { delay(1_600); if (step == Step.HELLO) step = Step.PHONE }
    Box(Modifier.fillMaxSize().background(Ink.black)) {
        AnimatedContent(step, transitionSpec = {
            fadeIn(tween(Motion.ENTER, delayMillis = 60, easing = Motion.expo)) togetherWith fadeOut(tween(Motion.EXIT))
        }, label = "welcome") { s ->
            when (s) {
                Step.HELLO -> Hello { step = Step.PHONE }
                Step.PHONE -> PhoneStep(onPaired = { Welcome.finish() }, onSkip = { step = Step.MUSIC })
                Step.MUSIC -> MusicStep { step = Step.LIKE }
                Step.LIKE -> LikeStep { Welcome.finish() }
            }
        }
    }
}

/** A step's frame: the page's edge, its heading big, what it holds, and its words along the foot. */
@Composable
private fun StepFrame(heading: String, foot: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit, body: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 56.dp)) {
        BasicText(heading, style = headline(56.sp).copy(lineHeight = 60.sp))
        Spacer(Modifier.height(32.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) { body() }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { foot() }
    }
}

@Composable
private fun Hello(onNext: () -> Unit) {
    Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null, onClick = onNext), contentAlignment = Alignment.CenterStart) {
        Column(Modifier.padding(horizontal = 64.dp)) {
            BasicText("INK", style = headline(200.sp).copy(lineHeight = 190.sp))
            BasicText("MUSIC", style = pixel(64.sp), modifier = Modifier.padding(top = 4.dp, start = 8.dp))
        }
    }
}

/** The phone: its camera on the code, and paired, everything it has comes over: done. */
@Composable
private fun PhoneStep(onPaired: () -> Unit, onSkip: () -> Unit) {
    LaunchedEffect(Unit) { if (TubeAccount.code.value == null) TubeAccount.await() }
    val code by TubeAccount.code.collectAsState()
    val link by TubeAccount.link.collectAsState()
    val peers by SyncHub.peers.collectAsState()
    val paired = peers.isNotEmpty()
    LaunchedEffect(paired) { if (paired) { delay(1_400); onPaired() } }
    // ten minutes up with no phone: the code made again while this is open
    LaunchedEffect(code) { if (code == null && !paired) { delay(500); TubeAccount.await() } }
    StepFrame(if (paired) "PAIRED" else "ON YOUR PHONE TOO?", foot = {
        if (!paired) Word("no phone", pixel(14.sp, Ink.mid), touch = 8.dp) { TubeAccount.stop(); onSkip() }
    }) {
        if (!paired) Column {
            BasicText("point its camera here", style = pixel(13.sp, Ink.mid))
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                link?.let { QrCode(it, Modifier.size(240.dp)) } ?: Box(Modifier.size(240.dp))
                Spacer(Modifier.width(28.dp))
                code?.let { BasicText(it, style = pixel(13.sp, Ink.mid)) }
            }
        } else BasicText("bringing it all over", style = pixel(13.sp, Ink.mid))
    }
}

/** Your music: the folder found (how many songs in it), another, or your server. */
@Composable
private fun MusicStep(onNext: () -> Unit) {
    val songs by Library.songs.collectAsState()
    val at by Library.folderAt.collectAsState()
    val account by Server.account.collectAsState()
    var server by remember { mutableStateOf(false) }
    val files = songs.count { !it.server }
    val fromServer = songs.count { it.server }
    StepFrame("WHERE'S YOUR MUSIC?", foot = {
        Word("NEXT", headline(40.sp, if (files + fromServer > 0) Ink.ink else Ink.mid), touch = 10.dp, onClick = onNext)
    }) {
        Column(Modifier.verticalScroll(rememberScrollState()).widthIn(max = 640.dp)) {
            Column(Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { chooseFolder { Library.choose(it) } }).padding(vertical = 10.dp)) {
                BasicText("YOUR FILES", style = headline(30.sp, if (files > 0) Ink.ink else Ink.mid))
                BasicText(remember(at, files) { "${Library.said} · " + if (files > 0) "$files songs" else "none here: pick a folder" },
                    style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { server = !server }).padding(vertical = 10.dp)) {
                BasicText("YOUR SERVER", style = headline(30.sp, if (account != null) Ink.ink else Ink.mid))
                BasicText(account?.let { "${it.name.lowercase()} · $fromServer songs" } ?: "navidrome or subsonic",
                    style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 4.dp))
            }
            if (server && account == null) { Spacer(Modifier.height(12.dp)); SignIn() }
        }
    }
}

/**
 * Whom you like, as faces: your library's most-kept first, then well-loved ones across kinds of music (the phone's
 * offer). Picked are lit; done follows them (Follows), and their mixes lead listen.
 */
@Composable
private fun LikeStep(onDone: () -> Unit) {
    val songs by Library.songs.collectAsState()
    val mine = remember(songs) {
        songs.flatMap { it.artist.split(", ") }.filter { it.isNotBlank() && it.lowercase() != "unknown" }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(8).map { it.key }
    }
    val offered = remember(mine) { (mine + OFFERED).distinctBy { it.lowercase() }.take(24) }
    val picked = remember { mutableStateListOf<String>() }
    StepFrame("WHAT DO YOU LIKE?", foot = {
        BasicText(if (picked.isEmpty()) "" else "${picked.size}", style = pixel(13.sp, Ink.mid), modifier = Modifier.weight(1f))
        Word("skip", pixel(14.sp, Ink.mid), Modifier.padding(end = 28.dp), touch = 8.dp, onClick = onDone)
        Word("DONE", headline(40.sp, if (picked.isEmpty()) Ink.mid else Ink.ink), touch = 10.dp) {
            picked.reversed().forEach { Follows.set(it, "", true) }
            onDone()
        }
    }) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val across = (maxWidth / 150.dp).toInt().coerceIn(4, 8)
            val side = (maxWidth - 18.dp * (across - 1)) / across
            FlowRow(Modifier.verticalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                offered.forEach { name ->
                    val on = name in picked
                    Face(name, on, Modifier.width(side)) { if (on) picked.remove(name) else picked.add(name) }
                }
            }
        }
    }
}

/** An artist's face (found by name; ink's sleeve with the name till then), the name under it; dimmed till picked. */
@Composable
private fun Face(name: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val info by produceState<ArtistInfo?>(null, name) { Artists.of(name).collect { if (it != null) value = it } }
    val shown = info?.photo?.let { remember(it) { faceSong(name, it, photo = true) } }
    Column(modifier.alpha(if (on) 1f else .45f).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt(then = onClick))) {
        CoverOf(shown, name, Modifier.fillMaxWidth().aspectRatio(1f), px = 300)
        BasicText(name.lowercase(), style = pixel(12.sp, if (on) Ink.ink else Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp))
    }
}

/** Well-loved across kinds of music, so most people see a few of theirs (the phone's Seeds.offered). */
private val OFFERED = listOf(
    "Taylor Swift", "Kendrick Lamar", "Billie Eilish", "Frank Ocean", "Radiohead", "Phoebe Bridgers",
    "SZA", "The Beatles", "Daft Punk", "Fleetwood Mac", "Tyler, The Creator", "Lana Del Rey",
    "Bad Bunny", "Beyoncé", "Tame Impala", "Arctic Monkeys", "Olivia Rodrigo", "Mitski",
    "Bon Iver", "Rosalía", "The Weeknd", "Fred again..", "Japanese Breakfast", "Charli xcx",
)
