package app.ink.music

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Right-clicking anything that plays (2026-11-11, a tester: "I can see it, but I'm not allowed to do something as basic
 * as right-clicking it until I locate its original file"): a record's cover, an artist, a song in the grid, the player's
 * and the mini player's song, each its options in a menu where it was clicked, as a song's row has had since 2026-11-09.
 */

/** A right click on this: where, in it. */
fun Modifier.rightClick(onAt: (Offset) -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent()
            if (e.type == PointerEventType.Press && e.buttons.isSecondaryPressed) {
                onAt(e.changes.first().position); e.changes.forEach { it.consume() }
            }
        }
    }
}

/**
 * Options in a menu at a click, as a song's (SongMenu): on black, a hairline round it, the words one under another in
 * kinds; away by a click elsewhere, esc, or a word chosen; kept inside the window. `accent`: what a word lights in.
 */
@Composable
fun MenuAt(at: Offset, accent: androidx.compose.ui.graphics.Color, groups: List<List<Pair<String, () -> Unit>>>, onClose: () -> Unit) {
    val shown = remember { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } }
    fun close(then: () -> Unit = {}) { then(); shown.targetState = false }
    LaunchedEffect(shown.currentState, shown.isIdle) { if (shown.isIdle && !shown.currentState) onClose() }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val gap = with(density) { 6.dp.roundToPx() }
    androidx.compose.ui.window.Popup(
        popupPositionProvider = object : androidx.compose.ui.window.PopupPositionProvider {
            override fun calculatePosition(anchorBounds: androidx.compose.ui.unit.IntRect, windowSize: androidx.compose.ui.unit.IntSize,
                layoutDirection: androidx.compose.ui.unit.LayoutDirection, popupContentSize: androidx.compose.ui.unit.IntSize): androidx.compose.ui.unit.IntOffset {
                val x = anchorBounds.left + at.x.toInt() + gap
                val y = anchorBounds.top + at.y.toInt() + gap
                val fx = if (x + popupContentSize.width > windowSize.width - gap) (x - popupContentSize.width - gap * 2) else x
                val fy = if (y + popupContentSize.height > windowSize.height - gap) (y - popupContentSize.height - gap * 2) else y
                return androidx.compose.ui.unit.IntOffset(fx.coerceAtLeast(gap), fy.coerceAtLeast(gap))
            }
        },
        onDismissRequest = { close() },
        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
        onKeyEvent = { k -> if (k.type == KeyEventType.KeyDown && k.key == Key.Escape) { close(); true } else false },
    ) {
        androidx.compose.animation.AnimatedVisibility(
            shown,
            enter = fadeIn(tween(Motion.ENTER, easing = Motion.expo)) + slideInVertically(tween(Motion.ENTER, easing = Motion.expo)) { -it / 14 },
            exit = fadeOut(tween(Motion.EXIT, easing = Motion.expo)),
        ) {
            Column(Modifier.width(IntrinsicSize.Max).widthIn(min = 180.dp)
                .background(Ink.black).border(1.dp, Ink.line).padding(horizontal = 18.dp, vertical = 12.dp)) {
                var n = 0
                groups.filter { it.isNotEmpty() }.forEachIndexed { g, words ->
                    if (g > 0) Box(Modifier.padding(vertical = 8.dp).fillMaxWidth().height(1.dp).background(Ink.line))
                    words.forEach { (word, act) -> MenuWord(word, Ink.ink, accent, n++) { close(act) } }
                }
            }
        }
    }
}

/** A record's options: play it, shuffle it, radio from it, love it all, queue it, keep it, its artist, its folder. */
@Composable
fun RecordMenu(a: Album, at: Offset, onClose: () -> Unit) {
    val loved by Loved.ids.collectAsState()
    val kept by Offline.kept.collectAsState()
    val songs = a.songs
    val allLoved = songs.isNotEmpty() && songs.all { it.id in loved }
    val keepable = songs.filter { Offline.keepable(it) }
    val file = songs.firstOrNull()?.let { SongLink.file(it) }
    MenuAt(at, rememberCover(songs.firstOrNull(), 300)?.accent ?: Ink.ink, listOf(
        listOf(
            "play" to { Player.play(songs) },
            "shuffle" to { Player.play(songs.shuffled()) },
            "add to queue" to { songs.forEach { Player.add(it) } },
            "radio" to { songs.firstOrNull()?.let { Player.radioFrom(it) } ?: Unit },
        ),
        listOfNotNull(
            (if (allLoved) "unlove" else "love") to { songs.filter { (it.id in loved) == allLoved }.forEach { Loved.toggle(it) } },
            (if (keepable.isNotEmpty() && keepable.all { it.id in kept }) "not offline" to { Offline.remove(keepable.map { it.id }) }
                else "keep offline" to { Offline.keep(keepable) }).takeIf { keepable.isNotEmpty() },
        ),
        listOfNotNull(
            "record" to { Nav.open(Page.Record(a.id)) },
            "artist" to { Nav.open(Page.Artist(a.artist)) },
            file?.let { f -> (if (Home.mac) "show in finder" else "show in folder") to { SongLink.reveal(f) } },
        ),
    ), onClose)
}

/** An artist's options: play them, shuffle them, radio from them, their page. */
@Composable
fun ArtistMenu(name: String, at: Offset, onClose: () -> Unit) {
    val all by Library.songs.collectAsState()
    val theirs = remember(all, name) {
        val n = name.lowercase()
        all.filter { it.albumArtist.lowercase() == n || it.artist.lowercase().substringBefore(",").trim() == n }
    }
    MenuAt(at, Ink.ink, listOf(
        listOfNotNull(
            ("play" to { Player.play(theirs.sortedWith(compareBy({ it.year }, { it.album }, { it.disc }, { it.track }))) }).takeIf { theirs.isNotEmpty() },
            ("shuffle" to { Player.play(theirs.shuffled()) }).takeIf { theirs.isNotEmpty() },
            ("radio" to { theirs.randomOrNull()?.let { Player.radioFrom(it) } ?: Unit }).takeIf { theirs.isNotEmpty() },
        ),
        listOf("artist" to { Nav.open(Page.Artist(name)) }),
    ), onClose)
}


/**
 * Go to the song playing (2026-11-11, a tester: finding it in a long list took a search): the now column's title
 * clicked; the songs list scrolls to it where it's shown, else its record opens.
 */
object GoToPlaying {
    val asked = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)
    fun go(s: Song) {
        if (Nav.place == "songs" && Nav.pages.isEmpty()) asked.tryEmit(s.id)
        else if (s.album.isNotEmpty() && !s.station && !s.episode) Nav.open(Page.Record(s.albumId))
    }
}
