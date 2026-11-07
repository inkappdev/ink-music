package app.ink.music

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * A mixtape (2026-11-07): a name, who it's from, a line to whoever gets it, and up to 24 songs on two sides. The whole
 * tape travels in its link, after the "#" (inkhome.app/tape#...): never sent to any server (a browser keeps what's
 * after the "#" to itself), so there's nothing of ours in between, and a tape can't be taken down or go missing.
 * Packed as JSON, deflated (raw, as a browser's DecompressionStream("deflate-raw") reads it), base64url: 24 songs
 * make a link of about a thousand characters, which a QR code holds.
 *
 * Each song is said by its title and artist (and its YouTube id when it was a YouTube song), so whoever gets it finds
 * each one in what they have: their phone's, their server's, else YouTube Music's.
 */
data class Tape(val name: String, val from: String = "", val note: String = "", val tracks: List<Track>, val sideB: Int = sides(tracks)) {
    /** A song on a tape: as it's said, how long (s; 0 not known), its YouTube id if it was one. */
    data class Track(val title: String, val artist: String, val seconds: Int = 0, val tube: String = "")

    val a get() = tracks.take(sideB)
    val b get() = tracks.drop(sideB)
    /** Each side's length (s). */
    val lengths get() = a.sumOf { it.seconds } to b.sumOf { it.seconds }

    /** The tape it would be on: the shortest that holds the longer side (a C60 is 30 minutes a side). */
    val cassette: String get() {
        val side = maxOf(lengths.first, lengths.second) / 60.0
        return when { side <= 23 -> "C46"; side <= 30 -> "C60"; side <= 45 -> "C90"; else -> "C120" }
    }

    /** Its link: what a QR code holds, and what's shared. */
    fun link(): String = LINK + pack()

    fun pack(): String {
        val o = JSONObject().put("v", 1).put("n", name.take(NAME))
        if (from.isNotBlank()) o.put("f", from.take(FROM))
        if (note.isNotBlank()) o.put("m", note.take(NOTE))
        o.put("b", sideB)
        o.put("t", JSONArray().apply {
            for (t in tracks.take(MAX)) put(JSONArray().put(t.title.take(60)).put(t.artist.take(40)).apply {
                if (t.seconds > 0 || t.tube.isNotEmpty()) put(t.seconds)
                if (t.tube.isNotEmpty()) put(t.tube)
            })
        })
        val raw = o.toString().toByteArray(Charsets.UTF_8)
        val d = Deflater(9, true).apply { setInput(raw); finish() }
        val out = ByteArrayOutputStream(); val buf = ByteArray(1024)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    companion object {
        const val LINK = "https://inkhome.app/tape#"
        /** Twelve a side: more and the QR code is too fine to read off a screen. */
        const val MAX = 24
        const val NAME = 40
        const val FROM = 24
        const val NOTE = 120

        /** Where side B begins: at the middle of the running time (by count when the lengths aren't known). */
        fun sides(tracks: List<Track>): Int {
            if (tracks.size < 2) return tracks.size
            val total = tracks.sumOf { it.seconds }
            if (total <= 0 || tracks.any { it.seconds <= 0 }) return (tracks.size + 1) / 2
            var run = 0
            for ((i, t) in tracks.withIndex()) {
                // the song that crosses the middle goes on the side it's mostly on
                if (run + t.seconds / 2.0 >= total / 2.0) return i.coerceIn(1, tracks.size - 1)
                run += t.seconds
            }
            return tracks.size - 1
        }

        /** A tape from its link (inkhome.app/tape#..., inkmusic://tape#..., or what's after the "#"); null if it isn't one. */
        fun read(link: String): Tape? = runCatching {
            val packed = link.substringAfter('#', link).trim()
            val bytes = Base64.getUrlDecoder().decode(packed)
            val inf = Inflater(true).apply { setInput(bytes) }
            val out = ByteArrayOutputStream(); val buf = ByteArray(1024)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, n)
                if (out.size() > 64_000) break   // no tape is that long
            }
            inf.end()
            val o = JSONObject(out.toString("UTF-8"))
            val t = o.getJSONArray("t")
            val tracks = (0 until minOf(t.length(), MAX)).mapNotNull { i ->
                val r = t.optJSONArray(i) ?: return@mapNotNull null
                Track(r.optString(0), r.optString(1), r.optInt(2), r.optString(3)).takeIf { it.title.isNotBlank() }
            }
            if (tracks.isEmpty()) return null
            Tape(o.optString("n").take(NAME).ifBlank { "a tape" }, o.optString("f").take(FROM), o.optString("m").take(NOTE), tracks,
                o.optInt("b", sides(tracks)).coerceIn(0, tracks.size))
        }.getOrNull()
    }
}
