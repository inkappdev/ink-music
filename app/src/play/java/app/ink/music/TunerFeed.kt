package app.ink.music

import android.content.Context

/** TUNER is YouTube's music videos: none in the play build. */
object TunerFeed {
    suspend fun of(c: Context): List<Tune> = emptyList()
    fun replayed(id: String): Long? = null
    fun stream(id: String, metered: Boolean): Pair<String, String?>? = null
    fun chorus(lines: List<Line>): Long? = null
}
