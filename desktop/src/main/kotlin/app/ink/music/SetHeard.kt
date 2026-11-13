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
    // what to ask when, and the list in the set's order, shared with the phone (SetTracks)
    private val tracks = SetTracks(Home.dir)
    /** Every track heard in the set playing, in the order they played. */
    val heard = tracks.heard
    /** The last one found: typed in as it comes. */
    val added = tracks.added
    /** The track playing in the set now, as far as it's known; null when none's been yet (or it's not a set). */
    val now = MutableStateFlow<SetTracks.Track?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false

    fun isSet(s: Song?) = s != null && s.album == "live dj set" && s.tube

    fun start() {
        if (started || Local.on) return
        started = true
        scope.launch {
            // every few seconds: what's playing that's a set, watched (Watching) or listened to (Player), and where;
            // looked at (the window not minimized), the moment playing asked and any stretch missed asked over
            var url: Pair<String, String>? = null
            var current: String? = null
            while (true) {
                delay(3_000)
                val watching = Watching.set.value?.takeIf { !Watching.paused }
                val listening = Player.now.value?.takeIf { it.playing && isSet(it.song) }?.song
                val id = watching?.first?.let { "y$it" } ?: listening?.id
                if (id == null) {
                    if (Watching.set.value == null && !isSet(Player.now.value?.song)) { if (current != null) tracks.reset(null); current = null; now.value = null }
                    continue
                }
                current = id
                fun pos() = if (watching != null) Watching.at() else Player.position.value
                // minimized too (2026-11-12, Bronson: "lets just have it fill anyway")
                val looking = true
                val due = tracks.due(id, pos(), System.currentTimeMillis(), looking)
                if (due.isNotEmpty()) {
                    val link = watching?.second ?: url?.takeIf { it.first == id }?.second
                        ?: YouTube.stream(id.removePrefix("y"), small = true)?.also { url = id to it } ?: continue
                    due.forEachIndexed { k, p ->
                        val found = runCatching { Recognize.at(link, p / 1000.0) }.getOrNull()
                        val new = tracks.found(id, p, found?.title, found?.artist, System.currentTimeMillis(), live = k == due.lastIndex)
                        // every track found, kept in "from dj sets" (2026-11-12, Bronson: a playlist of every song it's found)
                        if (new != null) runCatching { YouTube.find(new.title, new.artist) }.getOrNull()?.let { HereLists.fromSet(it) }
                        if (k < due.lastIndex) delay(2_000)
                    }
                }
                now.value = tracks.at(pos())
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
