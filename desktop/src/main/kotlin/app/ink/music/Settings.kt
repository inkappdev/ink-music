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
 * Settings, as the phone's are (2026-10-29): its
 * parts down the left, each its name big and a line saying how it stands (your phone, your music, sound, listening,
 * about); the one chosen beside them.
 */
@Composable
fun SettingsPage(onClose: () -> Unit) {
    var part by remember { mutableStateOf(Settings.last) }
    LaunchedEffect(part) { Settings.last = part }
    Column(Modifier.fillMaxSize()) {
        Close(onClose)
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = 16.dp)) {
        // a narrow window (an upright screen): the parts as words across the top, the part chosen the whole width under
        // them (the 340 column of parts left the settings a third of the window, 2026-11-09)
        val narrow = maxWidth < 860.dp
        val parts = @Composable {
            if (narrow) {
                BasicText("SETTINGS", style = headline(40.sp))
                Spacer(Modifier.height(14.dp))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Settings.PARTS.forEach { p -> Word(p.uppercase(), headline(20.sp, if (p == part) Ink.ink else Ink.dim), touch = 4.dp) { part = p } }
                }
                Spacer(Modifier.height(28.dp))
            } else {
                // the parts' names alone, clean, nothing under them (2026-11-12; a line under each said how it stood, too
                // much writing, and with the big player under them keys and about were hidden); a window too short for
                // even these scrolls them
                Column(Modifier.width(340.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    BasicText("SETTINGS", style = headline(56.sp))
                    Spacer(Modifier.height(28.dp))
                    Settings.PARTS.forEach { p ->
                        Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { part = p })
                            .padding(vertical = 10.dp)) {
                            BasicText(p.uppercase(), style = headline(30.sp, if (p == part) Ink.ink else Ink.dim))
                        }
                    }
                }
            }
        }
        val chosen = @Composable { m: Modifier ->
            Column(m.verticalScroll(rememberScrollState()).padding(top = if (narrow) 0.dp else 12.dp, bottom = 40.dp)) {
                Column(Modifier.widthIn(max = 640.dp)) {
                    // each setting a row: its name and what it's set to; a click opens its words (SettingRows)
                    val opened = remember(part) { Opened() }
                    when (part) {
                        "your phone" -> {
                            val peers by SyncHub.peers.collectAsState()
                            // one paired: its name; more: how many (five names ran to two lines, 2026-11-12), named when opened
                            SettingRow("YOUR PHONE", when (peers.size) { 0 -> "pair it"; 1 -> peers[0].name.lowercase(); else -> "${peers.size} devices" }, opened) { PhoneSetting() }
                            RowsEnd()
                        }
                        "your music" -> {
                            val a by Server.account.collectAsState()
                            val who by TubeAccount.who.collectAsState()
                            val local = Library.songs.collectAsState().value.count { !it.server }
                            SettingRow("YOUR SERVER", a?.name?.lowercase() ?: "sign in", opened) { ServerSetting() }
                            SettingRow("YOUTUBE MUSIC", who?.name?.lowercase() ?: "sign in", opened) { YouTubeSetting() }
                            SettingRow("YOUR FILES", "$local ${if (local == 1) "song" else "songs"}", opened) { FilesSetting() }
                            RowsEnd()
                        }
                        // how big everything's drawn first (it was under about, and nobody found it: "everything looks way too
                        // big", 2026-11-10)
                        "the look" -> {
                            val size by Size.chosen.collectAsState()
                            SettingRow("SIZE", size?.let(::pct) ?: "fit", opened) { SizeSetting() }
                            // the places shown in the rail (2026-11-11): lit, shown; a click hides or shows one
                            val hidden by Places.hidden.collectAsState()
                            SettingRow("PLACES", if (hidden.isEmpty()) "all" else "${PLACES.size - hidden.size} of ${PLACES.size}", opened,
                                "the places along the side; a click hides or shows one" + if (Local.on) ". local files only: ink, radio and podcasts are away already" else "") {
                                Pick(PLACES.map { it to (it !in hidden) }) { i -> Places.show(PLACES[i], PLACES[i] in hidden) }
                            }
                            LookSetting(opened)
                        }
                        "sound" -> {
                            val sound by Player.sound.collectAsState()
                            val mode by Crossfade.mode.collectAsState()
                            val eq by Eq.state.collectAsState()
                            SettingRow("OUTPUT", listOfNotNull(if (sound.device == "auto") "the ${if (Home.mac) "mac" else "computer"}'s choice" else "its own",
                                if (sound.exclusive) "exact" else null, if (sound.even) "even volume" else "as recorded").joinToString(" · "), opened) { SoundSetting() }
                            SettingRow("TRANSITIONS", mode, opened) { TransitionsSetting() }
                            SettingRow("EQUALIZER", if (eq.on) eq.label.lowercase() else "off", opened) { EqualizerSetting() }
                            RowsEnd()
                        }
                        // what's kept on this computer (2026-11-11: its own part, out of sound)
                        "offline" -> { OfflineRows(opened); RowsEnd() }
                        "listening" -> {
                            val hidden by Private.on.collectAsState()
                            val discord by Discord.on.collectAsState()
                            val lb by ListenBrainz.account.collectAsState()
                            val fm by LastFm.account.collectAsState()
                            SettingRow("PRIVATE LISTENING", if (hidden) "on" else "off", opened) { PrivateSetting() }
                            if (!Local.on) SettingRow("SKIP THE TALKING", if (SkipTalking.on.collectAsState().value) "on" else "off", opened) { SkipTalkingSetting() }
                            SettingRow("DISCORD", if (discord) "on" else "off", opened) { DiscordSetting() }
                            SettingRow("LISTENBRAINZ", lb?.user?.lowercase() ?: "connect", opened) { ListenBrainzSetting() }
                            if (LastFm.available) SettingRow("LAST.FM", fm?.name?.lowercase() ?: "connect", opened) { LastFmSetting() }
                            RowsEnd()
                        }
                        "keys" -> KeysSetting()
                        else -> {
                            if (!Home.mac) { CloseSetting(); Spacer(Modifier.height(36.dp)) }
                            LocalSetting(); Spacer(Modifier.height(36.dp))
                            if (Home.windows) { TitleBarSetting(); Spacer(Modifier.height(36.dp)) }
                            AboutSetting()
                        }
                    }
                }
            }
        }
        if (narrow) Column(Modifier.fillMaxSize()) { parts(); chosen(Modifier.weight(1f).fillMaxWidth()) }
        else Row(Modifier.fillMaxSize()) { parts(); Spacer(Modifier.width(56.dp)); chosen(Modifier.weight(1f).fillMaxHeight()) }
        }
    }
}

