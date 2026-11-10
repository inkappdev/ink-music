package app.ink.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Whether a song can go into one of your playlists: you're signed in to your server or YouTube Music, and it's a song. */
@Composable
fun canAddToList(s: Song?): Boolean {
    val account by Server.account.collectAsState()
    val who by TubeAccount.who.collectAsState()
    return s != null && !s.station && !s.episode && (account != null || who != null)
}

/**
 * Into a playlist (the phone's, 2026-11-07: there was no way on the computer): a new one, named here, or one of yours,
 * on your server or YouTube Music; a song from elsewhere found there by its words. Once it's in, said, and back.
 */
@Composable
fun AddToPage(s: Song, onDone: () -> Unit, onClose: () -> Unit) = AddToPage(listOf(s), onDone, onClose)

/** Several songs into one playlist (picked: Picking): the server's ones there, every one found on YouTube Music there. */
@Composable
fun AddToPage(songs: List<Song>, onDone: () -> Unit, onClose: () -> Unit) {
    val s = songs.first()
    val lists by Server.playlists.collectAsState()
    val account by Server.account.collectAsState()
    val mine = remember(lists) { lists.filter { it.mine } }
    val who by TubeAccount.who.collectAsState()
    val tubeLists by TubeAccount.playlists.collectAsState()
    val scope = rememberCoroutineScope()
    // what's happening: adding, in, or didn't take
    var said by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // your server's lists as they are now (one made in another app since)
    LaunchedEffect(Unit) { Server.readPlaylists() }
    fun put(name: String, add: suspend () -> Boolean) {
        if (busy) return
        busy = true; said = "adding to ${name.lowercase()}"
        scope.launch {
            val ok = add()
            busy = false
            said = if (ok) "added to ${name.lowercase()}" else "that didn't take: ${if (s.server || s.tube) "try again" else "it isn't there to add"}"
            if (ok) { delay(900); onDone() }
        }
    }
    val list = rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        Close(onClose)
        LazyColumn(Modifier.fillMaxSize().feather(list), list, contentPadding = PaddingValues(start = SIDE, end = SIDE, top = 16.dp, bottom = 32.dp)) {
            item {
                Column(Modifier.widthIn(max = 640.dp)) {
                    BasicText("ADD TO A PLAYLIST", style = headline(56.sp), maxLines = 2)
                    Spacer(Modifier.height(8.dp))
                    BasicText(if (songs.size == 1) "${s.title}  ·  ${s.artist}".lowercase() else "${songs.size} songs", style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText(said ?: "", style = pixel(12.sp, Ink.ink), modifier = Modifier.padding(top = 10.dp))
                    Spacer(Modifier.height(10.dp))
                    if (account != null) {
                        Label("on your server")
                        NewList { name -> put(name) { Server.makePlaylist(name, songs) } }
                        if (mine.isEmpty()) BasicText("no playlists of yours there yet: name one above, enter makes it", style = pixel(12.sp, Ink.mid))
                    }
                }
            }
            if (account != null) items(mine, key = { "n" + it.id }) { pl ->
                ListChoice(pl.name, "${pl.count} ${if (pl.count == 1) "song" else "songs"}") { put(pl.name) { songs.map { Server.addTo(pl.id, it) }.all { it } } }
            }
            if (who != null) {
                item {
                    Column(Modifier.widthIn(max = 640.dp)) {
                        Spacer(Modifier.height(18.dp))
                        Label("on youtube music")
                        NewList { name -> put(name) { TubeAccount.makePlaylist(name, songs) } }
                    }
                }
                items(tubeLists, key = { "y" + it.url }) { pl ->
                    ListChoice(pl.title, pl.artist.lowercase()) { put(pl.title) { songs.map { TubeAccount.addTo(pl, it) }.all { it } } }
                }
            }
        }
    }
}

/** One of your lists to choose: its name, under it how many (or whose), a step brighter under the mouse. */
@Composable
private fun ListChoice(name: String, said: String, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val over by src.collectIsHoveredAsState()
    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().hoverable(src).clickable(src, indication = null, onClick = felt(then = onClick)).padding(vertical = 9.dp)) {
        BasicText(name.uppercase(), style = headline(22.sp, if (over) Ink.ink else Ink.mid), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (said.isNotEmpty()) BasicText(said, style = pixel(12.sp, Ink.mid), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A new list: "new >", its name typed, enter (or make) makes it with the song in it. */
@Composable
private fun NewList(onMake: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    fun make() { val n = name.trim(); if (n.isNotEmpty()) { onMake(n); name = "" } }
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText("new > ", style = pixel(14.sp, Ink.mid))
            Box(Modifier.weight(1f)) {
                if (name.isEmpty()) BasicText("a name for it", style = pixel(14.sp, Ink.dim))
                BasicTextField(
                    name, { name = it }, singleLine = true, textStyle = pixel(14.sp), cursorBrush = SolidColor(Ink.ink),
                    modifier = Modifier.fillMaxWidth().typing().onPreviewKeyEvent { k ->
                        if (k.type == KeyEventType.KeyDown && k.key == Key.Enter) { make(); true } else false
                    },
                )
            }
            if (name.isNotBlank()) Word("make", pixel(14.sp), touch = 8.dp) { make() }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line))
    }
}
