package app.ink.music

import androidx.compose.ui.Alignment
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import kotlinx.coroutines.launch

/** The cog (ink's, on Silkscreen's grid: the one icon): dim like an unselected tab, 55% while pressed. */
@Composable
fun Cog(onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val c by animateColorAsState(if (pressed) Ink.mid else Ink.dim, tween(Motion.FADE), label = "cog")
    Box(Modifier.clickable(src, indication = null, onClick = felt(then = onClick)).padding(6.dp)) {
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
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    FrostedPage { frost, top -> LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().hazeSource(frost),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 14.dp, bottom = 40.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth()) {
                BasicText(title, style = headline(52.sp), modifier = Modifier.weight(1f).then(if (part != null) Modifier.carried("settings-$part") else Modifier))
                CloseArrow { pages.close() }
            }
            if (part == null) {
                BasicText("version ${BuildConfig.VERSION_NAME}", style = pixel(12.sp, Ink.mid))
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
                        if (folders.isEmpty()) "every folder on the phone. voice notes, recordings and clips under 30 seconds are left out"
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
                        Column(Modifier.pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { Server.sync() },
                                onLongPress = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); Server.signOut(ctx) },
                            )
                        }) {
                            BasicText("${a.name} at ${a.base.substringAfter("://")}", style = pixel(13.sp))
                            BasicText(
                                "${a.user}   " + if (syncing) "reading your library" else "$count songs",
                                style = pixel(12.sp, Ink.mid),
                            )
                            Spacer(Modifier.height(4.dp))
                            BasicText("tap reads it again   hold signs out", style = pixel(12.sp, Ink.mid))
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
                                "your sign-in stays on this phone and goes only to youtube", style = pixel(12.sp, Ink.mid))
                            Spacer(Modifier.height(10.dp))
                            Word("SIGN IN", headline(40.sp), touch = 10.dp) { YouTubeAccount.openSignIn(ctx) }
                        } else {
                            Column(Modifier.pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { YouTubeAccount.sync() },
                                    onLongPress = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); YouTubeAccount.signOut(ctx) },
                                )
                            }) {
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
                Column(Modifier.cascade(3)) { ComputerSetting(pages) }

                // moving covers: a record's own looping sleeve in the player, where it has one (the full build)
                if (Moving.available) Column(Modifier.cascade(3)) {
                    val whenOn by Moving.whenOn.collectAsState()
                    val inLook by Moving.inLook.collectAsState()
                    BasicText("MOVING COVERS", style = headline(24.sp))
                    Spacer(Modifier.height(6.dp))
                    BasicText("where a record has one, its own looping sleeve in the player (apple music's). silent", style = pixel(12.sp, Ink.mid))
                    Spacer(Modifier.height(10.dp))
                    Row {
                        listOf(Moving.WIFI, Moving.ALWAYS, Moving.OFF).forEach { w ->
                            Word(w, pixel(14.sp, if (whenOn == w) Ink.ink else Ink.dim), Modifier.padding(end = 26.dp), touch = 8.dp) { Moving.set(ctx, whenOn = w) }
                        }
                    }
                    if (whenOn != Moving.OFF) {
                        Spacer(Modifier.height(6.dp))
                        // the shaders are Android 13's: before it, as it is
                        if (android.os.Build.VERSION.SDK_INT >= 33) Row {
                            Word("as it is", pixel(14.sp, if (!inLook) Ink.ink else Ink.dim), Modifier.padding(end = 26.dp), touch = 8.dp) { Moving.set(ctx, inLook = false) }
                            Word("in the look", pixel(14.sp, if (inLook) Ink.ink else Ink.dim), touch = 8.dp) { Moving.set(ctx, inLook = true) }
                        }
                        if (inLook && android.os.Build.VERSION.SDK_INT >= 33) {
                            Spacer(Modifier.height(6.dp))
                            BasicText("the player's look over it: dither, pixels or fluted glass (blur and plain leave it as it is)", style = pixel(12.sp, Ink.mid))
                        }
                    }
                    Spacer(Modifier.height(40.dp))
                }

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
                    Word("bring one in", pixel(14.sp), touch = 8.dp) { pages.open(Page.Import) }
                    Spacer(Modifier.height(40.dp))
                }


            }
            if (part == "sound") {
                Column(Modifier.cascade(1)) {
                    // how good a stream is: on wifi, and on mobile data (Quality)
                    BasicText("QUALITY", style = headline(24.sp))
                    Spacer(Modifier.height(6.dp))
                    BasicText("songs from your server and youtube music. original is the file as it is; high, mp3 at 320; data saver, the smallest. the phone's own files always play as they are", style = pixel(12.sp, Ink.mid))
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
                        BasicText(if (kept.isEmpty()) "keep a song, a record or a playlist on the phone (keep offline: on its page, or held) to play it with no signal"
                            else "${kept.size} ${if (kept.size == 1) "song" else "songs"} on this phone, $mb mb", style = pixel(12.sp, Ink.mid))
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
                    BasicText("ink music's own: the phone's bands, and a few shapes to start from" + if (eqOn) "   on" else "", style = pixel(12.sp, Ink.mid))
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

                Column(Modifier.cascade(6)) {
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
                            "the radio list comes from radio-browser.info, the indie chart and what a station is playing from the stations' own playlists; headphone tunings from autoeq (github), when you look for yours. no accounts of ours, no tracking, no ads.",
                        style = pixel(12.sp, Ink.mid),
                    )
                    Spacer(Modifier.height(26.dp))
                    BasicText("FREE, AND OPEN", style = headline(20.sp))
                    Spacer(Modifier.height(6.dp))
                    BasicText("gpl-3.0. the fonts are archivo black and silkscreen (sil open font license). lyrics from lrclib.net.", style = pixel(12.sp, Ink.mid))
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
        Column(Modifier.fillMaxWidth().cascade(order).pointerInput(part) { detectTapGestures { pages.open(Page.SettingsPart(part)) } }.padding(vertical = 12.dp)) {
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
    Entry("SOUND", listOfNotNull(
        Quality.words.getOrNull(quality.first)?.let { "$it on wifi" },
        if (eq.on) "equalizer on" else "equalizer off",
        if (soft) "soft pause" else null,
        kept.size.takeIf { it > 0 }?.let { "$it kept offline" },
    ).joinToString(" · "), "sound", 2)
    Entry("LISTENING", listOfNotNull(
        seeds.size.takeIf { it > 0 }?.let { "$it artists" },
        if (lb != null) "listenbrainz on" else null,
        if (lastfm != null) "last.fm on" else null,
        if (Moves.less.value) "moving less" else null,
    ).joinToString(" · "), "listening", 3)
    Entry("ABOUT", "version ${BuildConfig.VERSION_NAME} · what leaves the phone · free and open", "about", 4)
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
                    else "couldn't reach it. is the address right, and the phone on the same wifi?"
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
                    ListenBrainz.Connect.OK -> { token = ""; null }
                    ListenBrainz.Connect.WRONG -> "listenbrainz didn't know that token"
                    ListenBrainz.Connect.UNREACHABLE -> "couldn't reach listenbrainz. try again in a moment"
                }
                trying = false
            }
        }
    } else {
        Column(Modifier.pointerInput(Unit) {
            detectTapGestures(onLongPress = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); ListenBrainz.disconnect(ctx); runCatching { SyncPhone.kept.signed("listenbrainz", System.currentTimeMillis()) } })
        }) {
            BasicText(a.user.lowercase(), style = pixel(13.sp))
            BasicText("every song you play, but radio", style = pixel(12.sp, Ink.mid))
            Spacer(Modifier.height(4.dp))
            BasicText("hold forgets it", style = pixel(12.sp, Ink.mid))
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
                    LastFm.Connect.OK -> { password = ""; null }
                    LastFm.Connect.WRONG -> "last.fm didn't take that name and password"
                    LastFm.Connect.UNREACHABLE -> "couldn't reach last.fm. try again in a moment"
                }
                trying = false
            }
        }
    } else {
        Column(Modifier.pointerInput(Unit) {
            detectTapGestures(onLongPress = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); LastFm.disconnect(ctx); runCatching { SyncPhone.kept.signed("lastfm", System.currentTimeMillis()) } })
        }) {
            BasicText(a.name.lowercase(), style = pixel(13.sp))
            BasicText("every song you play, but radio", style = pixel(12.sp, Ink.mid))
            Spacer(Modifier.height(4.dp))
            BasicText("hold forgets it", style = pixel(12.sp, Ink.mid))
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
 * Your computer (Bronson, 2026-10-29: "make everything seamlessly sync"): the computers paired, each when it was last in
 * step, sync now, forget; pairing one from here (shown, unpaired, only while one on this wifi is waiting for it, so no
 * one sees it before there's a Mac app to pair).
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
    if (peers.isEmpty() && waiting.isEmpty()) return
    val scope = rememberCoroutineScope()
    BasicText("YOUR COMPUTER", style = headline(32.sp))
    Spacer(Modifier.height(6.dp))
    if (peers.isEmpty()) {
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
                    BasicText(if (p.seen == 0L) "paired, not in step yet" else "in step " + ago(tick - p.seen), style = pixel(12.sp, Ink.mid))
                }
                Word("forget", pixel(13.sp, Ink.mid), touch = 8.dp) { SyncPhone.forget(p.id) }
            }
            Spacer(Modifier.height(8.dp))
        }
        BasicText(said ?: "on the same wifi they keep in step by themselves, every minute this app is open", style = pixel(12.sp, Ink.mid))
        Spacer(Modifier.height(10.dp))
        Row {
            Word("sync now", pixel(14.sp), Modifier.padding(end = 22.dp), touch = 8.dp) { scope.launch { SyncPhone.now() } }
            if (waiting.isNotEmpty()) Word("pair another", pixel(14.sp, Ink.mid), touch = 8.dp) { pages.open(Page.HandOver) }
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