/** What closing the window does (Windows and Linux): the app quits, or the music plays on with ink music in the tray. */
@Composable
private fun CloseSetting() {
    val keep by Tucked.keep.collectAsState()
    SettingHead("CLOSING THE WINDOW")
    BasicText(if (keep) "the music plays on: ink music waits in the tray, by the clock" else "ink music quits, and the music with it", style = pixel(12.sp, Ink.mid))
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
        Word("quit", pixel(14.sp, if (!keep) Ink.ink else Ink.dim), touch = 8.dp) { Tucked.choose(false) }
        Word("keep playing in the tray", pixel(14.sp, if (keep) Ink.ink else Ink.dim), touch = 8.dp) { Tucked.choose(true) }
    }
}

/**
 * Local files only ([Local]): ink music kept to your files, your server, lyrics and your phone; everything else online
 * (YouTube Music, INK, radio, podcasts, videos, Discord, the catalogs' covers) never started, lighter on memory and the
 * network. Taken the whole way from the next time ink music opens.
 */
@Composable
private fun LocalSetting() {
    val wanted by Local.wanted.collectAsState()
    SettingHead("LOCAL FILES ONLY")
    BasicText((if (wanted) "your files and your server, lyrics and your phone; nothing else online, lighter on memory"
        else "everything: youtube music, ink, radio, podcasts and the rest, with your files") +
        (if (wanted != Local.on) ". from the next time ink music opens" else ""), style = pixel(12.sp, Ink.mid))
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
        Word("everything", pixel(14.sp, if (!wanted) Ink.ink else Ink.dim), touch = 8.dp) { Local.choose(false) }
        Word("local files only", pixel(14.sp, if (wanted) Ink.ink else Ink.dim), touch = 8.dp) { Local.choose(true) }
    }
}

