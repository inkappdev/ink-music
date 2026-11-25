package app.ink.music

/**
 * An artist page's "fans also like" heard by more ears (the phone's, 2026-11-24): YouTube Music's own list, those
 * Last.fm's and ListenBrainz's listeners also name first (Similar.alsoLike), and up to three only they name, yours first,
 * each found on YouTube Music under that very name with a photo (one that can't be opened isn't shown). Local files
 * only (Local), none added. Blocking; YouTube's list as it is on any failure.
 */
object FansAlsoLike {
    fun of(artist: String, like: List<TubeArtist>): List<TubeArtist> = runCatching {
        val me = Curation.folded(artist)
        val kin = Similar.artists(artist, LastFm.key, 20).filter { Curation.folded(it.first) != me }
        if (kin.isEmpty()) return like
        val yours = Library.songs.value.flatMap { it.artist.split(", ") + it.albumArtist }.map { Curation.folded(it.trim()) }.toHashSet()
        val b = Similar.alsoLike(like.map { it.name }, kin, 6) { Curation.folded(it) in yours }
        val ids = like.map { it.id }.toHashSet()
        val added = if (Local.on) emptyList() else b.extra.asSequence()
            .mapNotNull { n -> Artists.find(n)?.takeIf { Curation.folded(it.name) == Curation.folded(n) && it.face != null && it.id.isNotEmpty() && it.id !in ids } }
            .take(3).toList()
        val ordered = b.order.map { like[it] }
        ordered.take(b.agreed) + added + ordered.drop(b.agreed)
    }.getOrDefault(like)
}
