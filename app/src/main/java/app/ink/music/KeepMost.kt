package app.ink.music

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * Keep what you play most, the phone's (settings, offline; 2026-11-11, after SimpMusic's smart downloads): your 50 most
 * played of the last three months ([LoveKeeping.mostPlayed], from what you've heard), kept on the phone, looked at again
 * once a day on wifi; one that's dropped out let go, unless a love or your own hand kept it too. Off at first (it's a
 * lot to fetch unasked). Kept in files/keep-most.json: on, the songs kept for it, the day last looked.
 */
object KeepMost {
    const val N = 50
    private val _on = MutableStateFlow(false)
    val on: StateFlow<Boolean> = _on
    private var forMost: Set<String> = emptySet()
    private var day = ""
    private var app: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun file(c: Context) = File(c.filesDir, "keep-most.json")

    fun start(c: Context) {
        if (app != null) return
        app = c.applicationContext
        runCatching {
            val o = JSONObject(file(c).readText())
            _on.value = o.optBoolean("on")
            forMost = o.optJSONArray("forMost")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
            day = o.optString("day")
        }
        scope.launch { look() }
        // back on wifi, a day not yet looked at: looked at then
        runCatching {
            c.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                    if (_on.value && day != today() && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) scope.launch { look() }
                }
            })
        }
    }

    fun set(c: Context, on: Boolean) {
        _on.value = on
        day = ""
        if (!on) { Offline.remove(c, forMost.filter { !KeepLoved.holds(it) }); forMost = emptySet() }
        save(c)
        if (on) scope.launch { look() }
    }

    /** Whether it's kept for being played most (a love let go of keeps it then). */
    fun holds(id: String) = id in forMost

    /** Kept by hand: no longer this one's to let go. */
    fun byHand(ids: Collection<String>) {
        val c = app ?: return
        if (ids.none { it in forMost }) return
        forMost = forMost - ids.toSet()
        save(c)
    }

    /** Once a day, on wifi: the most played kept, the ones dropped out let go. */
    @Synchronized private fun look() {
        val c = app ?: return
        if (!_on.value || day == today() || Quality.metered(c)) return
        val heard = History.read(c).map { it.id to it.at }
        // the most played that can be kept (a file's own is here already), ranked from more than enough
        val top = LoveKeeping.mostPlayed(heard, System.currentTimeMillis(), n = N * 8).mapNotNull(Library::song).filter(Offline::keepable).take(N)
        if (top.isEmpty()) return
        val have = Offline.kept.value + Offline.keeping.value.keys
        val ids = top.map { it.id }.toSet()
        val out = forMost - ids
        Offline.remove(c, out.filter { !KeepLoved.holds(it) })
        forMost = (forMost - out) + top.filter { it.id !in have }.map { it.id }
        Offline.keep(c, top, auto = true)
        day = today()
        save(c)
    }

    private fun today() = LocalDate.now().toString()

    private fun save(c: Context) = runCatching {
        file(c).writeText(JSONObject().put("on", _on.value).put("forMost", JSONArray(forMost.toList())).put("day", day).toString())
    }
}
