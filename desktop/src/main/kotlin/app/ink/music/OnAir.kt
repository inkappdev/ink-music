package app.ink.music

import kotlinx.coroutines.launch

/**
 * What a station is playing, said where a song's words go (the mini player, the player): from the stream's own
 * title (ICY: most of ink radio sends "artist - title"), which mpv reads as the stream's media title. The phone's
 * OnAir also asks a few stations' own playlists (KEXP, KCRW...); not yet here.
 */
object OnAir {
    /** What a station says it's playing: the song (no artist: a show, a line). */
    data class Said(val title: String, val artist: String?)

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    @Volatile private var asking: kotlinx.coroutines.Job? = null

    /** NTS 1 or 2: its streams say only "stream" (shown as a song called STREAM, 2026-11-10). */
    fun isNts(id: String) = id == "snts1" || id == "snts2"

    /**
     * NTS's own word for what's on: its live feed's show on that channel ("Pender Street Steppers"), asked as it starts
     * and each minute while it plays, said where a song's name goes (no artist: a show, not a song to look for). Its
     * tracklists come only after a show's over, so the song itself isn't known here.
     */
    fun nts(id: String) {
        val channel = if (id == "snts1") "1" else if (id == "snts2") "2" else return
        asking?.cancel()
        Player.onAir.value = null
        asking = scope.launch {
            do {
                runCatching {
                    val results = org.json.JSONObject(Curation.get("https://www.nts.live/api/v2/live")).getJSONArray("results")
                    for (i in 0 until results.length()) {
                        val ch = results.getJSONObject(i)
                        if (ch.optString("channel_name") != channel) continue
                        val show = ch.optJSONObject("now")?.optString("broadcast_title")?.trim().orEmpty()
                        if (show.isNotEmpty() && Player.now.value?.song?.id.let { it == null || it == id }) Player.onAir.value = Said(show, null)
                    }
                }
                kotlinx.coroutines.delay(60_000)
            } while (Player.now.value?.song?.id == id)
        }
    }

    fun parse(raw: String, station: String): Said? {
        val s = raw.trim().removeSuffix(".mp3").trim()
        val up = s.uppercase()
        // dublab's "-" between shows, and the like: nothing said
        if (s.none { it.isLetterOrDigit() } || "THIS STATION WILL" in up || "NOW PLAYING INFO" in up || up.endsWith("OFFLINE")) return null
        Regex("^\"(.+)\" (?:by|von) (.+?)(?: (?:on|auf) .+)?$").find(s)?.let { return Said(it.groupValues[1], it.groupValues[2]) }
        val dash = s.indexOf(" - ")
        if (dash < 0) return Said(s, null)
        val a = s.substring(0, dash).trim()
        val b = s.substring(dash + 3).trim(' ', '-')
        val first = station.substringBefore(' ').lowercase()
        return when {
            b.isEmpty() -> Said(a, null)
            a.isEmpty() || (first.length > 2 && a.lowercase().startsWith(first)) || a.lowercase() in setOf("live now", "archive") -> Said(b, null)
            else -> Said(b, a)
        }
    }
}
