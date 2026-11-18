package app.ink.music

/**
 * New releases' last shelf, from YouTube Music's editors (2026-11-18, Bronson: its own new records feed "so bad", AI
 * records and the radio's week): its new-music playlists by genre, each made by its editors and new every week. They
 * list songs, so a song's record is what's shown, when it's a record in its own right (an album or an EP, its title not
 * the song's, NewOut's padding out). Which lists: the ones whose artists are nearest your taste; with no taste yet, the
 * indie, alternative and electronic ones (ink's own kind).
 */
object EditorsNew {
    /** The lists, by their names on YouTube Music, and their ids (its moods & genres pages, 2026-11-18). */
    val LISTS = listOf(
        "fresh new indie" to "RDCLAK5uy_mA3uToE28jqsXw85IA7hwbrQP4HDoKDBM",
        "new alternative indie" to "RDCLAK5uy_l4QirOJJ99DTK5t5h7PdS_apsSClRfPpc",
        "your new alternative" to "RDCLAK5uy_n1quIxbUdCfJDCklZQxyss75yUKLp1oMQ",
        "new alt-rock" to "RDCLAK5uy_mFnNPSTd-qlOftBsmv3L_zPxZQtkhiiQg",
        "new rock" to "RDCLAK5uy_mHLCuEiu46C-PlUU4vF5eLKw-IfPNlWQU",
        "new metal" to "RDCLAK5uy_kvoM_zNp2def1e49ddEJhTdsPdA_uJQBc",
        "new electronica" to "RDCLAK5uy_kBbljBMZ2exMXUc3MZdtXHsMwvGqf3eE8",
        "chemistry: fresh dance" to "RDCLAK5uy_nHGuTnohZ82uuk0HQle7s-UgP7h3aoCqg",
        "new r&b" to "RDCLAK5uy_nGZRi-an-ruqiZlNJSGhCDHucdp2FBNfI",
        "pop fresh" to "RDCLAK5uy_nOwL35BM_GUTEbdbw_9FmvQhPWWdd3sAg",
        "clout rising: new hip-hop" to "RDCLAK5uy_k7O9KByATGA1TqFYhyOkylpJ6fM1avtww",
        "the new roots" to "RDCLAK5uy_lIW3ypboNs_OcFEJgmrdMIF0PPgCJyT6s",
        "new jazz impressions" to "RDCLAK5uy_nlJdxcHTlQmkhdN48_-8CjPKyja3x86tQ",
        "country's new crop" to "RDCLAK5uy_n0vqPVYwwLGVv8XMpjj7IovO50hqegreo",
        "released" to "RDCLAK5uy_k5n4srrEB1wgvIjPNTXS9G1ufE9WQxhnA",
        // beyond English (2026-11-18: chosen the same way, by whose artists are nearest you)
        "latin now" to "RDCLAK5uy_lOAAW-PX5XUed76iQefCxkOXd6m6ZvyiM",
        "latin pop before it breaks" to "RDCLAK5uy_lXoTYt3x31LVKo68NbfKdOK1EM98qBDbI",
        "new tropical generation" to "RDCLAK5uy_kmwgk4Dlix6W33hNl4gaZtO8Iup18nDUM",
        "afrobeats now" to "RDCLAK5uy_kmq8ZD0NbHbKkZZ6frtlEjd8khfeZrAd0",
        "fresh sparkling k-pop" to "RDCLAK5uy_kpuUbU8iJ4nMJCwXTKm94T4eO9ixEWY4I",
        "brand new korean r&b" to "RDCLAK5uy_kF8by5xrvesd2WJVI1iANa2rJKVtNKRzI",
        "new j-rock" to "RDCLAK5uy_kPg2TV0-JyQhvB-uciA1_WNUELIv6vKGk",
        "new music hindi" to "RDCLAK5uy_nNhhgRET3NcJ4SJBvqhAIJ6t7vjsQYowc",
        "new music punjabi" to "RDCLAK5uy_mk3xwsayv9PxawuXS-U6ao9eMeNmSwYAM",
        "new indian indie" to "RDCLAK5uy_l8jPcXx__5Dsu2D7vTiXPlO8pZhZsjBFk",
        "fresh arabic" to "RDCLAK5uy_kkDewCncbIEmpbXsGk-2OLy9aWQLMO2-g",
        "hotlist rap francophone" to "RDCLAK5uy_kFeijgv9TyjJ78k-kWLgCrdFr-2iS5lGM",
    )
    private val UNKNOWN = listOf("fresh new indie", "new alternative indie", "new alt-rock", "new electronica", "your new alternative", "the new roots")

