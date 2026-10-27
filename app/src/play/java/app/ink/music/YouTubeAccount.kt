package app.ink.music

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The play build has no YouTube Music: no one to sign in, nothing to read. */
object YouTubeAccount {
    const val available = false
    val who: StateFlow<TubeWho?> = MutableStateFlow(null)
    val playlists = MutableStateFlow<List<TubeList>>(emptyList())
    val records = MutableStateFlow<List<TubeAlbum>>(emptyList())
    val history = MutableStateFlow<List<Song>>(emptyList())
    val liked = MutableStateFlow<List<Song>>(emptyList())
    val artists = MutableStateFlow<List<TubeArtist>>(emptyList())
    val mixes = MutableStateFlow<List<TubeList>>(emptyList())
    val fresh = MutableStateFlow<List<TubeAlbum>>(emptyList())
    val reading = MutableStateFlow(false)
    fun start(c: android.content.Context) {}
    fun signOut(c: android.content.Context) {}
    fun sync() {}
    suspend fun playlist(id: String): List<Song> = emptyList()
    fun like(s: Song, on: Boolean) {}
    suspend fun artist(a: TubeArtist): Pair<List<Song>, List<TubeAlbum>> = emptyList<Song>() to emptyList()
    fun openSignIn(c: android.content.Context) {}
}
