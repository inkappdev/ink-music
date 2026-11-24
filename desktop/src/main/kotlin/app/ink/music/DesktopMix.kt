package app.ink.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Automix for the desktop around ink music (2026-11-24, Bronson's INK OS: its wallpaper's progress line draws the mix
 * as this app's line does). Off unless asked for: only on Linux, and only while ~/.config/ink-music/desktop-mix exists,
 * so no one else's ink music writes a thing. Then, on each change (never each frame), ~/.cache/ink-music/mix.json says
 * the song playing, its length, its mix point (where the next comes in, ms) once planned, and during a handover its
 * start (the wall clock, ms), length and beats (first, period, ms into it). Written aside and moved in place; any
 * failure is left be, and playback never waits on it.
 */
object DesktopMix {
    private val home = System.getProperty("user.home")
    private val asked = File(home, ".config/ink-music/desktop-mix")
    private val out = File(home, ".cache/ink-music/mix.json")

    fun start() {
        if (!Home.linux || !asked.exists()) return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            combine(Player.now, Crossfade.point, Crossfade.mixing) { now, point, mix ->
                val o = JSONObject()
                now?.let { o.put("title", it.song.title).put("artist", it.song.artist).put("duration", it.duration) }
                if (now != null && point != null && point.first == now.song.id) o.put("point", point.second)
                mix?.let {
                    o.put("mix", JSONObject().put("start", it.start).put("length", it.length)
                        .put("beatFirst", it.beatFirst).put("beatPeriod", it.beatPeriod).put("from", it.from.title))
                }
                o.toString()
            }.distinctUntilChanged().collect { text ->
                runCatching {
                    out.parentFile.mkdirs()
                    val part = File(out.parentFile, "mix.json.part")
                    part.writeText(text)
                    part.renameTo(out)
                }
            }
        }
    }
}
