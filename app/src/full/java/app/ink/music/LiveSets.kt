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
    /**
     * What's kept and what's just read, as one: every set once, a set read again its newer word, the newest [DjSets.KEPT].
     * The shelf shuffles them each time it's shown.
     */
    private fun merged(kept: List<MusicVideos.Clip>, read: List<MusicVideos.Clip>): List<MusicVideos.Clip> =
        (read + kept).distinctBy { it.id }.sortedByDescending { it.at }.take(DjSets.KEPT)

    private val _clips = MutableStateFlow<List<MusicVideos.Clip>>(emptyList())
    val clips: StateFlow<List<MusicVideos.Clip>> = _clips
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false

    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        scope.launch {
            val file = File(app.filesDir, "youtube/dj-sets-3.json")
            val was = runCatching { JSONObject(file.readText()) }.getOrNull()
            was?.optJSONArray("sets")?.let { a -> _clips.value = (0 until a.length()).map { a.getJSONObject(it) }
                .map { MusicVideos.Clip(it.getString("id"), it.getString("dj"), it.getString("channel"), it.getLong("when")) } }
            if (Quality.metered(app)) { started = false; return@launch }
            if (was != null && System.currentTimeMillis() - was.optLong("at") < 6 * 3_600_000L) return@launch
            val now = System.currentTimeMillis()
            // far back once a week (and the first time; on wifi, as every read is), the newest page between; what's been
            // read before kept
            val deepAt = was?.optLong("deep") ?: 0L
            val deep = now - deepAt > DjSets.DEEP_EVERY
            val sets = runCatching { DjSets.read(now / 86_400_000L, deep) }.onFailure { android.util.Log.d("ink-youtube", "dj sets: $it") }.getOrDefault(emptyList())
            if (sets.isEmpty()) return@launch
            val clips = merged(_clips.value, sets.map { MusicVideos.Clip(it.id, it.dj, it.channel, now - it.days * 86_400_000L) })
            _clips.value = clips
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(JSONObject().put("at", now).put("deep", if (deep) now else deepAt).put("sets", JSONArray(clips.map {
                    JSONObject().put("id", it.id).put("dj", it.title).put("channel", it.artist).put("when", it.at)
                })).toString())
            }
        }
    }

    /**
     * A set's sound alone, in the music player (2026-11-10: lock the phone and listen): the set as a YouTube song, the
     * DJ its title, the channel its artist, its frame its cover; from `at`, where the video was.
     */
    fun listen(c: MusicVideos.Clip, at: Long = 0L) {
        val s = Song(
            id = "y${c.id}", uri = android.net.Uri.parse("ytm://${c.id}"), title = c.title, artist = c.artist, album = "live dj set",
            albumId = "y${c.id}", albumArtist = c.artist, duration = 0, disc = 1, track = 0, year = 0,
            art = "https://i.ytimg.com/vi/${c.id}/maxresdefault.jpg", format = "youtube",
        ).also(YouTubeMusic::remember)
        Playback.play(listOf(s), 0, at)
    }
}
