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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Keep what you love, the phone's (settings, offline: KEEP WHAT YOU LOVE; [LoveKeeping]'s rule): a song loved here, on
 * the computer, as a star on the server or a like on YouTube Music is kept on the phone (on wifi unless said: a love
 * on a phone's data waits for wifi), and let go when unloved if the love is what kept it. Kept in
 * files/keep-loved.json: the choice, the loves seen, the songs kept for love, the ones waiting.
 */
object KeepLoved {
    private val _mode = MutableStateFlow(LoveKeeping.WIFI)
    /** Off, on wifi, or always. */
    val mode: StateFlow<String> = _mode
    private val _offered = MutableStateFlow(false)
    /** Whether the loves from before have been offered (kept, or passed over). */
    val offered: StateFlow<Boolean> = _offered
    private var seen: Set<String>? = null
    private var forLove: Set<String> = emptySet()
    private var waiting: Set<String> = emptySet()
    private var app: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun file(c: Context) = File(c.filesDir, "keep-loved.json")

    fun start(c: Context) {
        if (app != null) return
        app = c.applicationContext
        runCatching {
            val o = JSONObject(file(c).readText())
            _mode.value = o.optString("mode", LoveKeeping.WIFI).takeIf { it in listOf(LoveKeeping.OFF, LoveKeeping.WIFI, LoveKeeping.ALWAYS) } ?: LoveKeeping.WIFI
            _offered.value = o.optBoolean("offered")
            seen = o.optJSONArray("seen")?.let(::set)
            forLove = o.optJSONArray("forLove")?.let(::set).orEmpty()
            waiting = o.optJSONArray("waiting")?.let(::set).orEmpty()
        }
        // a love (here, the computer's, the server's, YouTube's) as it comes; the library read, the waiting tried again
        scope.launch {
            combine(Loved.ids, Library.songs) { ids, _ -> ids }.collect { ids ->
                val t = LoveKeeping.turn(ids, seen)
                seen = t.seen
                if (_mode.value != LoveKeeping.OFF && t.keep.isNotEmpty()) waiting = waiting + t.keep
                flush()
            }
        }
        // back on wifi: the waiting kept
        runCatching {
            c.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) && waiting.isNotEmpty()) scope.launch { flush() }
                }
            })
        }
    }

    fun set(c: Context, mode: String) {
        _mode.value = mode
        if (mode == LoveKeeping.OFF) waiting = emptySet()
        save(c)
        scope.launch { flush() }
    }

    /** The songs loved before that could be kept and aren't: offered once in settings. */
    fun before(): List<Song> {
        val kept = Offline.kept.value + Offline.keeping.value.keys
        return Loved.ids.value.filter { it !in kept }.mapNotNull(::song).filter(Offline::keepable)
    }

    /** The loves from before kept too (`keep`), or passed over: either way, not offered again. */
    fun answer(c: Context, keep: Boolean) {
        if (keep) waiting = waiting + before().map { it.id }
        _offered.value = true
        save(c)
        if (keep) scope.launch { flush() }
    }

    /** Unloved: let go if the love is what kept it (and not waited for any more). */
    fun unloved(id: String) {
        val c = app ?: return
        seen = seen?.minus(id)
        waiting = waiting - id
        if (id in forLove) { forLove = forLove - id; Offline.remove(c, listOf(id)) }
        save(c)
    }

    /** Kept by hand: no longer the love's to let go. */
    fun byHand(ids: Collection<String>) {
        val c = app ?: return
        if (ids.none { it in forLove || it in waiting }) return
        forLove = forLove - ids.toSet(); waiting = waiting - ids.toSet()
        save(c)
    }

    /** The waiting kept, where the network allows (on wifi, unless always); the ones not found yet wait on. */
    @Synchronized private fun flush() {
        val c = app ?: return
        if (_mode.value == LoveKeeping.OFF || waiting.isEmpty()) { save(c); return }
        if (_mode.value == LoveKeeping.WIFI && Quality.metered(c)) { save(c); return }
        val songs = waiting.mapNotNull(::song)
        // a file's own is here already; a station's or one nothing knows can't be: let go of those
        val keep = songs.filter(Offline::keepable)
        val have = Offline.kept.value + Offline.keeping.value.keys
        forLove = forLove + keep.filter { it.id !in have }.map { it.id }
        waiting = waiting - songs.map { it.id }.toSet()
        if (keep.isNotEmpty()) Offline.keep(c, keep, forLove = true)
        save(c)
    }

    private fun song(id: String): Song? = Library.song(id) ?: Loved.tube.value.firstOrNull { it.id == id }
        ?: YouTubeAccount.liked.value.firstOrNull { it.id == id }

    private fun set(a: JSONArray) = (0 until a.length()).map { a.getString(it) }.toSet()

    private fun save(c: Context) = runCatching {
        // the loves seen only once they've been looked at (none: the first look still to come, which keeps nothing)
        file(c).writeText(JSONObject().put("mode", _mode.value).put("offered", _offered.value)
            .apply { seen?.let { put("seen", JSONArray(it.toList())) } }.put("forLove", JSONArray(forLove.toList()))
            .put("waiting", JSONArray(waiting.toList())).toString())
    }
}
