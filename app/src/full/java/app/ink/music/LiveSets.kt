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
 * Live DJ sets (2026-11-10, [DjSets]: Humano Studios, HÖR Berlin, The Lot Radio, Boiler Room), a shelf on listen: read
 * every six hours on wifi (each channel's page is half a megabyte), kept on the phone, watched as the videos are.
 * Each is a clip: the DJ for its title, the channel for its artist.
 */
object LiveSets {
    private val _clips = MutableStateFlow<List<MusicVideos.Clip>>(emptyList())
    val clips: StateFlow<List<MusicVideos.Clip>> = _clips
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false

    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        scope.launch {
            val file = File(app.filesDir, "youtube/dj-sets-2.json")
            val was = runCatching { JSONObject(file.readText()) }.getOrNull()
            was?.optJSONArray("sets")?.let { a -> _clips.value = (0 until a.length()).map { a.getJSONObject(it) }
                .map { MusicVideos.Clip(it.getString("id"), it.getString("dj"), it.getString("channel"), it.getLong("when")) } }
            if (Quality.metered(app)) { started = false; return@launch }
            if (was != null && System.currentTimeMillis() - was.optLong("at") < 6 * 3_600_000L) return@launch
            val now = System.currentTimeMillis()
            val sets = runCatching { DjSets.read(System.currentTimeMillis() / 86_400_000L) }.onFailure { android.util.Log.d("ink-youtube", "dj sets: $it") }.getOrDefault(emptyList())
            if (sets.isEmpty()) return@launch
            val clips = sets.map { MusicVideos.Clip(it.id, it.dj, it.channel, now - it.days * 86_400_000L) }
            _clips.value = clips
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(JSONObject().put("at", now).put("sets", JSONArray(clips.map {
                    JSONObject().put("id", it.id).put("dj", it.title).put("channel", it.artist).put("when", it.at)
                })).toString())
            }
        }
    }
}