/**
 * Windows: the window's top, ink's own black (Windows' buttons drawn on it) or Windows' own bar, as every app has it
 * (2026-11-10: on Windows 10 ink's wouldn't minimise from the taskbar, a Reddit report; there Windows' own is the first
 * choice). It's set as the window opens, so it changes the next time.
 */
@Composable
private fun TitleBarSetting() {
    var chosen by remember { mutableStateOf(InkTitle.wanted) }
    SettingHead("THE WINDOW'S TOP")
    BasicText((if (chosen == "ink") "ink's own, black to the top, windows' buttons on it" else "windows' own bar, as every app has it") +
        (if (chosen != (if (InkTitle.on) "ink" else "windows")) ". from the next time ink music opens" else ""), style = pixel(12.sp, Ink.mid))
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
        Word("ink's", pixel(14.sp, if (chosen == "ink") Ink.ink else Ink.dim), touch = 8.dp) { InkTitle.choose("ink"); chosen = "ink" }
        Word("windows' own", pixel(14.sp, if (chosen == "windows") Ink.ink else Ink.dim), touch = 8.dp) { InkTitle.choose("windows"); chosen = "windows" }
    }
}

/**
 * The keys the window answers to, written down (2026-11-09): on Windows and Linux there's no menu bar to show them,
 * so Ctrl F, Ctrl Shift M and the rest were found by chance or not at all. The Mac's are in its menus.
 */
@Composable
private fun KeysSetting() {
    SettingHead("KEYS")
    BasicText("anywhere in the window, but while you type in find", style = pixel(12.sp, Ink.mid))
    for ((head, keys) in Settings.KEYS) {
        Spacer(Modifier.height(22.dp))
        BasicText(head, style = pixel(12.sp, Ink.dim))
        Spacer(Modifier.height(6.dp))
        keys.forEach { (key, what) ->
            Row(Modifier.padding(vertical = 3.dp)) {
                BasicText(key, style = pixel(13.sp), modifier = Modifier.width(180.dp))
                BasicText(what, style = pixel(13.sp, Ink.mid))
            }
        }
    }
}

/** The settings' parts, and how each stands in a line. */
object Settings {
    val PARTS = listOf("your phone", "your music", "the look", "sound", "offline", "listening") + (if (Home.mac) emptyList() else listOf("keys")) + "about"

    /** The window's keys (Main's App), as the keys part says them: Ctrl's first, then the plain ones. */
    val KEYS = listOf(
        "playing" to listOf(
            "space" to "play, pause", "ctrl →  ctrl ←" to "next, back", "→  ←" to "10 seconds on, back",
            "↑  ↓" to "louder, quieter", "ctrl s" to "shuffle", "ctrl r" to "repeat: all, one, off",
        ),
        "going" to listOf(
            "ctrl 1 to 8" to PLACES.joinToString(", "), "ctrl f" to "find", "ctrl ," to "settings", "ctrl y" to "your year",
            "ctrl [  esc" to "back",
        ),
        "seeing" to listOf(
            "ctrl 0" to "the player", "ctrl shift f" to "the player, full screen", "ctrl shift m" to "the mini player",
            "ctrl l" to "lyrics", "ctrl u" to "up next", "ctrl p" to "play on", "ctrl \\" to "what's playing beside, or along the foot",
            "ctrl shift \\" to "the places beside, or folded in", "ctrl =  ctrl -" to "bigger, smaller",
        ),
    )
    /** The part last open, for the next time (this run). */
    var last = "your music"
}

