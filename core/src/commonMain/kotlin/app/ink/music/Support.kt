package app.ink.music

import kotlinx.coroutines.sync.withLock

/**
 * Where to support an artist (2026-11-18, Bronson: "where to support them"): their Bandcamp and their own site, from
 * MusicBrainz (open, no key; one ask a second, as it asks), as plain links (no referral, his rule since 2026-11-10).
 * The artist by name, the one called just that ("Wednesday", not "Wednesday 13", which MusicBrainz finds first); where
 * several are, the one with a song of theirs we know. None found: no links (smaller acts often aren't there yet).
 */
object Support {
    data class Links(val bandcamp: String?, val site: String?) {
        val any get() = bandcamp != null || site != null
    }

    private const val AGENT = "ink-music/2 ( https://inkhome.app )"
    private val gate = kotlinx.coroutines.sync.Mutex()
    private var last = 0L

    /** MusicBrainz's answer to `url`, a second after the last ask at least (call it off the main thread). */
    private suspend fun ask(url: String): JSONObject? {
        gate.withLock {
            val wait = 1_100 - (nowMs() - last)
            if (wait > 0) kotlinx.coroutines.delay(wait)
            last = nowMs()
        }
        val r = runCatching { http(url, headers = mapOf("User-Agent" to AGENT, "Accept" to "application/json")) }.getOrNull() ?: return null
        return if (r.code == 200) runCatching { JSONObject(r.text) }.getOrNull() else null
    }

    /** `name`'s links, `songs` (titles of theirs) to tell apart artists of one name; null if none found (blocking). */
    suspend fun of(name: String, songs: List<String> = emptyList()): Links? {
        val q = urlEncode("artist:\"${name.replace("\"", "")}\"")
        val found = ask("https://musicbrainz.org/ws/2/artist?query=$q&fmt=json&limit=8")?.optJSONArray("artists") ?: return null
        val exact = (0 until found.length()).mapNotNull { found.optJSONObject(it) }.filter { it.optString("name").equals(name, ignoreCase = true) }
        val id = when {
            exact.isEmpty() -> return null
            exact.size == 1 || songs.isEmpty() -> exact.first().optString("id")
            // several of one name: the one with a song of theirs we know
            else -> {
                var hit: String? = null
                for (title in songs.take(3)) {
                    val rq = urlEncode("recording:\"${title.replace("\"", "")}\" AND artist:\"${name.replace("\"", "")}\"")
                    val recs = ask("https://musicbrainz.org/ws/2/recording?query=$rq&fmt=json&limit=5")?.optJSONArray("recordings") ?: continue
                    hit = (0 until recs.length()).mapNotNull { recs.optJSONObject(it)?.optJSONArray("artist-credit")?.optJSONObject(0)?.optJSONObject("artist")?.optString("id") }
                        .firstOrNull { a -> exact.any { it.optString("id") == a } }
                    if (hit != null) break
                }
                hit ?: exact.first().optString("id")
            }
        }
        val rels = ask("https://musicbrainz.org/ws/2/artist/$id?inc=url-rels&fmt=json")?.optJSONArray("relations") ?: return Links(null, null)
        val urls = (0 until rels.length()).mapNotNull { rels.optJSONObject(it) }
            .filter { !it.optBoolean("ended") }.map { it.optString("type") to (it.optJSONObject("url")?.optString("resource").orEmpty()) }
        val bandcamp = urls.firstOrNull { it.first == "bandcamp" }?.second?.takeIf { it.startsWith("http") }
        // their own site: not a label's page of them where one of their own is listed too
        val sites = urls.filter { it.first == "official homepage" }.map { it.second }.filter { it.startsWith("http") }
        val site = sites.firstOrNull { s -> name.lowercase().filter { it.isLetterOrDigit() }.let { n -> n.length >= 3 && s.lowercase().contains(n) } } ?: sites.firstOrNull()
        return Links(bandcamp, site)
    }
}
