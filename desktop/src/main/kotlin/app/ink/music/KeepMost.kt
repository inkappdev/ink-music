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
 * your own hand kept it too. Off at first. How many, 25 to 200 (a listener's ask, 2026-11-27). Kept in keep-most.json.
 * A kept song nothing can name yet is still in the rotation: only one fallen out of the top is let go.
 */
object KeepMost {
    val counts = listOf(25, 50, 100, 200)
    private val _on = MutableStateFlow(false)
    val on: StateFlow<Boolean> = _on
    private val _n = MutableStateFlow(50)
    /** How many are kept. */
    val n: StateFlow<Int> = _n
    private val _charging = MutableStateFlow(false)
    /** Only while plugged in (a laptop's choice, Power; the phone's). */
    val charging: StateFlow<Boolean> = _charging
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
            _n.value = o.optInt("n", 50).takeIf { it in counts } ?: 50
            _charging.value = o.optBoolean("charging")
            forMost = o.optJSONArray("forMost")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
            day = o.optString("day")
        }
        // a minute in (the library and the server read), then each hour (each ten minutes while waiting to be plugged in): a
        // new day looked at
        scope.launch { delay(60_000); while (true) { look(); delay(if (_charging.value && day != today()) 600_000 else 3_600_000) } }
    }

    fun set(on: Boolean) {
        _on.value = on
        day = ""
        if (!on) { Offline.remove(forMost.filter { !KeepLoved.holds(it) }); forMost = emptySet() }
        save()
        if (on) scope.launch { look() }
    }

    /** How many and when; looked at again now (fewer: the ones past the new count let go). */
    fun choose(n: Int = _n.value, charging: Boolean = _charging.value) {
        _n.value = n; _charging.value = charging
        day = ""
        save()
        scope.launch { look() }
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
        if (_charging.value && !Power.charging()) return
        val heard = History.read().map { it.id to it.at }
        val n = _n.value
        val have = Offline.kept.value + Offline.keeping.value.keys
        // one kept already stays in the count whether or not it can be named now
        val top = ArrayList<String>(); val fetch = ArrayList<Song>()
        for (id in LoveKeeping.mostPlayed(heard, System.currentTimeMillis(), n = n * 8)) {
            if (top.size >= n) break
            if (id in have) { top += id; continue }
            val s = Library.song(id)?.takeIf(Offline::keepable) ?: continue
            top += id; fetch += s
        }
        if (top.isEmpty()) return
        val out = forMost - top.toSet()
        Offline.remove(out.filter { !KeepLoved.holds(it) })
        forMost = (forMost - out) + fetch.map { it.id }
        if (fetch.isNotEmpty()) Offline.keep(fetch, auto = true)
        day = today()
        save()
    }

    private fun today() = LocalDate.now().toString()

    private fun save() = runCatching {
        file.writeText(JSONObject().put("on", _on.value).put("n", _n.value).put("charging", _charging.value).put("forMost", JSONArray(forMost.toList())).put("day", day).toString())
    }
}
