package app.ink.music

/**
 * An artist's own places (2026-11-12, Bronson: "artists socials like instagram on their pages"): Instagram, TikTok, X,
 * Bandcamp, SoundCloud and their website, as MusicBrainz keeps them (open data; its rules: a request a second at most,
 * the client named). Never someone else's: one of many of a name ("Wednesday" is five bands) is found by a record of
 * theirs, and a name alone only when one artist has it. Kept a month in `dir`; a miss, a week. Blocking.
 */
object Socials {
    data class Link(val word: String, val url: String)

    private val gate = Signal()
    private var last = 0L
    private const val DAY = 24L * 3600 * 1000
    private const val API = "https://musicbrainz.org/ws/2"

    /** The words in the order they're shown, each with what its link looks like. */
    private val KINDS = listOf(
        "instagram" to Regex("""^https?://(www\.)?instagram\.com/[^/?#]+"""),
        "tiktok" to Regex("""^https?://(www\.)?tiktok\.com/@"""),
        "x" to Regex("""^https?://(www\.)?(twitter|x)\.com/[^/?#]+"""),
        "bandcamp" to Regex("""^https?://[^/]+\.bandcamp\.com"""),
        "soundcloud" to Regex("""^https?://(www\.)?soundcloud\.com/[^/?#]+"""),
    )

    /** `name`'s places, read from what's kept or asked for; `records`, titles of theirs, tell one of a shared name. */
    fun of(dir: File, name: String, records: List<String> = emptyList()): List<Link> {
        val who = name.trim()
        if (who.isEmpty()) return emptyList()
        val f = File(dir, "socials-" + sha1(Curation.folded(who).encodeToByteArray()).hex())
        if (f.exists()) {
            val kept = runCatching { f.readText() }.getOrDefault("")
            val fresh = nowMs() - f.lastModified() < (if (kept.isEmpty()) 7 else 30) * DAY
            if (fresh) return read(kept)
        }
        val links = runCatching { find(who, records) }.getOrElse { return if (f.exists()) read(runCatching { f.readText() }.getOrDefault("")) else emptyList() }
        // a miss with no record to go by isn't kept: asked again with one, a shared name may yet be told apart
        if (links.isNotEmpty() || records.isNotEmpty()) runCatching { dir.mkdirs(); f.writeText(links.joinToString("\n") { "${it.word} ${it.url}" }) }
        return links
    }

    private fun read(kept: String): List<Link> = kept.lines().mapNotNull { l ->
        val word = l.substringBefore(' ', ""); val url = l.substringAfter(' ', "")
        if (word.isEmpty() || url.isEmpty()) null else Link(word, url)
    }

    private fun find(name: String, records: List<String>): List<Link> {
        val id = byRecord(name, records) ?: byName(name) ?: return emptyList()
        val rels = ask("$API/artist/$id?inc=url-rels&fmt=json").optJSONArray("relations") ?: return emptyList()
        val all = (0 until rels.length()).map { rels.getJSONObject(it) }.filter { !it.optBoolean("ended") }
        val urls = all.mapNotNull { it.optJSONObject("url")?.optString("resource")?.takeIf(String::isNotEmpty)?.let { u -> it.optString("type") to u } }
        val out = KINDS.mapNotNull { (word, looks) -> urls.firstOrNull { looks.containsMatchIn(it.second) }?.let { Link(word, it.second) } }
        val site = urls.firstOrNull { it.first == "official homepage" }?.let { Link("site", it.second) }
        return out + listOfNotNull(site)
    }

    /** The artist credited on a record of that title, under that name: the name's one even when several share it. */
    private fun byRecord(name: String, records: List<String>): String? {
        val want = Curation.folded(name)
        for (r in records.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(2)) {
            val q = "releasegroup:\"${quoted(r)}\" AND artist:\"${quoted(name)}\""
            val groups = ask("$API/release-group/?query=${urlEncode(q)}&limit=5&fmt=json").optJSONArray("release-groups") ?: continue
            for (i in 0 until groups.length()) {
                val g = groups.getJSONObject(i)
                if (g.optInt("score") < 90) continue
                val credits = g.optJSONArray("artist-credit") ?: continue
                for (j in 0 until credits.length()) {
                    val a = credits.getJSONObject(j).optJSONObject("artist") ?: continue
                    if (Curation.folded(a.optString("name")) == want) return a.optString("id").takeIf(String::isNotEmpty)
                }
            }
        }
        return null
    }

    /** The one artist of exactly that name; none when there are more (whose would be a guess). */
    private fun byName(name: String): String? {
        val want = Curation.folded(name)
        val found = ask("$API/artist/?query=${urlEncode("artist:\"${quoted(name)}\"")}&limit=10&fmt=json").optJSONArray("artists") ?: return null
        val same = (0 until found.length()).map { found.getJSONObject(it) }.filter { Curation.folded(it.optString("name")) == want }
        return same.singleOrNull()?.optString("id")?.takeIf(String::isNotEmpty)
    }

    private fun quoted(s: String) = s.replace("\\", "").replace("\"", "")

    /** One request, a second and a bit after the last (MusicBrainz's rule); a busy answer (503) waited out once. */
    private fun ask(url: String): JSONObject {
        repeat(2) { attempt ->
            gate.run {
                while (true) { val wait = last + 1_100 - nowMs(); if (wait <= 0) break; gate.await(wait) }
                last = nowMs()
            }
            val r = http(url, headers = mapOf("User-Agent" to AGENT, "Accept" to "application/json"), connectMs = 8_000, readMs = 15_000)
            if (r.code == 200) return JSONObject(r.text)
            if (r.code != 503 || attempt == 1) throw IOException("${r.code} $url")
        }
        throw IOException(url)
    }
}
