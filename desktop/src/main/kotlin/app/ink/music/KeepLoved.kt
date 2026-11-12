package app.ink.music

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
 * Keep what you love, the computer's (settings, kept offline; [LoveKeeping]'s rule, the phone's KeepLoved): a song loved
 * here, on the phone, as a star on the server or a like on YouTube Music is kept on this computer, and let go when
 * unloved if the love is what kept it. On or off (a computer's seldom on a metered network: on at first). Kept in
 * keep-loved.json: the choice, the loves seen, the songs kept for love, the ones waiting.
 */
object KeepLoved {
    private val _on = MutableStateFlow(true)
    val on: StateFlow<Boolean> = _on
    private val _offered = MutableStateFlow(false)
    /** Whether the loves from before have been offered (kept, or passed over). */
    val offered: StateFlow<Boolean> = _offered
    private var seen: Set<String>? = null
    private var forLove: Set<String> = emptySet()
    private var waiting: Set<String> = emptySet()
    private var started = false
    private val file = File(Home.dir, "keep-loved.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        if (started) return
        started = true
        runCatching {
            val o = JSONObject(file.readText())
            _on.value = o.optString("mode", LoveKeeping.ALWAYS) != LoveKeeping.OFF
            _offered.value = o.optBoolean("offered")
            seen = o.optJSONArray("seen")?.let(::set)
            forLove = o.optJSONArray("forLove")?.let(::set).orEmpty()
            waiting = o.optJSONArray("waiting")?.let(::set).orEmpty()
        }
        // a love (here, the phone's, the server's, YouTube's) as it comes; the library read, the waiting tried again
        scope.launch {
            combine(Loved.ids, Library.songs) { ids, _ -> ids }.collect { ids ->
                val t = LoveKeeping.turn(ids, seen)
                seen = t.seen
                if (_on.value && t.keep.isNotEmpty()) waiting = waiting + t.keep
                flush()
            }
        }
    }

    fun set(on: Boolean) {
        _on.value = on
        if (!on) waiting = emptySet()
        save()
        scope.launch { flush() }
    }

    /** The songs loved before that could be kept and aren't: offered once in settings. */
    fun before(): List<Song> {
        val kept = Offline.kept.value + Offline.keeping.value.keys
        return Loved.ids.value.filter { it !in kept }.mapNotNull(::song).filter(Offline::keepable)
    }

    /** The loves from before kept too (`keep`), or passed over: either way, not offered again. */
    fun answer(keep: Boolean) {
        if (keep) waiting = waiting + before().map { it.id }
        _offered.value = true
        save()
        if (keep) scope.launch { flush() }
    }

    /** Unloved: let go if the love is what kept it (and not waited for any more). */
    fun unloved(id: String) {
        seen = seen?.minus(id)
        waiting = waiting - id
        if (id in forLove) { forLove = forLove - id; Offline.remove(listOf(id)) }
        save()
    }

    /** Kept by hand: no longer the love's to let go. */
    fun byHand(ids: Collection<String>) {
        if (ids.none { it in forLove || it in waiting }) return
        forLove = forLove - ids.toSet(); waiting = waiting - ids.toSet()
        save()
    }

    /** The waiting kept; the ones not found yet wait on. */
    @Synchronized private fun flush() {
        if (!_on.value || waiting.isEmpty()) { save(); return }
        val songs = waiting.mapNotNull(::song)
        val keep = songs.filter(Offline::keepable)
        val have = Offline.kept.value + Offline.keeping.value.keys
        forLove = forLove + keep.filter { it.id !in have }.map { it.id }
        waiting = waiting - songs.map { it.id }.toSet()
        if (keep.isNotEmpty()) Offline.keep(keep, forLove = true)
        save()
    }

    private fun song(id: String): Song? = Library.song(id) ?: Loved.tubeSongs().firstOrNull { it.id == id }

    private fun set(a: JSONArray) = (0 until a.length()).map { a.getString(it) }.toSet()

    private fun save() = runCatching {
        // the loves seen only once they've been looked at (none: the first look still to come, which keeps nothing)
        file.writeText(JSONObject().put("mode", if (_on.value) LoveKeeping.ALWAYS else LoveKeeping.OFF).put("offered", _offered.value)
            .apply { seen?.let { put("seen", JSONArray(it.toList())) } }.put("forLove", JSONArray(forLove.toList()))
            .put("waiting", JSONArray(waiting.toList())).toString())
    }
}
