package app.ink.music

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A genre page's songs from its editors' lists (GenreLists: Spotify's and Apple Music's public ones; 2026-11-08, in
 * place of Last.fm's tags, which put WHYM on dance & electronic). Read when the page opens, kept in
 * files/genres/<genre>.json half a day, so a page opened again shows them at once.
 */
object GenreSongs {
    private const val KEEP = 12 * 3_600_000L
    private val kept = HashMap<String, Pair<Long, List<Picks.Shown>>>()

    fun known(genre: String) = GenreLists.known(genre)

    /** What's kept for this genre now (none: an empty list), without asking. */
    fun now(c: Context, genre: String): List<Picks.Shown> = synchronized(kept) {
        kept[genre.lowercase()]?.second ?: read(c, genre)?.second.orEmpty()
    }

    /**
     * The genre's songs: kept (an old one too, asked again behind it), else read from its lists, all at once (blocking
     * a second or two; on IO). 2026-11-09: a list at a time, and a kept one past half a day waited for the new.
     */
    suspend fun of(c: Context, genre: String): List<Picks.Shown> = withContext(Dispatchers.IO) {
        val g = genre.lowercase()
        val have = synchronized(kept) { (kept[g] ?: read(c, g)?.also { kept[g] = it }) }
        if (have != null && have.second.isNotEmpty()) {
            if (System.currentTimeMillis() - have.first >= KEEP) Thread { runCatching { fetch(c, g) } }.apply { isDaemon = true }.start()
            return@withContext have.second
        }
        fetch(c, g)
    }

    /** The genres opened most, read ahead a while after the app opens, so their pages open with their songs there. */
    fun warm(c: Context, genres: List<String>) {
        val app = c.applicationContext
        Thread({
            runCatching {
                Thread.sleep(25_000)
                genres.filter { known(it) }.forEach { g ->
                    val k = g.lowercase()
                    val have = synchronized(kept) { kept[k] ?: read(app, k) }
                    if (have == null || System.currentTimeMillis() - have.first >= KEEP) runCatching { fetch(app, k) }
                }
            }
        }, "genres-warm").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    private val lists = java.util.concurrent.Executors.newFixedThreadPool(6) { r -> Thread(r, "genre-lists").apply { isDaemon = true } }
    private val fetching = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun fetch(c: Context, g: String): List<Picks.Shown> {
        if (!fetching.add(g)) return now(c, g)
        try {
            return fetched(c, g)
        } finally { fetching.remove(g) }
    }

    private fun fetched(c: Context, g: String): List<Picks.Shown> {
        val now = System.currentTimeMillis()
        val songs = GenreLists.songs(g, now, { android.util.Log.d("ink-picks", it) }) { all ->
            all.map { f -> lists.submit(java.util.concurrent.Callable { f() }) }.map { runCatching { it.get() }.getOrDefault(emptyList()) }
        }.map { Picks.Shown(it, it.art, listOf(it.from)) }
        if (songs.isEmpty()) return now(c, g)
        synchronized(kept) { kept[g] = now to songs }
        runCatching {
            file(c, g).also { it.parentFile?.mkdirs() }.writeText(org.json.JSONObject().put("at", now).put("songs", org.json.JSONArray(songs.map { s ->
                org.json.JSONObject().put("title", s.pick.title).put("artist", s.pick.artist).put("from", s.pick.from).put("art", s.art ?: "")
            })).toString())
        }
        return songs
    }

    private fun file(c: Context, g: String) = File(File(c.filesDir, "genres"), g.replace(Regex("[^a-z0-9]+"), "-") + ".json")

    private fun read(c: Context, g: String): Pair<Long, List<Picks.Shown>>? = runCatching {
        val o = org.json.JSONObject(file(c, g.lowercase()).readText())
        val a = o.getJSONArray("songs")
        o.getLong("at") to (0 until a.length()).map { a.getJSONObject(it) }.map { s ->
            val art = s.optString("art").ifEmpty { null }
            Picks.Shown(Curation.Pick(false, s.getString("title"), s.getString("artist"), s.getString("from"), "", "", o.getLong("at"), art), art, listOf(s.getString("from")))
        }
    }.getOrNull()
}
