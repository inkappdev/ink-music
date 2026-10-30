package app.ink.music

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Settings, as the phone's are (Bronson, 2026-10-29: "reorganize the desktop settings nicer, like the app"): its
 * parts down the left, each its name big and a line saying how it stands (your phone, your music, sound, listening,
 * about); the one chosen beside them.
 */
@Composable
fun SettingsPage(onClose: () -> Unit) {
    var part by remember { mutableStateOf(Settings.last) }
    LaunchedEffect(part) { Settings.last = part }
    Column(Modifier.fillMaxSize()) {
        Close(onClose)
        Row(Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = 16.dp)) {
            Column(Modifier.width(340.dp).fillMaxHeight()) {
                BasicText("SETTINGS", style = headline(56.sp))
                Spacer(Modifier.height(28.dp))
                Settings.PARTS.forEach { p ->
                    Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { part = p })
                        .padding(vertical = 10.dp)) {
                        BasicText(p.uppercase(), style = headline(30.sp, if (p == part) Ink.ink else Ink.dim))
                        BasicText(Settings.said(p), style = pixel(12.sp, if (p == part) Ink.mid else Ink.dim), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.width(56.dp))
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 40.dp)) {
                Column(Modifier.widthIn(max = 640.dp)) {
                    when (part) {
                        "your phone" -> PhoneSetting()
                        "your music" -> {
                            ServerSetting()
                            Spacer(Modifier.height(36.dp)); YouTubeSetting()
                            Spacer(Modifier.height(36.dp)); FilesSetting()
                        }
                        "the look" -> LookSetting()
                        "sound" -> { SoundSetting(); Spacer(Modifier.height(36.dp)); KeptSetting() }
                        "listening" -> {
                            PrivateSetting()
                            Spacer(Modifier.height(36.dp)); ListenBrainzSetting()
                            if (LastFm.available) { Spacer(Modifier.height(36.dp)); LastFmSetting() }
                        }
                        else -> { SizeSetting(); Spacer(Modifier.height(36.dp)); AboutSetting() }
                    }
                }
            }
        }
    }
}

/** The settings' parts, and how each stands in a line. */
object Settings {
    val PARTS = listOf("your phone", "your music", "the look", "sound", "listening", "about")
    /** The part last open, for the next time (this run). */
    var last = "your music"

    @Composable
    fun said(part: String): String = when (part) {
        "your phone" -> {
            val peers by SyncHub.peers.collectAsState()
            if (peers.isEmpty()) "not paired: pair it, and the two keep in step" else peers.joinToString(", ") { it.name.lowercase() } + ", in step"
        }
        "your music" -> {
            val a by Server.account.collectAsState()
            val who by TubeAccount.who.collectAsState()
            val local = Library.songs.collectAsState().value.count { !it.server }
            listOfNotNull(a?.name?.lowercase(), who?.let { "youtube music, signed in" }, "$local songs here").joinToString(" · ")
        }
        "sound" -> {
            val sound by Player.sound.collectAsState()
            val kept by Offline.kept.collectAsState()
            listOfNotNull(if (sound.device == "auto") "the ${if (Home.mac) "mac" else "computer"}'s choice" else "its own output",
                if (sound.exclusive) "exact" else null, if (sound.even) "even volume" else "as recorded", kept.size.takeIf { it > 0 }?.let { "$it kept" }).joinToString(" · ")
        }
        "the look" -> {
            val behind by Looks.on.collectAsState()
            val cover by Moving.cover.collectAsState()
            val moving by Moving.on.collectAsState()
            listOf("cover ${cover?.word ?: "as it is"}", if (behind == Look.PLAIN) "its print beside it" else "${behind.word} behind",
                if (moving) "moving covers" else "still covers").joinToString(" · ")
        }
        "listening" -> {
            val hidden by Private.on.collectAsState()
            val lb by ListenBrainz.account.collectAsState()
            val fm by LastFm.account.collectAsState()
            listOfNotNull(if (hidden) "private listening on" else null, lb?.let { "listenbrainz on" }, fm?.let { "last.fm on" })
                .ifEmpty { listOf("what's kept, and who's told") }.joinToString(" · ")
        }
        else -> "made by ink · free and open" + Keys.build.substringBefore(' ').takeIf { it.isNotEmpty() && it != "here" }?.let { " · $it" }.orEmpty()
    }
}

