package app.ink.music

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Podcasts on the phone ([Podcasts] is the shows, the feeds and where you stopped): an episode as a [Song] the player
 * plays ("e" and its id; its show the record, its cover the show's), and what the screens show, kept as flows.
 */
object Pods {
    /** Both processes, before anything's read: the screens' and the player's. */
    fun start(c: Context) { if (Podcasts.dir == null) Podcasts.dir = File(c.filesDir, "podcasts") }

    val following = MutableStateFlow<List<Podcasts.Show>>(emptyList())
    /** The newest episodes of the shows you follow, not yet heard through. */
    val fresh = MutableStateFlow<List<Song>>(emptyList())
    /** Where you stopped in each episode (ms; -1 heard through), as the player last wrote it. */
    val places = MutableStateFlow<Map<String, Long>>(emptyMap())

    fun song(show: Podcasts.Show, e: Podcasts.Episode) = Song(
        id = e.id, uri = Uri.parse(e.url), title = e.title, artist = show.title, album = show.title, albumId = show.id,
        albumArtist = show.author.ifEmpty { show.title }, duration = e.length, disc = 1, track = 0,
        year = if (e.published > 0) java.util.Calendar.getInstance().apply { timeInMillis = e.published }.get(java.util.Calendar.YEAR) else 0,
        // the show's cover on every episode (covers are kept a record at a time: one episode's own would stand for all)
        art = show.art ?: e.art, format = "podcast", mime = e.mime, added = e.published,
    )

    /** A show's face where a song's wanted (its cover, a tile): the show as a record. */
    fun face(show: Podcasts.Show) = Song(
        id = show.id, uri = Uri.EMPTY, title = show.title, artist = show.author, album = show.title, albumId = show.id,
        albumArtist = show.author, duration = 0, disc = 1, track = 0, year = 0, art = show.art, format = "podcast",
    )

    fun song(id: String): Song? = Podcasts.episode(id)?.let { (s, e) -> song(s, e) }

    /** The show an episode or a show's face is from. */
    fun showOf(s: Song): Podcasts.Show? = Podcasts.episode(s.id)?.first
        ?: Podcasts.following().firstOrNull { it.id == s.albumId }

    /** The notes of an episode. */
    fun notes(id: String): String = Podcasts.episode(id)?.second?.notes.orEmpty()

    /** What's kept read again into the flows (no network). */
    suspend fun load(c: Context) = withContext(Dispatchers.IO) {
        start(c)
        following.value = Podcasts.following()
        places.value = Podcasts.places().mapValues { it.value.first }
        fresh.value = Podcasts.fresh().map { (s, e) -> song(s, e) }
    }

    /**
     * The shows you follow read again from their feeds (those not read in the last hour), then the flows. On wifi, each
     * show's newest episode of the week not yet heard is kept on the phone (Offline: the morning's commute with no
     * signal); an episode kept and heard through is let go.
     */
    suspend fun refresh(c: Context) {
        load(c)
        withContext(Dispatchers.IO) { runCatching { Podcasts.refresh() } }
        load(c)
        withContext(Dispatchers.IO) {
            Offline.refresh(c)
            val places = places.value
            Offline.kept.value.filter { it.startsWith("e") && (places[it] ?: 0L) < 0 }.takeIf { it.isNotEmpty() }?.let { Offline.remove(c, it) }
            if (Quality.metered(c)) return@withContext
            val week = System.currentTimeMillis() - 7 * 86_400_000L
            val newest = Podcasts.following().mapNotNull { s ->
                Podcasts.kept(s.feed)?.let { (show, list) -> list.firstOrNull()?.takeIf { it.published > week && (places[it.id] ?: 0L) >= 0 }?.let { song(show, it) } }
            }
            if (newest.isNotEmpty()) Offline.keep(c, newest)
        }
    }

    suspend fun follow(c: Context, show: Podcasts.Show, on: Boolean) = withContext(Dispatchers.IO) {
        start(c)
        Podcasts.follow(show, on)
        if (on && Podcasts.kept(show.feed) == null) Podcasts.fetch(show.feed)
        load(c)
    }

    /** "oct 21 · 22 min", "12 min left", "heard": what's said under an episode. */
    fun said(s: Song, place: Long): String {
        val date = if (s.added > 0) java.text.SimpleDateFormat("MMM d", java.util.Locale.US).format(java.util.Date(s.added)).lowercase() else ""
        val left = when {
            place < 0 -> "heard"
            place > 0 && s.duration > 0 -> "${((s.duration - place) / 60_000).coerceAtLeast(1)} min left"
            s.duration > 0 -> minutes(s.duration)
            else -> ""
        }
        return listOf(date, left).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    private fun minutes(ms: Long): String {
        val m = ms / 60_000
        return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
    }
}
