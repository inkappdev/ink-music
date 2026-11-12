package app.ink.music

import androidx.compose.ui.Alignment
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.hazeSource
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import kotlinx.coroutines.launch

/** The cog (ink's, on Silkscreen's grid: the one icon): dim like an unselected tab, 55% while pressed. */
@Composable
fun Cog(onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val c by animateColorAsState(if (pressed) Ink.mid else Ink.dim, tween(Motion.FADE), label = "cog")
    // a TV's remote passes it by: settings is in the places' menu there, and the cog under "find" caught the way up,
    // so up from the page's right went cog, then find, past "now playing" beside it
    Box(Modifier.then(if (Device.tv) Modifier.noFocus() else Modifier).clickable(src, indication = null, onClick = felt(then = onClick)).padding(6.dp)) {
        Sprite(COG, c, 2.dp)
    }
}

private val COG = listOf(
    "..X..X..",
    ".XXXXXX.",
    "XXX..XXX",
    ".X....X.",
    ".X....X.",
    "XXX..XXX",
    ".XXXXXX.",
    "..X..X..",
)

/**
 * ink music's settings, as a record's sleeve: INK MUSIC big, your server (sign in: its address,
 * your name and password; signed in: what it holds, hold to sign out), then the liner notes:
 * where it's from, what leaves the phone, the licence, and ink.
 */
