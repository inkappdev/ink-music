package app.ink.music

import android.os.Handler
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.extractor.metadata.icy.IcyInfo
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executor

/**
 * What a station is playing, said where a song's words go (the player, the mini player, the
 * notification, the lock screen, the widget, the car): the playing item takes the song's title and
 * artist, the station's name as its record, and its own words back when nothing's said (a break, a
 * show with no song). From the stream's own title (ICY: most of ink radio sends "artist - title"),
 * or for the stations that send nothing there, their playlists, asked every 30 s while one plays:
 * KEXP's API, KCRW's, The Current's, WXPN's, FIP's (Radio France) and WFUV's feeds, and NPR's Cadence for
 * KUTX and WFPK. The picture stays the station's own sleeve ([StationSleeve], moving), whatever cover
 * the station sends (2026-10-27: theirs looked bad beside it). In the player's process; logged as `ink-onair`.
 */
class OnAir(private val player: Player, private val main: Handler, private val net: Executor, private val c: android.content.Context? = null) {
    /**
     * What a station says it's playing: the song (no artist: a show, a line), and its record's cover if it gives one.
     * `heard`: not said by the station but heard in its stream ([hearing]), its artist shown with [HEARD] after it.
     */
    data class Said(val title: String, val artist: String?, val art: String? = null, val heard: Boolean = false)

    /** The playing station's song as heard, while it's shown ([hearing]); and the show's name it came in, if one was said. */
    private var heardNow: Said? = null
    private var showNow: Said? = null
    /** The playing station named its own song last it spoke (ICY or its playlist): nothing to hear. */
    private var stationSays = false
    private var hearingFor: String? = null
    private var hearNext = 0L
    private var misses = 0
    @Volatile private var asking = false

