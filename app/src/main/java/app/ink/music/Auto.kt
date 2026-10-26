package app.ink.music

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The library read in the player's process too (Android Auto, covers), so the car has it with the
 * app closed: the phone's files and the server's kept songs (no sync: the screens do that), loves.
 */
object Shelf {
    fun load(c: Context) {
        Library.start(c)
        Server.startQuiet(c)
        Loved.start(c)
    }

    /** Waits (a few seconds at most) for the phone's files to have been read once. */
    suspend fun ready(c: Context) {
        load(c)
        withTimeoutOrNull(5_000) { Library.read.first { it } }
    }
}

/**
 * ink music in the car (Android Auto, through the player's MediaLibrarySession): records as a grid
 * of covers, artists, and lists (loved, your playlists); search, and "play ... on ink music". A
 * song is listed with where it was found ("r:<record>|<song>"), so tapped, the record (or artist,
 * or list) plays from there.
 */
object Auto {
    const val ROOT = "root"
    private const val RECORDS = "records"
    private const val ARTISTS = "artists"
    private const val LISTS = "lists"
    private const val LOVED = "l:loved"

    fun root(): MediaItem = folder(ROOT, "ink music", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    suspend fun children(c: Context, parent: String): List<MediaItem> {
        Shelf.ready(c)
        return when {
            parent == ROOT -> listOfNotNull(
                folder(RECORDS, "records", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, grid = true),
                folder(ARTISTS, "artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
                // lists once there's one (an empty folder in the car looks broken)
                folder(LISTS, "lists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS).takeIf { loved().isNotEmpty() || Server.playlistsNow(c).isNotEmpty() },
            )
            parent == RECORDS -> Library.albums.value.map { a ->
                folder("r:" + a.id, a.title, MediaMetadata.MEDIA_TYPE_ALBUM, subtitle = a.artist, art = a.songs.first().id)
            }
            parent == ARTISTS -> artistsOf(Library.albums.value, Library.songs.value).map { a ->
                folder("a:" + a.name, a.name, MediaMetadata.MEDIA_TYPE_ARTIST,
                    subtitle = "${a.songs} ${if (a.songs == 1) "song" else "songs"}")
            }
            parent == LISTS -> buildList {
                if (loved().isNotEmpty()) add(folder(LOVED, "loved", MediaMetadata.MEDIA_TYPE_PLAYLIST, subtitle = "${loved().size} songs"))
                Server.playlistsNow(c).forEach { add(folder("p:" + it.id, it.name, MediaMetadata.MEDIA_TYPE_PLAYLIST, subtitle = "${it.count} songs")) }
            }
            else -> songsIn(c, parent).map { song(parent, it) }
        }
    }

    /** The songs a browsable item holds, in its order. */
    private suspend fun songsIn(c: Context, list: String): List<Song> = when {
        list.startsWith("r:") -> Library.albums.value.firstOrNull { it.id == list.removePrefix("r:") }?.songs.orEmpty()
        list.startsWith("a:") -> list.removePrefix("a:").let { name ->
            Library.songs.value.filter { name in it.artist.split(", ") || it.albumArtist == name }
        }
        list == LOVED -> loved()
        list.startsWith("p:") -> Server.playlist(list.removePrefix("p:"))
        list.startsWith("s:") -> found(list.removePrefix("s:"))
        else -> emptyList()
    }

    private fun loved(): List<Song> {
        val ids = Loved.ids.value
        return (Library.songs.value.filter { it.id in ids } + Loved.tube.value).sortedBy { Library.sortName(it.title) }
    }

    /**
     * What a tapped song plays: the list it was found in, from it. A song asked for by id alone is
     * itself; an id not ours (a screen's queue item, say), null.
     */
    suspend fun play(c: Context, id: String): Pair<List<Song>, Int>? {
        Shelf.ready(c)
        val list = id.substringBefore('|', "")
        val songId = id.substringAfter('|')
        if (list.isEmpty()) return Library.song(id)?.let { listOf(it) to 0 }
        val songs = songsIn(c, list)
        // a playlist can hold a song twice: the first is as good as any
        val at = songs.indexOfFirst { it.id == songId }
        return if (at >= 0) songs to at else Library.song(songId)?.let { listOf(it) to 0 }
    }

    /** The car's search, and "play ... on ink music": an artist named, their songs; a record, it; else the songs found. */
    fun found(q: String): List<Song> {
        val l = q.trim().lowercase()
        if (l.isEmpty()) return emptyList()
        val songs = Library.songs.value
        artistsOf(Library.albums.value, songs).firstOrNull { it.name.lowercase() == l }?.let { a ->
            return songs.filter { a.name in it.artist.split(", ") || it.albumArtist == a.name }
        }
        Library.albums.value.firstOrNull { it.title.lowercase() == l }?.let { return it.songs }
        fun rank(s: String): Int? { val t = s.lowercase(); return when { t.startsWith(l) -> 0; t.contains(" $l") -> 1; t.contains(l) -> 2; else -> null } }
        return songs.mapNotNull { s -> listOfNotNull(rank(s.title), rank(s.artist)?.plus(1), rank(s.album)?.plus(2)).minOrNull()?.let { it to s } }
            .sortedBy { it.first }.map { it.second }.take(50)
    }

    fun searchResults(q: String): List<MediaItem> = found(q).map { song("s:$q", it) }

    private fun folder(id: String, title: String, type: Int, subtitle: String? = null, art: String? = null, grid: Boolean = false) =
        MediaItem.Builder().setMediaId(id).setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle).setIsBrowsable(true).setIsPlayable(false).setMediaType(type)
                .apply { art?.let { setArtworkUri(CoverProvider.of(it)) } }
                .apply {
                    // records as a grid of covers, as the library shows them
                    if (grid) setExtras(Bundle().apply {
                        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                    })
                }
                .build(),
        ).build()

    private fun song(list: String, s: Song) = MediaItem.Builder().setMediaId("$list|${s.id}").setMediaMetadata(
        MediaMetadata.Builder().setTitle(s.title).setArtist(s.artist).setAlbumTitle(s.album).setSubtitle(s.artist)
            .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setArtworkUri(if (s.tube) s.art?.let(android.net.Uri::parse) else CoverProvider.of(s.id)).build(),
    ).build()
}
