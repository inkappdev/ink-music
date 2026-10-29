package app.ink.music

import android.content.Context
import java.io.File

/**
 * Private listening (Bronson, 2026-10-29): while it's on, nothing you play is kept or told: not your history here (so
 * not your year, the orders by what you play, made for you), not your server, ListenBrainz, Last.fm or YouTube Music.
 * A file both processes read, so the player sees a switch the screens made at once.
 */
object Private {
    private fun file(c: Context) = File(c.filesDir, "private-listening")
    fun on(c: Context) = file(c).exists()
    fun set(c: Context, on: Boolean) { runCatching { if (on) file(c).createNewFile() else file(c).delete() } }
}
