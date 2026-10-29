package app.ink.music

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * What's been heard on this phone, for "your year": a song played 30 s or more, when, and for how
 * long. Written by the player's process (a line a song, appended), read by the screens'. Kept on
 * the phone only. Not while a speaker plays (it holds its own queue).
 */
object History {
    /** A song heard: when it started (wall clock ms), what it was, how long it played (ms). */
    data class Heard(val at: Long, val id: String, val title: String, val artist: String, val album: String, val record: String, val ms: Long)

    /** Less than this isn't a listen (a skip, a look). */
    const val ENOUGH = 30_000L

    private fun file(c: Context) = File(c.filesDir, "heard.jsonl")

    @Synchronized fun add(c: Context, h: Heard) {
        // a station isn't a song heard (it would top "your songs" by the hour)
        if (h.ms < ENOUGH || h.title.isEmpty() || h.id.startsWith("s")) return
        // private listening: not kept
        if (Private.on(c)) return
        runCatching {
            file(c).appendText(JSONObject().put("at", h.at).put("id", h.id).put("title", h.title).put("artist", h.artist)
                .put("album", h.album).put("record", h.record).put("ms", h.ms).toString() + "\n")
        }
    }

    /** Everything heard, oldest first (a line that won't read is passed over). */
    fun read(c: Context): List<Heard> = runCatching {
        file(c).readLines().mapNotNull { line ->
            runCatching {
                val o = JSONObject(line)
                Heard(o.getLong("at"), o.getString("id"), o.getString("title"), o.getString("artist"),
                    o.optString("album"), o.optString("record"), o.getLong("ms"))
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    fun forget(c: Context) { file(c).delete() }

    /**
     * The song now playing, timed in the player: how long it has actually played (paused time not
     * counted), written down when it changes or the player stops.
     */
    class Clock(private val c: Context) {
        private var item: androidx.media3.common.MediaItem? = null
        private var started = 0L      // wall clock, when it first played
        private var played = 0L       // ms played so far
        private var since = 0L        // elapsedRealtime it last started moving; 0 while still

        fun playing(on: Boolean) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (on && since == 0L) { since = now; if (started == 0L) started = System.currentTimeMillis() }
            if (!on && since != 0L) { played += now - since; since = 0L }
        }

        /** A new song (or none): the last one written down if it was heard. */
        fun song(next: androidx.media3.common.MediaItem?, moving: Boolean) {
            playing(false)
            item?.let { i ->
                val md = i.mediaMetadata
                add(c, Heard(started, i.mediaId, md.title?.toString().orEmpty(), md.artist?.toString().orEmpty(),
                    md.albumTitle?.toString().orEmpty(), md.extras?.getString(Levelling.RECORD).orEmpty(), played))
            }
            item = next; played = 0L; started = 0L
            if (moving) playing(true)
        }
    }
}
