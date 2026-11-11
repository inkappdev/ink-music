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
    val title = when (part) { "music" -> "YOUR MUSIC"; "sound" -> "SOUND"; "listening" -> "LISTENING"; "about" -> "ABOUT"; else -> "INK MUSIC" }
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

            if (part == "music") {
                // the phone's own music: every folder, or only the ones chosen (Android's folder chooser picks them)
                Column(Modifier.cascade(1)) {
                    val folders by Folders.chosen.collectAsState()
                    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
                    ) { uri -> uri?.let(Folders::pathOf)?.let { Folders.add(ctx, it) } }
                    BasicText("THE PHONE'S MUSIC", style = headline(24.sp))
                    Spacer(Modifier.height(6.dp))
                    BasicText(
                        if (folders.isEmpty()) "every folder on the ${Device.word}. voice notes, recordings and clips under 30 seconds are left out"
                        else "only these folders. a tap takes one off",
                        style = pixel(12.sp, Ink.mid),
                    )
                    Spacer(Modifier.height(12.dp))
                    if (folders.isEmpty()) BasicText("every folder", style = pixel(14.sp))
                    folders.forEach { f -> Word(f.trimEnd('/').lowercase(), pixel(14.sp), Modifier.padding(bottom = 6.dp), touch = 6.dp) { Folders.remove(ctx, f) } }
                    Spacer(Modifier.height(8.dp))
                    Word("add a folder >", pixel(14.sp, Ink.mid), touch = 8.dp) { runCatching { pick.launch(null) } }
                    Spacer(Modifier.height(40.dp))
                }

                Column(Modifier.cascade(1)) {
                    BasicText("YOUR SERVER", style = headline(24.sp))
                    Spacer(Modifier.height(6.dp))
                    val a = account
                    if (a == null) {
                        BasicText("navidrome, or any subsonic server: your whole library, streamed from home", style = pixel(12.sp, Ink.mid))
                        Spacer(Modifier.height(18.dp))
                        SignIn()
                    } else {
                        // signed in: what it holds; a tap reads it again, a hold signs out
                        // a tap and a hold the remote can give too (OK, OK held), not a gesture only a finger can
                        Column(Modifier.combinedClickable(remember { MutableInteractionSource() }, indication = null, hapticFeedbackEnabled = false,
                            onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); Server.signOut(ctx) },
                            onClick = { Server.sync() })) {
                            BasicText("${a.name} at ${a.base.substringAfter("://")}", style = pixel(13.sp))
                            BasicText(
                                "${a.user}   " + if (syncing) "reading your library" else "$count songs",
                                style = pixel(12.sp, Ink.mid),
                            )
                            Spacer(Modifier.height(4.dp))
                            BasicText("tap reads it again   hold signs out", style = pixel(12.sp, Ink.mid))
                        }
                        // its libraries, one or all (Navidrome's; shown only when it has more than one)
                        val folders by Server.folders.collectAsState()
                        val folder by Server.folder.collectAsState()
                        if (folders.size > 1) {
                            Spacer(Modifier.height(14.dp))
                            BasicText("library", style = pixel(12.sp, Ink.mid))
                            Spacer(Modifier.height(4.dp))
                            androidx.compose.foundation.layout.FlowRow(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                                Word("all", pixel(14.sp, if (folder == null) Ink.ink else Ink.dim), Modifier.padding(end = 26.dp), touch = 8.dp) { Server.choose(ctx, null) }
                                folders.forEach { (id, name) ->
                                    Word(name.lowercase(), pixel(14.sp, if (folder == id) Ink.ink else Ink.dim), Modifier.padding(end = 26.dp), touch = 8.dp) { Server.choose(ctx, id) }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(40.dp))
                }

                Column(Modifier.cascade(2)) {
                    // your youtube music (the full build): signed in on youtube's own page, read only
                    if (YouTubeAccount.available) {
                        val who by YouTubeAccount.who.collectAsState()
                        val reading by YouTubeAccount.reading.collectAsState()
                        val tubeLists by YouTubeAccount.playlists.collectAsState()
                        val tubeRecords by YouTubeAccount.records.collectAsState()
                        BasicText("YOUTUBE MUSIC", style = headline(24.sp))
                        Spacer(Modifier.height(6.dp))
                        val w = who
                        if (w == null) {
                            BasicText("your playlists, liked songs, records and what you played lately, from your own account. " +
                                "ink music isn't youtube's own app, and google could, rarely, mind one being used. " +
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
                            Spacer(Modifier.height(18.dp))
                            BasicText("youtube songs you play here go in your history there, as in its own app, and its mixes learn from them",
                                style = pixel(12.sp, Ink.mid))
                            Spacer(Modifier.height(10.dp))
                            var tells by remember { mutableStateOf(YouTubeAccount.tells(ctx)) }
                            Row {
                                Word("tell youtube", pixel(14.sp, if (tells) Ink.ink else Ink.dim), Modifier.padding(end = 22.dp), touch = 8.dp) { tells = true; YouTubeAccount.tell(ctx, true) }
                                Word("don't", pixel(14.sp, if (!tells) Ink.ink else Ink.dim), touch = 8.dp) { tells = false; YouTubeAccount.tell(ctx, false) }
                            }
                        }
                        Spacer(Modifier.height(40.dp))
                    }
                }

                // your computer: paired, kept in step on the wifi (Sync)
                Column(Modifier.cascade(3)) { if (onTv()) TvPhoneSetting() else ComputerSetting(pages) }

                // private listening: a switch, nothing kept or told while it's on
                Column(Modifier.cascade(3)) {
                    var hidden by remember { mutableStateOf(Private.on(ctx)) }
                    BasicText("PRIVATE LISTENING", style = headline(24.sp))
                    Spacer(Modifier.height(6.dp))
                    BasicText(if (hidden) "on: nothing you play is kept or told. it's okay, we know you like taylor swift"
                        else "while it's on, what you play isn't kept here or told anywhere: no history, no scrobbles, not in your year",
                        style = pixel(12.sp, Ink.mid))
                    Spacer(Modifier.height(10.dp))
                    Row {
                        Word("on", pixel(14.sp, if (hidden) Ink.ink else Ink.dim), Modifier.padding(end = 26.dp), touch = 8.dp) { hidden = true; Private.set(ctx, true) }
                        Word("off", pixel(14.sp, if (!hidden) Ink.ink else Ink.dim), touch = 8.dp) { hidden = false; Private.set(ctx, false) }
                    }
                    Spacer(Modifier.height(40.dp))
                }

                Column(Modifier.cascade(3)) {
                    // bringing playlists in from spotify, by a file (its own API is closed to small apps)
                    BasicText("FROM SPOTIFY", style = headline(24.sp))
                    Spacer(Modifier.height(6.dp))
                    BasicText(if (Spotify.available) "bring a playlist in: signed in to spotify, or from a file" else "bring a playlist in: exported from exportify.app, or from spotify's own download of your data", style = pixel(12.sp, Ink.mid))
                    Spacer(Modifier.height(10.dp))
                    // signed in: out again, to bring in another account's playlists (2026-11-11)
                    val spotifyAt by Spotify.at.collectAsState()
                    var spotifyIn by remember(spotifyAt) { mutableStateOf(Spotify.available && Spotify.signedIn()) }
                    Row {
                        Word("bring one in", pixel(14.sp), Modifier.padding(end = 26.dp), touch = 8.dp) { pages.open(Page.Import) }
                        if (spotifyIn) Word("sign out of spotify", pixel(14.sp, Ink.mid), touch = 8.dp) { Spotify.signOut(); spotifyIn = Spotify.signedIn() }
                    }
                    if (spotifyIn) {
                        Spacer(Modifier.height(6.dp))
                        BasicText("signed in to spotify. sign out, then bring one in, to sign in to another account", style = pixel(12.sp, Ink.mid))
                    }
                    Spacer(Modifier.height(40.dp))
                }


            }
            if (part == "sound") {
                Column(Modifier.cascade(1)) {
                    // how good a stream is: on wifi, and on mobile data (Quality)
                    BasicText("QUALITY", style = headline(24.sp))
                    Spacer(Modifier.height(6.dp))
                    BasicText("songs from your server and youtube. original, the file as it is; high, mp3 320; data saver, the smallest", style = pixel(12.sp, Ink.mid))
                    var quality by remember { mutableStateOf(Quality.read(ctx)) }
                    for ((where, onData) in listOf("on wifi" to false, "on mobile data" to true)) {
                        Spacer(Modifier.height(12.dp))
                        BasicText(where, style = pixel(12.sp, Ink.mid))
                        Spacer(Modifier.height(6.dp))
                        Row {
                            Quality.words.forEachIndexed { q, word ->
                                val lit = (if (onData) quality.second else quality.first) == q
                                Word(word, pixel(14.sp, if (lit) Ink.ink else Ink.dim), Modifier.padding(end = 22.dp), touch = 8.dp) {
                                    quality = if (onData) quality.first to q else q to quality.second
                                    Quality.set(ctx, quality.first, quality.second)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(40.dp))
                }

                Column(Modifier.cascade(2)) {
                    // your server's songs kept on the phone: how many, how much, in what, and all let go
                    if (account != null || YouTubeMusic.available) {
                        val kept by Offline.kept.collectAsState()
                        BasicText("OFFLINE", style = headline(24.sp))
                        Spacer(Modifier.height(6.dp))
                        val mb = remember(kept) { Offline.bytes(ctx) / 1_048_576 }
                        BasicText(if (kept.isEmpty()) "keep a song, a record or a playlist on the ${Device.word} (keep offline: on its page, or held) to play it with no signal"
                            else "${kept.size} ${if (kept.size == 1) "song" else "songs"} on this ${Device.word}, $mb mb", style = pixel(12.sp, Ink.mid))
                        // the list itself, from here too, as the computer's (2026-11-10)
                        if (kept.isNotEmpty()) Word("see them", pixel(14.sp), Modifier.padding(top = 8.dp), touch = 8.dp) { pages.open(Page.Kept) }
                        Spacer(Modifier.height(12.dp))
                        BasicText("kept as: your server's original file (lossless if it is) and youtube's best, or smaller (mp3 320, and youtube's lighter stream)", style = pixel(12.sp, Ink.mid))
                        Spacer(Modifier.height(6.dp))
                        var small by remember { mutableStateOf(Offline.mp3(ctx)) }
                        Row {
                            Word("as they are", pixel(14.sp, if (!small) Ink.ink else Ink.dim), Modifier.padding(end = 22.dp), touch = 8.dp) { small = false; Offline.mp3(ctx, false) }
                            Word("smaller", pixel(14.sp, if (small) Ink.ink else Ink.dim), touch = 8.dp) { small = true; Offline.mp3(ctx, true) }
                        }
                        if (kept.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            var sure by remember { mutableStateOf(false) }
                            androidx.compose.runtime.LaunchedEffect(sure) { if (sure) { kotlinx.coroutines.delay(3_000); sure = false } }
                            Word(if (sure) "remove them all" else "remove all", pixel(14.sp, if (sure) Ink.ink else Ink.mid), touch = 8.dp) {
                                if (!sure) sure = true else { sure = false; Offline.removeAll(ctx) }
                            }
                        }
                        Spacer(Modifier.height(40.dp))
                    }
                }

                Column(Modifier.cascade(3)) {
                    // the phone's own equalizer, for ink music's sound
                    BasicText("EQUALIZER", style = headline(24.sp))
                    Spacer(Modifier.height(6.dp))
                    val eqOn = remember { Eq.read(ctx).on }
                    BasicText("ink music's own: the ${Device.word}'s bands, and a few shapes to start from" + if (eqOn) "   on" else "", style = pixel(12.sp, Ink.mid))
                    Spacer(Modifier.height(10.dp))
                    Word("open it", pixel(14.sp), touch = 8.dp) { pages.open(Page.Equalizer) }
                    Spacer(Modifier.height(40.dp))
                }

                Column(Modifier.cascade(4)) {
                    // volume levelling: on, or off (each song as it was mastered)
                    BasicText("EVEN VOLUME", style = headline(24.sp))
                    Spacer(Modifier.height(6.dp))
                    BasicText("every song as loud as the next, by its replaygain. a record played through keeps its own quiet and loud", style = pixel(12.sp, Ink.mid))
                    Spacer(Modifier.height(10.dp))
                    var level by remember { mutableStateOf(Playback.levelling(ctx)) }
                    Row {
                        Word("on", pixel(14.sp, if (level) Ink.ink else Ink.dim), Modifier.padding(end = 22.dp), touch = 8.dp) { level = true; Playback.level(true) }
                        Word("off", pixel(14.sp, if (!level) Ink.ink else Ink.dim), touch = 8.dp) { level = false; Playback.level(false) }
                    }
                    Spacer(Modifier.height(40.dp))
                }

                Column(Modifier.cascade(5)) {
                    // pause and play eased: the sound fades out and back in (the player reads a file: off if it's there)
                    BasicText("SOFT PAUSE", style = headline(24.sp))
                    Spacer(Modifier.height(6.dp))
                    BasicText("pausing fades the music out, playing fades it back in, a breath each way", style = pixel(12.sp, Ink.mid))
                    Spacer(Modifier.height(10.dp))
                    var soft by remember { mutableStateOf(!java.io.File(ctx.filesDir, "soft-off").exists()) }
                    Row {
                        Word("on", pixel(14.sp, if (soft) Ink.ink else Ink.dim), Modifier.padding(end = 22.dp), touch = 8.dp) {
                            soft = true; java.io.File(ctx.filesDir, "soft-off").delete()
                        }
                        Word("off", pixel(14.sp, if (!soft) Ink.ink else Ink.dim), touch = 8.dp) {
                            soft = false; runCatching { java.io.File(ctx.filesDir, "soft-off").createNewFile() }
                        }
                    }
                    Spacer(Modifier.height(40.dp))
                }

                // a song going into the next (Crossfade; automix's first half)
                Column(Modifier.cascade(5)) {
                    BasicText("TRANSITIONS", style = headline(24.sp))
                    Spacer(Modifier.height(6.dp))
                    // one line (the page had too much to read); the rest shows on the player as it happens
                    BasicText("each song runs into the next where it fades. automix matches their beats too", style = pixel(12.sp, Ink.mid))
                    Spacer(Modifier.height(10.dp))
                    var mode by remember { mutableStateOf(Crossfade.mode(ctx)) }
                    val cross = mode != Crossfade.OFF
                    // said plainly when it's on but resting (it couldn't be told it wasn't crossfading: exact was on)
                    if (cross && Exact.inUse(ctx)) {
                        BasicText("resting while your usb dac is plugged in: exact is on (below)", style = pixel(12.sp, Ink.ink))
                        Spacer(Modifier.height(8.dp))
                    }
                    Row {
                        for (m in listOf(Crossfade.OFF, Crossfade.CROSSFADE, Crossfade.AUTOMIX))
                            Word(m, pixel(14.sp, if (mode == m) Ink.ink else Ink.dim), Modifier.padding(end = 22.dp), touch = 8.dp) { mode = m; Crossfade.set(ctx, m) }
                    }
                    // what the player last did with it: a song mixed into the next, or why one wasn't (2026-11-08:
                    // a listener's "not working on any of my phones", and nothing on the phone to say why)
                    if (cross) Crossfade.said(ctx)?.let { line ->
                        Spacer(Modifier.height(10.dp))
                        BasicText("last: $line", style = pixel(12.sp, Ink.mid))
                        // the last dozen, and which phone, onto the clipboard: to send when it isn't mixing (2026-11-11)
                        var copied by remember { mutableStateOf(false) }
                        Spacer(Modifier.height(8.dp))
                        Word(if (copied) "copied" else "copy what happened", pixel(14.sp, Ink.mid), touch = 8.dp) {
                            val text = (listOf("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, android ${android.os.Build.VERSION.RELEASE}, ink music ${BuildConfig.VERSION_NAME}, $mode") +
                                Crossfade.history(ctx)).joinToString("\n")
                            ctx.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("ink music transitions", text))
                            copied = true
                        }
                    }
                    Spacer(Modifier.height(40.dp))
                }

                // reading songs' tempos for the mixes (Tempos): anywhere, or on wifi only
                Column(Modifier.cascade(5)) {
                    BasicText("READING TEMPOS", style = headline(24.sp))
                    Spacer(Modifier.height(6.dp))
                    BasicText("the mixes read each new song's tempo once, about half a megabyte each. on wifi only, mobile data mixes the songs already read",
                        style = pixel(12.sp, Ink.mid))
                    Spacer(Modifier.height(10.dp))
                    var wifi by remember { mutableStateOf(Tempos.wifiOnly(ctx)) }
                    Row {
                        Word("anywhere", pixel(14.sp, if (!wifi) Ink.ink else Ink.dim), Modifier.padding(end = 22.dp), touch = 8.dp) { wifi = false; Tempos.wifiOnly(ctx, false) }
                        Word("on wifi only", pixel(14.sp, if (wifi) Ink.ink else Ink.dim), touch = 8.dp) { wifi = true; Tempos.wifiOnly(ctx, true) }
                    }
                    Spacer(Modifier.height(40.dp))
                }

                // exact to a usb dac: android 14's bit-perfect path (Exact), the equalizer and even volume resting
                Column(Modifier.cascade(6)) {
                    BasicText("EXACT TO A USB DAC", style = headline(24.sp))
                    Spacer(Modifier.height(6.dp))
                    // one line; what touches the sound is said on the signal path
                    BasicText("ink music plays to your dac itself: every sample as in the file, at its own rate. tap the format over the cover to see the way",
                        style = pixel(12.sp, Ink.mid))
                    Spacer(Modifier.height(10.dp))
                    var exact by remember { mutableStateOf(Exact.on(ctx)) }
                    Row {
                        Word("on", pixel(14.sp, if (exact) Ink.ink else Ink.dim), Modifier.padding(end = 22.dp), touch = 8.dp) { exact = true; Playback.exact(ctx, true) }
                        Word("off", pixel(14.sp, if (!exact) Ink.ink else Ink.dim), touch = 8.dp) { exact = false; Playback.exact(ctx, false) }
                    }
                    Spacer(Modifier.height(40.dp))
                }

                Column(Modifier.cascade(7)) {
                    // the hi-res moment: a lossless song's format typed across the cover as it starts
                    BasicText("HI-RES MOMENT", style = headline(24.sp))
                    Spacer(Modifier.height(6.dp))
                    BasicText("a lossless song says so across its cover as it starts. off, it's only the small word over the cover", style = pixel(12.sp, Ink.mid))
                    Spacer(Modifier.height(10.dp))
                    val moment by Moments.on.collectAsState()
                    Row {
                        Word("on", pixel(14.sp, if (moment) Ink.ink else Ink.dim), Modifier.padding(end = 22.dp), touch = 8.dp) { Moments.set(ctx, true) }
                        Word("off", pixel(14.sp, if (!moment) Ink.ink else Ink.dim), touch = 8.dp) { Moments.set(ctx, false) }
                    }
                    Spacer(Modifier.height(40.dp))
                }


            }
            if (part == "listening") {
                Column(Modifier.cascade(1)) {
                    // the artists the listen page grows from (YouTube Music's mixes and records: the full build)
                    if (YouTubeMusic.available) {
                        val seeds by Seeds.artists.collectAsState()
                        BasicText("YOUR ARTISTS", style = headline(24.sp))
                        Spacer(Modifier.height(6.dp))
                        BasicText(if (seeds.isEmpty()) "none picked yet: pick a few and the listen page makes mixes of them"
                            else seeds.joinToString(", ") { it.name.lowercase() }, style = pixel(12.sp, Ink.mid))
                        Spacer(Modifier.height(10.dp))
                        Word(if (seeds.isEmpty()) "pick" else "change", pixel(14.sp), touch = 8.dp) { pages.open(Page.Pick) }
                        Spacer(Modifier.height(40.dp))
                    }
                }

                Column(Modifier.cascade(3)) {
                    // listenbrainz: what you play, told there (the player's process sends it)
                    ListenBrainzSetting()
                    Spacer(Modifier.height(40.dp))
                }


                // last.fm: scrobbled there too, connected by your name and password once
                if (LastFm.available) Column(Modifier.cascade(4)) {
                    LastFmSetting()
                    Spacer(Modifier.height(40.dp))
                }

                // how much moves: full, less, or as the phone says (Moves)
                Column(Modifier.cascade(4)) {
                    BasicText("MOTION", style = headline(24.sp))
                    Spacer(Modifier.height(6.dp))
                    BasicText("pages arriving word by word, covers developing, the blur by the bars. less is easier on a slower phone" +
                        if (Moves.phoneSaysLess(ctx)) "; this phone says less" else "", style = pixel(12.sp, Ink.mid))
                    Spacer(Modifier.height(10.dp))
                    val chosen by Moves.chosen.collectAsState()
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(22.dp)) {
                        listOf(Moves.AUTO, Moves.FULL, Moves.LESS).forEach { m ->
                            Word(m, pixel(14.sp, if (chosen == m) Ink.ink else Ink.dim), touch = 8.dp) { Moves.set(ctx, m) }
                        }
                    }
                    Spacer(Modifier.height(40.dp))
                }

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
                            (if (Moving.available) "with moving covers on, the record playing goes to apple music, for its moving cover; " +
                                "signed in to spotify, the song playing goes to spotify, for its canvas (and your playlists, when you bring one in). " else "") +
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
    ).joinToString(" · "), "look", 2)
    Entry("SOUND", listOfNotNull(
        Quality.words.getOrNull(quality.first)?.let { "$it on wifi" },
        if (eq.on) "equalizer on" else "equalizer off",
        if (soft) "soft pause" else null,
        kept.size.takeIf { it > 0 }?.let { "$it kept offline" },
    ).joinToString(" · "), "sound", 3)
    Entry("LISTENING", listOfNotNull(
        seeds.size.takeIf { it > 0 }?.let { "$it artists" },
        if (lb != null) "listenbrainz on" else null,
        if (lastfm != null) "last.fm on" else null,
        if (Moves.less.value) "moving less" else null,
    ).joinToString(" · "), "listening", 4)
    Entry("ABOUT", "version ${BuildConfig.VERSION_NAME} · what leaves the ${Device.word} · free and open", "about", 5)
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
private fun ListenBrainzSetting() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val account by ListenBrainz.account.collectAsState()
    var brought by remember { mutableStateOf(ListenBrainz.brought(ctx)) }
    var bringing by remember { mutableStateOf(false) }
    LaunchedEffect(account) { if (account != null) repeat(20) { if (brought != null) return@LaunchedEffect; kotlinx.coroutines.delay(1_000); brought = ListenBrainz.brought(ctx) } }
    BasicText("LISTENBRAINZ", style = headline(24.sp))
    Spacer(Modifier.height(6.dp))
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
private fun LastFmSetting() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val account by LastFm.account.collectAsState()
    var brought by remember { mutableStateOf(LastFm.brought(ctx)) }
    var bringing by remember { mutableStateOf(false) }
    // connected just now: brought in meanwhile, read again when it lands
    LaunchedEffect(account) { if (account != null) repeat(20) { if (brought != null) return@LaunchedEffect; kotlinx.coroutines.delay(1_000); brought = LastFm.brought(ctx) } }
    BasicText("LAST.FM", style = headline(24.sp))
    Spacer(Modifier.height(6.dp))
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
private fun ComputerSetting(pages: Pages) {
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
    BasicText("YOUR COMPUTER", style = headline(32.sp))
    Spacer(Modifier.height(6.dp))
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
    Spacer(Modifier.height(40.dp))
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
