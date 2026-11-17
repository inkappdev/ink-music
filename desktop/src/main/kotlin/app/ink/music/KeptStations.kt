package app.ink.music

import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Stations kept (2026-11-17, a listener on Reddit: "I can't find a way to add any additional stations"): any of
 * radio-browser's, ink radio's or a stream's own link pasted, kept as yours at the top of the radio tab, the latest kept
 * first. Each with what's said under it (its place and quality). In stations-kept.json.
 */
object KeptStations {
    data class Kept(val song: Song, val said: String)

    private val file get() = File(Home.dir, "stations-kept.json")
    val all = MutableStateFlow(read())

    fun has(id: String) = all.value.any { it.song.id == id }

    fun keep(s: Song, said: String) {
        if (has(s.id)) return
        Stations.remember(s)
        all.value = listOf(Kept(s, said)) + all.value; save()
    }

    fun letGo(id: String) { all.value = all.value.filter { it.song.id != id }; save() }

    fun toggle(s: Song, said: String) = if (has(s.id)) letGo(s.id) else keep(s, said)

    private fun read(): List<Kept> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map { o -> Kept(songOf(o.getJSONObject("song")), o.optString("said")) }
            .onEach { Stations.remember(it.song) }
    }.getOrDefault(emptyList())

    private fun save() = runCatching {
        Home.write(file, JSONArray(all.value.map { JSONObject().put("song", it.song.json()).put("said", it.said) }).toString())
    }
}
