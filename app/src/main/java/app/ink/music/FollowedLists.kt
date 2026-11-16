package app.ink.music

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * YouTube Music playlists you follow (2026-11-16, Bronson, as the computer's): the playlist itself on lists (the list
 * and the grid), read fresh as it's opened, not a copy. Kept in files/followed-lists.json. Signed in to YouTube Music,
 * saved to your library there too (its own app's follow), so the computer and YouTube's app have it.
 */
object FollowedLists {
    val lists = MutableStateFlow<List<TubeAlbum>>(emptyList())
    private var file: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun load(c: Context) {
        val f = File(c.filesDir, "followed-lists.json").also { file = it }
        lists.value = runCatching {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                TubeAlbum(it.getString("url"), it.getString("title"), it.optString("artist"), it.optString("art").ifEmpty { null })
            }
        }.getOrDefault(emptyList())
    }

    private fun keep() = runCatching {
        file?.writeText(JSONArray(lists.value.map { JSONObject().put("url", it.url).put("title", it.title).put("artist", it.artist).put("art", it.art ?: "") }).toString())
    }

    /** A playlist's id in its link (…/playlist?list=PL…). */
    fun idOf(a: TubeAlbum) = a.url.substringAfter("list=").substringBefore("&")

    fun follows(a: TubeAlbum) = lists.value.any { idOf(it) == idOf(a) }

    /** Followed, or not any more: here at once, and on YouTube Music when signed in. */
    fun set(a: TubeAlbum, on: Boolean) {
        lists.value = if (on) listOf(a) + lists.value.filter { idOf(it) != idOf(a) } else lists.value.filter { idOf(it) != idOf(a) }
        keep()
        scope.launch { runCatching { YouTubeAccount.saveList(idOf(a), on) } }
    }
}
