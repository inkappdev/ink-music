package app.ink.music

/**
 * A song's family as MusicBrainz keeps it (2026-11-24): the original where it's a cover, who else covered it, what it
 * samples and what sampled it, what it's a remix of and its remixes. Four asks at most, a second apart (Socials' gate):
 * the recording found by title and artist, its relations, then its song (the work) and the work's recordings, for the
 * original and the covers. Kept a month a song in `dir`; a song with none, a month too; a failure isn't kept. Blocking.
 */
object Lineage {
    data class Song(val title: String, val artist: String)

    data class Of(
        val original: Song? = null,
        val covers: List<Song> = emptyList(),
        val samples: List<Song> = emptyList(),
        val sampledIn: List<Song> = emptyList(),
        val remixOf: List<Song> = emptyList(),
        val remixes: List<Song> = emptyList(),
    ) {
        val empty get() = original == null && covers.isEmpty() && samples.isEmpty() && sampledIn.isEmpty() && remixOf.isEmpty() && remixes.isEmpty()
    }

    /** What a recording's own relations say: its work (and whether it's a cover of it), and the songs it's tied to. */
    data class Recording(
        val work: String?, val cover: Boolean,
        val samples: List<Song>, val sampledIn: List<Song>, val remixOf: List<Song>, val remixes: List<Song>,
    )

    private const val API = "https://musicbrainz.org/ws/2"
    private const val DAY = 24L * 3600 * 1000
    private const val MOST = 5

    /** `title` by `artist`'s family, kept or asked for; null when MusicBrainz couldn't be asked (nothing kept then). */
    fun of(dir: File, title: String, artist: String): Of? {
        val t = bare(title); val a = artist.substringBefore(", ").trim()
        if (t.isEmpty() || a.isEmpty() || a == "unknown") return Of()
        val f = File(dir, "lineage-" + sha1((Curation.folded(t) + "\n" + Curation.folded(a)).encodeToByteArray()).hex())
        if (f.exists() && nowMs() - f.lastModified() < 30 * DAY) runCatching { return read(f.readText()) }
        val found = runCatching { ask(t, a) }.getOrNull() ?: return null
        runCatching { dir.mkdirs(); f.writeText(write(found)) }
        return found
    }

    private fun ask(title: String, artist: String): Of {
        val q = "recording:\"${quoted(title)}\" AND artist:\"${quoted(artist)}\""
        val id = pick(Socials.ask("$API/recording/?query=${urlEncode(q)}&fmt=json&limit=25").toString(), title, artist) ?: return Of()
        val r = recording(Socials.ask("$API/recording/$id?inc=work-rels+recording-rels+artist-credits&fmt=json").toString())
        val (original, covers) = r.work?.let { w ->
            val work = Socials.ask("$API/work/$w?inc=recording-rels+artist-rels&fmt=json").toString()
            val all = Socials.ask("$API/recording?work=$w&inc=artist-credits&fmt=json&limit=100").toString()
            work(work, all, artist, r.cover)
        } ?: (null to emptyList())
        return Of(original, covers, r.samples, r.sampledIn, r.remixOf, r.remixes)
    }

    /**
     * The search's best answer for the song: its title and artist both (the parts in brackets aside), no video; of those,
     * one with no note on it ("live, ...", "part of a DJ-mix") first, then the one on the most releases (the album's own
     * take, not a compilation's one-off), then the earliest.
     */
    fun pick(text: String, title: String, artist: String): String? {
        val a = JSONObject(text).optJSONArray("recordings") ?: return null
        val t = Curation.folded(bare(title))
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            .filter { it.optInt("score") >= 90 && !it.optBoolean("video") && Curation.folded(bare(it.optString("title"))) == t && credited(it, artist) }
            .sortedWith(compareBy<JSONObject> { it.optString("disambiguation").isNotEmpty() }
                .thenByDescending { it.optJSONArray("releases")?.length() ?: 0 }
                .thenBy { it.optString("first-release-date").ifEmpty { "9999" } })
            .firstOrNull()?.optString("id")?.takeIf(String::isNotEmpty)
    }

    /**
     * A recording's relations read: its work (a performance of it; "cover" said so), what it samples ("samples material",
     * forward) and what sampled it (backward), what it's a remix of ("remix", forward) and its remixes (backward).
     * Videos, edits and mash-ups left out.
     */
    fun recording(text: String): Recording {
        val o = JSONObject(text)
        val rels = o.optJSONArray("relations")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
        val perf = rels.firstOrNull { it.optString("type") == "performance" && it.optString("target-type") == "work" }
        fun songs(type: String, forward: Boolean) = rels.filter {
            it.optString("type") == type && it.optString("target-type") == "recording" && (it.optString("direction") == "forward") == forward
        }.mapNotNull { it.optJSONObject("recording") }.filter { !it.optBoolean("video") }
            .map { Song(it.optString("title"), credit(it)) }
            .filter { it.title.isNotBlank() && it.artist.isNotBlank() }
            .distinctBy { Curation.folded(it.title) + "\n" + Curation.folded(it.artist) }.take(MOST)
        return Recording(
            perf?.optJSONObject("work")?.optString("id")?.takeIf(String::isNotEmpty),
            perf?.optJSONArray("attributes")?.let { a -> (0 until a.length()).any { a.optString(it) == "cover" } } ?: false,
            songs("samples material", true), songs("samples material", false), songs("remix", true), songs("remix", false),
        )
    }

