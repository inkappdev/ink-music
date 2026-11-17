package app.ink.music

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Stations kept (2026-11-17, a listener on Reddit: "I can't find a way to add any additional stations"; the computer's):
 * any of radio-browser's, ink radio's or a stream's own link pasted, kept as yours at the top of radio, the latest kept
 * first, each with what's said under it (its place and quality). In stations-kept.json.
 */
object KeptStations {
    data class Kept(val song: Song, val said: String)

    val all = MutableStateFlow<List<Kept>>(emptyList())
    @Volatile private var read = false

    fun load(c: Context) {
        if (read) return
        read = true
        all.value = runCatching {
            val a = JSONArray(File(c.filesDir, FILE).readText())
            (0 until a.length()).map { a.getJSONObject(it) }.map { o -> Kept(songOf(o.getJSONObject("song")), o.optString("said")) }
                .onEach { Stations.remember(it.song) }
        }.getOrDefault(emptyList())
    }

    fun has(id: String) = all.value.any { it.song.id == id }

    fun toggle(c: Context, s: Song, said: String) {
        load(c)
        all.value = if (has(s.id)) all.value.filter { it.song.id != s.id } else { Stations.remember(s); listOf(Kept(s, said)) + all.value }
        runCatching { File(c.filesDir, FILE).writeText(JSONArray(all.value.map { JSONObject().put("song", it.song.json()).put("said", it.said) }).toString()) }
    }

    private const val FILE = "stations-kept.json"
}

/** What radio looks for, asked from find too ("more stations": radio opened, its words typed). */
object RadioAsk {
    val query = MutableStateFlow("")
    val asked = MutableStateFlow(0)
    /** Each ask to go to radio (the library's pager goes there). */
    val go = MutableStateFlow(0)
    fun look(q: String) { query.value = q; asked.value++; go.value++ }
}
