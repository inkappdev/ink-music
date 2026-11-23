package app.ink.music

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Bringing playlists in from Spotify, by a file the listener already has the right to (Spotify's own API is
 * closed to small apps, and its pages aren't ours to read): a CSV from Exportify (exportify.app: one playlist a
 * file, or all of them zipped as CSVs), or the playlists file of Spotify's own data download ("Playlist1.json",
 * every playlist in one). Each song matched to one that plays: your own (the phone's, your server's) first, else
 * YouTube Music's (the full build). Then made where you keep playlists, or played as it is.
 */
object Import {
    /** The playlists in a file (one for an Exportify CSV, every one for Spotify's own): read here, parsed by [SpotifyLibrary]. */
    suspend fun read(c: Context, uri: Uri): List<SpotifyLibrary.Listed> = withContext(Dispatchers.IO) {
        val text = c.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } ?: return@withContext emptyList()
        val name = runCatching {
            c.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull().orEmpty().substringBeforeLast('.').replace('_', ' ').ifEmpty { "from spotify" }
        SpotifyLibrary.parse(text, name)
    }

    /**
     * Each song as one that plays, in order (null where none was found): your own first (by title and artist,
     * the way the indie chart matches), else YouTube Music's, eight asked at a time; [found] told as it goes.
     */
    suspend fun match(songs: List<SpotifyLibrary.Said>, found: (Int) -> Unit = {}): List<Song?> = coroutineScope {
        val mine = Library.songs.value.associateBy { Radio.key(it.title, it.artist) }
        var done = 0
        songs.chunked(8).flatMap { batch ->
            batch.map { s ->
                async(Dispatchers.IO) {
                    (mine[Radio.key(s.title, s.artist)] ?: if (YouTubeMusic.available) YouTubeMusic.find(s.title, s.artist)?.takeIf { SpotifyLibrary.sameArtist(it.artist, s.artist) } else null)
                        .also { synchronized(this@Import) { done++; found(done) } }
                }
            }.awaitAll()
        }
    }
}
