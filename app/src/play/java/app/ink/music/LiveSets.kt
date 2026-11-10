package app.ink.music

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The play build has no YouTube: no live DJ sets (the shelf never shows). */
object LiveSets {
    val clips: StateFlow<List<MusicVideos.Clip>> = MutableStateFlow(emptyList())
    fun start(ctx: Context) {}
    fun listen(c: MusicVideos.Clip, at: Long = 0L) {}
}
