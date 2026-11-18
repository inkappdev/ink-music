package app.ink.music

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The play build has no YouTube: no live sessions (the shelf never shows). */
object LiveSessions {
    data class One(val id: String, val artist: String, val what: String, val says: String, val at: Long)
    val all: StateFlow<List<One>> = MutableStateFlow(emptyList())
    fun start(ctx: Context) {}
}
