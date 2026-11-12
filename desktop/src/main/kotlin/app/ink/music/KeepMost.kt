package app.ink.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * Keep what you play most, the computer's (settings, kept offline; the phone's KeepMost): the 50 most in your rotation (lately
 * counts more) kept on this computer, looked at again once a day; one that's dropped out let go, unless a love or
 * your own hand kept it too. Off at first. Kept in keep-most.json.
 */
object KeepMost {
    const val N = 50
    private val _on = MutableStateFlow(false)
    val on: StateFlow<Boolean> = _on
    private var forMost: Set<String> = emptySet()
    private var day = ""
    private var started = false
    private val file = File(Home.dir, "keep-most.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        if (started) return
        started = true
        runCatching {
            val o = JSONObject(file.readText())
            _on.value = o.optBoolean("on")
            forMost = o.optJSONArray("forMost")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
            day = o.optString("day")
        }
        // a minute in (the library and the server read), then each hour: a new day looked at
        scope.launch { delay(60_000); while (true) { look(); delay(3_600_000) } }
    }

    fun set(on: Boolean) {
        _on.value = on
        day = ""
        if (!on) { Offline.remove(forMost.filter { !KeepLoved.holds(it) }); forMost = emptySet() }
        save()
        if (on) scope.launch { look() }
    }

    /** Whether it's kept for being played most (a love let go of keeps it then). */
    fun holds(id: String) = id in forMost

    /** Kept by hand: no longer this one's to let go. */
    fun byHand(ids: Collection<String>) {
        if (ids.none { it in forMost }) return
        forMost = forMost - ids.toSet()
        save()
    }

    /** Once a day: the most played kept, the ones dropped out let go. */
    @Synchronized private fun look() {
        if (!_on.value || day == today()) return
        val heard = History.read().map { it.id to it.at }
        val top = LoveKeeping.mostPlayed(heard, System.currentTimeMillis(), n = N * 8).mapNotNull(Library::song).filter(Offline::keepable).take(N)
        if (top.isEmpty()) return
        val have = Offline.kept.value + Offline.keeping.value.keys
        val ids = top.map { it.id }.toSet()
        val out = forMost - ids
        Offline.remove(out.filter { !KeepLoved.holds(it) })
        forMost = (forMost - out) + top.filter { it.id !in have }.map { it.id }
        Offline.keep(top, auto = true)
        day = today()
        save()
    }

    private fun today() = LocalDate.now().toString()

    private fun save() = runCatching {
        file.writeText(JSONObject().put("on", _on.value).put("forMost", JSONArray(forMost.toList())).put("day", day).toString())
    }
}