@Composable
fun SettingsPage(pages: Pages, part: String? = null) {
    val ctx = LocalContext.current
    val title = when (part) { "music" -> "YOUR MUSIC"; "sound" -> "SOUND"; "offline" -> "OFFLINE"; "listening" -> "LISTENING"; "about" -> "ABOUT"; else -> "INK MUSIC" }
    val account by Server.account.collectAsState()
    val count by Server.count.collectAsState()
    val syncing by Server.syncing.collectAsState()
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val list = pageList()
    FrostedPage { frost, top -> LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 14.dp, bottom = LocalFoot.current + 40.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth()) {
                BasicText(title, style = headline(52.sp), modifier = Modifier.weight(1f).then(if (part != null) Modifier.carried("settings-$part") else Modifier))
                CloseArrow { pages.close() }
            }
            if (part == null) {
                BasicText("version ${BuildConfig.VERSION_NAME}", style = pixel(12.sp, Ink.mid))
                // looking for a newer one now (the full build: the play build's store does it), and what it found
                if (Updates.manual) {
                    val said by Updates.said.collectAsState()
                    val offer by Updates.offer.collectAsState()
                    Word("check for an update", pixel(12.sp), Modifier.padding(top = 6.dp), touch = 8.dp) { Updates.check(ctx, now = true) }
                    said?.let { BasicText(it, style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 2.dp)) }
                    offer?.let { Spacer(Modifier.height(14.dp)); UpdateCard(it, side = 0.dp) }
                }
                Spacer(Modifier.height(34.dp))
                SettingsIndex(pages)
            } else Spacer(Modifier.height(30.dp))

            // each setting a row: its name and what it's set to; a tap opens its words (SettingRows)
            val opened = rememberOpened()
            if (part == "music") Column(Modifier.cascade(1)) {
                // the phone's own music: every folder, or only the ones chosen (Android's folder chooser picks them)
                val folders by Folders.chosen.collectAsState()
                val pick = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
                ) { uri -> uri?.let(Folders::pathOf)?.let { Folders.add(ctx, it) } }
                SettingRow("THE ${Device.word.uppercase()}'S MUSIC", if (folders.isEmpty()) "every folder" else "${folders.size} ${if (folders.size == 1) "folder" else "folders"}", opened,
                    if (folders.isEmpty()) "voice notes and clips under 30 seconds left out" else "only these. a tap takes one off") {
                    folders.forEach { f -> Word(f.trimEnd('/').lowercase(), pixel(14.sp), Modifier.padding(bottom = 6.dp), touch = 6.dp) { Folders.remove(ctx, f) } }
                    Word("add a folder >", pixel(14.sp, Ink.mid), touch = 8.dp) { runCatching { pick.launch(null) } }
                }

                var addonUrl by remember { mutableStateOf(AddonMusic.baseUrl(ctx)) }
                var addonDialog by remember { mutableStateOf(false) }
                SettingRow("BITCHORD ADDON", addonUrl.ifBlank { "not configured" }, opened,
                    "try this addon before the song's usual source; optional LastWave HMAC") {
                    Word(if (addonUrl.isBlank()) "add addon >" else "configure addon >", pixel(14.sp, Ink.mid), touch = 8.dp) {
                        addonDialog = true
                    }
                    if (addonDialog) AddonConfigDialog(
                        ctx = ctx,
                        onDismiss = { addonDialog = false },
                        onSave = { addonUrl = AddonMusic.baseUrl(ctx); addonDialog = false },
                    )
                }

                val a = account
                SettingRow("YOUR SERVER", a?.name?.lowercase() ?: "sign in", opened, if (a == null) "navidrome, or any subsonic server" else null) {
                    if (a == null) SignIn()
                    else {
                        // signed in: what it holds; a tap reads it again, a hold signs out
                        // a tap and a hold the remote can give too (OK, OK held), not a gesture only a finger can
                        Column(Modifier.combinedClickable(remember { MutableInteractionSource() }, indication = null, hapticFeedbackEnabled = false,
                            onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); Server.signOut(ctx) },
                            onClick = { Server.sync() })) {
                            BasicText("${a.name} at ${a.base.substringAfter("://")}", style = pixel(13.sp))
                            BasicText("${a.user}   " + if (syncing) "reading your library" else "$count songs", style = pixel(12.sp, Ink.mid))
                            Spacer(Modifier.height(4.dp))
                            BasicText("tap reads it again   hold signs out", style = pixel(12.sp, Ink.mid))
                        }
                        // its libraries, one or all (Navidrome's; shown only when it has more than one)
                        val folders by Server.folders.collectAsState()
                        val folder by Server.folder.collectAsState()
                        if (folders.size > 1) {
                            PickLabel("library")
                            Pick(listOf("all" to (folder == null)) + folders.map { (id, name) -> name.lowercase() to (folder == id) }) { i ->
                                Server.choose(ctx, if (i == 0) null else folders[i - 1].first)
                            }
                        }
                    }
                }

                // your youtube music (the full build): signed in on youtube's own page, read only
                if (YouTubeAccount.available) {
                    val who by YouTubeAccount.who.collectAsState()
                    val reading by YouTubeAccount.reading.collectAsState()
                    val tubeLists by YouTubeAccount.playlists.collectAsState()
                    val tubeRecords by YouTubeAccount.records.collectAsState()
                    val w = who
                    SettingRow("YOUTUBE MUSIC", w?.name?.lowercase() ?: "sign in", opened, if (w == null) "your playlists, likes and records, from your own account" else null) {
                        if (w == null) {
                            BasicText("ink music isn't youtube's own app, and google could, rarely, mind one being used. " +
                                "your sign-in stays on this ${Device.word} and goes only to youtube", style = pixel(12.sp, Ink.mid))
                            Spacer(Modifier.height(10.dp))
                            Word("SIGN IN", headline(40.sp), touch = 10.dp) { YouTubeAccount.openSignIn(ctx) }
                        } else {
                            Column(Modifier.combinedClickable(remember { MutableInteractionSource() }, indication = null, hapticFeedbackEnabled = false,
                                onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); YouTubeAccount.signOut(ctx) },
                                onClick = { YouTubeAccount.sync() })) {
                                BasicText(listOf(w.name, w.handle).filter { it.isNotEmpty() }.joinToString("   ").lowercase(), style = pixel(13.sp))
                                BasicText(
                                    if (reading) "reading your library" else "${tubeLists.size} ${if (tubeLists.size == 1) "playlist" else "playlists"}   " +
                                        "${tubeRecords.size} ${if (tubeRecords.size == 1) "record" else "records"}",
                                    style = pixel(12.sp, Ink.mid),
                                )
                                Spacer(Modifier.height(4.dp))
                                BasicText("tap reads it again   hold signs out", style = pixel(12.sp, Ink.mid))
                            }
                            // what you play here, in your history there (and what youtube's mixes learn from)
                            PickLabel("youtube songs you play here, in your history there")
                            var tells by remember { mutableStateOf(YouTubeAccount.tells(ctx)) }
                            Pick(listOf("tell youtube" to tells, "don't" to !tells)) { tells = it == 0; YouTubeAccount.tell(ctx, tells) }
                        }
                    }
                }

                // your computer: paired, kept in step on the wifi (Sync)
                if (onTv()) { RowsEnd(); Spacer(Modifier.height(30.dp)); TvPhoneSetting() }
                else {
                    val peers by SyncPhone.peers.collectAsState()
                    SettingRow("YOUR COMPUTER", if (peers.isEmpty()) "pair one" else peers.joinToString(", ") { it.name.lowercase() }, opened) { ComputerSetting(pages, head = false) }
                }

                // bringing playlists in from spotify, by a file (its own API is closed to small apps)
                val spotifyAt by Spotify.at.collectAsState()
                var spotifyIn by remember(spotifyAt) { mutableStateOf(Spotify.available && Spotify.signedIn()) }
                SettingRow("FROM SPOTIFY", if (spotifyIn) "signed in" else "bring a playlist in", opened,
                    if (Spotify.available) "signed in to spotify, or from a file" else "exported from exportify.app, or from spotify's own download of your data") {
                    Row {
                        Word("bring one in", pixel(14.sp), Modifier.padding(end = 26.dp), touch = 8.dp) { pages.open(Page.Import) }
                        if (spotifyIn) Word("sign out of spotify", pixel(14.sp, Ink.mid), touch = 8.dp) { Spotify.signOut(); spotifyIn = Spotify.signedIn() }
                    }
                    if (spotifyIn) BasicText("sign out, then bring one in, to sign in to another account", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 6.dp))
                }
                if (!onTv()) RowsEnd()
            }

            if (part == "sound") Column(Modifier.cascade(1)) {
                // how good a stream is: on wifi, and on mobile data (Quality)
                var quality by remember { mutableStateOf(Quality.read(ctx)) }
                SettingRow("QUALITY", "${Quality.words[quality.first]} · ${Quality.words[quality.second]} on data", opened,
                    "original, the file as it is; high, mp3 320; data saver, the smallest") {
                    for ((where, onData) in listOf("on wifi" to false, "on mobile data" to true)) {
                        PickLabel(where)
                        Pick(Quality.words.mapIndexed { q, w -> w to ((if (onData) quality.second else quality.first) == q) }) { q ->
                            quality = if (onData) quality.first to q else q to quality.second
                            Quality.set(ctx, quality.first, quality.second)
                        }
                    }
                }
                // the phone's own equalizer, for ink music's sound
                val eqOn = remember(opened.name) { Eq.read(ctx).on }
                SettingRow("EQUALIZER", if (eqOn) "on" else "off", opened, "the ${Device.word}'s bands, and shapes to start from") {
                    Word("open it", pixel(14.sp), touch = 8.dp) { pages.open(Page.Equalizer) }
                }
                // volume levelling: on, or off (each song as it was mastered)
                var level by remember { mutableStateOf(Playback.levelling(ctx)) }
                SettingRow("EVEN VOLUME", if (level) "on" else "off", opened, "every song as loud as the next; a record keeps its own") {
                    Pick(listOf("on" to level, "off" to !level)) { level = it == 0; Playback.level(level) }
                }
                // pause and play eased: the sound fades out and back in (the player reads a file: off if it's there)
                var soft by remember { mutableStateOf(!java.io.File(ctx.filesDir, "soft-off").exists()) }
                SettingRow("SOFT PAUSE", if (soft) "on" else "off", opened, "a breath out at pause, in at play") {
                    Pick(listOf("on" to soft, "off" to !soft)) {
                        soft = it == 0
                        if (soft) java.io.File(ctx.filesDir, "soft-off").delete() else runCatching { java.io.File(ctx.filesDir, "soft-off").createNewFile() }
                    }
                }
                // a song going into the next (Crossfade; automix's first half), and reading the mixes' tempos (Tempos)
                var mode by remember { mutableStateOf(Crossfade.mode(ctx)) }
                val cross = mode != Crossfade.OFF
                SettingRow("TRANSITIONS", mode, opened, "each song into the next; automix on the beat") {
                    // said plainly when it's on but resting (it couldn't be told it wasn't crossfading: exact was on)
                    if (cross && Exact.inUse(ctx)) { BasicText("resting while your usb dac is plugged in: exact is on", style = pixel(12.sp, Ink.ink)); Spacer(Modifier.height(8.dp)) }
                    Pick(listOf(Crossfade.OFF, Crossfade.CROSSFADE, Crossfade.AUTOMIX).map { it to (mode == it) }) { i ->
                        mode = listOf(Crossfade.OFF, Crossfade.CROSSFADE, Crossfade.AUTOMIX)[i]; Crossfade.set(ctx, mode)
                    }
                    var wifi by remember { mutableStateOf(Tempos.wifiOnly(ctx)) }
                    PickLabel("reading tempos, half a megabyte a song")
                    Pick(listOf("on wifi only" to wifi, "anywhere" to !wifi)) { wifi = it == 0; Tempos.wifiOnly(ctx, wifi) }
                    // what the player last did with it: a song mixed into the next, or why one wasn't (2026-11-08:
                    // a listener's "not working on any of my phones", and nothing on the phone to say why)
                    if (cross) Crossfade.said(ctx)?.let { line ->
                        Spacer(Modifier.height(10.dp))
                        BasicText("last: $line", style = pixel(12.sp, Ink.mid))
                        // the last dozen, and which phone, onto the clipboard: to send when it isn't mixing (2026-11-11)
                        var copied by remember { mutableStateOf(false) }
                        Spacer(Modifier.height(6.dp))
                        Word(if (copied) "copied" else "copy what happened", pixel(14.sp, Ink.mid), touch = 8.dp) {
                            val text = (listOf("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, android ${android.os.Build.VERSION.RELEASE}, ink music ${BuildConfig.VERSION_NAME}, $mode") +
                                Crossfade.history(ctx)).joinToString("\n")
                            ctx.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("ink music transitions", text))
                            copied = true
                        }
                    }
                }
                // exact to a usb dac: android 14's bit-perfect path (Exact), the equalizer and even volume resting
                var exact by remember { mutableStateOf(Exact.on(ctx)) }
                SettingRow("MORE", "exact to a usb dac" + if (exact) " · on" else "", opened) {
                    PickLabel("exact to a usb dac: every sample as in the file, at its own rate")
                    Pick(listOf("on" to exact, "off" to !exact)) { exact = it == 0; Playback.exact(ctx, exact) }
                }
                RowsEnd()
            }

            // what's kept on the phone (2026-11-11: its own part, out of sound): how many and how much, kept as, keeping
            // what you love and what you play most
            if (part == "offline") Column(Modifier.cascade(1)) {
                val kept by Offline.kept.collectAsState()
                val bytes = remember(kept) { Offline.bytes(ctx) }
                SettingRow("KEPT", if (kept.isEmpty()) "none yet" else "${kept.size} ${if (kept.size == 1) "song" else "songs"} · ${LoveKeeping.said(bytes).removePrefix("about ")}", opened,
                    if (kept.isEmpty()) "keep a song, record or playlist (on its page, or held) to play it with no signal" else null) {
                    if (kept.isNotEmpty()) {
                        Word("see them", pixel(14.sp), touch = 8.dp) { pages.open(Page.Kept) }
                        Spacer(Modifier.height(8.dp))
                        var sure by remember { mutableStateOf(false) }
                        LaunchedEffect(sure) { if (sure) { kotlinx.coroutines.delay(3_000); sure = false } }
                        Word(if (sure) "remove them all" else "remove all", pixel(14.sp, if (sure) Ink.ink else Ink.mid), touch = 8.dp) {
                            if (!sure) sure = true else { sure = false; Offline.removeAll(ctx) }
                        }
                    }
                }
                var small by remember { mutableStateOf(Offline.mp3(ctx)) }
                SettingRow("KEPT AS", if (small) "smaller" else "as they are", opened, "the original (lossless if it is), or mp3 320 and youtube's lighter stream") {
                    Pick(listOf("as they are" to !small, "smaller" to small)) { small = it == 1; Offline.mp3(ctx, small) }
                }
                // a song loved, kept (KeepLoved): off, on wifi, always; the loves from before offered once
                val keepMode by KeepLoved.mode.collectAsState()
                val offered by KeepLoved.offered.collectAsState()
                SettingRow("KEEP WHAT YOU LOVE", keepMode, opened, "a song you love is kept on this ${Device.word}") {
                    Pick(listOf(LoveKeeping.OFF, LoveKeeping.WIFI, LoveKeeping.ALWAYS).map { it to (keepMode == it) }) { i ->
                        KeepLoved.set(ctx, listOf(LoveKeeping.OFF, LoveKeeping.WIFI, LoveKeeping.ALWAYS)[i])
                    }
                    if (keepMode != LoveKeeping.OFF && !offered) {
                        val before = remember(kept) { KeepLoved.before() }
                        if (before.isNotEmpty()) {
                            val size = remember(before, small) { before.sumOf { LoveKeeping.about(it.duration / 1000, small, it.tube) } }
                            PickLabel("keep the ${before.size} you've loved already? ${LoveKeeping.said(size)}")
                            Row {
                                Word("keep them", pixel(14.sp), Modifier.padding(end = 22.dp), touch = 8.dp) { KeepLoved.answer(ctx, true) }
                                Word("just new ones", pixel(14.sp, Ink.mid), touch = 8.dp) { KeepLoved.answer(ctx, false) }
                            }
                        }
                    }
                }
                // the most played kept, refreshed daily on wifi (KeepMost): off at first
                val most by KeepMost.on.collectAsState()
                SettingRow("KEEP WHAT YOU PLAY MOST", if (most) "on" else "off", opened, "the ${KeepMost.N} most in your rotation, changed as they change (on wifi)") {
                    Pick(listOf("on" to most, "off" to !most)) { KeepMost.set(ctx, it == 0) }
                }
                RowsEnd()
            }

            if (part == "listening") Column(Modifier.cascade(1)) {
                // the artists the listen page grows from (YouTube Music's mixes and records: the full build)
                if (YouTubeMusic.available) {
                    val seeds by Seeds.artists.collectAsState()
                    SettingRow("YOUR ARTISTS", if (seeds.isEmpty()) "pick a few" else "${seeds.size} picked", opened, "the listen page makes mixes of them") {
                        if (seeds.isNotEmpty()) { BasicText(seeds.joinToString(", ") { it.name.lowercase() }, style = pixel(12.sp, Ink.mid)); Spacer(Modifier.height(8.dp)) }
                        Word(if (seeds.isEmpty()) "pick" else "change", pixel(14.sp), touch = 8.dp) { pages.open(Page.Pick) }
                    }
                }
                // private listening: a switch, nothing kept or told while it's on
                var hidden by remember { mutableStateOf(Private.on(ctx)) }
                SettingRow("PRIVATE LISTENING", if (hidden) "on" else "off", opened,
                    if (hidden) "nothing kept or told. it's okay, we know you like taylor swift" else "while on, nothing you play is kept or told") {
                    Pick(listOf("on" to hidden, "off" to !hidden)) { hidden = it == 0; Private.set(ctx, hidden) }
                }
                // a YouTube video's talking skipped (SponsorBlock: the player reads files/skip-talking-off), the full build's
                if (YouTubeMusic.available) {
                    var skip by remember { mutableStateOf(!java.io.File(ctx.filesDir, "skip-talking-off").exists()) }
                    SettingRow("SKIP THE TALKING", if (skip) "on" else "off", opened, "past the talking in music videos, where sponsorblock has it marked") {
                        Pick(listOf("on" to skip, "off" to !skip)) {
                            skip = it == 0
                            if (skip) java.io.File(ctx.filesDir, "skip-talking-off").delete() else runCatching { java.io.File(ctx.filesDir, "skip-talking-off").createNewFile() }
                        }
                    }
                }
                // listenbrainz: what you play, told there (the player's process sends it)
                val lb by ListenBrainz.account.collectAsState()
                SettingRow("LISTENBRAINZ", lb?.user?.lowercase() ?: "connect", opened) { ListenBrainzSetting(head = false) }
                // last.fm: scrobbled there too, connected by your name and password once
                if (LastFm.available) {
                    val fm by LastFm.account.collectAsState()
                    SettingRow("LAST.FM", fm?.name?.lowercase() ?: "connect", opened) { LastFmSetting(head = false) }
                }
                RowsEnd()
            }

            if (part == "about") {
                Column(Modifier.cascade(1)) {
                    // the liner notes
                    BasicText("WHAT LEAVES THE PHONE", style = headline(20.sp))
                    Spacer(Modifier.height(6.dp))
                    BasicText(
                        "nothing of yours, to anyone but your own server. a song's artist and title go to lrclib for its lyrics" + (if (YouTubeMusic.available) " (and to youtube music when lrclib has none)" else "") + "; " +
                            (if (YouTubeMusic.available) "what you search for and the artists you pick go to youtube music (and, signed in there, your sign-in, to read your library, and the youtube songs you play, for your history there); " else "") +
                            "with listenbrainz or last.fm connected, what you play goes there. " +
                            (if (AddonMusic.baseUrl(ctx).isNotBlank()) "your configured addon server receives your searches and audio stream requests; its optional HMAC secret signs LastWave requests. " else "") +
                            (if (Moving.available) "with moving covers on, the record playing goes to apple music, for its moving cover; " +
                                "signed in to spotify, the song playing goes to spotify, for its canvas (and your playlists, when you bring one in). " else "") +
                            (if (YouTubeMusic.available) "with skip the talking on, the first four characters of a scrambled youtube video id go to sponsorblock, never the id itself. " else "") +
                            "the radio list comes from radio-browser.info, the indie chart and what a station is playing from the stations' own playlists; headphone tunings from autoeq (github), when you look for yours. no accounts of ours, no tracking, no ads.",
                        style = pixel(12.sp, Ink.mid),
                    )
                    Spacer(Modifier.height(26.dp))
                    BasicText("FREE, AND OPEN", style = headline(20.sp))
                    Spacer(Modifier.height(6.dp))
                    BasicText("gpl-3.0. the fonts are archivo black and silkscreen (sil open font license). lyrics from lrclib.net.", style = pixel(12.sp, Ink.mid))
                    // a coffee, offered once and quietly; not in the play build (Google Play allows no outside donation links)
                    if (BuildConfig.FLAVOR == "full") {
                        Spacer(Modifier.height(10.dp))
                        BasicText("free, always. if ink music makes your day a little better:", style = pixel(12.sp, Ink.mid))
                        Word("buy us a coffee", pixel(13.sp), touch = 8.dp) {
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Coffee.PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                    }
                    Spacer(Modifier.height(26.dp))
                }

                Column(Modifier.cascade(2)) {
                    // the funnel: said once, plainly, never a nag
                    BasicText("FROM THE MAKERS OF INK", style = headline(20.sp))
                    Spacer(Modifier.height(6.dp))
                    BasicText("ink is a launcher for people who listen: your phone as words, your music on your home screen.", style = pixel(12.sp, Ink.mid))
                    Spacer(Modifier.height(8.dp))
                    Word("ink on google play", pixel(13.sp), touch = 8.dp) {
                        runCatching {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=app.ink.launcher")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Word("inkhome.app", pixel(13.sp, Ink.mid), touch = 8.dp) {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://inkhome.app")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                }
            }
        }
    } }
}


