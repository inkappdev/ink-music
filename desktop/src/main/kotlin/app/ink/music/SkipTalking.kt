package app.ink.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/**
 * A YouTube video's talking skipped, the computer's (settings, sound: SKIP THE TALKING; the phone's in its player):
 * where SponsorBlock's listeners have marked a music video's talking, skits or credits ([SponsorBlock]), the song jumps
 * past as it reaches them, the place watched as it ticks. On unless said (skip-talking-off).
 */
object SkipTalking {
    private val off = File(Home.dir, "skip-talking-off")
    private val _on = MutableStateFlow(!off.exists())
    val on: StateFlow<Boolean> = _on
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // the song playing's marks (its id with them, so a late answer isn't another song's)
    @Volatile private var marks: Pair<String, List<LongRange>>? = null

    fun set(on: Boolean) { _on.value = on; runCatching { if (on) off.delete() else off.writeText("") } }

    fun start() {
        // a new song: its marks asked for, if it's a YouTube video's
        scope.launch {
            Player.now.map { it?.song }.distinctUntilChanged { a, b -> a?.id == b?.id }.collect { s ->
                marks = null
                if (s != null && s.tube && _on.value) {
                    val got = SponsorBlock.notMusic(s.id.removePrefix("y"))
                    if (got.isNotEmpty() && Player.now.value?.song?.id == s.id) { marks = s.id to got; Log.d("skip talking ${s.id}: ${got.size} marks") }
                }
            }
        }
        // as it plays: inside a mark, on past it (once each: a seek back into one by hand is let be)
        scope.launch {
            var jumped = HashSet<LongRange>()
            var of = ""
            Player.position.collect { at ->
                val (id, all) = marks ?: return@collect
                if (id != of) { of = id; jumped = HashSet() }
                if (!_on.value || Player.now.value?.song?.id != id) return@collect
                all.firstOrNull { at in it && it !in jumped }?.let { m -> jumped += m; Player.seek(m.last) }
            }
        }
    }
}
