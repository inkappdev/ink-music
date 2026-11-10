package app.ink.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
    private val file = File(Home.cache, "dj-sets.json")
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            val was = runCatching { JSONObject(file.readText()) }.getOrNull()
            was?.optJSONArray("sets")?.let { a -> _clips.value = (0 until a.length()).map { a.getJSONObject(it) }
                .map { MusicVideos.Clip(it.getString("id"), it.getString("dj"), it.getString("channel"), it.getLong("when")) } }
            if (was != null && System.currentTimeMillis() - was.optLong("at") < 6 * 3_600_000L) return@launch
            val now = System.currentTimeMillis()
            val sets = runCatching { DjSets.read() }.onFailure { Log.d("dj sets: $it") }.getOrDefault(emptyList())
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

/** Listen's shelf of them: each frame wide, the DJ, the channel and how long ago. */
@Composable
fun LiveSetsShelf(open: (Page) -> Unit) {
    val clips by LiveSets.clips.collectAsState()
    LaunchedEffect(Unit) { LiveSets.start() }
    if (clips.isEmpty()) return
    Column {
        Section("LIVE DJ SETS", "humano studios, hör berlin, the lot radio, boiler room")
        Shelf(clips.size, 300.dp, cover = 16 / 9f) { each ->
            items(clips, key = { it.id }) { c ->
                Column(Modifier.width(each).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { open(Page.Watch(c, clips)) })) {
                    VideoFrame(c, Modifier.fillMaxWidth().aspectRatio(16 / 9f))
                    Spacer(Modifier.height(8.dp))
                    UnderCover(c.title, "${c.artist}  ·  ${MusicVideos.ago(c.at)}")
                }
            }
        }
    }
}
