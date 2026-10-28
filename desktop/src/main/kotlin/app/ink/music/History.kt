package app.ink.music

import org.json.JSONObject
import java.io.File

/**
 * What's been heard on this computer: a song played 30 s or more, when, and for how long (the phone's
 * History, one line a song, the same words). Kept here only; the orders read it ([Listening]).
 */
object History {
    /** A song heard: when it started (wall clock ms), what it was, how long it played (ms). */
    data class Heard(val at: Long, val id: String, val title: String, val artist: String, val album: String, val record: String, val ms: Long)

    /** Less than this isn't a listen (a skip, a look). */
    const val ENOUGH = 30_000L

    private val file = File(Home.dir, "heard.jsonl")

    @Synchronized fun add(h: Heard) {
        if (h.ms < ENOUGH || h.title.isEmpty()) return
        runCatching {
            file.appendText(JSONObject().put("at", h.at).put("id", h.id).put("title", h.title).put("artist", h.artist)
                .put("album", h.album).put("record", h.record).put("ms", h.ms).toString() + "\n")
        }
        Listened.read()
    }

    /** Everything heard, oldest first (a line that won't read is passed over). */
    fun read(): List<Heard> = runCatching {
        file.readLines().mapNotNull { line ->
            runCatching {
                val o = JSONObject(line)
                Heard(o.getLong("at"), o.getString("id"), o.getString("title"), o.getString("artist"),
                    o.optString("album"), o.optString("record"), o.getLong("ms"))
            }.getOrNull()
        }
    }.getOrDefault(emptyList())
}
