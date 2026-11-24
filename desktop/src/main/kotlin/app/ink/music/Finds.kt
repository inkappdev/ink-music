package app.ink.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * MONDAY FINDS and FRIDAY DROPS on the computer (the phone's Finds, 2026-11-24: Bronson saw them on Android and not
 * here): your two weekly playlists made for you on Spotify, read with the passes the phone hands over in sync (the
 * computer can't sign in to Spotify itself; SpotifyHanded), each song found to play here (Import.match: yours first,
 * else YouTube Music's). In ink's words, never Spotify's; kept in finds-*.json, so a week shows till the next, read
 * again the first time fresh passes arrive after its day. Nothing without passes from the phone.
 */
object Finds {
    enum class Kind(val title: String, val line: String, val day: Int, val file: String, val names: List<String>, val prefix: String) {
        // Discover Weekly, new each Monday; Release Radar, each Friday (its ids begin so: a name in another language too)
        MONDAY("monday finds", "new to you, every monday", Calendar.MONDAY, "finds-monday.json", listOf("discover weekly"), "37i9dQZEVXc"),
        FRIDAY("friday drops", "new from your artists, every friday", Calendar.FRIDAY, "finds-friday.json", listOf("release radar"), "37i9dQZEVXb"),
    }

    /** A week's list: when it was read, its songs (each one that plays here). */
    data class Week(val at: Long, val songs: List<Song>)

    private val _weeks = MutableStateFlow<Map<Kind, Week>>(emptyMap())
    val weeks: StateFlow<Map<Kind, Week>> = _weeks
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    @Volatile private var started = false

    /** The kept weeks read, then a new one fetched each time the phone hands fresh passes. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            _weeks.value = Kind.entries.mapNotNull { k -> load(k)?.let { k to it } }.toMap()
            SpotifyHanded.pass.collect { p -> if (p != null && p.fresh) refresh(p) }
        }
    }

    /** The day this week's list came out (its day at midnight, this week or last). */
    private fun since(k: Kind, now: Long = System.currentTimeMillis()): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        while (get(Calendar.DAY_OF_WEEK) != k.day) add(Calendar.DAY_OF_YEAR, -1)
    }.timeInMillis

    /** Each list not yet read since its day, read now with these passes. */
    private suspend fun refresh(pass: SpotifyCanvas.Pass) = gate.withLock {
        val due = Kind.entries.filter { (_weeks.value[it]?.at ?: 0L) < since(it) }
        if (due.isEmpty()) return@withLock
        val lists = runCatching { SpotifyLibrary.playlists(pass) }.onFailure { Log.d("finds: $it") }.getOrNull() ?: return@withLock
        for (k in due) {
            val theirs = lists.firstOrNull { it.id.startsWith(k.prefix) } ?: lists.firstOrNull { l -> k.names.any { l.name.equals(it, true) } } ?: continue
            val said = runCatching { SpotifyLibrary.songs(theirs.id, pass) }.onFailure { Log.d("finds: $it") }.getOrNull() ?: continue
            val songs = Import.match(said).filterNotNull().distinctBy { it.id }
            Log.d("${k.title}: ${said.size} songs, ${songs.size} found here")
            if (songs.isEmpty()) continue
            val week = Week(System.currentTimeMillis(), songs)
            _weeks.value = _weeks.value + (k to week)
            runCatching {
                File(Home.dir, k.file).writeText(JSONObject().put("at", week.at).put("songs", JSONArray(songs.map { it.json() })).toString())
            }
        }
    }

    private fun load(k: Kind): Week? = runCatching {
        val o = JSONObject(File(Home.dir, k.file).readText())
        val a = o.getJSONArray("songs")
        Week(o.getLong("at"), (0 until a.length()).map { songOf(a.getJSONObject(it)) })
    }.getOrNull()

    /** "nov 17": when a week's list came out. */
    fun dated(k: Kind, w: Week): String =
        java.text.SimpleDateFormat("MMM d", java.util.Locale.US).format(java.util.Date(since(k, w.at))).lowercase()
}
