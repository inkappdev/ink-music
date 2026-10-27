package app.ink.music

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * ink weekly: songs picked by hand by the people who make ink, a new week every Friday night.
 * The weeks are one file in the repo (weekly.json, written by tools/weekly.py from a YouTube Music
 * playlist), read from GitHub: kept on the phone and shown at once, asked for again once it's a few
 * hours old. Nothing about you is sent. Each song plays from your own copy when you have it (the
 * phone's, your server's), else from YouTube Music (the full build; the play build keeps only yours).
 */
object Weekly {
    /** A week: its number, the day it came out, its name and a line about it, its songs as they'll play. */
    data class Week(val week: Int, val date: String, val title: String, val note: String, val songs: List<Song>)

    /** A song as the week lists it, before it's matched to one that plays. */
    private data class Pick(val title: String, val artist: String, val album: String, val seconds: Int, val video: String, val art: String?)
    private data class Raw(val week: Int, val date: String, val title: String, val note: String, val picks: List<Pick>)

    private val raw = MutableStateFlow<List<Raw>>(emptyList())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Every week, newest first, each song matched to your own copy where there is one (again as the library's read). */
    val weeks: StateFlow<List<Week>> = combine(raw, Library.songs) { weeks, library ->
        val mine = library.associateBy { Radio.key(it.title, it.artist) }
        weeks.map { w -> Week(w.week, w.date, w.title, w.note, w.picks.mapNotNull { p -> mine[Radio.key(p.title, p.artist)] ?: tube(p) }) }
            .filter { it.songs.isNotEmpty() }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private var loaded = false

    fun load(c: Context) {
        if (loaded) return
        loaded = true
        scope.launch {
            val f = File(c.filesDir, "weekly.json")
            runCatching { raw.value = parse(f.readText()) }
            // a new week comes Friday night: asked again once what's kept is a few hours old
            if (f.exists() && System.currentTimeMillis() - f.lastModified() < 3 * 3_600_000L) return@launch
            runCatching {
                val text = IndieChart.get(BuildConfig.WEEKLY)
                raw.value = parse(text)
                f.writeText(text)
            }.onFailure { android.util.Log.d("ink-weekly", "read: $it") }
        }
    }

    private fun parse(text: String): List<Raw> {
        val a = JSONObject(text).getJSONArray("weeks")
        return (0 until a.length()).map { a.getJSONObject(it) }.map { w ->
            val s = w.getJSONArray("songs")
            Raw(w.getInt("week"), w.optString("date"), w.optString("title"), w.optString("note"), (0 until s.length()).map { s.getJSONObject(it) }.map {
                Pick(it.getString("title"), it.getString("artist"), it.optString("album"), it.optInt("seconds"), it.optString("video"), it.optString("art").ifEmpty { null })
            })
        }.sortedByDescending { it.week }
    }

    /** A song YouTube Music plays (the full build only), its own cover. */
    private fun tube(p: Pick): Song? {
        if (!YouTubeMusic.available || p.video.isEmpty()) return null
        return Song(
            id = "y${p.video}", uri = Uri.parse("ytm://${p.video}"), title = p.title, artist = p.artist, album = p.album,
            albumId = "y${p.video}", albumArtist = p.artist.substringBefore(", "), duration = p.seconds * 1000L, disc = 1, track = 0, year = 0,
            art = p.art, format = "youtube",
        ).also(YouTubeMusic::remember)
    }

    /** "30 october", for a week's day. */
    fun day(date: String): String = runCatching {
        java.time.LocalDate.parse(date).format(java.time.format.DateTimeFormatter.ofPattern("d MMMM", java.util.Locale.ENGLISH)).lowercase()
    }.getOrDefault("")
}