/** Your server: signed in, its name, where, how many songs, read again or sign out; else signing in. */
@Composable
private fun ServerSetting() {
    val account by Server.account.collectAsState()
    val count by Server.count.collectAsState()
    val syncing by Server.syncing.collectAsState()
    BasicText("YOUR SERVER", style = headline(24.sp))
    Spacer(Modifier.height(6.dp))
    val a = account
    if (a == null) {
        BasicText("navidrome, or any subsonic server: its songs, played from it", style = pixel(12.sp, Ink.mid))
        Spacer(Modifier.height(16.dp))
        SignIn()
    } else {
        BasicText(
            listOf(a.name.lowercase(), a.base.removePrefix("http://").removePrefix("https://").lowercase(),
                if (syncing) "reading" else "$count songs").joinToString(" · "),
            style = pixel(12.sp, Ink.mid),
        )
        Spacer(Modifier.height(12.dp))
        Word("read again", pixel(14.sp), touch = 8.dp) { Server.sync() }
        Word("sign out", pixel(14.sp, Ink.mid), touch = 8.dp) { Server.signOut() }
    }
}

/** Your files: the folder they're read from, how many, another folder or read again. */
@Composable
private fun FilesSetting() {
    BasicText("YOUR FILES", style = headline(24.sp))
    Spacer(Modifier.height(6.dp))
    val at by Library.folderAt.collectAsState()
    val local = Library.songs.collectAsState().value.count { !it.server }
    BasicText(remember(at, local) { "every song in ${Library.said} · $local songs" }, style = pixel(12.sp, Ink.mid))
    Spacer(Modifier.height(12.dp))
    Word("choose another folder", pixel(14.sp), touch = 8.dp) { chooseFolder()?.let(Library::choose) }
    Word("read again", pixel(14.sp, Ink.mid), touch = 8.dp) { Library.scan() }
}

/** About: who made this, that it's free and open, the build; ink, from its makers, said once and plainly. */
@Composable
private fun AboutSetting() {
    BasicText("INK MUSIC", style = headline(24.sp))
    Spacer(Modifier.height(6.dp))
    BasicText("free and open, gpl-3.0. no ads, no account, nothing sold", style = pixel(12.sp, Ink.mid))
    if (Keys.build.isNotEmpty()) BasicText("build ${Keys.build}", style = pixel(12.sp, Ink.dim), modifier = Modifier.padding(top = 4.dp))
    Spacer(Modifier.height(10.dp))
    Word("the code", pixel(14.sp, Ink.mid), touch = 8.dp) { browse("https://github.com/inkappdev/ink-music") }
    Spacer(Modifier.height(36.dp))
    // the funnel: said once, plainly, never a nag
    BasicText("FROM THE MAKERS OF INK", style = headline(24.sp))
    Spacer(Modifier.height(6.dp))
    BasicText("ink is a launcher for people who listen: your phone as words, your music on your home screen", style = pixel(12.sp, Ink.mid))
    Spacer(Modifier.height(10.dp))
    Word("inkhome.app", pixel(14.sp), touch = 8.dp) { browse("https://inkhome.app") }
}

/** Signing in to your server: its address, your name and password; what went wrong, said plainly. */
@Composable
private fun SignIn() {
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var trying by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }
    fun go() {
        if (trying || address.isBlank() || user.isBlank()) return
        trying = true; said = null
        scope.launch {
            said = when (Server.signIn(address, user, password)) {
                Server.SignIn.OK -> { password = ""; null }
                Server.SignIn.WRONG -> "that name and password weren't right"
                Server.SignIn.UNREACHABLE ->
                    if (Home.mac) "couldn't reach it. is the address right? a mac asks first: allow ink music on your local network (system settings, privacy and security, local network)"
                    else "couldn't reach it. is the address right, and this computer on the same network?"
                Server.SignIn.NOT_A_SERVER -> "something answered, but not a subsonic server"
            }
            trying = false
        }
    }
    Column {
        Field("address", address, { address = it }, "192.168.1.10:4533", onEnter = ::go)
        Field("name", user, { user = it }, "", onEnter = ::go)
        Field("password", password, { password = it }, "", secret = true, onEnter = ::go)
        Spacer(Modifier.height(10.dp))
        said?.let { BasicText(it, style = pixel(12.sp, Ink.ink)); Spacer(Modifier.height(10.dp)) }
        Word(if (trying) "SIGNING IN" else "SIGN IN", headline(40.sp, if (trying) Ink.mid else Ink.ink), touch = 10.dp) { go() }
    }
}

