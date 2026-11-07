package app.ink.music

/** The iPhone's runners for YouTube's streams (iosMain: JavaScriptCore and a hidden WKWebView). */
expect fun tubeJs(): TubeJs

/**
 * YouTube Music on the iPhone (2026-11-07): its search, records, artists and mixes by InnerTube (shared with the phone
 * and the computer), signed out for now; its songs' audio found as each plays ([TubeStream]). A song's id is
 * YouTube's ("youtube" its kind), its record's the browse id ("MPREb_...").
 */
object Tube {
    val api by lazy { InnerTube() }
    private val streams by lazy { TubeStream(tubeJs(), Here.dir).apply { log = { println("ink-youtube $it") } } }
    private val found = HashMap<String, Pair<String, Long>>()
    private val guard = Guard()

    fun song(t: InnerTube.Track, record: String? = null): Song = Song(t.videoId, t.title, t.artists.joinToString(", "), t.album ?: record.orEmpty(),
        t.albumId.orEmpty(), t.art, t.seconds * 1000L, 0, 1, kind = "youtube")

    fun record(i: InnerTube.Item): Album = Album(i.id, i.title, i.subtitle.split(" • ").getOrNull(1) ?: i.subtitle, i.art,
        i.subtitle.split(" • ").lastOrNull()?.toIntOrNull() ?: 0, youtube = true)

    /** A song's stream link (blocking), kept a few hours (YouTube's links last six). */
    fun stream(id: String): String? {
        guard.run { found[id]?.takeIf { nowMs() - it.second < 4 * 3_600_000L }?.first }?.let { return it }
        val s = streams.stream(id) ?: return null
        guard.run { found[id] = s.url to nowMs() }
        return s.url
    }

    /** A record's songs, in order. */
    fun songs(a: Album): List<Song> = api.album(a.id).second.map { song(it, a.title) }.mapIndexed { i, s -> s.copy(track = i + 1) }

    /** A critic's pick found on YouTube Music: its record of that name by that artist (a song's, for a song), or null. */
    fun find(title: String, artist: String, record: Boolean): Album? {
        val t = Search.fold(title.substringBefore(" (").substringBefore(" [")); val a = Search.fold(artist)
        fun by(s: String) = Search.fold(s).let { it == a || it.contains(a) || a.contains(it) }
        return if (record) api.searchItems("$artist $title", InnerTube.Kind.RECORD, 8).firstOrNull { i ->
            Search.fold(i.title.substringBefore(" (")) == t && by(i.subtitle)
        }?.let(::record)
        else api.searchSongs("$artist $title", 8).firstOrNull { s -> Search.fold(s.title.substringBefore(" (")) == t && s.artists.any(::by) }
            ?.let { s -> s.albumId?.let { Album(it, s.album.orEmpty(), s.artists.joinToString(", "), s.art, 0, youtube = true) } }
    }
}
