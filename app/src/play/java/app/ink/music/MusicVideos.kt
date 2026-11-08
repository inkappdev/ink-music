package app.ink.music

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The play build has no YouTube: no new videos (the shelf never shows). */
object MusicVideos {
    data class Clip(val id: String, val title: String, val artist: String, val at: Long)

    val clips: StateFlow<List<Clip>> = MutableStateFlow(emptyList())
    fun start(ctx: Context) {}
    fun stream(id: String): Pair<String, String?>? = null
}