/** A word to type: its name above, a hairline under; in Archivo as typed (an address is case sensitive). */
@Composable
private fun Field(name: String, value: String, onValue: (String) -> Unit, hint: String, secret: Boolean = false, onEnter: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        BasicText(name, style = pixel(12.sp, Ink.mid))
        Spacer(Modifier.height(6.dp))
        Box {
            if (value.isEmpty() && hint.isNotEmpty()) BasicText(hint, style = headline(18.sp, Ink.dim))
            BasicTextField(
                value, onValue, singleLine = true, textStyle = headline(18.sp), cursorBrush = SolidColor(Ink.ink),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { k ->
                    if (k.type == KeyEventType.KeyDown && k.key == Key.Enter) { onEnter(); true } else false
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line))
    }
}

/** ListenBrainz: your user token, tried and kept; then whose it is (disconnect forgets it). */
@Composable
private fun ListenBrainzSetting() {
    val scope = rememberCoroutineScope()
    val account by ListenBrainz.account.collectAsState()
    BasicText("LISTENBRAINZ", style = headline(24.sp))
    Spacer(Modifier.height(6.dp))
    val a = account
    if (a == null) {
        var token by remember { mutableStateOf("") }
        var trying by remember { mutableStateOf(false) }
        var said by remember { mutableStateOf<String?>(null) }
        fun go() {
            if (trying || token.isBlank()) return
            trying = true; said = null
            scope.launch {
                said = when (ListenBrainz.connect(Home.dir, token)) {
                    ListenBrainz.Connect.OK -> { token = ""; null }
                    ListenBrainz.Connect.WRONG -> "listenbrainz didn't know that token"
                    ListenBrainz.Connect.UNREACHABLE -> "couldn't reach listenbrainz. try again in a moment"
                }
                trying = false
            }
        }
        BasicText("what you play, kept as your listening history on listenbrainz.org: open, and yours. " +
            "your user token is on its settings page", style = pixel(12.sp, Ink.mid))
        Spacer(Modifier.height(4.dp))
        Word("listenbrainz.org/settings", pixel(13.sp, Ink.mid), touch = 8.dp) { browse("https://listenbrainz.org/settings/") }
        Spacer(Modifier.height(10.dp))
        Field("user token", token, { token = it }, "", secret = true, onEnter = ::go)
        said?.let { BasicText(it, style = pixel(12.sp, Ink.ink)); Spacer(Modifier.height(10.dp)) }
        Word(if (trying) "CONNECTING" else "CONNECT", headline(40.sp, if (trying) Ink.mid else Ink.ink), touch = 10.dp) { go() }
    } else {
        BasicText(a.user.lowercase(), style = pixel(13.sp))
        BasicText("every song you play, but radio", style = pixel(12.sp, Ink.mid))
        Spacer(Modifier.height(8.dp))
        Word("disconnect", pixel(14.sp, Ink.mid), touch = 8.dp) { ListenBrainz.disconnect(Home.dir); SyncHub.kept.signed("listenbrainz", System.currentTimeMillis()) }
    }
}

