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
 * YouTube Music playlists you follow (2026-11-16, Bronson: "if you find a youtube playlist you like how do you add the
 * entire thing to be saved and viewable on the lists grid", "like a playlist follow feature?"): the playlist itself on
 * lists (list and grid), read fresh as it's opened, not a copy ("keep as your list" is the copy). Kept in
 * followed-lists.json. Signed in to YouTube Music, followed there too (saved to your library, as its own app's follow),
 * so the phone and YouTube's app have it.
 */
object FollowedLists {
    private val file = File(Home.dir, "followed-lists.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val lists = MutableStateFlow(read())

    private fun read(): List<TubeAlbum> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            TubeAlbum(it.getString("url"), it.getString("title"), it.optString("artist"), it.optString("art").ifEmpty { null })
        }
    }.getOrDefault(emptyList())

    private fun keep() = runCatching {
        Home.write(file, JSONArray(lists.value.map { JSONObject().put("url", it.url).put("title", it.title).put("artist", it.artist).put("art", it.art ?: "") }).toString())
    }

    private fun idOf(a: TubeAlbum) = TubeAccount.listId(a)

    fun follows(a: TubeAlbum): Boolean = lists.value.any { idOf(it) == idOf(a) }

    /** Followed, or not any more: here at once, and on YouTube Music when signed in. */
    fun set(a: TubeAlbum, on: Boolean) {
        lists.value = if (on) listOf(a) + lists.value.filter { idOf(it) != idOf(a) } else lists.value.filter { idOf(it) != idOf(a) }
        keep()
        val t = TubeAccount.tube() ?: return
        scope.launch {
            val ok = t.save(idOf(a), on)
            Log.d("followed list ${if (on) "saved to" else "taken from"} youtube music: ${if (ok) "yes" else "it said no"}")
        }
    }
}
