package app.ink.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The artists you follow: kept on the computer, and (signed in to YouTube Music) followed there too, so the phone's
 * and YouTube's own app agree; whom you follow there come in as well. Their mixes come first among mixes for you.
 */
object Follows {
    data class Who(val name: String, val id: String)

    val all = MutableStateFlow<List<Who>>(emptyList())
    private val file = File(Home.dir, "following.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        all.value = runCatching { JSONArray(file.readText()).let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { Who(it.getString("name"), it.optString("id")) } } }
            .getOrDefault(emptyList())
        scope.launch { TubeAccount.who.collect { if (it != null) fromYouTube() } }
    }

    fun follows(name: String) = all.value.any { it.name.equals(name, true) }

    /** Followed or not; on YouTube Music too when signed in (an artist's page there is its channel, "UC..."). */
    fun set(name: String, id: String, on: Boolean) {
        all.value = if (on) listOf(Who(name, id)) + all.value.filterNot { it.name.equals(name, true) } else all.value.filterNot { it.name.equals(name, true) }
        keep()
        val t = TubeAccount.tube() ?: return
        if (id.startsWith("UC")) scope.launch { runCatching { t.follow(id, on) } }
    }

    /** Whom you follow on YouTube Music, added (signed in). */
    private fun fromYouTube() {
        val t = TubeAccount.tube() ?: return
        runCatching {
            val there = t.items("FEmusic_library_corpus_artists", 500).filter { it.kind == InnerTube.Kind.ARTIST }.map { Who(it.title, it.id) }
            all.value = (all.value + there).distinctBy { it.name.lowercase() }
            keep()
        }
    }

    private fun keep() {
        val text = JSONArray(all.value.map { JSONObject().put("name", it.name).put("id", it.id) }).toString()
        scope.launch { runCatching { Home.write(file, text) } }
    }
}
