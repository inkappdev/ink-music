package app.ink.music

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * ink music on the iPhone (2026-11-07): your server (signed in here, or brought by pairing with your computer), and
 * the places the phone has: listen, records, INK, podcasts, radio, settings. YouTube Music follows later.
 */
@Composable
fun App() {
    val account by Server.account.collectAsState()
    val peers by Together.peers.collectAsState()
    LaunchedEffect(Unit) { Here.dir; Here.off { Tube.start() }; Together.open(); Here.off { Tube.warm() } }
    Box(Modifier.fillMaxSize().background(Ink.black)) {
        if (account == null) {
            if (Pages.pairing) Pairing { Pages.pairing = false } else SignIn(paired = peers.isNotEmpty())
        } else Places()
    }
}

@Composable
fun Field(name: String, value: String, onValue: (String) -> Unit, secret: Boolean = false, type: KeyboardType = KeyboardType.Text) {
    Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
        BasicText(name, style = pixel(12.sp, Ink.mid))
        Spacer(Modifier.height(6.dp))
        BasicTextField(value, onValue, singleLine = true, textStyle = headline(18.sp), cursorBrush = SolidColor(Ink.ink),
            keyboardOptions = KeyboardOptions(keyboardType = type, autoCorrectEnabled = false),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line))
    }
}

/** Your server, once: its address, you, your password (kept as a token, never as it is). */
@Composable
private fun SignIn(paired: Boolean) {
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var said by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 24.dp).padding(top = 40.dp)) {
        BasicText("INK MUSIC", style = headline(52.sp))
        BasicText("your server: navidrome, or any subsonic", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 6.dp, bottom = 32.dp))
        Field("address", address, { address = it }, type = KeyboardType.Uri)
        Field("name", user, { user = it })
        Field("password", password, { password = it }, secret = true, type = KeyboardType.Password)
        Word(if (said == "signing in") "SIGNING IN" else "SIGN IN", headline(40.sp, if (said == "signing in") Ink.dim else Ink.ink)) {
            if (said == "signing in") return@Word
            said = "signing in"
            scope.launch { said = runCatching { Server.signIn(address, user, password) }.exceptionOrNull()?.let { "couldn't: ${it.message.orEmpty().lowercase()}" } }
        }
        said?.takeIf { it != "signing in" }?.let { BasicText(it, style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(36.dp))
        BasicText(if (paired) "paired: your server comes across at the next sync" else "or bring it from ink music on your computer",
            style = pixel(12.sp, Ink.mid))
        if (!paired) Word("PAIR WITH YOUR COMPUTER", headline(22.sp)) { Pages.pairing = true }
    }
}

@Composable
fun Cover(id: String?, size: Int, name: String, modifier: Modifier) {
    val img by produceState<ImageBitmap?>(Images.cached(id, size), id) { if (value == null && id != null) value = Images.load(id, size) }
    Box(modifier.background(Ink.plate)) {
        val i = img
        if (i != null) Image(i, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else BasicText(name.uppercase(), style = headline(16.sp, Ink.mid), maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(12.dp))
    }
}

/** Every record on your server, newest or a to z. */
@Composable
fun RecordsPlace(top: androidx.compose.ui.unit.Dp, foot: androidx.compose.ui.unit.Dp) {
    var order by remember { mutableStateOf("newest") }
    val albums by produceState<List<Album>?>(null, order) { value = runCatching { Server.albums(if (order == "newest") "newest" else "alphabeticalByName") }.getOrDefault(emptyList()) }
    LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top, bottom = foot),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item(span = { GridItemSpan(2) }) {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    for (o in listOf("newest", "a to z")) Word(o, pixel(14.sp, if (order == o) Ink.ink else Ink.dim)) { order = o }
                }
                if (albums == null) BasicText("reading your server", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 10.dp))
                else if (albums!!.isEmpty()) BasicText("nothing came back from your server", style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(top = 10.dp))
            }
        }
        items(albums.orEmpty(), key = { it.id }) { a -> RecordTile(a, Modifier.fillMaxWidth()) }
    }
}

/** A record as a cover, its name, whose: opens it. */
@Composable
fun RecordTile(a: Album, modifier: Modifier) {
    Column(modifier.clickable(remember { MutableInteractionSource() }, indication = null) { Pages.open(Page.Record(a)) }) {
        Cover(a.cover, 400, a.title, Modifier.fillMaxWidth().aspectRatio(1f))
        BasicText(a.title.uppercase(), style = headline(15.sp), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        BasicText(a.artist.lowercase(), style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A record: its cover across, its name, play, its songs; back closes it. */
@Composable
fun Record(a: Album, onClose: () -> Unit) {
    val songs by produceState<List<Song>?>(null, a.id) {
        value = runCatching { if (a.youtube) Here.off { Tube.songs(a) } else Server.songs(a.id) }.getOrDefault(emptyList())
    }
    val now by Sound.now.collectAsState()
    LazyColumn(Modifier.fillMaxSize().background(Ink.black).windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = if (now != null) 110.dp else 30.dp)) {
        item {
            Word("back", pixel(14.sp, Ink.mid)) { onClose() }
            Cover(a.cover, 900, a.title, Modifier.fillMaxWidth().aspectRatio(1f).padding(top = 8.dp))
            BasicText(a.title.uppercase(), style = headline(36.sp), modifier = Modifier.padding(top = 16.dp))
            BasicText((a.artist + if (a.year > 0) "   ${a.year}" else "").lowercase(), style = pixel(14.sp, Ink.mid), modifier = Modifier.padding(top = 4.dp))
            songs?.takeIf { it.isNotEmpty() }?.let { all ->
                Row(Modifier.padding(top = 12.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                    Word("play", pixel(16.sp)) { Sound.play(all, 0) }
                    Word("shuffle", pixel(16.sp, Ink.mid)) { Sound.play(all.shuffled(), 0) }
                }
            }
        }
        itemsIndexed(songs.orEmpty()) { i, s ->
            val on = now?.song?.id == s.id
            Row(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null) { Sound.play(songs!!, i) }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                BasicText("${s.track}", style = pixel(12.sp, if (on) Ink.ink else Ink.dim), modifier = Modifier.width(32.dp))
                BasicText(s.title.uppercase(), style = headline(16.sp, if (on) Ink.ink else Ink.ink.copy(alpha = .9f)), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                BasicText(clock(s.duration), style = pixel(12.sp, Ink.mid), modifier = Modifier.padding(start = 10.dp))
            }
        }
    }
}