/**
 * Settings, as four words, each opening its page, how things stand said dim under it: your music (where it comes
 * from), sound (how it reaches you), listening (how it learns you), about (what it is, what leaves the phone).
 */
@Composable
private fun SettingsIndex(pages: Pages) {
    val ctx = LocalContext.current
    val account by Server.account.collectAsState()
    val who by YouTubeAccount.who.collectAsState()
    val lb by ListenBrainz.account.collectAsState()
    val lastfm by LastFm.account.collectAsState()
    val seeds by Seeds.artists.collectAsState()
    val kept by Offline.kept.collectAsState()
    val quality = remember { Quality.read(ctx) }
    val eq = remember { Eq.read(ctx) }
    val soft = remember { !java.io.File(ctx.filesDir, "soft-off").exists() }
    @Composable
    fun Entry(word: String, said: String, part: String, order: Int) {
        Column(Modifier.fillMaxWidth().cascade(order).then(if (order == 1) Modifier.tvFirst() else Modifier)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { pages.open(Page.SettingsPart(part)) }).padding(vertical = 12.dp)) {
            // the word itself carries on into its page, as its title
            BasicText(word, style = headline(40.sp), modifier = Modifier.carried("settings-$part"))
            Spacer(Modifier.height(4.dp))
            BasicText(said, style = pixel(12.sp, Ink.mid), maxLines = 2)
        }
    }
    Entry("YOUR MUSIC", listOfNotNull(
        account?.let { it.name.lowercase() },
        if (YouTubeMusic.available) (if (who != null) "youtube music, signed in" else "youtube music") else null,
        "from spotify",
    ).joinToString(" · "), "music", 1)
    val look by Looks.on.collectAsState()
    val moving by Moving.whenOn.collectAsState()
    Entry("THE LOOK", listOfNotNull(
        look.word,
        if (Moving.available) (if (moving == Moving.OFF) "covers still" else "moving covers ${moving}") else null,
        if (Spotify.available && Spotify.signedIn() && Moving.canvasOn.collectAsState().value) "spotify canvas" else null,
        if (Moves.less.value) "moving less" else null,
    ).joinToString(" · "), "look", 2)
    Entry("SOUND", listOfNotNull(
        Quality.words.getOrNull(quality.first)?.let { "$it on wifi" },
        if (eq.on) "equalizer on" else "equalizer off",
        if (soft) "soft pause" else null,
    ).joinToString(" · "), "sound", 3)
    // what's kept on the phone, its own part since 2026-11-11 (your server's or youtube's songs: only with one of them)
    if (account != null || YouTubeMusic.available) {
        val keepMode by KeepLoved.mode.collectAsState()
        Entry("OFFLINE", listOfNotNull(
            if (kept.isEmpty()) "nothing kept yet" else "${kept.size} kept",
            if (keepMode != LoveKeeping.OFF) "keeping what you love" else null,
            if (KeepMost.on.collectAsState().value) "and what you play most" else null,
        ).joinToString(" · "), "offline", 4)
    }
    Entry("LISTENING", listOfNotNull(
        seeds.size.takeIf { it > 0 }?.let { "$it artists" },
        if (Private.on(ctx)) "private" else null,
        if (lb != null) "listenbrainz on" else null,
        if (lastfm != null) "last.fm on" else null,
    ).joinToString(" · "), "listening", 5)
    Entry("ABOUT", "version ${BuildConfig.VERSION_NAME} · what leaves the ${Device.word} · free and open", "about", 6)
}

