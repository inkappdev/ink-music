package app.ink.music

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * TUNER's sources (2026-11-18), each a pool of clips TunerMix chooses from as you go: the editors' new-music lists
 * nearest your taste and your artists' new videos at the start; artists your artists' fans also like (theirs found as
 * the page opens, and for whatever you like); YouTube's up next for a clip you like. Nothing shown in the last month,
 * nothing of an artist said not for you, blocked or on the AI list.
 */
object TunerFeed {
    private fun fine(c: Context, id: String, title: String, artist: String): Boolean {
        val tuned = Fresh.tuned ?: return false
        return !tuned.shown(id) && Curation.folded(artist) !in Fresh.turnedDown(c) && !Blocked.hides(title, artist) && !Fresh.taste.value.refused(artist)
    }

    private fun clean(xs: List<Tune>): List<Tune> {
        val ai = Fresh.ai ?: return xs
        return xs.filterNot { ai.made(it.artist, InnerTube()::onWikipedia) }
    }

    /** The first pool: the editors' lists' songs and your artists' new videos. */
    suspend fun start(c: Context): List<Tune> = withContext(Dispatchers.IO) {
        val taste = Fresh.taste.value
        val editors = EditorsNew.songs(YouTubeMusic.editorsLists(), taste::of).filter { fine(c, it.videoId, it.title, it.artists.joinToString(" & ")) }
            .map { t -> val who = t.artists.joinToString(" & ")
                Tune(t.videoId, plain(t.title, t.artists), who, YouTubeMusic.songOf(t), t.seconds, "editors", "editors", still = t.albumId != null) }
        val yours = MusicVideos.clips.value.filter { fine(c, it.id, it.title, it.artist) }.map { Tune(it.id, it.title, it.artist, null, 0, "yours", it.artist) }
        clean((editors + yours).distinctBy { it.id })
    }

    /**
     * Artists whose fans also like those `of` (their YouTube Music pages), new to you (not near you yet), a video or
     * two each, their newest; found through `of`'s artist (the tag a like of them brings forward).
     */
    suspend fun fans(c: Context, of: List<String>, have: Set<String>, each: Int = 4): List<Tune> = withContext(Dispatchers.IO) {
        val taste = Fresh.taste.value
        coroutineScope {
            of.map { name ->
                async {
                    val like = withTimeoutOrNull(6_000) { YouTubeAccount.artist(name, "").firstOrNull { it != null } }?.like.orEmpty()
                        .map { it.name }.filter { taste.of(it) < .3 && Curation.folded(it) !in have }.take(each)
                    like.map { n -> async { runCatching { MusicVideos.of(n) }.getOrDefault(emptyList()).take(2).map { Tune(it.id, it.title, it.artist, null, 0, "fans", name) } } }
                        .awaitAll().flatten()
                }
            }.awaitAll().flatten()
        }.filter { fine(c, it.id, it.title, it.artist) }.distinctBy { it.id }.let(::clean)
    }

    /** YouTube's up next for a clip (its song's radio, as videos), found through its artist. */
    suspend fun related(c: Context, t: Tune): List<Tune> = withContext(Dispatchers.IO) {
        runCatching { InnerTube().queue("RDAMVM${t.id}", 16, t.id, audioOnly = false) }.getOrDefault(emptyList()).drop(1)
            .map { r -> Tune(r.videoId, plain(r.title, r.artists), r.artists.joinToString(" & "), YouTubeMusic.songOf(r), r.seconds, "related", t.artist, still = r.albumId != null) }
            .filter { fine(c, it.id, it.title, it.artist) }.let(::clean)
    }

    /** A song YouTube has only as its cover: its own music video, among its artist's (blocking; null: none of it). */
    suspend fun videoOf(t: Tune): String? {
        val who = t.artist.substringBefore(" & ")
        return MusicVideos.of(who).firstOrNull { Pipe.same(it.title, who, t.title, who) }?.id
    }

    /**
     * A clip's streams, kept small so it starts at once (2026-11-18, Bronson: capped if that helps): 480p on wifi, 360p
     * on someone's data, YouTube's smallest sound (blocking).
     */
    fun stream(id: String, metered: Boolean): Pair<String, String?>? =
        Pipe.streamInfo(id)?.let { Pipe.video(it, if (metered) 360 else 480, smallSound = true) }

    private val DRESS = Regex("""\s*[(\[][^)\]]*\b(official|video|audio|visuali[sz]er|lyrics?|mv|clip|live)\b[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
    private val APART = Regex("""\s+[-–—|/]\s+""")

    /**
     * A video's own title as its song's: "Fontaines D.C. - Marianne (Official Video)", "Friends - Veronica Everheart",
     * "Newport — everything was fickle (feat. Arden Jones)": the artist's part and the video's dress taken off.
     */
    fun plain(title: String, artists: List<String>): String {
        val names = artists.map { Curation.folded(it) }
        val parts = title.replace(DRESS, "").split(APART).map { it.trim() }.filter { it.isNotEmpty() }
        val kept = parts.filterNot { p -> names.any { n -> Curation.folded(p) == n || Curation.folded(p).startsWith("$n ") && p.length < n.length + 4 } }
        return (kept.firstOrNull() ?: title).trim().ifEmpty { title }
    }

    /** Where its clip starts: YouTube's most replayed moment, null when it has none (blocking). */
    fun replayed(id: String): Long? = Hooks.replayed(id)

    /** Where its chorus starts, from its words' times (null: none repeats). */
    fun chorus(lines: List<Line>): Long? = Hooks.chorus(lines.map { it.at to it.words })
}
