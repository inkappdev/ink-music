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
 * New music videos from the artists you play (the computer's, 2026-11-07): a shelf on listen. Found once a day on wifi
 * ([TubeVideos]: each artist's channels, their newest uploads, the real videos told by title; a channel's page is half
 * a megabyte, so not on someone's data), kept on the phone, watched in [WatchPage].
 */
object MusicVideos {
    /** A video: its id, its song's title and artist (tidied), when it went up. */
    data class Clip(val id: String, val title: String, val artist: String, val at: Long)

    private val _clips = MutableStateFlow<List<Clip>>(emptyList())
    val clips: StateFlow<List<Clip>> = _clips
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false
    // each artist's channels, found once (none: asked again in a week)
    private val channels = HashMap<String, Pair<List<TubeVideos.Channel>, Long>>()

    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        scope.launch {
            val file = File(app.filesDir, "youtube/videos.json")
            val was = runCatching { JSONObject(file.readText()) }.getOrNull()
            was?.let { read(it) }
            if (Quality.metered(app)) { started = false; return@launch }
            if (was == null || !was.has("channels2") || System.currentTimeMillis() - was.optLong("at") > 20 * 3_600_000L) runCatching { find(app, file) }
                .onFailure { android.util.Log.d("ink-youtube", "videos: $it") }
        }
    }

    /** What it plays from: the picture and its sound apart (or one with both), found as it's opened (they expire). */
    fun stream(id: String): Pair<String, String?>? = Pipe.streamInfo(id)?.let { Pipe.video(it) }

    // the artists you chose, then whom you've played most these three months
    private fun artists(ctx: Context): List<String> {
        val since = System.currentTimeMillis() - 90L * 86_400_000
        val played = History.read(ctx).filter { it.at >= since && it.artist.isNotBlank() }
            .groupingBy { it.artist.substringBefore(", ").trim() }.eachCount()
            .filterKeys { it != "unknown" && !it.equals("various artists", true) }
            .entries.sortedByDescending { it.value }.map { it.key }
        return (Seeds.artists.value.map { it.name } + played).distinctBy { it.lowercase() }.take(30)
    }

    private fun find(ctx: Context, file: File) {
        val now = System.currentTimeMillis()
        val yt = InnerTube()
        val found = ArrayList<Clip>()
        for (name in artists(ctx)) {
            if (Quality.metered(ctx)) break
            val known = channels[name.lowercase()]?.takeIf { it.first.isNotEmpty() || now - it.second < 7 * 86_400_000L }
            val theirs = known?.first ?: run {
                val id = runCatching { yt.searchItems(name, InnerTube.Kind.ARTIST, 3) }.getOrNull()
                    ?.firstOrNull { plain(it.title).let { t -> t.isNotEmpty() && (t.contains(plain(name)) || plain(name).contains(t)) } }?.id.orEmpty()
                (if (id.startsWith("UC")) TubeVideos.channelsOf(name, id) else emptyList()).also { channels[name.lowercase()] = it to now }
            }
            val videos = theirs.flatMap { ch -> TubeVideos.uploads(ch.id).filter { it.days <= 35 && TubeVideos.isVideo(it, name, ch) } }
                .distinctBy { it.id }.sortedBy { it.days }
            // an album's week: the newest two
            found += videos.take(2).map { u ->
                val (title, _) = Pipe.tidy(u.title, name)
                Clip(u.id, title.trim().removeSurrounding("\"").removeSurrounding("“", "”").ifEmpty { u.title }, name, now - u.days * 86_400_000L)
            }
            android.util.Log.d("ink-youtube", "videos: $name, ${theirs.joinToString { it.name }}: ${videos.size}")
        }
        val clips = found.sortedByDescending { it.at }.take(16)
        _clips.value = clips
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(JSONObject().put("at", now)
                .put("clips", JSONArray(clips.map { JSONObject().put("id", it.id).put("title", it.title).put("artist", it.artist).put("when", it.at) }))
                .put("channels2", JSONObject().apply {
                    channels.forEach { (k, v) ->
                        put(k, JSONObject().put("at", v.second).put("list", JSONArray(v.first.map { JSONObject().put("id", it.id).put("name", it.name).put("own", it.own) })))
                    }
                }).toString())
        }
    }

    private fun plain(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun read(o: JSONObject) {
        _clips.value = o.optJSONArray("clips")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
            .map { Clip(it.getString("id"), it.getString("title"), it.getString("artist"), it.getLong("when")) }
        o.optJSONObject("channels2")?.let { c ->
            for (k in c.keys()) c.getJSONObject(k).let { j ->
                val list = j.optJSONArray("list")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
                    .map { TubeVideos.Channel(it.getString("id"), it.optString("name"), it.optBoolean("own")) }
                channels[k] = list to j.optLong("at")
            }
        }
    }
}
