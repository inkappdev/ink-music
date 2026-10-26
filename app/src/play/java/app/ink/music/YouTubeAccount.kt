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
    val reading = MutableStateFlow(false)
    fun start(c: android.content.Context) {}
    fun signOut(c: android.content.Context) {}
    fun sync() {}
    suspend fun playlist(id: String): List<Song> = emptyList()
    fun openSignIn(c: android.content.Context) {}
}
