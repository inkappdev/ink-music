package app.ink.music

import android.content.Context
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
 * Live sessions (2026-11-18, [Sessions]: KEXP, Tiny Desk, COLORS, Audiotree and more): read every six hours on wifi,
 * further back once a week, kept on the phone (the newest 1,200). INK's shelf and TUNER take from them; watched as
 * music videos are.
 */
object LiveSessions {
    /** A session as the app keeps it: its video, who, what, how it's said ("live on kexp"), when it went up. */
    data class One(val id: String, val artist: String, val what: String, val says: String, val at: Long)

    private val _all = MutableStateFlow<List<One>>(emptyList())
    val all: StateFlow<List<One>> = _all
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false

    fun start(ctx: Context) {
        if (started || JustYours.on) return
        started = true
        val app = ctx.applicationContext
        scope.launch {
            val file = File(app.filesDir, "youtube/sessions.json")
            val was = runCatching { JSONObject(file.readText()) }.getOrNull()
            was?.optJSONArray("sessions")?.let { a ->
                _all.value = (0 until a.length()).map { a.getJSONObject(it) }.map { One(it.getString("id"), it.getString("artist"), it.getString("what"), it.getString("says"), it.getLong("at")) }
            }
            if (Quality.metered(app)) { started = false; return@launch }
            if (was != null && System.currentTimeMillis() - was.optLong("at") < 6 * 3_600_000L) return@launch
            val now = System.currentTimeMillis()
            val deepAt = was?.optLong("deep") ?: 0L
            val deep = now - deepAt > 7 * 86_400_000L
            val read = runCatching { Sessions.read(now / 86_400_000L, deep) }.onFailure { android.util.Log.d("ink-youtube", "sessions: $it") }.getOrDefault(emptyList())
            if (read.isEmpty()) return@launch
            val all = (read.map { One(it.id, it.artist, it.what, it.says, now - it.days * 86_400_000L) } + _all.value).distinctBy { it.id }
                .sortedByDescending { it.at }.take(1_200)
            _all.value = all
            android.util.Log.d("ink-youtube", "sessions: ${read.size} read, ${all.size} kept")
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(JSONObject().put("at", now).put("deep", if (deep) now else deepAt).put("sessions", JSONArray(all.map {
                    JSONObject().put("id", it.id).put("artist", it.artist).put("what", it.what).put("says", it.says).put("at", it.at)
                })).toString())
            }
        }
    }
}
