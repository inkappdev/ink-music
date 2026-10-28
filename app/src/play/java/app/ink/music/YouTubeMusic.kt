package app.ink.music

/** The play build has no YouTube Music: nothing found, nothing played. */
object YouTubeMusic {
    const val available = false
    fun attach(c: android.content.Context) {}
    fun known(id: String): Song? = null
    fun remember(s: Song) {}
    suspend fun search(q: String) = TubeFound()
    suspend fun album(a: TubeAlbum): List<Song> = emptyList()
    fun lyrics(id: String, title: String, artist: String): List<Line>? = emptyList()
    fun stream(id: String, forSpeaker: Boolean, small: Boolean = false): Pair<String, String>? = null
    fun kbps(id: String, small: Boolean): Int = 0
    fun refused(id: String) {}
    fun related(id: String): List<Song> = emptyList()
    fun find(title: String, artist: String): Song? = null
    fun artist(name: String): TubeArtist? = null
    fun hit(name: String): Song? = null
    fun records(name: String): List<TubeAlbum> = emptyList()
}