/** Signing in to your server: its address, your name and password; what went wrong, said plainly. */
@Composable
fun SignIn() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var trying by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }
    // signs in; on Android 17 first asks to reach the wifi's devices (the server at home), and
    // signs in once that's answered
    fun go() {
        trying = true; said = null
        scope.launch {
            said = when (Server.signIn(ctx, address, user, password)) {
                Server.SignIn.OK -> { password = ""; null }
                Server.SignIn.WRONG -> "that name and password weren't right"
                Server.SignIn.UNREACHABLE ->
                    if (!Server.localAllowed(ctx)) "ink music needs to reach devices on your wifi to find your server. tap sign in again to allow it"
                    else "couldn't reach it. is the address right, and the ${Device.word} on the same wifi?"
                Server.SignIn.NOT_A_SERVER -> "something answered, but not a subsonic server"
            }
            trying = false
        }
    }
    val askLocal = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { go() }

    Column {
        Field("address", address, { address = it }, "192.168.1.10:4533", KeyboardType.Uri)
        Field("name", user, { user = it }, "", KeyboardType.Text)
        Field("password", password, { password = it }, "", KeyboardType.Password, secret = true)
        Spacer(Modifier.height(10.dp))
        said?.let { BasicText(it, style = pixel(12.sp, Ink.ink)); Spacer(Modifier.height(10.dp)) }
        Word(if (trying) "SIGNING IN" else "SIGN IN", headline(40.sp, if (trying) Ink.mid else Ink.ink), touch = 10.dp) {
            if (trying || address.isBlank() || user.isBlank()) return@Word
            val perm = Server.local
            if (perm != null && !Server.localAllowed(ctx)) askLocal.launch(perm) else go()
        }
    }
}

