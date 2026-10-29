package app.ink.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Songs kept on the computer, to play with no signal (Bronson, 2026-10-29: "a lot of people ask", and the phone's
 * too): your server's (the original file, lossless if it is) and YouTube Music's (its best stream), fetched one at a
 * time in the background in pieces, into ink music's own folder ("<id>", its words beside it as "<id>.json"; "<id>.dl"
 * while it comes). The player plays the kept file in the stream's place whenever there is one.
 */
object Offline {
    private val dir = File(Home.dir, "offline").apply { mkdirs() }
    /** The songs kept, by id. */
    val kept = MutableStateFlow(read())
    /** The songs on their way, each how far (0 to 1). */
    val keeping = MutableStateFlow<Map<String, Float>>(emptyMap())
    private val one = Executors.newSingleThreadExecutor { Thread(it, "ink-offline").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + one)

    private fun read(): Set<String> = dir.listFiles()?.filter { !it.name.endsWith(".dl") && !it.name.endsWith(".json") && it.length() > 0 }
        ?.map { it.name }?.toSet().orEmpty()

    private val smallFile = File(Home.dir, "offline-small")
    /** Kept smaller (MP3 320 from your server, YouTube's lighter stream), or as they are. */
    val small = MutableStateFlow(smallFile.exists())
    fun small(on: Boolean) { small.value = on; runCatching { if (on) smallFile.writeText("") else smallFile.delete() } }

    /** Whether a song can be kept: your server's, or YouTube Music's (a file's here already; a station's live). */
    fun keepable(s: Song) = s.server || s.tube

    /** A song's kept file, if it's whole (the player asks as each song's loaded: quick). */
    fun file(id: String): File? = File(dir, id).takeIf { it.isFile && it.length() > 0 }

    /** These songs kept: each not kept or on its way fetched, in turn. */
    fun keep(songs: List<Song>) {
        val todo = songs.filter { keepable(it) && it.id !in kept.value && it.id !in keeping.value }
        if (todo.isEmpty()) return
        keeping.value = keeping.value + todo.associate { it.id to 0f }
        todo.forEach { s -> scope.launch { runCatching { fetch(s) }.onFailure { Log.d("offline ${s.id}: $it") }; keeping.value = keeping.value - s.id } }
    }

    private fun fetch(s: Song) {
        if (s.id !in keeping.value) return   // let go before its turn
        val url = (if (s.server) Server.stream(s, mp3 = small.value) else YouTube.stream(s.id, small = small.value)) ?: return
        File(dir, "${s.id}.json").writeText(s.json().toString())
        val part = File(dir, "${s.id}.dl")
        part.delete()
        var at = 0L; var total = -1L
        // in pieces (YouTube's streams slow a long single read to playing speed)
        while (total < 0 || at < total) {
            if (s.id !in keeping.value) { part.delete(); return }
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.setRequestProperty("Range", "bytes=$at-${at + PIECE - 1}")
            try {
                if (c.responseCode !in 200..299) error("${c.responseCode}")
                total = c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: c.contentLengthLong.takeIf { c.responseCode == 200 } ?: -1L
                val got = c.inputStream.use { input -> java.io.FileOutputStream(part, true).use { input.copyTo(it) } }
                if (got <= 0) break
                at += got
                if (c.responseCode == 200) break   // the whole thing, in one
            } finally { c.disconnect() }
            if (total > 0) keeping.value = keeping.value + (s.id to (at.toFloat() / total).coerceIn(0f, 1f))
        }
        if (part.length() > 0) { part.renameTo(File(dir, s.id)); kept.value = kept.value + s.id }
    }

    /** These songs no longer kept (any on their way called off). */
    fun remove(ids: Collection<String>) {
        keeping.value = keeping.value - ids.toSet()
        ids.forEach { File(dir, it).delete(); File(dir, "$it.dl").delete(); File(dir, "$it.json").delete() }
        kept.value = kept.value - ids.toSet()
    }

    /** The songs kept, to list and play: the library's own where it has them, else from their words kept beside them. */
    fun songs(): List<Song> = kept.value.mapNotNull { id ->
        Library.song(id) ?: runCatching { songOf(JSONObject(File(dir, "$id.json").readText())).also { if (it.tube) YouTube.remember(it) } }.getOrNull()
    }

    /** How much the kept take (bytes). */
    fun bytes(): Long = dir.listFiles()?.filter { !it.name.endsWith(".dl") && !it.name.endsWith(".json") }?.sumOf { it.length() } ?: 0L

    private const val PIECE = 4L shl 20
}
