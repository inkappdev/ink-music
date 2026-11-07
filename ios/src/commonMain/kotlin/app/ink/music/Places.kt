package app.ink.music

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What's pushed over the places: a record, a show. */
sealed class Page {
    data class Record(val album: Album) : Page()
    data class Show(val show: Podcasts.Show) : Page()
}

/** The pages pushed over the places (back closes the top one), the pairing page, the place shown. */
object Pages {
    val stack = mutableStateListOf<Page>()
    var place by mutableStateOf("listen")
    var pairing by mutableStateOf(false)
    var player by mutableStateOf(false)
    fun open(p: Page) { stack += p }
    fun close() { if (stack.isNotEmpty()) stack.removeAt(stack.size - 1) }
}

val PLACES = listOf("listen", "records", "find", "ink", "podcasts", "radio", "settings")

/** The places under one big word: pressed, the others drop down under it (as the phone's). The player at the foot. */
@Composable
fun Places() {
    val now by Sound.now.collectAsState()
    var menu by remember { mutableStateOf(false) }
    val top = 86.dp
    val foot = if (now != null) 110.dp else 30.dp
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            when (Pages.place) {
                "listen" -> ListenPlace(top, foot)
                "records" -> RecordsPlace(top, foot)
                "find" -> FindPlace(top, foot)
                "ink" -> InkPlace(top, foot)
                "podcasts" -> PodcastsPlace(top, foot)
                "radio" -> RadioPlace(top, foot)
                "settings" -> SettingsPlace(top, foot)
            }
            // the place's word, over a black bar
            Column(Modifier.fillMaxWidth().background(Ink.black).padding(horizontal = 20.dp).padding(top = 10.dp)) {
                Word(Pages.place.uppercase(), headline(48.sp)) { menu = !menu }
                Box(Modifier.fillMaxWidth().height(1.dp).background(if (menu) Ink.line else Ink.black))
            }
            AnimatedVisibility(menu, enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { -it / 6 },
                exit = fadeOut(tween(140)) + slideOutVertically(tween(150)) { -it / 6 }, modifier = Modifier.padding(top = top - 14.dp)) {
                Column(Modifier.fillMaxSize().background(Ink.black).clickable(remember { MutableInteractionSource() }, indication = null) { menu = false }
                    .padding(horizontal = 20.dp).padding(top = 8.dp)) {
                    for (p in PLACES) if (p != Pages.place) Word(p.uppercase(), headline(40.sp, Ink.mid)) { Pages.place = p; menu = false }
                }
            }
        }
        Pages.stack.lastOrNull()?.let { p ->
            when (p) {
                is Page.Record -> Record(p.album) { Pages.close() }
                is Page.Show -> ShowPage(p.show, foot) { Pages.close() }
            }
        }
        if (Pages.pairing) Pairing { Pages.pairing = false }
        now?.let { n -> Box(Modifier.align(Alignment.BottomCenter)) { MiniPlayer(n) { Pages.player = true } } }
        if (Pages.player) now?.let { n -> Player(n) { Pages.player = false } }
    }
}

/** A shelf's name: small, in pixels, at 70%. */
@Composable
fun Label(text: String, modifier: Modifier = Modifier) {
    BasicText(text.lowercase(), style = pixel(12.sp, Ink.mid), modifier = modifier.padding(top = 26.dp, bottom = 10.dp))
}

/** A name and whose under it, as in a list. */
@Composable
fun Named(title: String, by: String, lit: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick).padding(vertical = 8.dp)) {
        BasicText(title.uppercase(), style = headline(18.sp, if (lit) Ink.ink else Ink.ink.copy(alpha = .92f)), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (by.isNotEmpty()) BasicText(by.lowercase(), style = pixel(12.sp, Ink.mid), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** A row of words to choose between, the chosen lit. */
@Composable
fun Choose(options: List<String>, chosen: String, onPick: (String) -> Unit) {
    Row(Modifier.padding(top = 4.dp)) {
        for (o in options) Word(o, pixel(14.sp, if (o == chosen) Ink.ink else Ink.dim), Modifier.padding(end = 22.dp)) { onPick(o) }
    }
}
