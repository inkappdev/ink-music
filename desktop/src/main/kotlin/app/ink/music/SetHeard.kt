package app.ink.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * What's playing in a DJ set (2026-11-12, Bronson: "if im listening to audio only of a dj set can the track being played be
 * displayed"): while a live set plays, every 45 s the twelve seconds playing now are heard (Recognize, Shazam's
 * fingerprint, a second decode of the set's stream at the same place); a track that's new is [now], typed out where the
 * set shows, and kept in the set's tracklist ([heard]). Only a live set, only while it plays, watched or listened to.
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
            // every few seconds: what's playing that's a set, watched (Watching) or listened to (Player), and where
            var url: Pair<String, String>? = null
            var lastAsked = 0L
            while (true) {
                delay(5_000)
                val watching = Watching.set.value?.takeIf { !Watching.paused }
                val listening = Player.now.value?.takeIf { it.playing && isSet(it.song) }?.song
                val id = watching?.first?.let { "y$it" } ?: listening?.id
                if (id == null) { if (Watching.set.value == null && !isSet(Player.now.value?.song)) { now.value = null; set = null }; continue }
                if (set != id) { set = id; now.value = null; heard.value = emptyList(); lastAsked = System.currentTimeMillis() - 30_000 }
                if (System.currentTimeMillis() - lastAsked < 45_000) continue
                lastAsked = System.currentTimeMillis()
                val link = watching?.second ?: url?.takeIf { it.first == id }?.second
                    ?: YouTube.stream(id.removePrefix("y"), small = true)?.also { url = id to it } ?: continue
                val pos = if (watching != null) Watching.at() else Player.position.value
                val found = runCatching { Recognize.at(link, pos / 1000.0) }.getOrNull() ?: continue
                if (set != id) continue
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
@androidx.compose.runtime.Composable
fun TypedLine(text: String, style: androidx.compose.ui.text.TextStyle, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    var shown by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(text) { for (i in 1..text.length) { shown = text.take(i); delay(28) } }
    androidx.compose.foundation.text.BasicText(shown, style = style, modifier = modifier, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
}
