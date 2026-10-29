package app.ink.music

import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * Private listening (the phone's): while it's on, nothing you play is kept or told: not your history here (your year,
 * the orders, made for you), not your server, ListenBrainz, Last.fm or YouTube Music. Kept across restarts.
 */
object Private {
    private val file = File(Home.dir, "private-listening")
    val on = MutableStateFlow(file.exists())
    fun set(to: Boolean) { on.value = to; runCatching { if (to) file.writeText("") else file.delete() } }
}
