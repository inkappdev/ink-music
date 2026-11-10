package app.ink.music

import android.content.Context

/** The play build reads no editors' lists (they're kept with YouTube's code): its genre pages keep INK's picks by tag. */
object GenreSongs {
    fun known(genre: String) = false
    fun now(c: Context, genre: String): List<Picks.Shown> = emptyList()
    suspend fun of(c: Context, genre: String): List<Picks.Shown> = emptyList()
    fun warm(c: Context, genres: List<String>) {}
}
