package app.ink.music

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The play build signs in to nothing it isn't its own: Spotify's playlists come in by file (the full build's own). */
object Spotify {
    const val available = false
    const val LIKED_ID = "liked"
    val at: StateFlow<Long> = MutableStateFlow(0L)
    fun ready() = false
    fun signedIn() = false
    suspend fun canvas(c: Context, title: String, artist: String): String? = null
    fun signIn(c: Context) {}
    fun handover(c: Context, scope: kotlinx.coroutines.CoroutineScope): String? = null
    suspend fun playlists(): List<Import.Theirs>? = null
    suspend fun songs(id: String, got: (Int) -> Unit = {}): List<Import.Said>? = null
}
