package app.ink.music

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The play build has no YouTube: no new videos (the shelf never shows). */
object MusicVideos {
    data class Clip(val id: String, val title: String, val artist: String, val at: Long)

    val clips: StateFlow<List<Clip>> = MutableStateFlow(emptyList())
    fun start(ctx: Context) {}
    suspend fun of(name: String, browseId: String = ""): List<Clip> = emptyList()
    fun stream(id: String, high: Int = 720): Pair<String, String?>? = null
}
