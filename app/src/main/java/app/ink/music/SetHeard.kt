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
/**
 * Every track ever heard in a DJ set, found on YouTube Music, newest first (2026-11-12, Bronson: "makes you a playlist of
 * all the songs its ever found in the dj sets you listened to"): lists' from dj sets. In files/dj-set-songs.json.
 */
object FromSets {
    val songs = MutableStateFlow<List<Song>>(emptyList())
    private fun file(c: Context) = java.io.File(c.filesDir, "dj-set-songs.json")

    fun load(c: Context) {
        songs.value = runCatching {
            val a = org.json.JSONArray(file(c).readText())
            (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
                val v = o.getString("id").removePrefix("y")
                Song(id = "y$v", uri = android.net.Uri.parse("ytm://$v"), title = o.getString("title"), artist = o.getString("artist"),
                    album = o.optString("album"), albumId = "y$v", albumArtist = o.getString("artist"), duration = 0, disc = 1, track = 0, year = 0,
                    format = "youtube").also(YouTubeMusic::remember)
            }
        }.getOrDefault(emptyList())
    }

    /** When the file last changed (the player writes it; the screens read it again then). */
    fun changed(c: Context): Long = file(c).takeIf { it.exists() }?.lastModified() ?: 0L

    /** One more, at the top (each song once). */
    @Synchronized fun add(c: Context, s: Song) {
        if (songs.value.any { it.id == s.id }) return
        songs.value = listOf(s) + songs.value
        runCatching {
            Keep.write(file(c), org.json.JSONArray(songs.value.map { x ->
                org.json.JSONObject().put("id", x.id).put("title", x.title).put("artist", x.artist).put("album", x.album)
            }).toString())
        }
    }
}

/**
 * The listening itself, in the player's process (2026-11-12, Bronson: "lets just have it fill anyway", the screen locked
 * too): the screens' process is frozen soon after the screen goes off, the player's never while it plays. While a live
 * set plays, what's playing is asked (SetTracks: two minutes after a hit, 45 s after a miss, a stretch missed asked
 * over), each set's list written to sets-heard.json and every track found to from dj sets (FromSets); the screens read
 * both (SetHeard).
 */
object SetListening {
    /** What's playing, as the player has it: its id, its record's name, where it is, whether it's playing. */
    data class Now(val id: String, val title: String, val artist: String, val album: String, val pos: Long, val playing: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false

    fun start(c: Context, now: () -> Now?) {
        if (started) return
        started = true
        val app = c.applicationContext
        val tracks = SetTracks(app.filesDir)
        FromSets.load(app)
        scope.launch {
            while (true) {
                delay(3_000)
                val n = runCatching { now() }.getOrNull()
                if (n == null || n.album != "live dj set" || !n.playing || !n.id.startsWith("y")) continue
                val v = n.id.removePrefix("y")
                val song = Song(id = n.id, uri = android.net.Uri.parse("ytm://$v"), title = n.title, artist = n.artist, album = n.album, albumId = n.id,
                    albumArtist = n.artist, duration = 0, disc = 1, track = 0, year = 0, format = "youtube")
                val due = tracks.due(n.id, n.pos, System.currentTimeMillis(), looking = true)
                due.forEachIndexed { k, p ->
                    val found = runCatching { Recognize.at(app, song, p) }.getOrNull()
                    android.util.Log.d("ink-recognize", "set ${n.id} at ${p / 1000} s: ${found?.let { it.artist + " - " + it.title } ?: "nothing"}")
                    val new = tracks.found(n.id, p, found?.title, found?.artist, System.currentTimeMillis(), live = k == due.lastIndex)
                    // every track found, kept in from dj sets
                    if (new != null) runCatching { YouTubeMusic.find(new.title, new.artist) }.getOrNull()?.let { FromSets.add(app, it) }
                    if (k < due.lastIndex) delay(2_000)
                }
            }
        }
    }
}

/**
 * The screens' side: a set's list as the player keeps it (SetListening writes sets-heard.json), read every couple of
 * seconds while ink music's open; a track that's new to the list [added] (typed in), the one playing [now].
 */
object SetHeard {
    private val tracks = SetTracks()
    /** Every track heard in the set playing, in the order they played. */
    val heard = MutableStateFlow<List<SetTracks.Track>>(emptyList())
    /** The last one found: typed in as it comes. */
    val added = MutableStateFlow<SetTracks.Track?>(null)
    /** The track playing in the set now, as far as it's known. */
    val now = MutableStateFlow<SetTracks.Track?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false

    fun isSet(n: Now?) = n != null && n.album == "live dj set"

    fun start(c: Context) {
        if (started) return
        started = true
        val app = c.applicationContext
        tracks.dir = app.filesDir
        FromSets.load(app)
        scope.launch {
            var set: String? = null
            var seen = 0L
            var songsSeen = 0L
            while (true) {
                delay(2_000)
                // from dj sets, as the player last wrote it
                FromSets.changed(app).takeIf { it != songsSeen }?.let { songsSeen = it; FromSets.load(app) }
                val n = Playback.now.value
                val id = n?.song?.id?.takeIf { isSet(n) }
                if (id == null) { set = null; heard.value = emptyList(); now.value = null; added.value = null; continue }
                val changed = tracks.changed()
                if (id != set || changed != seen) {
                    val list = tracks.peek(id)
                    val was = if (id == set) heard.value else list
                    set = id; seen = changed
                    list.lastOrNull { it !in was }?.let { added.value = it }
                    heard.value = list
                }
                now.value = heard.value.lastOrNull { it.at <= n.positionNow() + 15_000 }
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
