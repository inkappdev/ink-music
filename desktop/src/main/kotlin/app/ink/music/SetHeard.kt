package app.ink.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * What's playing in a DJ set (2026-11-12, Bronson: "if im listening to audio only of a dj set can the track being played be
 * displayed"): while a live set plays, every 45 s the twelve seconds playing now are heard (Recognize, Shazam's
 * fingerprint, a second decode of the set's stream at the same place); a track that's new is [now], typed out where the
 * set shows, and kept in the set's tracklist ([heard]). Only a live set, only while it plays.
 */
object SetHeard {
    data class Track(val title: String, val artist: String, val at: Long)

    /** The track playing in the set now, as last heard; null when none's been yet (or it's not a set). */
    val now = MutableStateFlow<Track?>(null)
    /** Every track heard in the set playing, in order. */
    val heard = MutableStateFlow<List<Track>>(emptyList())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false
    @Volatile private var set: String? = null

    fun isSet(s: Song?) = s != null && s.album == "live dj set" && s.tube

    fun start() {
        if (started || Local.on) return
        started = true
        scope.launch {
            Player.now.map { n -> n?.song?.takeIf { isSet(it) }?.id to (n?.playing == true) }.distinctUntilChanged().collectLatest { (id, playing) ->
                if (id == null) { now.value = null; heard.value = emptyList(); set = null; return@collectLatest }
                // another set: its own tracklist
                if (set != id) { set = id; now.value = null; heard.value = emptyList() }
                if (!playing) return@collectLatest
                val url = YouTube.stream(id.removePrefix("y"), small = true) ?: return@collectLatest
                delay(15_000)
                while (true) {
                    val pos = Player.position.value
                    val found = runCatching { Recognize.at(url, pos / 1000.0) }.getOrNull()
                    if (found != null && Player.now.value?.song?.id == id) {
                        val last = now.value
                        if (last == null || !found.title.equals(last.title, true) || !found.artist.equals(last.artist, true)) {
                            val t = Track(found.title, found.artist, pos)
                            now.value = t
                            heard.value = heard.value + t
                        }
                    }
                    delay(45_000)
                }
            }
        }
    }
}


/** Words typed out a letter at a time whenever they change (a set's track as it's heard), the last kept. */
@androidx.compose.runtime.Composable
fun TypedLine(text: String, style: androidx.compose.ui.text.TextStyle, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    var shown by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(text) { for (i in 1..text.length) { shown = text.take(i); delay(28) } }
    androidx.compose.foundation.text.BasicText(shown, style = style, modifier = modifier, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
}
