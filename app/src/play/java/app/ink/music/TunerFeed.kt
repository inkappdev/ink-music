package app.ink.music

import android.content.Context

/** TUNER is YouTube's music videos: none in the play build. */
object TunerFeed {
    suspend fun start(c: Context): List<Tune> = emptyList()
    suspend fun fans(c: Context, of: List<String>, have: Set<String>, each: Int = 4): List<Tune> = emptyList()
    suspend fun related(c: Context, t: Tune): List<Tune> = emptyList()
    fun replayed(id: String): Long? = null
    suspend fun videoOf(t: Tune): String? = null
    fun stream(id: String, metered: Boolean): Pair<String, String?>? = null
    fun chorus(lines: List<Line>): Long? = null
}
