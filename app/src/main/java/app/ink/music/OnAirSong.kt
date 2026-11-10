package app.ink.music

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState

/**
 * What a station's playing, as a song you can have, as the computer's (2026-11-10): the title and artist the station says
 * ([OnAir], in the playing item's words) found on YouTube Music, once a song, kept for the run. Its artist opens their
 * page, its title find with it typed, the heart loves it, playlist puts it in a list. A station that says nothing (or a
 * show's name, NTS's) has nothing to find here.
 */
object OnAirSong {
    private val found = java.util.Collections.synchronizedMap(HashMap<String, Song?>())
    /** What find opens with, typed, when the player asks for it (taken once). */
    @Volatile private var typed: String? = null

    /** The song on YouTube Music, null where it has none (asked once a song). */
    suspend fun of(title: String, artist: String): Song? {
        val k = (artist + "/" + title).lowercase()
        if (found.containsKey(k)) return found[k]
        return runCatching { Picks.song(title, artist) }.getOrNull().also { found[k] = it }
    }

    /** Find, with the song on the air typed: everything ink has of it. */
    fun find(title: String, artist: String, onOpen: (Page) -> Unit) { typed = "$artist $title"; onOpen(Page.Find) }
    fun take(): String? = typed.also { typed = null }
}

/** What the station playing says it's on, title and artist, when it says both (not its own words, not a show's name). */
fun aired(n: Now?): OnAir.Said? {
    val s = n?.song?.takeIf { it.station } ?: return null
    if (n.album != s.title || n.title.isBlank() || n.title == s.title || n.artist.isBlank() || n.artist == s.artist) return null
    return OnAir.Said(n.title, n.artist)
}

/** That song found on YouTube Music (null while it's looked for, or where there's none; the play build has none). */
@Composable
fun rememberAiredSong(n: Now?): Song? {
    val a = aired(n)
    return produceState<Song?>(null, a?.title, a?.artist) {
        value = null
        if (a != null && YouTubeMusic.available) value = OnAirSong.of(a.title, a.artist!!)
    }.value
}
