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
 * set's tracklist ([heard], up next). While ink music is open.
 */
object SetHeard {
    data class Track(val title: String, val artist: String, val at: Long)

    val now = MutableStateFlow<Track?>(null)
    val heard = MutableStateFlow<List<Track>>(emptyList())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false
    @Volatile private var set: String? = null

    fun isSet(n: Now?) = n != null && n.album == "live dj set"

    fun start(c: Context) {
        if (started) return
        started = true
        val app = c.applicationContext
        scope.launch {
            var lastAsked = 0L
            while (true) {
                delay(5_000)
                val n = Playback.now.value
                val song = n?.song?.takeIf { isSet(n) && n.playing }
                if (song == null) { if (!isSet(n)) { now.value = null; set = null }; continue }
                if (set != song.id) { set = song.id; now.value = null; heard.value = emptyList(); lastAsked = System.currentTimeMillis() - 30_000 }
                if (System.currentTimeMillis() - lastAsked < 45_000) continue
                lastAsked = System.currentTimeMillis()
                val pos = n.positionNow()
                val found = runCatching { Recognize.at(app, song, pos) }.getOrNull() ?: continue
                if (set != song.id) continue
                val last = now.value
                if (last == null || !found.title.equals(last.title, true) || !found.artist.equals(last.artist, true)) {
                    val t = Track(found.title, found.artist, pos)
                    now.value = t
                    heard.value = heard.value + t
                }
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
