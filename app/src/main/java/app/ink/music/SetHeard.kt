package app.ink.music

import android.content.Context
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * What's playing in a DJ set, the phone's (the computer's since 2026-11-12; Bronson: the track typed out as it's heard):
 * while a live set plays (listened to, from INK's live sets), every 45 s the twelve seconds playing now are heard
 * (Recognize); a track that's new is [now], typed across the cover and under the set in the mini player, and kept in the
 * set's tracklist ([heard], up next). Asked only while ink music is on screen; back, the stretch missed is asked over (SetTracks).
 */
object SetHeard {
    // what to ask when, and the list in the set's order, shared with the computer (SetTracks)
    private val tracks = SetTracks()
    val heard = tracks.heard
    /** The last one found: typed in as it comes. */
    val added = tracks.added
    /** The track playing in the set now, as far as it's known. */
    val now = MutableStateFlow<SetTracks.Track?>(null)
    /** ink music on screen (Main's started): only then is anything asked; back, what was missed is asked over. */
    @Volatile var looking = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false

    fun isSet(n: Now?) = n != null && n.album == "live dj set"

    fun start(c: Context) {
        if (started) return
        started = true
        val app = c.applicationContext
        tracks.dir = app.filesDir
        scope.launch {
            var current: String? = null
            while (true) {
                delay(3_000)
                val n = Playback.now.value
                val song = n?.song?.takeIf { isSet(n) && n.playing }
                if (song == null) {
                    if (!isSet(n)) { if (current != null) tracks.reset(null); current = null; now.value = null }
                    continue
                }
                current = song.id
                val due = tracks.due(song.id, n.positionNow(), System.currentTimeMillis(), looking)
                due.forEachIndexed { k, p ->
                    val found = runCatching { Recognize.at(app, song, p) }.getOrNull()
                    android.util.Log.d("ink-recognize", "set ${song.id} at ${p / 1000} s: ${found?.let { it.artist + " - " + it.title } ?: "nothing"}")
                    tracks.found(song.id, p, found?.title, found?.artist, System.currentTimeMillis(), live = k == due.lastIndex)
                    if (k < due.lastIndex) delay(2_000)
                }
                now.value = tracks.at(Playback.now.value?.positionNow() ?: n.positionNow())
            }
        }
    }
}

/** Words typed out a letter at a time whenever they change (a set's track as it's heard), the last kept. */
@Composable
fun TypedLine(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf("") }
    LaunchedEffect(text) { for (i in 1..text.length) { shown = text.take(i); delay(28) } }
    BasicText(shown, style = style, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