/** ListenBrainz: your user token, tried and kept; then whose it is (a hold forgets it). */
@Composable
private fun ListenBrainzSetting(head: Boolean = true) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val account by ListenBrainz.account.collectAsState()
    var brought by remember { mutableStateOf(ListenBrainz.brought(ctx)) }
    var bringing by remember { mutableStateOf(false) }
    LaunchedEffect(account) { if (account != null) repeat(20) { if (brought != null) return@LaunchedEffect; kotlinx.coroutines.delay(1_000); brought = ListenBrainz.brought(ctx) } }
    if (head) { BasicText("LISTENBRAINZ", style = headline(24.sp)); Spacer(Modifier.height(6.dp)) }
    val a = account
    if (a == null) {
        var token by remember { mutableStateOf("") }
        var trying by remember { mutableStateOf(false) }
        var said by remember { mutableStateOf<String?>(null) }
        BasicText("what you play, kept as your listening history on listenbrainz.org: open, and yours. " +
            "your user token is on its settings page", style = pixel(12.sp, Ink.mid))
        Spacer(Modifier.height(8.dp))
        Word("listenbrainz.org/settings", pixel(13.sp, Ink.mid), touch = 8.dp) {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://listenbrainz.org/settings/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        Spacer(Modifier.height(14.dp))
        Field("user token", token, { token = it }, "", KeyboardType.Password, secret = true)
        said?.let { BasicText(it, style = pixel(12.sp, Ink.ink)); Spacer(Modifier.height(10.dp)) }
        Word(if (trying) "CONNECTING" else "CONNECT", headline(40.sp, if (trying) Ink.mid else Ink.ink), touch = 10.dp) {
            if (trying || token.isBlank()) return@Word
            trying = true; said = null
            scope.launch {
                said = when (ListenBrainz.connect(ctx, token)) {
                    // connected: your listening there brought in at once (Fresh)
                    ListenBrainz.Connect.OK -> { token = ""; ListenBrainz.account.value?.let { Fresh.bringIn(ctx, it.user, brainz = true) }; null }
                    ListenBrainz.Connect.WRONG -> "listenbrainz didn't know that token"
                    ListenBrainz.Connect.UNREACHABLE -> "couldn't reach listenbrainz. try again in a moment"
                }
                trying = false
            }
        }
        // or only your name: what you've played there brought in, nothing sent (2026-11-11)
        var name by remember { mutableStateOf("") }
        Spacer(Modifier.height(18.dp))
        Field("or your listenbrainz name", name, { name = it }, "", KeyboardType.Text)
        Word(if (bringing) "bringing your listening in" else "bring my listening in, by name", pixel(14.sp, Ink.mid), touch = 8.dp) {
            if (bringing || name.isBlank()) return@Word
            bringing = true; said = null
            Fresh.bringIn(ctx, name, brainz = true) { b -> bringing = false; said = if (b == null) "listenbrainz has no listening worked out under that name yet" else null; brought = b }
        }
    } else {
        Column(Modifier.combinedClickable(remember { MutableInteractionSource() }, indication = null, hapticFeedbackEnabled = false,
            onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); ListenBrainz.disconnect(ctx); runCatching { SyncPhone.kept.signed("listenbrainz", System.currentTimeMillis()) } },
            onClick = {})) {
            BasicText(a.user.lowercase(), style = pixel(13.sp))
            BasicText("every song you play, but radio", style = pixel(12.sp, Ink.mid))
            Spacer(Modifier.height(4.dp))
            BasicText("hold forgets it", style = pixel(12.sp, Ink.mid))
        }
    }
    brought?.let { b ->
        Spacer(Modifier.height(14.dp))
        Column(Modifier.combinedClickable(remember { MutableInteractionSource() }, indication = null, hapticFeedbackEnabled = false,
            onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); ListenBrainz.forget(ctx); brought = null; Fresh.build(ctx) },
            onClick = {})) {
            BasicText("${b.artists.size} artists from ${b.user.lowercase()}'s listening, brought in", style = pixel(12.sp, Ink.mid))
            BasicText("so picks know you from the first day. hold lets them go", style = pixel(12.sp, Ink.mid))
        }
    }
}

