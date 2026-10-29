package app.ink.music

import kotlinx.coroutines.launch

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

/**
 * Your server's songs kept on the phone, and YouTube Music's (the full build: Bronson, 2026-10-29, "a lot of people
 * ask"; its stream found first, the plain one a speaker gets, its name and cover kept beside it as "<id>.json"), to
 * play with no signal (a flight, the car, the country): fetched by
 * Android's own download manager (it carries on with the app closed and through a bad signal, and shows itself in
 * the notifications) into ink music's own folder, out of the library's sight (so they're not listed twice). The
 * original file (lossless, if it is), or MP3 320 to keep it small (settings). The player plays the kept file in
 * the stream's place whenever there is one. A song as it's fetched is "<id>.dl", renamed "<id>" once whole.
 */
object Offline {
    private val _kept = MutableStateFlow<Set<String>>(emptySet())
    /** The songs kept, by id ("n..."). */
    val kept: StateFlow<Set<String>> = _kept
    private val _keeping = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** The songs being fetched: their ids, and the download manager's for each. */
    val keeping: StateFlow<Map<String, Long>> = _keeping

    private fun dir(c: Context) = File(c.getExternalFilesDir(null) ?: c.filesDir, "offline")
    private fun pending(c: Context) = File(c.filesDir, "offline-pending.json")
    private fun small(c: Context) = File(c.filesDir, "offline-mp3")
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** Whether a song can be kept: your server's, or YouTube Music's (the full build). */
    fun keepable(s: Song) = s.server || (s.tube && YouTubeMusic.available)

    /** A song's kept file, if it's whole (the player asks this as each server song starts: quick, no listing). */
    fun file(c: Context, id: String): File? = File(dir(c), id).takeIf { it.isFile && it.length() > 0 }

    /** Kept as MP3 320 (smaller), or the original. */
    fun mp3(c: Context) = small(c).exists()
    fun mp3(c: Context, on: Boolean) { if (on) runCatching { small(c).createNewFile() } else small(c).delete() }

    /** What's kept and what's being fetched, read from the folder and the download manager. */
    fun start(c: Context) = refresh(c)

    /** The songs (your server's, YouTube Music's) kept: each not kept or on its way asked for; a YouTube song's stream found first. */
    fun keep(c: Context, songs: List<Song>) {
        val tube = songs.filter { it.tube && YouTubeMusic.available && it.id !in _kept.value && it.id !in _keeping.value }
        if (tube.isNotEmpty()) scope.launch { keepTube(c, tube) }
        val dm = c.getSystemService(DownloadManager::class.java) ?: return
        val now = _keeping.value.toMutableMap()
        dir(c).mkdirs()
        for (s in songs) {
            if (!s.server || s.id in _kept.value || s.id in now) continue
            val url = Quality.server(s.uri, if (mp3(c)) Quality.HIGH else Quality.ORIGINAL)
            val r = DownloadManager.Request(url)
                .setTitle(s.title).setDescription("ink music · keeping it on the phone")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationUri(Uri.fromFile(File(dir(c), "${s.id}.dl")))
            now[s.id] = runCatching { dm.enqueue(r) }.getOrNull() ?: continue
        }
        _keeping.value = now; save(c)
    }