    /** A list's songs, as many as are read (blocking: one or two reads). */
    fun read(id: String, tube: InnerTube = InnerTube()): List<InnerTube.Track> = runCatching { tube.tracks("VL$id", 100) }.getOrDefault(emptyList())

    /** The shelf: the lists chosen (their names) and their records, nearest you first, each list in its own order. */
    class Shelf(val lists: List<String>, val records: List<InnerTube.Track>)

    /** The lists read (name to songs), the four nearest your taste (`near` 0 to 1) chosen, their records. */
    fun shelf(read: List<Pair<String, List<InnerTube.Track>>>, near: (String) -> Double, lists: Int = 4): Shelf {
        fun of(t: InnerTube.Track) = t.artists.maxOfOrNull(near) ?: 0.0
        // only lists with a record this week; the nearest you, then ink's own kind to make up four
        val scored = read.map { (name, songs) -> Triple(name, songs.filter(::record), songs.sumOf(::of)) }.filter { it.second.isNotEmpty() }
        val nearest = scored.filter { it.third > 0 }.sortedByDescending { it.third }
        val chosen = (nearest + UNKNOWN.mapNotNull { n -> scored.firstOrNull { it.first == n } }).distinctBy { it.first }.take(lists)
        val records = chosen.map { it.second }
        // the lists taken in turn, so one list never fills the shelf; then the ones near you to the front
        val turned = (0 until (records.maxOfOrNull { it.size } ?: 0)).flatMap { i -> records.mapNotNull { it.getOrNull(i) } }
            .distinctBy { it.albumId }
        val (close, rest) = turned.partition { of(it) >= .3 }
        return Shelf(chosen.map { it.first }, close.sortedByDescending(::of) + rest)
    }

    /**
     * TUNER's new songs, each with its list's name: the lists nearest your taste (`near`) as for the shelf, but every
     * song of theirs, not only those on records; the lists in turn, nearest you first.
     */
    fun songs(read: List<Pair<String, List<InnerTube.Track>>>, near: (String) -> Double, lists: Int = 5): List<Pair<String, InnerTube.Track>> {
        fun of(t: InnerTube.Track) = t.artists.maxOfOrNull(near) ?: 0.0
        val scored = read.filter { it.second.isNotEmpty() }.map { (name, songs) -> Triple(name, songs, songs.sumOf(::of)) }
        val chosen = (scored.filter { it.third > 0 }.sortedByDescending { it.third } + UNKNOWN.mapNotNull { n -> scored.firstOrNull { it.first == n } })
            .distinctBy { it.first }.take(lists).map { (name, songs) -> songs.map { name to it } }
        return (0 until (chosen.maxOfOrNull { it.size } ?: 0)).flatMap { i -> chosen.mapNotNull { it.getOrNull(i) } }.distinctBy { it.second.videoId }
    }

    /** A song from a record in its own right: an album or an EP, not the song's own single, nor a deluxe or a soundtrack. */
    private fun record(t: InnerTube.Track): Boolean {
        val album = t.album ?: return false
        if (t.albumId == null) return false
        val a = Curation.folded(album).filter { it.isLetterOrDigit() }
        val s = Curation.folded(t.title).substringBefore(" (").filter { it.isLetterOrDigit() }
        return a.isNotEmpty() && !a.startsWith(s) && !s.startsWith(a) && NewOut.kept(album, "album")
    }
}
