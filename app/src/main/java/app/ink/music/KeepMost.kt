package app.ink.music

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
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
 * Keep what you play most, the phone's (settings, offline; 2026-11-11, after SimpMusic's smart downloads): the 50 most
 * in your rotation ([LoveKeeping.mostPlayed]: lately counts more), kept on the phone, looked at again
 * once a day on wifi; one that's dropped out let go, unless a love or your own hand kept it too. Off at first (it's a
 * lot to fetch unasked). How many (25 to 200), on wifi or any network, any time or only charging: a listener's ask
 * (2026-11-27). Kept in files/keep-most.json: on, how many, where, when, the songs kept for it, the day last looked.
 *
 * A kept song nothing can name yet (the app just opened: the library not read, YouTube's songs not met this run) is
 * still in the rotation: only one that's fallen out of the top is let go (before, a morning's first look on wifi
 * found 5 it could name and let the other 45 go).
 */
object KeepMost {
    val counts = listOf(25, 50, 100, 200)
    private val _on = MutableStateFlow(false)
    val on: StateFlow<Boolean> = _on
    private val _n = MutableStateFlow(50)
    /** How many are kept. */
    val n: StateFlow<Int> = _n
    private val _anyNetwork = MutableStateFlow(false)
    /** Fetched on a phone's data too, not just wifi. */
    val anyNetwork: StateFlow<Boolean> = _anyNetwork
    private val _charging = MutableStateFlow(false)
    /** Only while charging. */
    val charging: StateFlow<Boolean> = _charging
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
            _n.value = o.optInt("n", 50).takeIf { it in counts } ?: 50
            _anyNetwork.value = o.optBoolean("anyNetwork")
            _charging.value = o.optBoolean("charging")
            forMost = o.optJSONArray("forMost")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
            day = o.optString("day")
        }
        scope.launch { look() }
        // back on wifi, a day not yet looked at: looked at then
        runCatching {
            c.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                    if (_on.value && day != today() && (_anyNetwork.value || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))) scope.launch { look() }
                }
            })
        }
        // plugged in, a day not yet looked at: looked at then
        runCatching {
            c.applicationContext.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(x: Context, i: Intent) { if (_on.value && day != today()) scope.launch { look() } }
            }, IntentFilter(Intent.ACTION_POWER_CONNECTED))
        }
        // the library read: looked at again, as it can name more now
        scope.launch { Library.songs.collect { if (it.isNotEmpty() && _on.value && day != today()) look() } }
    }

    /** How many, where and when; looked at again now (fewer: the ones past the new count let go). */
    fun choose(c: Context, n: Int = _n.value, anyNetwork: Boolean = _anyNetwork.value, charging: Boolean = _charging.value) {
        _n.value = n; _anyNetwork.value = anyNetwork; _charging.value = charging
        day = ""
        save(c)
        scope.launch { look() }
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

    /** Once a day, where and when allowed: the most played kept, the ones fallen out of the top let go. */
    @Synchronized private fun look() {
        val c = app ?: return
        if (!_on.value || day == today()) return
        if (!_anyNetwork.value && Quality.metered(c)) return
        if (_charging.value && !charging(c)) return
        val heard = History.read(c).map { it.id to it.at }
        val n = _n.value
        val have = Offline.kept.value + Offline.keeping.value.keys
        val tube = Offline.tubeSongs(c).associateBy { it.id }
        // the most played that can be kept (a file's own is here already), ranked from more than enough; one kept
        // already stays in the count whether or not it can be named this early
        val top = ArrayList<String>(); val fetch = ArrayList<Song>()
        for (id in LoveKeeping.mostPlayed(heard, System.currentTimeMillis(), n = n * 8)) {
            if (top.size >= n) break
            if (id in have) { top += id; continue }
            val s = (Library.song(id) ?: tube[id])?.takeIf(Offline::keepable) ?: continue
            top += id; fetch += s
        }
        if (top.isEmpty()) return
        val ids = top.toSet()
        val out = forMost - ids
        Offline.remove(c, out.filter { !KeepLoved.holds(it) })
        forMost = (forMost - out) + fetch.map { it.id }
        if (fetch.isNotEmpty()) Offline.keep(c, fetch, auto = true)
        day = today()
        save(c)
    }

    private fun charging(c: Context) = runCatching { c.getSystemService(BatteryManager::class.java)?.isCharging }.getOrNull() ?: true

    private fun today() = LocalDate.now().toString()

    private fun save(c: Context) = runCatching {
        file(c).writeText(JSONObject().put("on", _on.value).put("n", _n.value).put("anyNetwork", _anyNetwork.value)
            .put("charging", _charging.value).put("forMost", JSONArray(forMost.toList())).put("day", day).toString())
    }
}