/** Last.fm: your name and password swapped for a session (the password not kept); then whose it is. */
@Composable
private fun LastFmSetting() {
    val scope = rememberCoroutineScope()
    val account by LastFm.account.collectAsState()
    BasicText("LAST.FM", style = headline(24.sp))
    Spacer(Modifier.height(6.dp))
    val a = account
    if (a == null) {
        var name by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var trying by remember { mutableStateOf(false) }
        var said by remember { mutableStateOf<String?>(null) }
        fun go() {
            if (trying || name.isBlank() || password.isBlank()) return
            trying = true; said = null
            scope.launch {
                said = when (LastFm.connect(Home.dir, name, password)) {
                    LastFm.Connect.OK -> { password = ""; null }
                    LastFm.Connect.WRONG -> "last.fm didn't take that name and password"
                    LastFm.Connect.UNREACHABLE -> "couldn't reach last.fm. try again in a moment"
                }
                trying = false
            }
        }
        BasicText("scrobble what you play to last.fm. your password goes to last.fm once, and isn't kept here", style = pixel(12.sp, Ink.mid))
        Spacer(Modifier.height(10.dp))
        Field("name", name, { name = it }, "", onEnter = ::go)
        Field("password", password, { password = it }, "", secret = true, onEnter = ::go)
        said?.let { BasicText(it, style = pixel(12.sp, Ink.ink)); Spacer(Modifier.height(10.dp)) }
        Word(if (trying) "CONNECTING" else "CONNECT", headline(40.sp, if (trying) Ink.mid else Ink.ink), touch = 10.dp) { go() }
    } else {
        BasicText(a.name.lowercase(), style = pixel(13.sp))
        BasicText("every song you play, but radio", style = pixel(12.sp, Ink.mid))
        Spacer(Modifier.height(8.dp))
        Word("disconnect", pixel(14.sp, Ink.mid), touch = 8.dp) { LastFm.disconnect(Home.dir); SyncHub.kept.signed("lastfm", System.currentTimeMillis()) }
    }
}

/** A page in the browser. */
fun browse(url: String) {
    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
}

/**
 * Where the sound goes: each output there is (lit is chosen; "this mac's choice" follows what the Mac's set to), and
 * alone or shared. Alone (exclusive), nothing else plays through it and a Mac sets it to the file's own sample rate.
 */
@Composable
private fun SoundSetting() {
    val sound by Player.sound.collectAsState()
    val outputs = remember { Player.outputs() }
    BasicText("SOUND", style = headline(24.sp))
    Spacer(Modifier.height(6.dp))
    BasicText("where it plays, and whether it has it alone", style = pixel(12.sp, Ink.mid))
    Spacer(Modifier.height(12.dp))
    outputs.forEach { (name, said) ->
        val label = if (name == "auto") "the ${if (Home.mac) "mac" else "computer"}'s choice" else said.lowercase().ifEmpty { name }
        Word(label, pixel(14.sp, if (sound.device == name) Ink.ink else Ink.dim), touch = 6.dp) { Player.sound(sound.copy(device = name)) }
    }
    Spacer(Modifier.height(16.dp))
    androidx.compose.foundation.layout.Row {
        Word("shared", pixel(14.sp, if (!sound.exclusive) Ink.ink else Ink.dim), androidx.compose.ui.Modifier.padding(end = 26.dp), touch = 8.dp) {
            Player.sound(sound.copy(exclusive = false))
        }
        Word("alone", pixel(14.sp, if (sound.exclusive) Ink.ink else Ink.dim), touch = 8.dp) { Player.sound(sound.copy(exclusive = true)) }
    }
    Spacer(Modifier.height(6.dp))
    BasicText(
        if (sound.exclusive) "nothing else plays through it meanwhile; it runs at each file's own sample rate"
        else "mixed with everything else the ${if (Home.mac) "mac" else "computer"} plays, at its own sample rate",
        style = pixel(12.sp, Ink.mid),
    )
    Spacer(Modifier.height(16.dp))
    androidx.compose.foundation.layout.Row {
        Word("even volume", pixel(14.sp, if (sound.even) Ink.ink else Ink.dim), androidx.compose.ui.Modifier.padding(end = 26.dp), touch = 8.dp) {
            Player.sound(sound.copy(even = true))
        }
        Word("as recorded", pixel(14.sp, if (!sound.even) Ink.ink else Ink.dim), touch = 8.dp) { Player.sound(sound.copy(even = false)) }
    }
    Spacer(Modifier.height(6.dp))
    BasicText(
        if (sound.even) "every record as loud as the next, by the files' own replaygain tags" else "each file at the level it was made",
        style = pixel(12.sp, Ink.mid),
    )
    Spacer(Modifier.height(6.dp))
    BasicText("alone, as recorded, at volume 100: the file's samples reach the output unchanged", style = pixel(12.sp, Ink.mid))
}