    /** YouTube songs kept: each its stream found (the plain one, which the download manager can fetch as it is), its words beside it. */
    @Synchronized private fun keepTube(c: Context, songs: List<Song>) {
        val dm = c.getSystemService(DownloadManager::class.java) ?: return
        dir(c).mkdirs()
        for (s in songs) {
            if (s.id in _kept.value || s.id in _keeping.value) continue
            val url = runCatching { YouTubeMusic.stream(s.id, forSpeaker = true, small = mp3(c))?.first }.getOrNull() ?: continue
            runCatching {
                File(dir(c), "${s.id}.json").writeText(JSONObject().put("id", s.id).put("title", s.title).put("artist", s.artist).put("album", s.album)
                    .put("albumId", s.albumId).put("albumArtist", s.albumArtist).put("duration", s.duration).put("art", s.art ?: "").toString())
            }
            // the whole file asked for at once (its length is in the link): YouTube slows a plain long read to playing speed
            val whole = Regex("[?&]clen=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()
                ?.takeIf { "range=" !in url }?.let { "$url&range=0-${it - 1}" } ?: url
            val r = DownloadManager.Request(Uri.parse(whole))
                .setTitle(s.title).setDescription("ink music · keeping it on the phone")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationUri(Uri.fromFile(File(dir(c), "${s.id}.dl")))
            val id = runCatching { dm.enqueue(r) }.getOrNull() ?: continue
            _keeping.value = _keeping.value + (s.id to id); save(c)
        }
    }

    /** The YouTube songs kept, as songs to list and play (from their words kept beside them). */
    fun tubeSongs(c: Context): List<Song> = dir(c).listFiles()?.filter { it.name.endsWith(".json") }?.mapNotNull { f ->
        runCatching {
            val o = JSONObject(f.readText()); val id = o.getString("id")
            if (!File(dir(c), id).isFile) return@runCatching null
            YouTubeMusic.known(id) ?: Song(id = id, uri = Uri.parse("ytm://" + id.removePrefix("y")), title = o.optString("title"), artist = o.optString("artist"),
                album = o.optString("album"), albumId = o.optString("albumId").ifEmpty { id }, albumArtist = o.optString("albumArtist").ifEmpty { o.optString("artist") },
                duration = o.optLong("duration"), disc = 1, track = 0, year = 0, art = o.optString("art").ifEmpty { null }).also(YouTubeMusic::remember)
        }.getOrNull()
    }.orEmpty()

    /** These songs no longer kept (and any on their way called off). */
    fun remove(c: Context, songs: Collection<String>) {
        val dm = c.getSystemService(DownloadManager::class.java)
        val now = _keeping.value.toMutableMap()
        for (id in songs) {
            now.remove(id)?.let { runCatching { dm?.remove(it) } }
            File(dir(c), id).delete(); File(dir(c), "$id.dl").delete(); File(dir(c), "$id.json").delete()
        }
        _keeping.value = now; save(c)
        _kept.value = _kept.value - songs.toSet()
    }

    fun removeAll(c: Context) = remove(c, _kept.value + _keeping.value.keys)

    /** How far along these songs are, 0 to 1: the kept whole, the rest by the bytes come so far. */
    fun progress(c: Context, ids: List<String>): Float {
        if (ids.isEmpty()) return 1f
        val dm = c.getSystemService(DownloadManager::class.java) ?: return 0f
        val coming = _keeping.value.filterKeys { it in ids }
        val parts = HashMap<Long, Float>()
        if (coming.isNotEmpty()) runCatching {
            dm.query(DownloadManager.Query().setFilterById(*coming.values.toLongArray()))?.use { q ->
                val idCol = q.getColumnIndex(DownloadManager.COLUMN_ID)
                val so = q.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val all = q.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                while (q.moveToNext()) {
                    val total = q.getLong(all)
                    parts[q.getLong(idCol)] = if (total > 0) (q.getLong(so).toFloat() / total).coerceIn(0f, 1f) else 0f
                }
            }
        }
        val done = ids.count { it in _kept.value }
        return (done + coming.values.sumOf { (parts[it] ?: 0f).toDouble() }.toFloat()) / ids.size
    }

    /** How much the kept songs take (bytes). */
    fun bytes(c: Context): Long = dir(c).listFiles()?.filter { !it.name.endsWith(".dl") && !it.name.endsWith(".json") }?.sumOf { it.length() } ?: 0L

    /** Downloads finished made songs (renamed whole), failed ones let go; the kept read from the folder. */
    @Synchronized fun refresh(c: Context) {
        if (_keeping.value.isEmpty()) _keeping.value = read(c)
        val dm = c.getSystemService(DownloadManager::class.java)
        val now = _keeping.value.toMutableMap()
        if (dm != null && now.isNotEmpty()) {
            dm.query(DownloadManager.Query().setFilterById(*now.values.toLongArray()))?.use { q ->
                val idCol = q.getColumnIndex(DownloadManager.COLUMN_ID)
                val statusCol = q.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val seen = HashSet<Long>()
                while (q.moveToNext()) {
                    val dl = q.getLong(idCol); seen += dl
                    val song = now.entries.firstOrNull { it.value == dl }?.key ?: continue
                    when (q.getInt(statusCol)) {
                        DownloadManager.STATUS_SUCCESSFUL -> { File(dir(c), "$song.dl").renameTo(File(dir(c), song)); now.remove(song) }
                        DownloadManager.STATUS_FAILED -> { File(dir(c), "$song.dl").delete(); now.remove(song) }
                    }
                }
                now.entries.removeAll { it.value !in seen }   // cleared from the download manager: gone
            }
        }
        _keeping.value = now; save(c)
        _kept.value = dir(c).listFiles()?.filter { !it.name.endsWith(".dl") && !it.name.endsWith(".json") && it.length() > 0 }?.map { it.name }?.toSet().orEmpty()
    }

    private fun save(c: Context) = runCatching {
        pending(c).writeText(JSONObject(_keeping.value.mapValues { it.value }).toString())
    }

    private fun read(c: Context): Map<String, Long> = runCatching {
        val o = JSONObject(pending(c).readText())
        o.keys().asSequence().associateWith { o.getLong(it) }
    }.getOrDefault(emptyMap())

    /** A download finished (the app open or not): the kept read again. */
    class Done : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { refresh(c) }
    }
}
