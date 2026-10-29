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
    val followed = MutableStateFlow<Set<String>>(emptySet())
    val uploads = MutableStateFlow<List<Song>>(emptyList())
    fun saved(a: TubeAlbum) = false
    suspend fun save(a: TubeAlbum, on: Boolean) = false
    suspend fun follow(a: TubeArtist, on: Boolean) = false
    suspend fun addTo(list: TubeList, s: Song) = false
    suspend fun makePlaylist(name: String, s: Song?) = false
    suspend fun mine(id: String) = false
    suspend fun deletePlaylist(id: String) = false
    suspend fun makePlaylistOf(name: String, songs: List<Song>): Pair<TubeList, Int>? = null
    fun start(c: android.content.Context) {}
    fun signOut(c: android.content.Context) {}
    fun sync() {}
    fun playlist(id: String): kotlinx.coroutines.flow.Flow<List<Song>> = kotlinx.coroutines.flow.emptyFlow()
    fun like(s: Song, on: Boolean) {}
    fun played(c: android.content.Context, songId: String) {}
    fun tells(c: android.content.Context) = false
    fun tell(c: android.content.Context, on: Boolean) {}
    fun artist(name: String, id: String = ""): kotlinx.coroutines.flow.Flow<ArtistInfo?> = kotlinx.coroutines.flow.flowOf(null)
    fun warm(ctx: android.content.Context, name: String, id: String = "") {}
    fun warmLibrary(ctx: android.content.Context, names: List<String>) {}
    fun warmNow(ctx: android.content.Context, name: String) {}
    fun openSignIn(c: android.content.Context) {}
    fun handover(c: android.content.Context): String? = null
}