    /**
     * A station that says nothing, heard (2026-11-12, Bronson, as the computer's AirHeard): twelve seconds of its stream
     * fingerprinted and the song said where its words go, "heard" after the artist. 20 s after it starts saying
     * nothing, 2 min after a song's found, 45 s after a miss, every 3 min after four misses running; never while the
     * station names its songs; only with the screen on (a phone's data and battery; the lock screen still shows what
     * was heard last). Never a listen: stations aren't scrobbled or kept in your history.
     */
    private val hearing = object : Runnable {
        override fun run() {
            main.postDelayed(this, 5_000)
            val ctx = c ?: return
            if (asking) return
            val item = player.currentMediaItem
            val id = item?.mediaId?.takeIf { it.startsWith("s") && player.isPlaying }
            if (id == null) { if (item?.mediaId?.startsWith("s") != true) hearingFor = null; return }
            val now = System.currentTimeMillis()
            if (id != hearingFor) { hearingFor = id; hearNext = now + 20_000; misses = 0; heardNow = null; showNow = null; stationSays = false }
            if (stationSays) { hearNext = now + 20_000; misses = 0; return }
            if (now < hearNext) return
            val screenOn = (ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
            if (!screenOn) return
            val url = item.localConfiguration?.uri?.toString() ?: item.requestMetadata.mediaUri?.toString() ?: return
            asking = true
            net.execute {
                val found = runCatching { Recognize.live(ctx, url) }.getOrNull()
                android.util.Log.d("ink-recognize", "station $id: ${found?.let { it.artist + " - " + it.title } ?: "nothing"}")
                main.post {
                    asking = false
                    val after = System.currentTimeMillis()
                    if (player.currentMediaItem?.mediaId != id || stationSays) return@post
                    if (found != null) {
                        misses = 0; hearNext = after + 120_000
                        heardNow = Said(found.title, found.artist, heard = true)
                        say(id, heardNow, fromHearing = true)
                    } else {
                        misses++; hearNext = after + if (misses >= 4) 180_000L else 45_000L
                        // what was heard before is likely over: back to the show's name, or the station's own words
                        if (heardNow != null) { heardNow = null; say(id, showNow, fromHearing = true) }
                    }
                }
            }
        }
    }

    init { if (c != null) main.postDelayed(hearing, 5_000) }

    /** A station's own words, kept as first played: what the queue keeps and what a break puts back. */
    private val plain = HashMap<String, MediaMetadata>()
    private var polling: String? = null

    private val poll = object : Runnable {
        override fun run() {
            val id = polling ?: return
            if (player.currentMediaItem?.mediaId != id) return
            if (player.isPlaying) net.execute { val said = runCatching { feeds.getValue(id)() }.getOrNull(); main.post { say(id, said) } }
            main.postDelayed(this, 30_000)
        }
    }

    /** The item's words as they were, the song said aside (what's kept for next time). */
    fun plain(id: String, md: MediaMetadata): MediaMetadata = plain[id] ?: md

    /** The stream's metadata: an ICY title, for a station without a playlist of its own. */
    fun metadata(m: Metadata) {
        val id = player.currentMediaItem?.mediaId?.takeIf { it.startsWith("s") && it !in feeds } ?: return
        for (i in 0 until m.length()) (m.get(i) as? IcyInfo)?.let { icy ->
            // a few send the record's cover as the stream's link (Radio Paradise)
            val art = icy.url?.takeIf { Regex("^https?://.+\\.(jpe?g|png|webp)(\\?.*)?$", RegexOption.IGNORE_CASE).matches(it) }
            say(id, icy.title?.let { parse(it, stationName(id)) }?.copy(art = art))
        }
    }

    /** A new item (or playing or not): the playlist asked while one of those stations plays, other stations' words put back. */
    fun changed() {
        val id = player.currentMediaItem?.mediaId
        for (i in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(i)
            val was = plain[item.mediaId] ?: continue
            if (item.mediaId != id && item.mediaMetadata != was) player.replaceMediaItem(i, item.buildUpon().setMediaMetadata(was).build())
        }
        val feed = id?.takeIf { it in feeds && player.isPlaying }
        if (feed != polling) {
            main.removeCallbacks(poll)
            polling = feed
            if (feed != null) main.post(poll)
        }
    }

    fun stop() { main.removeCallbacks(poll); polling = null; main.removeCallbacks(hearing) }

    private fun stationName(id: String) = plain[id]?.title?.toString() ?: player.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty()

    /** The playing station's words: the song and its cover, or its own (its sleeve) with nothing said. */
    private fun say(id: String, given: Said?, fromHearing: Boolean = false) {
        var said = given
        if (!fromHearing) {
            // the station's own word: its song wins over what was heard; a show's name or its silence leaves a song
            // that was heard on show (the show's name kept for after)
            stationSays = said?.artist?.isNotBlank() == true
            if (stationSays) heardNow = null
            else {
                if (said != null) showNow = said
                heardNow?.let { said = it }
            }
        }
        val at = player.currentMediaItemIndex
        val item = player.currentMediaItem?.takeIf { it.mediaId == id } ?: return
        val own = plain.getOrPut(id) { item.mediaMetadata }
        val s = said
        val md = if (s == null) own else own.buildUpon()
            .setTitle(s.title).setArtist(s.artist?.let { if (s.heard) it + HEARD else it } ?: own.artist).setAlbumTitle(own.title)
            // always the station's own sleeve, moving, never the cover it sends (2026-10-27: they looked
            // bad, the sleeve is ink's): the song's words change, the picture stays the station's
            .setArtworkUri(own.artworkUri).build()
        if (md == item.mediaMetadata) return
        android.util.Log.i("ink-onair", "$id: ${s?.let { "${it.artist} - ${it.title}" + (if (it.heard) " (heard)" else "") + (it.art?.let { a -> " [$a]" } ?: "") } ?: "(its own)"}")
        player.replaceMediaItem(at, item.buildUpon().setMediaMetadata(md).build())
    }

    companion object {
        /** After a heard song's artist, wherever it's shown (the player, the lock screen, the car): not the station's word. */
        const val HEARD = "  ·  heard"

        /** The covers the stations gave this run: the only links the cover provider fetches. */
        val arts: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /** The stations whose streams say nothing, by their playlists: the newest play, if it's a song and recent. */
        private val feeds: Map<String, () -> Said?> = mapOf(
            "skexp" to {
                JSONObject(IndieChart.get("https://api.kexp.org/v2/plays/?limit=1")).getJSONArray("results").optJSONObject(0)
                    ?.takeIf { it.optString("play_type") == "trackplay" && recent(OffsetDateTime.parse(it.getString("airdate")).toInstant().toEpochMilli()) }
                    ?.let { song(it.optString("song"), it.optString("artist"), it.optString("image_uri")) }
            },
            "skcrw-e24" to {
                JSONArray(IndieChart.get("https://tracklist-api.kcrw.com/Music/all/1")).optJSONObject(0)
                    ?.takeIf { recent(OffsetDateTime.parse(it.getString("datetime")).toInstant().toEpochMilli()) }
                    ?.let { song(it.optString("title"), it.optString("artist"), it.optString("albumImageLarge").replace("100x100bb", "600x600bb")) }
            },
            // from its own playlist page, as the computer's (2026-11-10: its old feed, nowplaying.publicradio.org, no longer answers)
            "sthe-current" to { Curation.current()?.let { (t, a, art) -> song(t, a, art.orEmpty()) } },
            // NTS's streams say only "stream": its live feed's show on that channel, said where a song's name goes (no
            // artist: a show, not a song to look for), as the computer's (2026-11-10)
            "snts1" to { nts("1") },
            "snts2" to { nts("2") },
            "swxpn" to {
                val there = ZoneId.of("America/New_York")
                JSONArray(IndieChart.get("https://origin.xpn.org/utils/playlist/json/${java.time.LocalDate.now(there)}.json")).optJSONObject(0)
                    ?.takeIf {
                        recent(LocalDateTime.parse(it.getString("timeslice"), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(there).toInstant().toEpochMilli())
                    }
                    ?.let { song(it.optString("song"), it.optString("artist"), it.optString("image")) }
            },
            // Radio France's live steps: the one on now, if it's a song
            "sfip" to {
                val now = System.currentTimeMillis() / 1000
                val steps = JSONObject(IndieChart.get("https://api.radiofrance.fr/livemeta/pull/7")).getJSONObject("steps")
                steps.keys().asSequence().map { steps.getJSONObject(it) }
                    .firstOrNull { it.optString("embedType") == "song" && it.optLong("start") <= now + 30 && now < it.optLong("end") }
                    ?.let { st ->
                        val by = st.optJSONArray("highlightedArtists")?.let { a -> (0 until a.length()).joinToString(", ") { a.getString(it) } }
                        song(st.optString("title"), by?.ifEmpty { null } ?: st.optString("authors"), st.optString("visual"))
                    }
            },
            "skutx" to { cadence("b90e054f-2565-42b1-aff5-c744bc8fc70d") },
            "swfpk" to { cadence("71fdc4e3-653f-4a1b-a341-3dba6a3e2bf7") },
            "swfuv" to {
                val there = ZoneId.of("America/New_York")
                JSONObject(IndieChart.get("https://wfuv.org/sites/default/files/player/playlist/now_playing.json")).optJSONObject("live")
                    ?.takeIf {
                        it.optString("event_type") == "MUSIC" &&
                            recent(LocalDateTime.parse(it.getString("timestamp"), DateTimeFormatter.ofPattern("MMddyyyy-HHmmss")).atZone(there).toInstant().toEpochMilli())
                    }
                    // no cover of its own (a placeholder)
                    ?.let { song(it.optString("title"), it.optString("artist"), "") }
            },
        )

        /** NPR's Cadence widget (public radio's now playing): the song on now, till its end; a countdown's "#6 - " dropped. */
        private fun cadence(widget: String): Said? {
            val o = JSONObject(IndieChart.get("https://cadence.nprstations.org/api/cadence/widget/$widget")).optJSONObject("currentlyPlayingSong") ?: return null
            val end = o.optJSONObject("end")?.optString("utc")?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
            if (end != null && end < System.currentTimeMillis() - 60_000) return null
            val by = o.optJSONArray("artist")?.let { a -> (0 until a.length()).joinToString(", ") { a.getString(it) } }.orEmpty()
            return song(o.optString("title").replace(Regex("^#\\d+ - "), ""), by, o.optString("artworkUrl").replace("100x100bb", "600x600bb"))
        }

        /** Played in the last 12 minutes (older, the DJ's talking or it's a break). */
        private fun recent(at: Long) = System.currentTimeMillis() - at < 12 * 60_000L

        /** A song from a playlist, its cover asked for at 600 or so (Spotify's 300 px link made its 640). */
        private fun song(title: String, artist: String, art: String) =
            if (title.isBlank() || artist.isBlank() || artist.startsWith("[")) null
            else Said(title.trim(), artist.trim(), art.takeIf { it.startsWith("http") }?.replace("ab67616d00001e02", "ab67616d0000b273"))

        /**
         * A stream's title as (title, artist), the artist null for a show or a line with no song:
         * "artist - title", WFMU's "\"title\" by artist on show" (Radio Eins's "von"), a show's "station - show".
         */
        /** NTS's names come HTML-escaped ("MANTRA &amp; DOUBLE O"). */
        private fun unescape(t: String) = android.text.Html.fromHtml(t, android.text.Html.FROM_HTML_MODE_LEGACY).toString()

        private fun nts(channel: String): Said? {
            val results = JSONObject(IndieChart.get("https://www.nts.live/api/v2/live")).getJSONArray("results")
            for (i in 0 until results.length()) {
                val ch = results.getJSONObject(i)
                if (ch.optString("channel_name") != channel) continue
                return ch.optJSONObject("now")?.optString("broadcast_title")?.let(::unescape)?.trim()?.takeIf { it.isNotEmpty() }?.let { Said(it, null) }
            }
            return null
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
}
