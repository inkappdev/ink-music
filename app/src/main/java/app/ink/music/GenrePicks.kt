package app.ink.music

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * INK's picks by genre ([Genres]): the picked artists' Last.fm tags, read a few a second (Last.fm's own pace) the
 * first time a genre's page or INK opens, kept in files/genre-tags.json. `tagged` moves on as they come, so a genre's
 * page fills in while it's open.
 */
object GenrePicks {
    val tagged = MutableStateFlow(0)
    private val one = Mutex()
    @Volatile private var loaded = false

    private fun file(c: Context) = File(c.filesDir, "genre-tags.json")

    /** These artists' tags read where they're missing, behind (ink mapped's artists), kept as they come. */
    fun tagIn(c: Context, artists: Collection<String>) {
        val app = c.applicationContext
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { runCatching { tag(app, artists) } }
    }

    /** Every pick of INK's (the critics' records and songs, electronic's), each once. */
    fun all(): List<Picks.Shown> =
        (Picks.records.value + Picks.songs.value + Picks.electronic.value.map { Picks.Shown(it, it.art, listOf(it.from)) }).distinctBy { Curation.key(it.pick) }

    /**
     * The picks' artists' tags read where they're missing, and `more` besides (the songs tab's, filtered by genre, as the
     * computer's: 2026-11-10), the picks' first; kept as they come, the songs tab's genres filling as they do.
     */
    suspend fun tag(c: Context, more: Collection<String> = emptyList()) = withContext(Dispatchers.IO) {
        // a second ask for the picks alone is the first's; one with more waits its turn
        if (one.isLocked && more.isEmpty()) return@withContext
        one.withLock {
            if (!loaded) { runCatching { Genres.load(file(c).readText()) }; loaded = true; tagged.value++ }
            if (LastFm.key.isBlank()) return@withLock
            val now = System.currentTimeMillis()
            val missing = Genres.missing(all().map { it.pick.artist } + more, now).take(if (more.isEmpty()) 300 else 2_000)
            missing.forEachIndexed { i, a ->
                Genres.read(a, LastFm.key, now)
                if (i % 15 == 14) { keep(c); tagged.value++ }
                delay(220)
            }
            if (missing.isNotEmpty()) { keep(c); tagged.value++ }
        }
    }

    private fun keep(c: Context) = runCatching { file(c).writeText(Genres.save()) }
}
