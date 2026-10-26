package app.ink.music

/** A YouTube Music record found: its link, name, artist and cover (the full build's only). */
data class TubeAlbum(val url: String, val title: String, val artist: String, val art: String?)

/** What a YouTube Music search found: songs and records. */
data class TubeFound(val songs: List<Song> = emptyList(), val albums: List<TubeAlbum> = emptyList())

/** An artist on YouTube Music: their name and photo (the onboarding's faces). */
data class TubeArtist(val name: String, val face: String?)
