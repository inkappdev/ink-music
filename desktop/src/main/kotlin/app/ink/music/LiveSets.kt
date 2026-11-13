package app.ink.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Live DJ sets (2026-11-10, [DjSets]: Humano Studios, HÖR Berlin, The Lot Radio, Boiler Room), a shelf on listen: read
 * every six hours, kept on disk, watched as the videos are. Each is a clip: the DJ for its title, the channel for its artist.
 */
object LiveSets {
    private val _clips = MutableStateFlow<List<MusicVideos.Clip>>(emptyList())
    val clips: StateFlow<List<MusicVideos.Clip>> = _clips
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file = File(Home.cache, "dj-sets-2.json")
    @Volatile private var started = false

    fun start() {
        // local files only: not started (Local)
        if (Local.on) return
        if (started) return
        started = true
        scope.launch {
            val was = runCatching { JSONObject(file.readText()) }.getOrNull()
            was?.optJSONArray("sets")?.let { a -> _clips.value = (0 until a.length()).map { a.getJSONObject(it) }
                .map { MusicVideos.Clip(it.getString("id"), it.getString("dj"), it.getString("channel"), it.getLong("when")) } }
            if (was != null && System.currentTimeMillis() - was.optLong("at") < 6 * 3_600_000L) return@launch
            val now = System.currentTimeMillis()
            val sets = runCatching { DjSets.read(System.currentTimeMillis() / 86_400_000L) }.onFailure { Log.d("dj sets: $it") }.getOrDefault(emptyList())
            if (sets.isEmpty()) return@launch
            val clips = sets.map { MusicVideos.Clip(it.id, it.dj, it.channel, now - it.days * 86_400_000L) }
            _clips.value = clips
            runCatching {
                Home.write(file, JSONObject().put("at", now).put("sets", JSONArray(clips.map {
                    JSONObject().put("id", it.id).put("dj", it.title).put("channel", it.artist).put("when", it.at)
                })).toString())
            }
        }
    }
}

/**
 * A set's sound alone, in the music player (2026-11-10: listen with the window away): the set as a YouTube song, the
 * DJ its title, the channel its artist, its frame its cover; from `at`, where the video was, once it's begun.
 */
fun listenTo(c: MusicVideos.Clip, at: Long = 0L) {
    val s = Song(
        id = "y${c.id}", path = "", title = c.title, artist = c.artist, album = "live dj set", albumId = "y${c.id}",
        albumArtist = c.artist, duration = 0, disc = 1, track = 0, year = 0, format = "youtube",
        art = "https://i.ytimg.com/vi/${c.id}/maxresdefault.jpg",
    ).also(YouTube::remember)
    Player.play(listOf(s))
    if (at > 5_000) kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
        repeat(80) {
            kotlinx.coroutines.delay(250)
            if (Player.now.value?.song?.id == s.id && Player.position.value > 0) { Player.seek(at); return@launch }
        }
    }
}

/** Listen's shelf of them: each frame wide, the DJ, the channel and how long ago; watch it, or listen only. */
@Composable
fun LiveSetsShelf(open: (Page) -> Unit) {
    val clips by LiveSets.clips.collectAsState()
    LaunchedEffect(Unit) { LiveSets.start() }
    if (clips.isEmpty()) return
    // shuffled each time the shelf's shown, so it's never the same row twice running (2026-11-12, Bronson: "less stale")
    val sets = remember(clips) { clips.shuffled() }
    Column {
        Section("LIVE DJ SETS", "humano studios, hör berlin, the lot radio, boiler room, kiosk radio")
        Shelf(sets.size, 300.dp, cover = 16 / 9f) { each ->
            items(sets, key = { it.id }) { c ->
                Column(Modifier.width(each).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { open(Page.Watch(c, sets)) })) {
                    VideoFrame(c, Modifier.fillMaxWidth().aspectRatio(16 / 9f))
                    Spacer(Modifier.height(8.dp))
                    UnderCover(c.title, "${c.artist}  ·  ${MusicVideos.ago(c.at)}")
                    androidx.compose.foundation.layout.Row(Modifier.padding(top = 4.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(20.dp)) {
                        Word("watch", pixel(13.sp), touch = 6.dp) { open(Page.Watch(c, sets)) }
                        Word("listen", pixel(13.sp), touch = 6.dp) { listenTo(c) }
                    }
                }
            }
        }
    }
}