/** A folder picked in the system's own way (the Mac's: its open panel, folders only); null if it's cancelled. */
private fun chooseFolder(): java.io.File? = if (Home.mac) {
    System.setProperty("apple.awt.fileDialogForDirectories", "true")
    val d = java.awt.FileDialog(null as java.awt.Frame?, "Your music", java.awt.FileDialog.LOAD)
    d.isVisible = true
    System.setProperty("apple.awt.fileDialogForDirectories", "false")
    d.file?.let { java.io.File(d.directory, it) }
} else {
    val c = javax.swing.JFileChooser(Library.folder).apply { fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY; dialogTitle = "Your music" }
    if (c.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) c.selectedFile else null
}

/**
 * YouTube Music: signed in from the phone (the computer has no browser of its own to sign in with). Waiting, the code
 * to type on the phone, big; signed in, who, how much was read, read again or sign out.
 */
@Composable
private fun YouTubeSetting() {
    val who by TubeAccount.who.collectAsState()
    val code by TubeAccount.code.collectAsState()
    val said by TubeAccount.said.collectAsState()
    val lists by TubeAccount.playlists.collectAsState()
    val liked by TubeAccount.liked.collectAsState()
    BasicText("YOUTUBE MUSIC", style = headline(24.sp))
    Spacer(Modifier.height(6.dp))
    val w = who
    val c = code
    when {
        c != null -> BasicText("pairing your phone (your phone, on the left): its sign-in comes with it", style = pixel(12.sp, Ink.mid))
        w == null -> {
            BasicText("your playlists, liked songs and mixes, and radio made as you, from your own account. " +
                "signed in from your phone: a computer has no youtube sign-in page of its own here. " +
                "ink music isn't youtube's own app, and google could, rarely, mind one being used", style = pixel(12.sp, Ink.mid))
            Spacer(Modifier.height(10.dp))
            Word("SIGN IN FROM YOUR PHONE", headline(36.sp), touch = 10.dp) { TubeAccount.await() }
            BasicText("pairing your phone (your phone, on the left) does it too", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 4.dp))
        }
        else -> {
            BasicText(listOf(w.name, w.handle).filter { it.isNotEmpty() }.joinToString("   ").lowercase(), style = pixel(13.sp))
            BasicText("${lists.size} ${if (lists.size == 1) "playlist" else "playlists"}   ${liked.size} liked songs", style = pixel(12.sp, Ink.mid))
            BasicText("youtube songs you play here go in your history there, as in its own app", style = pixel(12.sp, Ink.mid))
            Spacer(Modifier.height(10.dp))
            Word("read again", pixel(14.sp), touch = 8.dp) { TubeAccount.sync() }
            Word("sign out", pixel(14.sp, Ink.mid), touch = 8.dp) { TubeAccount.signOut() }
        }
    }
}

/**
 * Your phone (Bronson, 2026-10-29: "make everything seamlessly sync"): paired once by the code, then the two kept in
 * step on the wifi ([SyncHub]): what you've heard, loves, sign-ins, what each was playing. Waiting, the code big;
 * paired, each phone and when it was last in step, forget it.
 */
