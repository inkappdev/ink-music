package app.ink.music

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A playlist brought in from Spotify kept in step with it (2026-11-23): signed in to Spotify, once a day the Spotify
 * playlist's songs are read again, and the ones added since are found (yours first, else YouTube Music's) and put at
 * the end of your copy, on YouTube Music or your server. Only added: a song taken off on Spotify stays in yours (it
 * may be one you added). Each link keeps the songs it has seen (artist / title, folded), in files/in-step.json.
 */
object InStep {
    /** One copy kept in step: the Spotify playlist, the copy ("youtube" or "server", its id), the songs seen. */
    data class Link(val spotify: String, val name: String, val where: String, val target: String, val known: Set<String>, val at: Long)

    val links = MutableStateFlow<List<Link>>(emptyList())
    private const val DAY = 24 * 3600 * 1000L
    @Volatile private var loaded = false
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun file(c: Context) = File(c.filesDir, "in-step.json")

    fun key(title: String, artist: String) = Search.fold(artist.substringBefore(", ") + " / " + title)

    fun load(c: Context) {
        if (loaded) return
        loaded = true
        links.value = runCatching {
            val a = JSONArray(file(c).readText())
            (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
                val k = o.getJSONArray("known")
                Link(o.getString("spotify"), o.optString("name"), o.getString("where"), o.getString("target"),
                    (0 until k.length()).map { k.getString(it) }.toSet(), o.optLong("at"))
            }
        }.getOrDefault(emptyList())
    }

    private fun save(c: Context) = runCatching {
        file(c).writeText(JSONArray(links.value.map { l ->
            JSONObject().put("spotify", l.spotify).put("name", l.name).put("where", l.where).put("target", l.target)
                .put("known", JSONArray(l.known.toList())).put("at", l.at)
        }).toString())
    }

    fun of(where: String, target: String): Link? = links.value.firstOrNull { it.where == where && it.target == target }

    /** A copy kept in step from now on, the Spotify playlist's songs now taken as already in it. */
    fun link(c: Context, spotify: String, name: String, where: String, target: String, songs: List<SpotifyLibrary.Said>) {
        load(c)
        links.value = links.value.filterNot { it.where == where && it.target == target } +
            Link(spotify, name, where, target, songs.map { key(it.title, it.artist) }.toSet(), System.currentTimeMillis())
        save(c)
    }

    fun unlink(c: Context, where: String, target: String) {
        load(c)
        links.value = links.value.filterNot { it.where == where && it.target == target }
        save(c)
    }

    /** Your Spotify playlist of the same name as a copy here, to keep it in step with (an import made before this). */
    suspend fun sameNamed(name: String): SpotifyLibrary.Theirs? {
        if (!Spotify.signedIn() || !Spotify.ready()) return null
        return Spotify.playlists()?.firstOrNull { it.name.trim().equals(name.trim(), ignoreCase = true) }
    }

    /** Links one already-made copy to its Spotify playlist: its songs read now, taken as already in the copy. */
    suspend fun linkExisting(c: Context, theirs: SpotifyLibrary.Theirs, where: String, target: String): Boolean {
        val songs = Spotify.songs(theirs.id) ?: return false
        link(c, theirs.id, theirs.name, where, target, songs)
        return true
    }

    /**
     * Every copy brought up to date: once a day each (or `now`), signed in to Spotify. Returns how many songs were added.
     */
    suspend fun catchUp(c: Context, now: Boolean = false): Int = withContext(Dispatchers.IO) {
        load(c)
        if (JustYours.on || links.value.isEmpty() || !Spotify.signedIn()) return@withContext 0
        val due = links.value.filter { now || System.currentTimeMillis() - it.at > DAY }
        if (due.isEmpty() || !Spotify.fresh(c)) return@withContext 0
        // one catch-up at a time (opening and a tap can both ask)
        if (!running.compareAndSet(false, true)) return@withContext 0
        var added = 0
        try {
            for (l in due) {
                val songs = Spotify.songs(l.spotify) ?: continue
                val fresh = songs.filter { key(it.title, it.artist) !in l.known }
                // songs found but not put in (offline, a sign-in lapsed): not taken as seen, so tried again next time
                // (2026-11-23, a review: they'd been marked seen and lost)
                val missed = HashSet<String>()
                if (fresh.isNotEmpty()) {
                    val found = Import.match(fresh)
                    found.forEachIndexed { i, s ->
                        s ?: return@forEachIndexed
                        val ok = when (l.where) {
                            "youtube" -> YouTubeAccount.addTo(TubeList(l.target, l.name, "", null), s)
                            "server" -> Server.addTo(l.target, s)
                            else -> false
                        }
                        if (ok) added++ else missed += key(fresh[i].title, fresh[i].artist)
                    }
                    android.util.Log.i("ink-spotify", "in step: ${l.name}, ${fresh.size} new on spotify, ${found.count { it != null }} found, ${missed.size} not put in")
                }
                // every other song now seen (found or not: one not found isn't looked for again each day)
                val seen = songs.map { s -> key(s.title, s.artist) }.filter { it !in missed }
                links.value = links.value.map { if (it.where == l.where && it.target == l.target) it.copy(known = it.known + seen, at = System.currentTimeMillis()) else it }
                save(c)
            }
        } finally { running.set(false) }
        added
    }
}