/** Last.fm: your name and password, swapped for a session (the password not kept); then whose it is (a hold forgets it). */
@Composable
private fun LastFmSetting(head: Boolean = true) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val account by LastFm.account.collectAsState()
    var brought by remember { mutableStateOf(LastFm.brought(ctx)) }
    var bringing by remember { mutableStateOf(false) }
    // connected just now: brought in meanwhile, read again when it lands
    LaunchedEffect(account) { if (account != null) repeat(20) { if (brought != null) return@LaunchedEffect; kotlinx.coroutines.delay(1_000); brought = LastFm.brought(ctx) } }
    if (head) { BasicText("LAST.FM", style = headline(24.sp)); Spacer(Modifier.height(6.dp)) }
    val a = account
    if (a == null) {
        var name by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var trying by remember { mutableStateOf(false) }
        var said by remember { mutableStateOf<String?>(null) }
        BasicText("scrobble what you play to last.fm. your password goes to last.fm once, and isn't kept here", style = pixel(12.sp, Ink.mid))
        Spacer(Modifier.height(14.dp))
        Field("name", name, { name = it }, "", KeyboardType.Text)
        Field("password", password, { password = it }, "", KeyboardType.Password, secret = true)
        said?.let { BasicText(it, style = pixel(12.sp, Ink.ink)); Spacer(Modifier.height(10.dp)) }
        Word(if (trying) "CONNECTING" else "CONNECT", headline(40.sp, if (trying) Ink.mid else Ink.ink), touch = 10.dp) {
            if (trying || name.isBlank() || password.isEmpty()) return@Word
            trying = true; said = null
            scope.launch {
                said = when (LastFm.connect(ctx, name, password)) {
                    // connected: your listening there brought in at once, so ink music knows you from today
                    LastFm.Connect.OK -> { password = ""; Fresh.bringIn(ctx, name); null }
                    LastFm.Connect.WRONG -> "last.fm didn't take that name and password"
                    LastFm.Connect.UNREACHABLE -> "couldn't reach last.fm. try again in a moment"
                }
                trying = false
            }
        }
        // or only your name: what you've played there brought in, nothing scrobbled (2026-11-11: knowing someone new)
        Spacer(Modifier.height(10.dp))
        Word(if (bringing) "bringing your listening in" else "or just bring my listening in, by name", pixel(14.sp, Ink.mid), touch = 8.dp) {
            if (bringing || name.isBlank()) { if (name.isBlank()) said = "your last.fm name first"; return@Word }
            bringing = true; said = null
            Fresh.bringIn(ctx, name) { b -> bringing = false; said = if (b == null) "last.fm has no listening under that name" else null; brought = b }
        }
    } else {
        Column(Modifier.combinedClickable(remember { MutableInteractionSource() }, indication = null, hapticFeedbackEnabled = false,
            onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); LastFm.disconnect(ctx); runCatching { SyncPhone.kept.signed("lastfm", System.currentTimeMillis()) } },
            onClick = {})) {
            BasicText(a.name.lowercase(), style = pixel(13.sp))
            BasicText("every song you play, but radio", style = pixel(12.sp, Ink.mid))
            Spacer(Modifier.height(4.dp))
            BasicText("hold forgets it", style = pixel(12.sp, Ink.mid))
        }
    }
    // what's been brought in from there, and a way to let it go
    brought?.let { b ->
        Spacer(Modifier.height(14.dp))
        Column(Modifier.combinedClickable(remember { MutableInteractionSource() }, indication = null, hapticFeedbackEnabled = false,
            onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); LastFm.forget(ctx); brought = null; Fresh.build(ctx) },
            onClick = {})) {
            BasicText("${b.artists.size} artists from ${b.user.lowercase()}'s listening, brought in", style = pixel(12.sp, Ink.mid))
            BasicText("so picks know you from the first day. hold lets them go", style = pixel(12.sp, Ink.mid))
        }
    }
}