/** Your server: signed in, its name, where, how many songs, read again or sign out; else signing in. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ServerSetting() {
    val account by Server.account.collectAsState()
    val count by Server.count.collectAsState()
    val syncing by Server.syncing.collectAsState()
    SettingHead("YOUR SERVER")
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
        // its libraries, one or all (Navidrome's; shown only when it has more than one)
        val folders by Server.folders.collectAsState()
        val folder by Server.folder.collectAsState()
        if (folders.size > 1) {
            Spacer(Modifier.height(16.dp))
            BasicText("library", style = pixel(12.sp, Ink.mid))
            Spacer(Modifier.height(6.dp))
            FlowRow(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Word("all", pixel(14.sp, if (folder == null) Ink.ink else Ink.dim), Modifier.padding(end = 26.dp), touch = 8.dp) { Server.choose(null) }
                folders.forEach { (id, name) ->
                    Word(name.lowercase(), pixel(14.sp, if (folder == id) Ink.ink else Ink.dim), Modifier.padding(end = 26.dp), touch = 8.dp) { Server.choose(id) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Word("read again", pixel(14.sp), touch = 8.dp) { Server.sync() }
        Word("sign out", pixel(14.sp, Ink.mid), touch = 8.dp) { Server.signOut() }
    }
}

/** Your files: the folders they're read from (each one let go of, beside the last), how many, another, or read again. */
@Composable
private fun FilesSetting() {
    SettingHead("YOUR FILES")
    val at by Library.folderAt.collectAsState()
    val local = Library.songs.collectAsState().value.count { !it.server }
    val folders = remember(at) { Library.folders }
    BasicText(remember(at, local) { "every song in ${if (folders.size == 1) "this folder" else "these ${folders.size} folders"} · $local songs" }, style = pixel(12.sp, Ink.mid))
    Spacer(Modifier.height(12.dp))
    for (f in folders) Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText(Library.said(f), style = pixel(14.sp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).padding(vertical = 8.dp))
        if (folders.size > 1) { Spacer(Modifier.width(16.dp)); Word("remove", pixel(12.sp, Ink.mid), touch = 8.dp) { Library.remove(f) } }
    }
    Spacer(Modifier.height(8.dp))
    Word("add a folder", pixel(14.sp), touch = 8.dp) { chooseFolder { Library.add(it) } }
    Word("read again", pixel(14.sp, Ink.mid), touch = 8.dp) { Library.scan() }
}

