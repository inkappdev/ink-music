package app.ink.music

/**
 * Records made by AI, kept out (2026-11-18, Bronson: "so many of the albums on the new releases list are AI"). No
 * service says which are (YouTube Music's pages carry no mark, Deezer's tag isn't in its API), so this goes by the
 * community's list of AI "artists" (CennoxX/spotify-ai-blocker, MIT: some 10,600 names, kept up daily), fetched
 * weekly and kept in `file`. A name can belong to a real artist too (AURORA's is on it), so one on the list still
 * stays if `real` says so: the apps ask YouTube Music, whose page for a real artist carries a bio from Wikipedia and
 * for an AI one, its own blurb. Asked once per name a run, and only for names on the list.
 */
class MadeByAi(private val file: File) {
    private val guard = Guard()
    private var names: Set<String>? = null
    private val verdicts = HashMap<String, Boolean>()

    /** The list, read from what's kept, fetched when that's a week old or missing (blocking; empty if neither works). */
    private fun list(): Set<String> = guard.run {
        names?.let { return@run it }
        val stale = !file.exists() || nowMs() - file.lastModified() > 7 * DAY
        if (stale) runCatching {
            val r = http(SOURCE)
            if (r.code == 200 && r.text.startsWith("artist,")) { file.getParentFile()?.mkdirs(); file.writeText(r.text) }
        }
        val read = runCatching { file.readLines() }.getOrDefault(emptyList()).drop(1)
            .map { Curation.folded(it.substringBeforeLast(',').trim().removeSurrounding("\"")) }
            .filter { it.isNotEmpty() }.toSet()
        if (read.isNotEmpty() || !stale) names = read
        read
    }

    /** Each name a credit gives ("A & B", "A, B", "A feat. B", "A x B"). */
    private fun credits(artist: String) = artist.split(Regex("""\s*(,|&|\bfeat\.?|\bft\.?|\bx\b|\bwith\b)\s*""", RegexOption.IGNORE_CASE))
        .map { it.trim() }.filter { it.isNotEmpty() }

    /** Any of a credit's names on the list and not shown real by `real` (blocking: `real` may read the network). */
    fun made(artist: String, real: (String) -> Boolean): Boolean {
        val listed = list()
        if (listed.isEmpty()) return false
        return credits(artist).any { name ->
            val key = Curation.folded(name)
            key in listed && !(guard.run { verdicts[key] } ?: runCatching { real(name) }.getOrDefault(false).also { v -> guard.run { verdicts[key] = v } })
        }
    }

    /** `xs` without what AI made (blocking). */
    fun <T> without(xs: List<T>, artist: (T) -> String, real: (String) -> Boolean): List<T> = xs.filterNot { made(artist(it), real) }

    companion object {
        const val SOURCE = "https://raw.githubusercontent.com/CennoxX/spotify-ai-blocker/main/SpotifyAiArtists.csv"
        private const val DAY = 86_400_000L

        /** A real artist, by YouTube Music's page for the name: its bio from Wikipedia (blocking). */
        fun wikipedia(about: String?): Boolean = about.orEmpty().contains("From Wikipedia")
    }
}