/** A line to type on: its name small and dim, what's typed in ink, a hairline under it. */
@Composable
internal fun Field(name: String, value: String, onValue: (String) -> Unit, hint: String, type: KeyboardType, secret: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        BasicText(name, style = pixel(12.sp, Ink.mid))
        Spacer(Modifier.height(6.dp))
        Box {
            if (value.isEmpty() && hint.isNotEmpty()) BasicText(hint, style = headline(18.sp, Ink.dim))
            // in Archivo, as typed: Silkscreen draws lowercase as capitals, and a name or an address
            // is case sensitive, so what's typed must show its case (the one place Archivo isn't UPPERCASE)
            BasicTextField(
                value, onValue, singleLine = true, textStyle = headline(18.sp), cursorBrush = SolidColor(Ink.ink),
                keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line))
    }
}

/**
 * Your computer (2026-10-29): the computers paired, each when it was last in
 * step, sync now, forget; pairing one from here. Shown even with none found: a computer its firewall hides (Windows,
 * a wifi it calls public) is never listed, and the way to type its address is on the page this opens.
 */
@Composable
private fun ComputerSetting(pages: Pages, head: Boolean = true) {
    val ctx = LocalContext.current
    SyncPhone.start(ctx)
    val peers by SyncPhone.peers.collectAsState()
    val said by SyncPhone.said.collectAsState()
    val waiting = remember { androidx.compose.runtime.mutableStateListOf<Computer>() }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val stop = Computers.find(ctx) { c -> if (waiting.none { it.name == c.name }) waiting += c }
        onDispose { stop() }
    }
    val scope = rememberCoroutineScope()
    if (head) { BasicText("YOUR COMPUTER", style = headline(32.sp)); Spacer(Modifier.height(6.dp)) }
    if (peers.isEmpty() && waiting.isEmpty()) {
        BasicText("ink music on a computer (mac, windows, linux) signed in to what this phone is, and the two kept in step on your wifi. " +
            "on the computer: settings, your phone, pair your phone; then here", style = pixel(12.sp, Ink.mid))
        Spacer(Modifier.height(10.dp))
        Word("SYNC WITH A COMPUTER", headline(28.sp), touch = 10.dp) { pages.open(Page.HandOver) }
    } else if (peers.isEmpty()) {
        BasicText("${waiting.first().name.lowercase()} is waiting to pair with this phone: it's signed in to what this phone is, and the two keep in step " +
            "on your wifi from then on (what you've heard, what you love, what you were playing). nothing goes through anyone else", style = pixel(12.sp, Ink.mid))
        Spacer(Modifier.height(10.dp))
        Word("SYNC WITH A COMPUTER", headline(28.sp), touch = 10.dp) { pages.open(Page.HandOver) }
    } else {
        val tick by androidx.compose.runtime.produceState(System.currentTimeMillis()) { while (true) { kotlinx.coroutines.delay(30_000); value = System.currentTimeMillis() } }
        peers.forEach { p ->
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    BasicText(p.name.uppercase(), style = headline(22.sp))
                    BasicText(if (p.seen == 0L) (if (p.hub) "paired through your phone" else "paired, not in step yet") else "in step " + ago(tick - p.seen), style = pixel(12.sp, Ink.mid))
                }
                Word("forget", pixel(13.sp, Ink.mid), touch = 8.dp) { SyncPhone.forget(p.id) }
            }
            Spacer(Modifier.height(8.dp))
        }
        BasicText(said ?: "on the same wifi they keep in step by themselves, every minute this app is open", style = pixel(12.sp, Ink.mid))
        Spacer(Modifier.height(10.dp))
        Row {
            Word("sync now", pixel(14.sp), Modifier.padding(end = 22.dp), touch = 8.dp) { scope.launch { SyncPhone.now() } }
            Word("pair another", pixel(14.sp, Ink.mid), touch = 8.dp) { pages.open(Page.HandOver) }
        }
    }
    if (head) Spacer(Modifier.height(40.dp))
}