@Composable
private fun PhoneSetting() {
    val peers by SyncHub.peers.collectAsState()
    val code by TubeAccount.code.collectAsState()
    val said by TubeAccount.said.collectAsState()
    BasicText("YOUR PHONE", style = headline(24.sp))
    Spacer(Modifier.height(6.dp))
    val c = code
    when {
        c != null -> {
            BasicText("on your phone: ink music, settings, sync with a computer. pick ${TubeAccount.name.lowercase()}, and type", style = pixel(12.sp, Ink.mid))
            Spacer(Modifier.height(8.dp))
            BasicText(c, style = headline(64.sp))
            BasicText("the two agree a key no one else on the wifi can work out; everything between them goes sealed under it", style = pixel(12.sp, Ink.mid))
            val where by TubeAccount.at.collectAsState()
            where?.let { BasicText("not listed on the phone? type its address there: $it", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 4.dp)) }
            Spacer(Modifier.height(10.dp))
            Word("cancel", pixel(14.sp, Ink.mid), touch = 8.dp) { TubeAccount.stop() }
        }
        peers.isEmpty() -> {
            BasicText("pair it once, and this computer is signed in to what your phone is (your server, youtube music, listenbrainz), " +
                "and the two keep in step on your wifi from then on: what you've heard, what you love, what you were playing. " +
                "nothing goes through anyone else", style = pixel(12.sp, Ink.mid))
            Spacer(Modifier.height(10.dp))
            Word("PAIR YOUR PHONE", headline(36.sp), touch = 10.dp) { TubeAccount.await() }
        }
        else -> {
            // a minute's tick, so "a minute ago" moves on
            val tick by androidx.compose.runtime.produceState(System.currentTimeMillis()) { while (true) { kotlinx.coroutines.delay(30_000); value = System.currentTimeMillis() } }
            peers.forEach { p ->
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        BasicText(p.name.uppercase(), style = headline(20.sp))
                        BasicText(if (p.seen == 0L) "paired, not in step yet" else "in step " + ago(tick - p.seen), style = pixel(12.sp, Ink.mid))
                    }
                    Word("forget", pixel(13.sp, Ink.mid), touch = 8.dp) { SyncHub.forget(p.id) }
                }
                Spacer(Modifier.height(8.dp))
            }
            BasicText("on the same wifi they keep in step by themselves, every minute the phone's app is open", style = pixel(12.sp, Ink.mid))
            Spacer(Modifier.height(10.dp))
            Word("pair another", pixel(14.sp, Ink.mid), touch = 8.dp) { TubeAccount.await() }
        }
    }
    said?.let { BasicText(it, style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 8.dp)) }
    // Windows: its firewall keeping the phone out, and the way in (Firewall)
    if (Home.windows) {
        LaunchedEffect(Unit) { Firewall.check() }
        val wall by Firewall.state.collectAsState()
        val wallSaid by Firewall.said.collectAsState()
        if (wall == Firewall.State.SHUT || wall == Firewall.State.PUBLIC) {
            Spacer(Modifier.height(14.dp))
            BasicText(
                if (wall == Firewall.State.PUBLIC) "windows has this wifi as public, so its firewall keeps your phone out. let it in: " +
                    "this wifi made private, and ink music allowed on private networks (windows asks you first)"
                else "windows' firewall keeps your phone out. let it in: ink music allowed on private networks (windows asks you first)",
                style = pixel(12.sp, Ink.mid),
            )
            Spacer(Modifier.height(8.dp))
            Word("LET IT IN", headline(24.sp), touch = 8.dp) { Firewall.letIn() }
        }
        wallSaid?.let { BasicText(it, style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 6.dp)) }
    }
}

/** "just now", "4 minutes ago", "2 hours ago", "3 days ago". */
fun ago(ms: Long): String = when {
    ms < 60_000 -> "just now"
    ms < 3_600_000 -> (ms / 60_000).let { "$it ${if (it == 1L) "minute" else "minutes"} ago" }
    ms < 86_400_000 -> (ms / 3_600_000).let { "$it ${if (it == 1L) "hour" else "hours"} ago" }
    else -> (ms / 86_400_000).let { "$it ${if (it == 1L) "day" else "days"} ago" }
}

/** Kept offline: how many, how much room; kept as they are or smaller; let them all go. */
@Composable
private fun KeptSetting() {
    val kept by Offline.kept.collectAsState()
    val small by Offline.small.collectAsState()
    BasicText("KEPT OFFLINE", style = headline(24.sp))
    Spacer(Modifier.height(6.dp))
    val mb = remember(kept) { Offline.bytes() / 1_048_576 }
    BasicText(if (kept.isEmpty()) "keep a song, a record or a playlist on this computer (keep offline: on its page, or a song's right click) to play it with no signal"
        else "${kept.size} ${if (kept.size == 1) "song" else "songs"} on this computer, $mb mb", style = pixel(12.sp, Ink.mid))
    Spacer(Modifier.height(10.dp))
    BasicText("kept as: your server's original file (lossless if it is) and youtube's best, or smaller (mp3 320, and youtube's lighter stream)", style = pixel(12.sp, Ink.mid))
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        Word("as they are", pixel(14.sp, if (!small) Ink.ink else Ink.dim), touch = 8.dp) { Offline.small(false) }
        Word("smaller", pixel(14.sp, if (small) Ink.ink else Ink.dim), touch = 8.dp) { Offline.small(true) }
    }
    if (kept.isNotEmpty()) {
        var sure by remember { mutableStateOf(false) }
        LaunchedEffect(sure) { if (sure) { kotlinx.coroutines.delay(3_000); sure = false } }
        Word(if (sure) "remove them all" else "remove all", pixel(14.sp, if (sure) Ink.ink else Ink.mid), Modifier.padding(top = 10.dp), touch = 8.dp) {
            if (!sure) sure = true else { sure = false; Offline.remove(kept) }
        }
    }
}

