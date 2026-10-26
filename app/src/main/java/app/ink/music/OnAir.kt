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
 * KEXP's API, KCRW's, The Current's and WXPN's feeds. With it the record's cover where the station
 * gives one (the playlists all do; a few streams), through [CoverProvider] so the notification and
 * the car have it too; else the station's own sleeve ([StationSleeve]). In the player's process;
 * logged as `ink-onair`.
 */
class OnAir(private val player: Player, private val main: Handler, private val net: Executor) {
    /** What a station says it's playing: the song (no artist: a show, a line), and its record's cover if it gives one. */
    data class Said(val title: String, val artist: String?, val art: String? = null)

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

    fun stop() { main.removeCallbacks(poll); polling = null }

    private fun stationName(id: String) = plain[id]?.title?.toString() ?: player.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty()

    /** The playing station's words: the song and its cover, or its own (its sleeve) with nothing said. */
    private fun say(id: String, said: Said?) {
        val at = player.currentMediaItemIndex
        val item = player.currentMediaItem?.takeIf { it.mediaId == id } ?: return
        val own = plain.getOrPut(id) { item.mediaMetadata }
        val md = if (said == null) own else own.buildUpon()
            .setTitle(said.title).setArtist(said.artist ?: own.artist).setAlbumTitle(own.title)
            .setArtworkUri(said.art?.let { arts += it; CoverProvider.art(it) } ?: own.artworkUri).build()
        if (md == item.mediaMetadata) return
        android.util.Log.i("ink-onair", "$id: ${said?.let { "${it.artist} - ${it.title}" + (it.art?.let { a -> " [$a]" } ?: "") } ?: "(its own)"}")
        player.replaceMediaItem(at, item.buildUpon().setMediaMetadata(md).build())
    }

    companion object {
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
            "sthe-current" to {
                JSONObject(IndieChart.get("https://nowplaying.publicradio.org/the-current/playlist")).getJSONObject("data").getJSONArray("songs").optJSONObject(0)
                    ?.takeIf { recent(OffsetDateTime.parse(it.getString("played_at")).toInstant().toEpochMilli()) }
                    ?.let { song(it.optString("title"), it.optString("artist"), it.optString("art_url")) }
            },
            "swxpn" to {
                val there = ZoneId.of("America/New_York")
                JSONArray(IndieChart.get("https://origin.xpn.org/utils/playlist/json/${java.time.LocalDate.now(there)}.json")).optJSONObject(0)
                    ?.takeIf {
                        recent(LocalDateTime.parse(it.getString("timeslice"), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(there).toInstant().toEpochMilli())
                    }
                    ?.let { song(it.optString("song"), it.optString("artist"), it.optString("image")) }
            },
        )

        /** Played in the last 12 minutes (older, the DJ's talking or it's a break). */
        private fun recent(at: Long) = System.currentTimeMillis() - at < 12 * 60_000L

        /** A song from a playlist, its cover asked for at 600 or so (Spotify's 300 px link made its 640). */
        private fun song(title: String, artist: String, art: String) =
            if (title.isBlank() || artist.isBlank() || artist.startsWith("[")) null
            else Said(title.trim(), artist.trim(), art.takeIf { it.startsWith("http") }?.replace("ab67616d00001e02", "ab67616d0000b273"))

        /**
         * A stream's title as (title, artist), the artist null for a show or a line with no song:
         * "artist - title", WFMU's "\"title\" by artist on show", a show's "station - show".
         */
        fun parse(raw: String, station: String): Said? {
            val s = raw.trim().removeSuffix(".mp3").trim()
            val up = s.uppercase()
            // dublab's "-" between shows, and the like: nothing said
            if (s.none { it.isLetterOrDigit() } || "THIS STATION WILL" in up || "NOW PLAYING INFO" in up || up.endsWith("OFFLINE")) return null
            Regex("^\"(.+)\" by (.+?)(?: on .+)?$").find(s)?.let { return Said(it.groupValues[1], it.groupValues[2]) }
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
