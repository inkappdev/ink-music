package app.ink.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

/**
 * The iPhone's own folder (Application Support/ink): the files core keeps, as the phone keeps them in its filesDir
 * (heard.jsonl, sync.json, the sign-ins, podcasts), so sync carries them across as they are.
 */
object Here {
    val dir: File by lazy { File(appFolder()).also { it.mkdirs(); Podcasts.dir = File(it, "podcasts") } }

    /** Work that waits on the network (core's calls are blocking): off the screens' thread. */
    val work = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    suspend fun <T> off(f: () -> T): T = withContext(Dispatchers.Default) { f() }

    /** The few words a request says it is (core's AGENT, as the phone's). */
    val agent get() = AGENT
}

/**
 * What you've heard, as the phone writes it (heard.jsonl: when, which, what, how long): a song heard for 30 s or more,
 * never a station. A server song's id as the phone's ("n" and the server's), so the two see the same song.
 */
object Heard {
    const val ENOUGH = 30_000L
    private val guard = Guard()

    fun add(s: Song, at: Long, ms: Long) {
        if (ms < ENOUGH || s.title.isEmpty() || s.kind == "station") return
        val id = when (s.kind) { "server" -> "n" + s.id; "youtube" -> "y" + s.id; else -> s.id }
        guard.run {
            runCatching {
                File(Here.dir, "heard.jsonl").appendText(JSONObject().put("at", at).put("id", id).put("title", s.title).put("artist", s.artist)
                    .put("album", s.album).put("record", if (s.kind == "server") "na" + s.albumId else "").put("ms", ms).toString() + "\n")
            }
        }
    }

    /** Everything heard here and on the paired devices, oldest first. */
    fun all(): List<JSONObject> = runCatching {
        File(Here.dir, "heard.jsonl").readLines().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
    }.getOrDefault(emptyList())
}
