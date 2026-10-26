package app.ink.music

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
fun SettingsPage(pages: Pages) {
    val ctx = LocalContext.current
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
                BasicText("INK MUSIC", style = headline(52.sp), modifier = Modifier.weight(1f))
                Word("close", pixel(11.sp, Ink.mid), touch = 10.dp) { pages.back() }
            }
            BasicText("version ${BuildConfig.VERSION_NAME}", style = pixel(11.sp, Ink.mid))
            Spacer(Modifier.height(34.dp))

            BasicText("YOUR SERVER", style = headline(24.sp))
            Spacer(Modifier.height(6.dp))
            val a = account
            if (a == null) {
                BasicText("navidrome, or any subsonic server: your whole library, streamed from home", style = pixel(11.sp, Ink.mid))
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
                        style = pixel(11.sp, Ink.mid),
                    )
                    Spacer(Modifier.height(4.dp))
                    BasicText("tap reads it again   hold signs out", style = pixel(10.sp, Ink.mid))
                }
            }
            Spacer(Modifier.height(40.dp))

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
                        "your sign-in stays on this phone and goes only to youtube", style = pixel(11.sp, Ink.mid))
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
                            style = pixel(11.sp, Ink.mid),
                        )
                        Spacer(Modifier.height(4.dp))
                        BasicText("tap reads it again   hold signs out", style = pixel(10.sp, Ink.mid))
                    }
                }
                Spacer(Modifier.height(40.dp))
            }

            // the artists the listen page grows from (YouTube Music's mixes and records: the full build)
            if (YouTubeMusic.available) {
                val seeds by Seeds.artists.collectAsState()
                BasicText("YOUR ARTISTS", style = headline(24.sp))
                Spacer(Modifier.height(6.dp))
                BasicText(if (seeds.isEmpty()) "none picked yet: pick a few and the listen page makes mixes of them"
                    else seeds.joinToString(", ") { it.name.lowercase() }, style = pixel(11.sp, Ink.mid))
                Spacer(Modifier.height(10.dp))
                Word(if (seeds.isEmpty()) "pick" else "change", pixel(14.sp), touch = 8.dp) { pages.open(Page.Pick) }
                Spacer(Modifier.height(40.dp))
            }

            // the library's order: a to z, or shuffled anew each time it's opened
            BasicText("ORDER", style = headline(24.sp))
            Spacer(Modifier.height(6.dp))
            BasicText("records, artists and songs a to z, or shuffled: a new order every time you open ink music", style = pixel(11.sp, Ink.mid))
            Spacer(Modifier.height(10.dp))
            val shuffled by Order.shuffled.collectAsState()
            Row {
                Word("a to z", pixel(14.sp, if (!shuffled) Ink.ink else Ink.dim), Modifier.padding(end = 22.dp), touch = 8.dp) { Order.set(false) }
                Word("shuffled", pixel(14.sp, if (shuffled) Ink.ink else Ink.dim), touch = 8.dp) { Order.set(true) }
            }
            Spacer(Modifier.height(40.dp))

            // volume levelling: on, or off (each song as it was mastered)
            BasicText("EVEN VOLUME", style = headline(24.sp))
            Spacer(Modifier.height(6.dp))
            BasicText("every song as loud as the next, by its replaygain. a record played through keeps its own quiet and loud", style = pixel(11.sp, Ink.mid))
            Spacer(Modifier.height(10.dp))
            var level by remember { mutableStateOf(Playback.levelling(ctx)) }
            Row {
                Word("on", pixel(14.sp, if (level) Ink.ink else Ink.dim), Modifier.padding(end = 22.dp), touch = 8.dp) { level = true; Playback.level(true) }
                Word("off", pixel(14.sp, if (!level) Ink.ink else Ink.dim), touch = 8.dp) { level = false; Playback.level(false) }
            }
            Spacer(Modifier.height(40.dp))

            // the liner notes
            BasicText("WHAT LEAVES THE PHONE", style = headline(20.sp))
            Spacer(Modifier.height(6.dp))
            BasicText(
                "nothing of yours, to anyone but your own server. a song's artist and title go to lrclib for its lyrics; " +
                    (if (YouTubeMusic.available) "what you search for and the artists you pick go to youtube music (and, signed in there, your sign-in, to read your library); " else "") +
                    "the radio list comes from radio-browser.info, the indie chart and what a station is playing from the stations' own playlists. no accounts of ours, no tracking, no ads.",
                style = pixel(11.sp, Ink.mid),
            )
            Spacer(Modifier.height(26.dp))
            BasicText("FREE, AND OPEN", style = headline(20.sp))
            Spacer(Modifier.height(6.dp))
            BasicText("gpl-3.0. the fonts are archivo black and silkscreen (sil open font license). lyrics from lrclib.net.", style = pixel(11.sp, Ink.mid))
            Spacer(Modifier.height(26.dp))
            // the funnel: said once, plainly, never a nag
            BasicText("FROM THE MAKERS OF INK", style = headline(20.sp))
            Spacer(Modifier.height(6.dp))
            BasicText("ink is a launcher for people who listen: your phone as words, your music on your home screen.", style = pixel(11.sp, Ink.mid))
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
    } }
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
        said?.let { BasicText(it, style = pixel(11.sp, Ink.ink)); Spacer(Modifier.height(10.dp)) }
        Word(if (trying) "SIGNING IN" else "SIGN IN", headline(40.sp, if (trying) Ink.mid else Ink.ink), touch = 10.dp) {
            if (trying || address.isBlank() || user.isBlank()) return@Word
            val perm = Server.local
            if (perm != null && !Server.localAllowed(ctx)) askLocal.launch(perm) else go()
        }
    }
}

/** A line to type on: its name small and dim, what's typed in ink, a hairline under it. */
@Composable
private fun Field(name: String, value: String, onValue: (String) -> Unit, hint: String, type: KeyboardType, secret: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        BasicText(name, style = pixel(10.sp, Ink.mid))
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