/** "just now", "4 minutes ago", "2 hours ago", "3 days ago". */
fun ago(ms: Long): String = when {
    ms < 60_000 -> "just now"
    ms < 3_600_000 -> (ms / 60_000).let { "$it ${if (it == 1L) "minute" else "minutes"} ago" }
    ms < 86_400_000 -> (ms / 3_600_000).let { "$it ${if (it == 1L) "hour" else "hours"} ago" }
    else -> (ms / 86_400_000).let { "$it ${if (it == 1L) "day" else "days"} ago" }
}


/**
 * Settings on a TV (2026-11-10: the phone's five parts were too much to read and steer from a sofa): one short page.
 * The version and an update; your phone (pairing, the phones in step); what's signed in, said, changed on the phone;
 * the look behind the player; how one song goes into the next. Everything else is the phone's.
 */
@Composable
fun TvSettingsPage(pages: Pages) {
    val ctx = LocalContext.current
    val account by Server.account.collectAsState()
    val who by YouTubeAccount.who.collectAsState()
    val lb by ListenBrainz.account.collectAsState()
    val fm by LastFm.account.collectAsState()
    val look by Looks.on.collectAsState()
    var mode by remember { mutableStateOf(Crossfade.mode(ctx)) }
    val list = pageList()
    @Composable
    fun Choice(word: String, lit: Boolean, onClick: () -> Unit) = Word(word, pixel(16.sp, if (lit) Ink.ink else Ink.dim), touch = 10.dp, onClick = onClick)
    @Composable
    fun Head(text: String) { BasicText(text, style = headline(32.sp)); Spacer(Modifier.height(10.dp)) }
    FrostedPage(onClose = { pages.close() }) { frost, top -> LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost),
        contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = top + 14.dp, bottom = LocalFoot.current + 40.dp),
    ) {
        item {
            Column(Modifier.widthIn(max = 640.dp)) {
                BasicText("SETTINGS", style = headline(48.sp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText("ink music ${BuildConfig.VERSION_NAME}", style = pixel(13.sp, Ink.mid))
                    if (Updates.manual) {
                        Spacer(Modifier.width(24.dp))
                        Word("check for an update", pixel(13.sp), touch = 10.dp) { Updates.check(ctx, now = true) }
                    }
                }
                if (Updates.manual) {
                    val said by Updates.said.collectAsState()
                    val offer by Updates.offer.collectAsState()
                    said?.let { BasicText(it, style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 4.dp)) }
                    offer?.let { Spacer(Modifier.height(12.dp)); UpdateCard(it, side = 0.dp) }
                }
                Spacer(Modifier.height(36.dp))
                TvPhoneSetting()
                Spacer(Modifier.height(36.dp))
                Head("SIGNED IN")
                val on = listOfNotNull(account?.let { "your server" }, who?.let { "youtube music" }, lb?.let { "listenbrainz" }, fm?.let { "last.fm" })
                BasicText(if (on.isEmpty()) "nothing yet: sign in on your phone, then pair it above" else on.joinToString("  ·  "), style = pixel(15.sp))
                BasicText("change these on your phone", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(36.dp))
                // the look: its own page, the player beside its choices, each showing there at once (2026-11-10)
                val moving by Moving.whenOn.collectAsState()
                Column(Modifier.clickable(remember { MutableInteractionSource() }, indication = null,
                    onClick = felt { pages.open(Page.SettingsPart("look")) })) {
                    BasicText("THE LOOK", style = headline(32.sp), modifier = Modifier.carried("settings-look"))
                    Spacer(Modifier.height(4.dp))
                    BasicText(listOfNotNull(look.word, if (Moving.available && moving != Moving.OFF && Moving.canvasOn.collectAsState().value) "spotify canvas" else null).joinToString(" · "),
                        style = pixel(12.sp, Ink.mid))
                }
                Spacer(Modifier.height(36.dp))
                Head("ONE SONG INTO THE NEXT")
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    // (no automix on a TV: too much for a TV box, 2026-11-10)
                    listOf(Crossfade.OFF to "one after another", Crossfade.CROSSFADE to "crossfade").forEach { (m, word) ->
                        Choice(word, mode == m) { Crossfade.set(ctx, m); mode = m }
                    }
                }
            }
        }
    } }
}


/** Configure a BitChord-compatible addon from Your Music, not from Search. */
@Composable
private fun AddonConfigDialog(ctx: android.content.Context, onDismiss: () -> Unit, onSave: () -> Unit) {
    var url by remember { mutableStateOf(AddonMusic.baseUrl(ctx)) }
    var secret by remember { mutableStateOf(AddonMusic.secret(ctx)) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().background(Ink.black).border(1.dp, Ink.mid).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicText("BITCHORD ADDON", style = headline(18.sp))
            BasicText("Addon base URL or manifest.json URL", style = pixel(10.sp, Ink.mid))
            BasicTextField(
                value = url, onValueChange = { url = it }, singleLine = true,
                textStyle = pixel(12.sp, Ink.ink),
                modifier = Modifier.fillMaxWidth().background(Ink.plate).padding(8.dp),
            )
            BasicText("Optional LastWave HMAC secret", style = pixel(10.sp, Ink.mid))
            BasicTextField(
                value = secret, onValueChange = { secret = it }, singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                textStyle = pixel(12.sp, Ink.ink),
                modifier = Modifier.fillMaxWidth().background(Ink.plate).padding(8.dp),
            )
            BasicText("Leave the secret empty for standard BitChord addons. It is used only for /a/{token} LastWave URLs.", style = pixel(10.sp, Ink.mid))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Word("save", pixel(12.sp, Ink.ink), onClick = { AddonMusic.configure(ctx, url, secret); onSave() })
                Word("clear", pixel(12.sp, Ink.mid), onClick = { AddonMusic.configure(ctx, "", ""); url = ""; secret = ""; onSave() })
                Word("cancel", pixel(12.sp, Ink.mid), onClick = onDismiss)
            }
        }
    }
}
