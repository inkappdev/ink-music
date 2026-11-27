package app.ink.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * Live sessions (the phone's since 2026-11-18, here since 2026-11-27; [Sessions]: KEXP, Tiny Desk, COLORS, Audiotree and
 * more): read every six hours, further back once a week, kept on disk (the newest 1,200). INK's shelf; watched as music
 * videos are.
 */
object LiveSessions {
    /** A session as kept: its video, who, what, how it's said ("live on kexp"), when it went up. */
    data class One(val id: String, val artist: String, val what: String, val says: String, val at: Long)

    private val _all = MutableStateFlow<List<One>>(emptyList())
    val all: StateFlow<List<One>> = _all
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file = File(Home.cache, "sessions.json")
    @Volatile private var started = false

    fun start() {
        // local files only: not started (Local)
        if (started || Local.on) return
        started = true
        scope.launch {
            val was = runCatching { JSONObject(file.readText()) }.getOrNull()
            was?.optJSONArray("sessions")?.let { a ->
                _all.value = (0 until a.length()).map { a.getJSONObject(it) }.map { One(it.getString("id"), it.getString("artist"), it.getString("what"), it.getString("says"), it.getLong("at")) }
            }
            if (was != null && System.currentTimeMillis() - was.optLong("at") < 6 * 3_600_000L) return@launch
            val now = System.currentTimeMillis()
            val deepAt = was?.optLong("deep") ?: 0L
            val deep = now - deepAt > 7 * 86_400_000L
            val read = runCatching { Sessions.read(now / 86_400_000L, deep) }.onFailure { Log.d("sessions: $it") }.getOrDefault(emptyList())
            if (read.isEmpty()) return@launch
            val all = (read.map { One(it.id, it.artist, it.what, it.says, now - it.days * 86_400_000L) } + _all.value).distinctBy { it.id }
                .sortedByDescending { it.at }.take(1_200)
            _all.value = all
            Log.d("sessions: ${read.size} read, ${all.size} kept")
            runCatching {
                Home.write(file, JSONObject().put("at", now).put("deep", if (deep) now else deepAt).put("sessions", JSONArray(all.map {
                    JSONObject().put("id", it.id).put("artist", it.artist).put("what", it.what).put("says", it.says).put("at", it.at)
                })).toString())
            }
        }
    }
}

/**
 * INK's shelf of them (the phone's): the ones by artists near you first, then the rest, one each; each frame wide, who
 * and where; watch it, or listen only. The blocked left out.
 */
@Composable
fun LiveSessionsShelf(open: (Page) -> Unit) {
    LaunchedEffect(Unit) { LiveSessions.start() }
    val all = LiveSessions.all.shown { "" to it.artist }
    val taste by Fresh.taste.collectAsState()
    val shown = remember(all, taste) {
        val (near, rest) = all.partition { taste.of(it.artist) >= .2 }
        (near.sortedByDescending { taste.of(it.artist) } + rest).distinctBy { it.artist.lowercase() }.take(24)
    }
    if (shown.isEmpty()) return
    val clips = remember(shown) { shown.map { MusicVideos.Clip(it.id, if (it.what == "full session") "live" else it.what, it.artist, it.at) } }
    Column {
        Section("LIVE SESSIONS", "kexp, tiny desk, colors and more")
        Shelf(shown.size, 300.dp, cover = 16 / 9f) { each ->
            items(shown.indices.toList(), key = { shown[it].id }) { i ->
                val s = shown[i]; val c = clips[i]
                WithMenu({ listOf(listOf("watch" to { open(Page.Watch(c, clips)) }, "listen" to { listenTo(c) }), listOf("copy link" to { copyText("https://youtu.be/${c.id}") })) }) {
                    Column(Modifier.width(each).clickable(remember { MutableInteractionSource() }, indication = null, onClick = felt { open(Page.Watch(c, clips)) })) {
                        VideoFrame(c, Modifier.fillMaxWidth().aspectRatio(16 / 9f))
                        Spacer(Modifier.height(8.dp))
                        UnderCover(s.artist, "${s.what}  ·  ${s.says}")
                        androidx.compose.foundation.layout.Row(Modifier.padding(top = 4.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(20.dp)) {
                            Word("watch", pixel(13.sp), touch = 6.dp) { open(Page.Watch(c, clips)) }
                            Word("listen", pixel(13.sp), touch = 6.dp) { listenTo(c) }
                        }
                    }
                }
            }
        }
    }
}