/** How big everything's drawn: fit to the screen, or a size; ⌘/Ctrl = and - step it. */
@Composable
private fun SizeSetting() {
    val chosen by Size.chosen.collectAsState()
    val key = if (Home.mac) "⌘" else "ctrl"
    BasicText("SIZE", style = headline(24.sp))
    Spacer(Modifier.height(6.dp))
    BasicText(
        if (chosen == null) "fit: this screen made about 1920 across (${pct(Size.fit)} of the desktop's own size)"
        else "${pct(chosen ?: 1f)} of the desktop's own size. $key = and $key - step it",
        style = pixel(12.sp, Ink.mid),
    )
    Spacer(Modifier.height(10.dp))
    androidx.compose.foundation.layout.Row {
        Word("fit", pixel(14.sp, if (chosen == null) Ink.ink else Ink.dim), Modifier.padding(end = 26.dp), touch = 8.dp) { Size.set(null) }
        Size.CHOICES.forEach { c ->
            Word(pct(c), pixel(14.sp, if (chosen == c) Ink.ink else Ink.dim), Modifier.padding(end = 26.dp), touch = 8.dp) { Size.set(c) }
        }
    }
}

private fun pct(f: Float) = "${Math.round(f * 100)}%"

/** Private listening: a switch; while it's on, nothing kept or told. */
@Composable
private fun PrivateSetting() {
    val on by Private.on.collectAsState()
    BasicText("PRIVATE LISTENING", style = headline(24.sp))
    Spacer(Modifier.height(6.dp))
    BasicText(if (on) "on: nothing you play is kept or told. it's okay, we know you like taylor swift"
        else "while it's on, what you play isn't kept here or told anywhere: no history, no scrobbles, not in your year", style = pixel(12.sp, Ink.mid))
    Spacer(Modifier.height(10.dp))
    androidx.compose.foundation.layout.Row {
        Word("on", pixel(14.sp, if (on) Ink.ink else Ink.dim), Modifier.padding(end = 26.dp), touch = 8.dp) { Private.set(true) }
        Word("off", pixel(14.sp, if (!on) Ink.ink else Ink.dim), touch = 8.dp) { Private.set(false) }
    }
}

