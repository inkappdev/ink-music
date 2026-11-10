package app.ink.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * INK's picks by genre ([Genres], the phone's GenrePicks): the picked artists' Last.fm tags, read a few a second the
 * first time INK or a genre's page opens, kept in the cache (genre-tags.json). `tagged` moves on as they come.
 */
object GenrePicks {
    val tagged = MutableStateFlow(0)
    private val one = Mutex()
    @Volatile private var loaded = false
    private val file get() = File(Home.cache, "genre-tags.json")

    /** Every pick of INK's (the critics' records and songs, electronic's), each once. */
    fun all(): List<Picks.Shown> =
        (Picks.records.value + Picks.songs.value + Picks.electronic.value.map { Picks.Shown(it, it.art, listOf(it.from)) }).distinctBy { Curation.key(it.pick) }

    /**
     * The picks' artists tagged, and `more` besides (the songs page's, filtered by genre: 2026-11-10), the picks' first;
     * a library's worth the first time is a few minutes in the background, the songs page's genres filling as they come.
     */
    suspend fun tag(more: Collection<String> = emptyList()) = withContext(Dispatchers.IO) {
        // a second ask for the picks alone is the first's; one with more waits its turn
        if (one.isLocked && more.isEmpty()) return@withContext
        one.withLock {
            if (!loaded) { runCatching { Genres.load(file.readText()) }; loaded = true; tagged.value++ }
            if (LastFm.key.isBlank()) return@withLock
            val now = System.currentTimeMillis()
            val missing = Genres.missing(all().map { it.pick.artist } + more, now).take(if (more.isEmpty()) 300 else 2_000)
            missing.forEachIndexed { i, a ->
                Genres.read(a, LastFm.key, now)
                if (i % 15 == 14) { keep(); tagged.value++ }
                delay(220)
            }
            if (missing.isNotEmpty()) { keep(); tagged.value++ }
        }
    }

    private fun keep() = runCatching { Home.write(file, Genres.save()) }
}
