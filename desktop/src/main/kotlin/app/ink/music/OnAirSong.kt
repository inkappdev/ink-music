package app.ink.music

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState

/**
 * What a station's playing, as a song you can have (2026-11-10, Bronson: "make the artist and song clickable and maybe a
 * shortcut to love it", "id so you can add"): the title and artist the station says ([Player.onAir]) found on YouTube
 * Music, once a song, kept for the run. Its artist opens their page, its title find with it typed, the heart loves it,
 * add puts it in a list. A station that says nothing (NTS between its shows' tracklists) has nothing to find here.
 */
object OnAirSong {
    private val found = java.util.Collections.synchronizedMap(HashMap<String, Song?>())

    /** The song on YouTube Music, null where it has none (asked once a song). */
    suspend fun of(title: String, artist: String): Song? {
        val k = (artist + "/" + title).lowercase()
        if (found.containsKey(k)) return found[k]
        return Picks.song(title, artist).also { found[k] = it }
    }

    /** Find, with the song on the air typed: everything ink has of it. */
    fun find(title: String, artist: String) { Finding.query = "$artist $title"; Nav.find() }
}

/** What the station playing says it's on (its title and artist), when it says both; null otherwise or not a station. */
@Composable
fun rememberAired(n: Player.Now?): OnAir.Said? {
    val air by Player.onAir.collectAsState()
    return air?.takeIf { n?.song?.station == true && it.title.isNotBlank() && !it.artist.isNullOrBlank() }
}

/** That song found on YouTube Music (null while it's looked for, or where there's none). */
@Composable
fun rememberAiredSong(n: Player.Now?): Song? {
    val a = rememberAired(n)
    return produceState<Song?>(null, a?.title, a?.artist) {
        value = null
        if (a != null) value = OnAirSong.of(a.title, a.artist!!)
    }.value
}
