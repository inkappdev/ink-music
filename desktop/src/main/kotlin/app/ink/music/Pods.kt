package app.ink.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * Podcasts on the computer (the phone's Pods; [Podcasts] is the shows, the feeds and where you stopped, kept in
 * Home.dir/podcasts and carried by sync): an episode as a [Song] ("e" and its id; its link the path, its show the
 * record, the show's cover), and what the pages show, kept as flows.
 */
object Pods {
    val following = MutableStateFlow<List<Podcasts.Show>>(emptyList())
    /** The newest episodes of the shows you follow, not yet heard through. */
    val fresh = MutableStateFlow<List<Song>>(emptyList())
    /** Where you stopped in each episode (ms; -1 heard through). */
    val places = MutableStateFlow<Map<String, Long>>(emptyMap())

    fun song(show: Podcasts.Show, e: Podcasts.Episode) = Song(
        id = e.id, path = e.url, title = e.title, artist = show.title, album = show.title, albumId = show.id,
        albumArtist = show.author.ifEmpty { show.title }, duration = e.length, disc = 1, track = 0,
        year = if (e.published > 0) java.util.Calendar.getInstance().apply { timeInMillis = e.published }.get(java.util.Calendar.YEAR) else 0,
        // the show's cover on every episode (covers are kept a record at a time: one episode's own would stand for all)
        format = "podcast", modified = e.published, art = show.art ?: e.art,
    )

    /** A show's face where a song's wanted (its cover, a tile): the show as a record. */
    fun face(show: Podcasts.Show) = Song(
        id = show.id, path = "", title = show.title, artist = show.author, album = show.title, albumId = show.id,
        albumArtist = show.author, duration = 0, disc = 1, track = 0, year = 0, format = "podcast", art = show.art,
    )

    fun song(id: String): Song? = Podcasts.episode(id)?.let { (s, e) -> song(s, e) }

    /** The show an episode is from. */
    fun showOf(s: Song): Podcasts.Show? = Podcasts.episode(s.id)?.first
        ?: Podcasts.following().firstOrNull { it.id == s.albumId }

    /** The show's page, for an episode's words (its show where a song's artist would be). */
    fun page(s: Song): Page? = showOf(s)?.let { Page.Show(it.feed, it) }

    /** The notes of an episode. */
    fun notes(id: String): String = Podcasts.episode(id)?.second?.notes.orEmpty()

    /** What's kept read again into the flows (no network): at start, after a sync, as what plays moves on. */
    suspend fun load() = withContext(Dispatchers.IO) {
        following.value = Podcasts.following()
        places.value = Podcasts.places().mapValues { it.value.first }
        fresh.value = Podcasts.fresh().map { (s, e) -> song(s, e) }
    }

    /** The shows you follow read again from their feeds (those not read in the last hour), then the flows. */
    suspend fun refresh() {
        load()
        withContext(Dispatchers.IO) { runCatching { Podcasts.refresh() } }
        load()
    }

    suspend fun follow(show: Podcasts.Show, on: Boolean) = withContext(Dispatchers.IO) {
        Podcasts.follow(show, on)
        if (on && Podcasts.kept(show.feed) == null) Podcasts.fetch(show.feed)
        load()
    }

    /**
     * Where the player is in an episode, kept (every 15 s playing, at a pause, on leaving it): the last 20 s is heard
     * through; under 5 s isn't kept (opened, not begun).
     */
    fun keep(s: Song, ms: Long, length: Long) {
        if (!s.episode) return
        val len = length.takeIf { it > 0 } ?: s.duration
        when {
            len > 0 && ms >= len - 20_000 -> Podcasts.setPlace(s.id, -1)
            ms >= 5_000 -> Podcasts.setPlace(s.id, ms)
            else -> return
        }
        places.value = Podcasts.places().mapValues { it.value.first }
    }

    /** "oct 21 · 22 min", "12 min left", "heard": what's said under an episode. */
    fun said(s: Song, place: Long): String {
        val date = if (s.modified > 0) java.text.SimpleDateFormat("MMM d", java.util.Locale.US).format(java.util.Date(s.modified)).lowercase() else ""
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
