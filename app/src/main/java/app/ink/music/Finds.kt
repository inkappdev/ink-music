package app.ink.music

import android.content.Context
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
 * MONDAY FINDS and FRIDAY DROPS (2026-11-18, Bronson: "how can my spotify discover weekly playlist show up every monday
 * in this app?", then for everyone, and "dont brand it as spotify"): signed in to Spotify, your own two weekly
 * playlists made for you there, read each week as Spotify's web player reads them (Spotify.songs, as the import does:
 * its API keeps them from apps since 2024) and each song found to play here (Import.match: yours first, else YouTube
 * Music's). Shown on listen in ink's words, never Spotify's; kept in files/finds-*.json, so a week shows till the next.
 * Nothing without a Spotify sign-in (and none in the play build, which signs in to nothing).
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
    @Volatile private var read = false

    /** The kept weeks read, then any new one fetched (Main's onStart: ink music opened). */
    fun start(c: Context) {
        val app = c.applicationContext
        scope.launch {
            if (!read) {
                read = true
                _weeks.value = Kind.entries.mapNotNull { k -> load(app, k)?.let { k to it } }.toMap()
            }
            refresh(app)
            // a week read on a phone's data: kept once ink's opened on wifi
            _weeks.value.forEach { (k, w) -> runCatching { keepOffline(app, k, null, w) } }
        }
    }

    /** The day this week's list came out (its day at midnight, this week or last). */
    private fun since(k: Kind, now: Long = System.currentTimeMillis()): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        while (get(Calendar.DAY_OF_WEEK) != k.day) add(Calendar.DAY_OF_YEAR, -1)
    }.timeInMillis

    /** Each list not yet read since its day read now: signed in to Spotify only, the passes fetched out of sight. */
    private suspend fun refresh(c: Context) = gate.withLock {
        if (!Spotify.available || !Spotify.signedIn()) return@withLock
        val due = Kind.entries.filter { (_weeks.value[it]?.at ?: 0L) < since(it) }
        if (due.isEmpty() || !Spotify.fresh(c)) return@withLock
        val lists = Spotify.playlists() ?: return@withLock
        for (k in due) {
            val theirs = lists.firstOrNull { it.id.startsWith(k.prefix) } ?: lists.firstOrNull { l -> k.names.any { l.name.equals(it, true) } } ?: continue
            val said = Spotify.songs(theirs.id) ?: continue
            val songs = Import.match(said).filterNotNull().distinctBy { it.id }
            android.util.Log.i("ink-finds", "${k.title}: ${said.size} songs, ${songs.size} found here")
            if (songs.isEmpty()) continue
            val week = Week(System.currentTimeMillis(), songs)
            keepOffline(c, k, _weeks.value[k], week)
            _weeks.value = _weeks.value + (k to week)
            runCatching {
                File(c.filesDir, k.file).writeText(JSONObject().put("at", week.at).put("songs", JSONArray(songs.map { it.json() })).toString())
            }
        }
    }

    /**
     * The week kept on the phone (2026-11-18, Bronson: discoveries offline): on wifi, as keep-what-you-love keeps on wifi
     * (off there, none); last week's let go, those you've since loved or kept by hand aside (a love keeps its own).
     */
    private fun keepOffline(c: Context, k: Kind, last: Week?, week: Week) {
        if (KeepLoved.mode.value == LoveKeeping.OFF) return
        val gone = last?.songs.orEmpty().map { it.id }.filter { id -> id !in week.songs.map { it.id } && id !in Loved.ids.value && id in autoKept(c, k) }
        if (gone.isNotEmpty()) Offline.remove(c, gone)
        val keep = week.songs.filter(Offline::keepable).filter { it.id !in Offline.kept.value }
        if (keep.isEmpty() || Quality.metered(c)) return
        Offline.keep(c, keep, auto = true)
        runCatching { File(c.filesDir, "finds-kept-${k.name.lowercase()}.json").writeText(JSONArray((autoKept(c, k) + keep.map { it.id }).toList()).toString()) }
    }

    /** The songs a week's list kept on the phone itself (only those are let go). */
    private fun autoKept(c: Context, k: Kind): Set<String> = runCatching {
        JSONArray(File(c.filesDir, "finds-kept-${k.name.lowercase()}.json").readText()).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
    }.getOrDefault(emptySet())

    private fun load(c: Context, k: Kind): Week? = runCatching {
        val o = JSONObject(File(c.filesDir, k.file).readText())
        val a = o.getJSONArray("songs")
        Week(o.getLong("at"), (0 until a.length()).map { songOf(a.getJSONObject(it)) })
    }.getOrNull()

    /** "nov 17": when a week's list came out. */
    fun dated(k: Kind, w: Week): String =
        java.text.SimpleDateFormat("MMM d", java.util.Locale.US).format(java.util.Date(since(k, w.at))).lowercase()
}
