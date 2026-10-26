package app.ink.music

/** The play build has no YouTube Music: nothing found, nothing played. */
object YouTubeMusic {
    const val available = false
    fun attach(c: android.content.Context) {}
    fun known(id: String): Song? = null
    fun remember(s: Song) {}
    suspend fun search(q: String) = TubeFound()
    suspend fun album(a: TubeAlbum): List<Song> = emptyList()
    fun stream(id: String, forSpeaker: Boolean): Pair<String, String>? = null
    fun related(id: String): List<Song> = emptyList()
    fun find(title: String, artist: String): Song? = null
    fun artist(name: String): TubeArtist? = null
    fun hit(name: String): Song? = null
    fun records(name: String): List<TubeAlbum> = emptyList()
}