/** About: who made this, that it's free and open, the build; ink, from its makers, said once and plainly. */
@Composable
private fun AboutSetting() {
    SettingHead("INK MUSIC")
    BasicText("free and open, gpl-3.0. no ads, no account, nothing sold", style = pixel(12.sp, Ink.mid))
    if (Keys.version.isNotEmpty()) BasicText("version ${Keys.version}", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 4.dp))
    if (Keys.build.isNotEmpty()) BasicText("build ${Keys.build}", style = pixel(12.sp, Ink.dim), modifier = Modifier.padding(top = 4.dp))
    // looking for a newer one now, and what it found (the update itself shown here to take)
    if (Updates.manual) {
        val said by Updates.said.collectAsState()
        val offer by Updates.offer.collectAsState()
        Word("check for an update", pixel(12.sp), Modifier.padding(top = 6.dp), touch = 8.dp) { Updates.check(now = true) }
        said?.let { BasicText(it, style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 2.dp)) }
        offer?.let { Spacer(Modifier.height(14.dp)); UpdateCard(it) }
    }
    Spacer(Modifier.height(10.dp))
    Word("the code", pixel(14.sp, Ink.mid), touch = 8.dp) { browse("https://github.com/inkappdev/ink-music") }
    // a coffee, offered once and quietly
    Spacer(Modifier.height(14.dp))
    BasicText("free, always. if ink music makes your day a little better:", style = pixel(12.sp, Ink.mid))
    Word("buy us a coffee", pixel(14.sp), touch = 8.dp) { browse(Coffee.PAGE) }
    Spacer(Modifier.height(36.dp))
    // the funnel: said once, plainly, never a nag
    SettingHead("FROM THE MAKERS OF INK")
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
internal fun Field(name: String, value: String, onValue: (String) -> Unit, hint: String, secret: Boolean = false, onEnter: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        BasicText(name, style = pixel(12.sp, Ink.mid))
        Spacer(Modifier.height(6.dp))
        Box {
            if (value.isEmpty() && hint.isNotEmpty()) BasicText(hint, style = headline(18.sp, Ink.dim))
            BasicTextField(
                value, onValue, singleLine = true, textStyle = headline(18.sp), cursorBrush = SolidColor(Ink.ink),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth().typing().onPreviewKeyEvent { k ->
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
    var brought by remember { mutableStateOf(ListenBrainz.brought(Home.dir)) }
    var bringing by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(account) { if (account != null) repeat(20) { if (brought != null) return@LaunchedEffect; kotlinx.coroutines.delay(1_000); brought = ListenBrainz.brought(Home.dir) } }
    SettingHead("LISTENBRAINZ")
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
                    // connected: your listening there brought in at once (Fresh)
                    ListenBrainz.Connect.OK -> { token = ""; ListenBrainz.account.value?.let { Fresh.bringIn(it.user, brainz = true) }; null }
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
        // or only your name: what you've played there brought in, nothing sent (2026-11-11)
        var name by remember { mutableStateOf("") }
        Spacer(Modifier.height(18.dp))
        Field("or your listenbrainz name", name, { name = it }, "", onEnter = {})
        Word(if (bringing) "bringing your listening in" else "bring my listening in, by name", pixel(14.sp, Ink.mid), touch = 8.dp) {
            if (bringing || name.isBlank()) return@Word
            bringing = true; said = null
            Fresh.bringIn(name, brainz = true) { b -> bringing = false; said = if (b == null) "listenbrainz has no listening worked out under that name yet" else null; brought = b }
        }
    } else {
        BasicText(a.user.lowercase(), style = pixel(13.sp))
        BasicText("every song you play, but radio", style = pixel(12.sp, Ink.mid))
        Spacer(Modifier.height(8.dp))
        Word("disconnect", pixel(14.sp, Ink.mid), touch = 8.dp) { ListenBrainz.disconnect(Home.dir); SyncHub.kept.signed("listenbrainz", System.currentTimeMillis()) }
    }
    brought?.let { b ->
        Spacer(Modifier.height(14.dp))
        BasicText("${b.artists.size} artists from ${b.user.lowercase()}'s listening, brought in, so INK knows you from the first day", style = pixel(12.sp, Ink.mid))
        Spacer(Modifier.height(6.dp))
        Word("let them go", pixel(14.sp, Ink.mid), touch = 8.dp) { ListenBrainz.forget(Home.dir); brought = null; Fresh.build() }
    }
}

/** Last.fm: your name and password swapped for a session (the password not kept); then whose it is. */
@Composable
private fun LastFmSetting() {
    val scope = rememberCoroutineScope()
    val account by LastFm.account.collectAsState()
    var brought by remember { mutableStateOf(LastFm.brought(Home.dir)) }
    var bringing by remember { mutableStateOf(false) }
    // connected just now: brought in meanwhile, read again when it lands
    androidx.compose.runtime.LaunchedEffect(account) { if (account != null) repeat(20) { if (brought != null) return@LaunchedEffect; kotlinx.coroutines.delay(1_000); brought = LastFm.brought(Home.dir) } }
    SettingHead("LAST.FM")
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
                    // connected: your listening there brought in at once, so INK knows you from today
                    LastFm.Connect.OK -> { password = ""; Fresh.bringIn(name); null }
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
        // or only your name: what you've played there brought in, nothing scrobbled (2026-11-11)
        Spacer(Modifier.height(10.dp))
        Word(if (bringing) "bringing your listening in" else "or just bring my listening in, by name", pixel(14.sp, Ink.mid), touch = 8.dp) {
            if (bringing || name.isBlank()) { if (name.isBlank()) said = "your last.fm name first"; return@Word }
            bringing = true; said = null
            Fresh.bringIn(name) { b -> bringing = false; said = if (b == null) "last.fm has no listening under that name" else null; brought = b }
        }
    } else {
        BasicText(a.name.lowercase(), style = pixel(13.sp))
        BasicText("every song you play, but radio", style = pixel(12.sp, Ink.mid))
        Spacer(Modifier.height(8.dp))
        Word("disconnect", pixel(14.sp, Ink.mid), touch = 8.dp) { LastFm.disconnect(Home.dir); SyncHub.kept.signed("lastfm", System.currentTimeMillis()) }
    }
    // what's been brought in from there, and a way to let it go
    brought?.let { b ->
        Spacer(Modifier.height(14.dp))
        BasicText("${b.artists.size} artists from ${b.user.lowercase()}'s listening, brought in, so INK knows you from the first day", style = pixel(12.sp, Ink.mid))
        Spacer(Modifier.height(6.dp))
        Word("let them go", pixel(14.sp, Ink.mid), touch = 8.dp) { LastFm.forget(Home.dir); brought = null; Fresh.build() }
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
    SettingHead("SOUND")
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

/**
 * A folder picked in the system's own way (the Mac's open panel, Windows' own picker, both folders only; Swing's
 * elsewhere); [picked] isn't called if it's cancelled.
 */
internal fun chooseFolder(picked: (java.io.File) -> Unit) {
    if (Home.windows) return WinFolder.choose("Your music", Library.folder, ::swingFolder, picked)
    (if (Home.mac) macFolder() else swingFolder())?.let(picked)
}

private fun macFolder(): java.io.File? {
    System.setProperty("apple.awt.fileDialogForDirectories", "true")
    val d = java.awt.FileDialog(null as java.awt.Frame?, "Your music", java.awt.FileDialog.LOAD)
    d.isVisible = true
    System.setProperty("apple.awt.fileDialogForDirectories", "false")
    return d.file?.let { java.io.File(d.directory, it) }
}

private fun swingFolder(): java.io.File? {
    val c = javax.swing.JFileChooser(Library.folder).apply { fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY; dialogTitle = "Your music" }
    return if (c.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) c.selectedFile else null
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
    SettingHead("YOUTUBE MUSIC")
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
 * Your phone (2026-10-29): paired once by the code, then the two kept in
 * step on the wifi ([SyncHub]): what you've heard, loves, sign-ins, what each was playing. Waiting, the code big;
 * paired, each phone and when it was last in step, forget it.
 */
@Composable
private fun PhoneSetting() {
    val peers by SyncHub.peers.collectAsState()
    val code by TubeAccount.code.collectAsState()
    val said by TubeAccount.said.collectAsState()
    SettingHead("YOUR PHONE")
    val c = code
    when {
        c != null -> {
            // the QR code (2026-11-12): the phone's camera on it, and nothing typed; the code beside it for a phone
            // without a camera, or an ink music from before
            val link by TubeAccount.link.collectAsState()
            BasicText("on your phone: ink music, settings, sync with a computer, and point it at this", style = pixel(12.sp, Ink.mid))
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                link?.let { QrCode(it, Modifier.size(184.dp)); Spacer(Modifier.width(24.dp)) }
                Column(Modifier.weight(1f)) {
                    BasicText("or pick ${TubeAccount.name.lowercase()} there, and type", style = pixel(12.sp, Ink.mid))
                    BasicText(c, style = headline(44.sp), maxLines = 1)
                }
            }
            Spacer(Modifier.height(10.dp))
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
                        BasicText(if (p.seen == 0L) (if (p.hub) "paired through your phone" else "paired, not in step yet") else "in step " + ago(tick - p.seen), style = pixel(12.sp, Ink.mid))
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
private fun OfflineRows(opened: Opened) {
    val kept by Offline.kept.collectAsState()
    val small by Offline.small.collectAsState()
    val bytes = remember(kept) { Offline.bytes() }
    SettingRow("KEPT", if (kept.isEmpty()) "none yet" else "${kept.size} ${if (kept.size == 1) "song" else "songs"} · ${LoveKeeping.said(bytes).removePrefix("about ")}", opened,
        if (kept.isEmpty()) "keep a song, record or playlist (on its page, or a right click) to play it with no signal" else null) {
        if (kept.isNotEmpty()) {
            // the list of them (lists, kept on this computer): to play, or let one go by its right click
            Word("see them", pixel(14.sp), touch = 8.dp) { Nav.open(Page.List(KEPT, "kept on this computer")) }
            var sure by remember { mutableStateOf(false) }
            LaunchedEffect(sure) { if (sure) { kotlinx.coroutines.delay(3_000); sure = false } }
            Word(if (sure) "remove them all" else "remove all", pixel(14.sp, if (sure) Ink.ink else Ink.mid), Modifier.padding(top = 10.dp), touch = 8.dp) {
                if (!sure) sure = true else { sure = false; Offline.remove(kept) }
            }
        }
    }
    SettingRow("KEPT AS", if (small) "smaller" else "as they are", opened, "the original (lossless if it is), or mp3 320 and youtube's lighter stream") {
        Pick(listOf("as they are" to !small, "smaller" to small)) { Offline.small(it == 1) }
    }
    // a song loved, kept (KeepLoved): on or off; the loves from before offered once
    val keepOn by KeepLoved.on.collectAsState()
    val offered by KeepLoved.offered.collectAsState()
    SettingRow("KEEP WHAT YOU LOVE", if (keepOn) "on" else "off", opened, "a song you love is kept on this computer") {
        Pick(listOf("on" to keepOn, "off" to !keepOn)) { KeepLoved.set(it == 0) }
        if (keepOn && !offered) {
            val before = remember(kept) { KeepLoved.before() }
            if (before.isNotEmpty()) {
                val size = remember(before, small) { before.sumOf { LoveKeeping.about(it.duration / 1000, small, it.tube) } }
                PickLabel("keep the ${before.size} you've loved already? ${LoveKeeping.said(size)}")
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    Word("keep them", pixel(14.sp), touch = 8.dp) { KeepLoved.answer(true) }
                    Word("just new ones", pixel(14.sp, Ink.mid), touch = 8.dp) { KeepLoved.answer(false) }
                }
            }
        }
    }
    // the most played kept, refreshed daily (KeepMost): off at first
    val most by KeepMost.on.collectAsState()
    SettingRow("KEEP WHAT YOU PLAY MOST", if (most) "on" else "off", opened, "the ${KeepMost.N} most in your rotation, changed as they change") {
        Pick(listOf("on" to most, "off" to !most)) { KeepMost.set(it == 0) }
    }
}

/** How big everything's drawn: fit to the screen, or a size; ⌘/Ctrl = and - step it. */
@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun SizeSetting() {
    val chosen by Size.chosen.collectAsState()
    val key = if (Home.mac) "⌘" else "ctrl"
    SettingHead("SIZE")
    BasicText(
        if (chosen == null) "fit: the desktop's own size, bigger only on a screen set small (${pct(Size.fit)} here)"
        else "${pct(chosen ?: 1f)} of the desktop's own size. $key = and $key - step it",
        style = pixel(12.sp, Ink.mid),
    )
    Spacer(Modifier.height(10.dp))
    FlowRow(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    SettingHead("PRIVATE LISTENING")
    BasicText(if (on) "on: nothing you play is kept or told. it's okay, we know you like taylor swift"
        else "while it's on, what you play isn't kept here or told anywhere: no history, no scrobbles, not in your year", style = pixel(12.sp, Ink.mid))
    Spacer(Modifier.height(10.dp))
    androidx.compose.foundation.layout.Row {
        Word("on", pixel(14.sp, if (on) Ink.ink else Ink.dim), Modifier.padding(end = 26.dp), touch = 8.dp) { Private.set(true) }
        Word("off", pixel(14.sp, if (!on) Ink.ink else Ink.dim), touch = 8.dp) { Private.set(false) }
    }
}

/** A YouTube video's talking skipped (SponsorBlock), on or off. */
@Composable
private fun SkipTalkingSetting() {
    val on by SkipTalking.on.collectAsState()
    SettingHead("SKIP THE TALKING")
    BasicText("in youtube's music videos, the talking, skits and credits are passed over, where sponsorblock's listeners have marked them", style = pixel(12.sp, Ink.mid))
    Spacer(Modifier.height(10.dp))
    androidx.compose.foundation.layout.Row {
        Word("on", pixel(14.sp, if (on) Ink.ink else Ink.dim), Modifier.padding(end = 26.dp), touch = 8.dp) { SkipTalking.set(true) }
        Word("off", pixel(14.sp, if (!on) Ink.ink else Ink.dim), touch = 8.dp) { SkipTalking.set(false) }
    }
}

/** Discord: what plays on your profile there, on or off. */
@Composable
private fun DiscordSetting() {
    val on by Discord.on.collectAsState()
    SettingHead("DISCORD")
    BasicText(if (on) "what you play shows on your discord profile while discord's open: the song, who, its cover. not while paused, or in private listening"
        else "off: nothing shown on discord", style = pixel(12.sp, Ink.mid))
    Spacer(Modifier.height(10.dp))
    androidx.compose.foundation.layout.Row {
        Word("on", pixel(14.sp, if (on) Ink.ink else Ink.dim), Modifier.padding(end = 26.dp), touch = 8.dp) { Discord.set(true) }
        Word("off", pixel(14.sp, if (!on) Ink.ink else Ink.dim), touch = 8.dp) { Discord.set(false) }
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
private fun LookSetting(opened: Opened) {
    val behind by Looks.on.collectAsState()
    val cover by Moving.cover.collectAsState()
    val moving by Moving.on.collectAsState()
    val names by Marquees.on.collectAsState()
    LookPreview()
    Spacer(Modifier.height(20.dp))
    val covers = listOf<Look?>(null) + Moving.coverLooks
    SettingRow("THE COVER", cover?.word ?: "clear", opened, "the record's cover, still or moving, in an effect of its own") {
        Pick(covers.map { (it?.word ?: "clear") to (it == cover) }) { Moving.set(cover = covers[it]) }
    }
    val behinds = listOf(Look.PLAIN, Look.BLUR, Look.PIXELS, Look.DITHER, Look.AS_IS)
    SettingRow("BEHIND IT", behind.word, opened, if (behind == Look.PLAIN) "plain: the cover's print in the dither beside it, on black"
        else "the cover across the whole window behind the player") {
        Pick(behinds.map { it.word to (it == behind) }) { Looks.set(behinds[it]) }
    }
    val pass by SpotifyHanded.pass.collectAsState()
    val canvasOn by Moving.canvasOn.collectAsState()
    SettingRow("SPOTIFY'S CANVAS", if (canvasOn) "on" else "off", opened, when {
        pass != null -> "a song's looping clip behind the player. signed in on your phone"
        SpotifyHanded.handedAt > 0 -> "signed in on your phone: open ink music there, paired, and canvases show here again"
        else -> "sign in to spotify on your phone, paired with this one, and canvases show here too"
    }) {
        Pick(listOf("on" to canvasOn, "off" to !canvasOn)) { Moving.set(canvasOn = it == 0) }
    }
    SettingRow("MOVING COVERS", if (moving) "on" else "off", opened, "apple music's looping cover, where a label made one") {
        Pick(listOf("on" to moving, "off" to !moving)) { Moving.set(on = it == 0) }
    }
    // how mixes and lists are drawn from their covers
    val print by CoverPrint.on.collectAsState()
    SettingRow("COVERS", print.word, opened, "how mixes and lists are drawn from their covers") {
        Pick(Print.entries.map { it.word to (it == print) }) { CoverPrint.set(Print.entries[it]) }
    }
    val artistVideo by Moving.artistVideo.collectAsState()
    val artistDither by Moving.artistDither.collectAsState()
    val artistGrain by Moving.artistGrain.collectAsState()
    SettingRow("ARTIST PAGES", "${if (artistVideo) "video" else "photo"} · ${if (artistGrain) "blur" else if (artistDither) "dithered" else "clear"}", opened,
        "their photo, or their moving one where apple music has one") {
        Pick(listOf("video" to artistVideo, "photo" to !artistVideo)) { Moving.set(artistVideo = it == 0) }
        Pick(listOf("dithered" to (artistDither && !artistGrain), "blur" to artistGrain, "clear" to (!artistDither && !artistGrain))) {
            Moving.set(artistDither = it == 0, artistGrain = it == 1)
        }
    }
    SettingRow("THE NAME", if (names) "moving" else "still", opened, "the big artist names along the player's foot and on their pages") {
        Pick(listOf("moving" to names, "still" to !names)) { Marquees.set(it == 0) }
    }
    RowsEnd()
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
        if (behind != Look.PLAIN) Backdrop(c, behind, song, true)
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