    /**
     * The work's original and its covers, from the work (its performances, each said a cover or not, and its writers) and
     * its recordings (who made each, and when). The original, only where this one's a cover: whoever made most of the
     * performances that aren't covers, not `artist`; none such, the writer. The covers: other artists' covers, who made
     * most of them first, then the earliest; never `artist`'s or the original's.
     */
    fun work(workText: String, recordingsText: String, artist: String, cover: Boolean): Pair<Song?, List<Song>> {
        val w = JSONObject(workText)
        val title = w.optString("title")
        val rels = w.optJSONArray("relations")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
        val covered = HashMap<String, Boolean>()
        rels.filter { it.optString("type") == "performance" && it.optString("target-type") == "recording" }.forEach { r ->
            val id = r.optJSONObject("recording")?.optString("id").orEmpty()
            if (id.isNotEmpty()) covered[id] = r.optJSONArray("attributes")?.let { a -> (0 until a.length()).any { a.optString(it) == "cover" } } ?: false
        }
        val writers = rels.filter { it.optString("target-type") == "artist" && it.optString("type") in setOf("composer", "writer", "lyricist") }
            .mapNotNull { it.optJSONObject("artist")?.optString("name")?.takeIf(String::isNotBlank) }
        val all = JSONObject(recordingsText).optJSONArray("recordings")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
            .filter { !it.optBoolean("video") && covered.containsKey(it.optString("id")) }
        // each artist's recordings of it, the earliest date among them, as covers or not
        fun tally(covers: Boolean) = all.filter { covered[it.optString("id")] == covers }.map { credit(it) to it.optString("first-release-date").ifEmpty { "9999" } }
            .filter { it.first.isNotBlank() && !credited(it.first, artist) }
            .groupBy { Curation.folded(it.first) }.values
            .map { Triple(it.first().first, it.size, it.minOf { d -> d.second }) }
            .sortedWith(compareByDescending<Triple<String, Int, String>> { it.second }.thenBy { it.third })
        val original = if (!cover) null else (tally(false).firstOrNull()?.first ?: writers.firstOrNull { !credited(it, artist) })?.let { Song(title, it) }
        val covers = tally(true).map { it.first }.filter { original == null || !credited(it, original.artist) }.take(MOST).map { Song(title, it) }
        return original to covers
    }

    /** A recording's artists as one name ("Lady Gaga feat. Beyoncé"). */
    private fun credit(o: JSONObject): String {
        val a = o.optJSONArray("artist-credit") ?: return ""
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.joinToString("") { it.optString("name") + it.optString("joinphrase") }.trim()
    }

    /** Whether `artist` is among a recording's credited artists. */
    private fun credited(o: JSONObject, artist: String): Boolean {
        val a = o.optJSONArray("artist-credit") ?: return false
        val want = Curation.folded(artist)
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.any { Curation.folded(it.optString("name")) == want } || Curation.folded(credit(o)) == want
    }

    /** Whether a credit's first name (or the whole of it) is `artist`. */
    private fun credited(credit: String, artist: String): Boolean {
        val c = Curation.folded(credit); val a = Curation.folded(artist)
        return c == a || c.startsWith("$a feat") || c.startsWith("$a & ") || c.startsWith("$a, ")
    }

    /** A title without its brackets' words or a " - remastered" tail: "Hurt (Remastered 2011)" is "Hurt". */
    fun bare(title: String): String = title.replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "")
        .replace(Regex("""\s+-\s+.*(remaster|version|edit|mono|stereo|live).*$""", RegexOption.IGNORE_CASE), "").trim().ifEmpty { title.trim() }

    private fun quoted(s: String) = s.replace("\\", "").replace("\"", "")

    private fun songs(a: JSONArray?) = a?.let { (0 until it.length()).mapNotNull { i -> it.optJSONObject(i) } }.orEmpty()
        .map { Song(it.optString("title"), it.optString("artist")) }
    private fun json(s: List<Song>) = JSONArray().also { a -> s.forEach { a.put(JSONObject().put("title", it.title).put("artist", it.artist)) } }

    private fun read(text: String): Of {
        val o = JSONObject(text)
        return Of(o.optJSONObject("original")?.let { Song(it.optString("title"), it.optString("artist")) },
            songs(o.optJSONArray("covers")), songs(o.optJSONArray("samples")), songs(o.optJSONArray("sampledIn")),
            songs(o.optJSONArray("remixOf")), songs(o.optJSONArray("remixes")))
    }

    private fun write(l: Of): String = JSONObject().apply {
        l.original?.let { put("original", JSONObject().put("title", it.title).put("artist", it.artist)) }
        put("covers", json(l.covers)); put("samples", json(l.samples)); put("sampledIn", json(l.sampledIn))
        put("remixOf", json(l.remixOf)); put("remixes", json(l.remixes))
    }.toString()
}
