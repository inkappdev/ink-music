package app.ink.music

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The gestures, said where they're used until they have been (2026-11-09, as ink's launcher does): typed out a letter
 * at a time where each one works, one at a time in each place, the next once the last is done; once done, never again
 * (prefs `hints`, the screens' own). Not on a TV (a remote has none of them).
 *  - the library's top: pull down to find, the place's word for every place, swiping across; then the tab's own: the
 *    albums turned sideways, a tab's order; on listen, after a few days, the look, and after two weeks, the computer
 *    (those two pointers once: tapped, or a day after first shown);
 *  - the player: circle a thumb round the cover, flick it for the next song, the artist's name for their page;
 *  - a record's songs: swipe one for next or the queue's end, hold it for more; the queue: hold one to move it.
 */
object Hints {
    const val PULL = "pull"; const val PLACES = "places"
    const val WHEEL = "wheel"; const val FLICK = "flick"; const val CLOSE = "close"
    const val SWIPE = "swipe"
    const val ACROSS = "across"; const val TURN = "turn"; const val ORDER = "order"; const val NAME = "name"; const val QUEUE = "queue"
    const val LOOK = "look"; const val COMPUTER = "computer"

    private var prefs: SharedPreferences? = null
    private val _done = MutableStateFlow<Set<String>>(setOf(PULL, PLACES, WHEEL, FLICK, CLOSE, SWIPE, ACROSS, TURN, ORDER, NAME, QUEUE, LOOK, COMPUTER))
    private var first = 0L
    val done: StateFlow<Set<String>> = _done

    fun start(c: Context) {
        if (prefs != null) return
        val p = c.getSharedPreferences("hints", Context.MODE_PRIVATE)
        prefs = p
        _done.value = p.getStringSet("done", emptySet()).orEmpty().toSet()
        // the first day the hints were read: the pointers wait from it (a phone updated today waits from today)
        first = p.getLong("first", 0L).takeIf { it > 0 } ?: System.currentTimeMillis().also { p.edit().putLong("first", it).apply() }
    }

    /**
     * A pointer's turn: `days` since the first, not done; shown, it's said for a day at most (from the first time it's
     * shown), then done whether tapped or not: once, never a nag.
     */
    fun due(done: Set<String>, key: String, days: Int): Boolean {
        if (key in done) return false
        val now = System.currentTimeMillis()
        if (now - first < days * 86_400_000L) return false
        val p = prefs ?: return false
        val at = p.getLong("shown-$key", 0L)
        if (at == 0L) { p.edit().putLong("shown-$key", now).apply(); return true }
        if (now - at > 86_400_000L) { did(key); return false }
        return true
    }

    /** A gesture used: its hint never shows again. */
    fun did(key: String) {
        if (key in _done.value) return
        _done.value = _done.value + key
        prefs?.edit()?.putStringSet("done", _done.value)?.apply()
    }

    /** The first of `keys` not yet done, in their order, or null. */
    fun next(done: Set<String>, vararg keys: String): String? = keys.firstOrNull { it !in done }
}

/**
 * Words typed out a letter at a time, a cursor at their end while they come (ink's run menu's way), after `wait` ms;
 * all at once where the phone moves less.
 */
@Composable
fun Typed(text: String, style: TextStyle, modifier: Modifier = Modifier, wait: Long = 500, lines: Int = 2) {
    val less by Moves.less.collectAsState()
    var shown by remember(text) { mutableIntStateOf(if (less) text.length else 0) }
    var blink by remember(text) { mutableStateOf(true) }
    LaunchedEffect(text, less) {
        if (less) { shown = text.length; return@LaunchedEffect }
        delay(wait)
        while (shown < text.length) {
            shown++
            // a pause after a word, as a hand types
            delay(if (text[shown - 1] == ' ') 70 else 32)
        }
        // the cursor blinks three times, then goes
        repeat(6) { delay(380); blink = !blink }
        blink = false
    }
    val cursor = if (shown < text.length || blink) "_" else " "
    BasicText(text.take(shown) + cursor, style = style, modifier = modifier, maxLines = lines, softWrap = lines > 1)
}
