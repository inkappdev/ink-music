package app.ink.music

import android.content.Context

/**
 * An artist page's "fans also like" heard by more ears (2026-11-24): YouTube Music's own list, those Last.fm's and
 * ListenBrainz's listeners also name first (Similar.alsoLike), and up to three only they name, yours first, each found
 * on YouTube Music under that very name with a photo (one that can't be opened isn't shown). The play build finds none
 * to add (no YouTube Music). Blocking; YouTube's list as it is on any failure.
 */
object FansAlsoLike {
    fun of(c: Context, artist: String, like: List<TubeArtist>): List<TubeArtist> = runCatching {
        // where ListenBrainz's answers are kept, and Last.fm's key (the player's process sets them for radio; this one may not have)
        if (Similar.dir == null) Similar.dir = java.io.File(c.cacheDir, "similar")
        if (LastFm.key.isBlank()) LastFm.key = BuildConfig.LASTFM_KEY
        val me = Curation.folded(artist)
        val kin = Similar.artists(artist, LastFm.key, 20).filter { Curation.folded(it.first) != me }
        if (kin.isEmpty()) return like
        val yours = Library.songs.value.flatMap { it.artist.split(", ") + it.albumArtist }.map { Curation.folded(it.trim()) }.toHashSet()
        val b = Similar.alsoLike(like.map { it.name }, kin, 6) { Curation.folded(it) in yours }
        val ids = like.map { it.id }.toHashSet()
        val added = if (!YouTubeMusic.available) emptyList() else b.extra.asSequence()
            .mapNotNull { n -> YouTubeMusic.artist(n)?.takeIf { Curation.folded(it.name) == Curation.folded(n) && it.face != null && it.id.isNotEmpty() && it.id !in ids } }
            .take(3).toList()
        val ordered = b.order.map { like[it] }
        ordered.take(b.agreed) + added + ordered.drop(b.agreed)
    }.getOrDefault(like)
}