/** macOS keeping ink music off your network ([Server.shut]): said, with where to allow it, till it's allowed. */
@Composable
fun NetworkShut(modifier: Modifier = Modifier) {
    val shut by Server.shut.collectAsState()
    if (!shut) return
    Column(modifier) {
        BasicText("MACOS IS KEEPING INK MUSIC OFF YOUR NETWORK", style = headline(20.sp))
        BasicText("so your server can't be reached, its covers and songs with it. system settings, privacy and security, " +
            "local network: switch ink music on (off and on again if it is), then open ink music again", style = pixel(13.sp, Ink.mid),
            modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * The look: a preview of the player (what plays, else a record of yours) over the choices, each its own: the cover's
 * effect, the background behind the player, moving covers, the big names moving or still.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LookSetting() {
    val behind by Looks.on.collectAsState()
    val cover by Moving.cover.collectAsState()
    val moving by Moving.on.collectAsState()
    val names by Marquees.on.collectAsState()
    LookPreview()
    Spacer(Modifier.height(28.dp))
    BasicText("THE COVER", style = headline(24.sp))
    Spacer(Modifier.height(6.dp))
    BasicText("the record's cover, still or moving, in an effect of its own", style = pixel(12.sp, Ink.mid))
    Spacer(Modifier.height(12.dp))
    FlowRow(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Word("as it is", pixel(14.sp, if (cover == null) Ink.ink else Ink.dim), Modifier.padding(end = 26.dp), touch = 8.dp) { Moving.set(cover = null) }
        for (l in Moving.coverLooks) Word(l.word, pixel(14.sp, if (cover == l) Ink.ink else Ink.dim), Modifier.padding(end = 26.dp), touch = 8.dp) { Moving.set(cover = l) }
    }
    Spacer(Modifier.height(36.dp))
    BasicText("BEHIND IT", style = headline(24.sp))
    Spacer(Modifier.height(6.dp))
    BasicText(if (behind == Look.PLAIN) "plain: the cover's print in the dither beside it, on black"
        else "the cover across the whole window behind the player, in this effect", style = pixel(12.sp, Ink.mid))
    Spacer(Modifier.height(12.dp))
    FlowRow(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (l in listOf(Look.PLAIN, Look.BLUR, Look.FLUTED, Look.PIXELS, Look.DITHER, Look.AS_IS))
            Word(l.word, pixel(14.sp, if (behind == l) Ink.ink else Ink.dim), Modifier.padding(end = 26.dp), touch = 8.dp) { Looks.set(l) }
    }
    Spacer(Modifier.height(36.dp))
    BasicText("MOVING COVERS", style = headline(24.sp))
    Spacer(Modifier.height(6.dp))
    BasicText("where a label made one, apple music's looping cover plays while the music does", style = pixel(12.sp, Ink.mid))
    Spacer(Modifier.height(12.dp))
    Row {
        Word("on", pixel(14.sp, if (moving) Ink.ink else Ink.dim), Modifier.padding(end = 26.dp), touch = 8.dp) { Moving.set(on = true) }
        Word("off", pixel(14.sp, if (!moving) Ink.ink else Ink.dim), touch = 8.dp) { Moving.set(on = false) }
    }
    Spacer(Modifier.height(36.dp))
    BasicText("THE NAME", style = headline(24.sp))
    Spacer(Modifier.height(6.dp))
    BasicText("the big artist names along the player's foot and on their pages", style = pixel(12.sp, Ink.mid))
    Spacer(Modifier.height(12.dp))
    Row {
        Word("moving", pixel(14.sp, if (names) Ink.ink else Ink.dim), Modifier.padding(end = 26.dp), touch = 8.dp) { Marquees.set(true) }
        Word("still", pixel(14.sp, if (!names) Ink.ink else Ink.dim), touch = 8.dp) { Marquees.set(false) }
    }
}

/** The player small: the background, the cover in its effect (moving where it can), the name along the foot. */
@Composable
private fun LookPreview() {
    val now by Player.now.collectAsState()
    val albums by Library.albums.collectAsState()
    val song = now?.song?.takeUnless { it.station } ?: remember(albums.isEmpty()) { albums.randomOrNull()?.songs?.firstOrNull() } ?: return
    val c = rememberCover(song, 800)
    val accent = c?.accent ?: Ink.ink
    val behind by Looks.on.collectAsState()
    val look by Moving.cover.collectAsState()
    val names by Marquees.on.collectAsState()
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(16 / 10f)
        .background(Ink.black).border(1.dp, Ink.line).clipToBounds()) {
        if (behind != Look.PLAIN) Backdrop(c, behind)
        val side = maxHeight * .58f
        val off = side * .05f
        Box(Modifier.padding(start = 28.dp, top = 28.dp)) {
            if (behind == Look.PLAIN) Dithered(song, Modifier.offset(off, off).size(side), px = 800)
            Box(Modifier.size(side)) {
                CoverOf(song, song.album.ifEmpty { song.title }, Modifier.fillMaxSize().graphicsLayer {
                    renderEffect = look?.let { Effects.of(it, accent, density, 1f, size.width) }
                }, px = 800)
                MovingCover(song.albumArtist.ifBlank { song.artist.substringBefore(", ") }, song.album, song.title, accent, true, Modifier.fillMaxSize())
            }
        }
        Column(Modifier.padding(start = 28.dp + side + 28.dp, top = 30.dp, end = 20.dp)) {
            BasicText(song.title.uppercase(), style = headline(20.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            BasicText(song.artist.lowercase(), style = pixel(12.sp, Ink.mid), maxLines = 1)
        }
        Marquee(song.artist.substringBefore(", "), names, Modifier.align(Alignment.BottomStart).fillMaxWidth(), size = 56.sp)
    }
}
