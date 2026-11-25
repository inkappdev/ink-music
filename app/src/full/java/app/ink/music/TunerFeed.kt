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
    /**
     * The searches' own lane, six at a time (2026-11-18, Bronson: "videos aren't loading"): fans of fans and a rabbit
     * hole's dozens of reads at once had filled the shared pool, and the clip in view waited behind them for its stream.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val LANE = Dispatchers.IO.limitedParallelism(6)

    // not a music video: lyric and visualiser uploads, reactions, covers, karaoke, sped up and slowed, hour-long loops
    private val NOT_A_VIDEO = Regex("""\b(lyrics?\s*video|lyric|reaction|reacts?|karaoke|cover\b|sped\s*up|slowed|nightcore|8d audio|1\s*hour|loop(ed)?\b|tutorial|instrumental)""", RegexOption.IGNORE_CASE)

    private fun fine(c: Context, id: String, title: String, artist: String): Boolean {
        val tuned = Fresh.tuned ?: return false
        if (NOT_A_VIDEO.containsMatchIn(title)) return false
        return !tuned.shown(id) && Curation.folded(artist) !in Fresh.turnedDown(c) && !Blocked.hides(title, artist) && !Fresh.taste.value.refused(artist)
    }

    private fun clean(xs: List<Tune>): List<Tune> {
        val ai = Fresh.ai ?: return xs
        return xs.filterNot { ai.made(it.artist, InnerTube()::onWikipedia) }
    }

    /** The first pool: the editors' lists' songs and your artists' new videos. */
    suspend fun start(c: Context): List<Tune> = withContext(LANE) {
        if (JustYours.on) return@withContext emptyList()
        val taste = Fresh.taste.value
        val editors = EditorsNew.songs(YouTubeMusic.editorsLists(), taste::of).filter { (_, t) -> fine(c, t.videoId, t.title, t.artists.joinToString(" & ")) }
            .map { (list, t) -> val who = t.artists.joinToString(" & ")
                Tune(t.videoId, plain(t.title, t.artists), who, YouTubeMusic.songOf(t), t.seconds, "editors", list, still = t.albumId != null) }
        val yours = MusicVideos.clips.value.filter { fine(c, it.id, it.title, it.artist) }.map { Tune(it.id, it.title, it.artist, null, 0, "yours", it.artist, at = it.at) }
        // live sessions (KEXP, Tiny Desk, COLORS...): the artists near you, and as many new to you
        val all = LiveSessions.all.value.filter { fine(c, it.id, it.what, it.artist) }
        val (near, far) = all.partition { taste.of(it.artist) >= .1 }
        val sessions = (near.take(20) + far.shuffled().take(20)).map { s ->
            Tune(s.id, if (s.what == "full session") "live" else s.what, s.artist, null, 0, "sessions", s.says, at = s.at)
        }
        clean((editors + yours + sessions).distinctBy { it.id })
    }

    /**
     * Artists whose fans also like those `of` (their YouTube Music pages), new to you (not near you yet), a video or
     * two each, their newest; found through `of`'s artist (the tag a like of them brings forward).
     */
    suspend fun fans(c: Context, of: List<String>, have: Set<String>, each: Int = 4, from: String = "fans"): List<Tune> = withContext(LANE) {
        val taste = Fresh.taste.value
        coroutineScope {
            of.map { name ->
                async {
                    val like = neighbors(name).filter { taste.of(it) < .3 && Curation.folded(it) !in have }.take(each)
                    like.map { n -> async { runCatching { MusicVideos.of(n) }.getOrDefault(emptyList()).take(2).map { Tune(it.id, it.title, it.artist, null, 0, from, name, at = it.at) } } }
                        .awaitAll().flatten()
                }
            }.awaitAll().flatten()
        }.filter { fine(c, it.id, it.title, it.artist) }.distinctBy { it.id }.let(::clean)
    }

    /** Whom an artist's fans also like (their YouTube Music page's, as kept). */
    private suspend fun neighbors(name: String): List<String> =
        withTimeoutOrNull(6_000) { YouTubeAccount.artist(name, "").firstOrNull { it != null } }?.like.orEmpty().map { it.name }

    /**
     * Two steps out (2026-11-18, Bronson: "find old crazy music"): artists whose fans also like those that `of`'s fans
     * like, and not near you at all, a video each: further from the middle, where the odd ones are.
     */
    suspend fun far(c: Context, of: List<String>, have: Set<String>, from: String = "far"): List<Tune> = withContext(LANE) {
        val taste = Fresh.taste.value
        coroutineScope {
            of.map { name ->
                async {
                    val first = neighbors(name).take(3)
                    val second = first.map { async { neighbors(it) } }.awaitAll().flatten()
                        .filter { n -> taste.of(n) < .05 && Curation.folded(n) !in have && first.none { Curation.folded(it) == Curation.folded(n) } }
                        .distinctBy { Curation.folded(it) }.take(4)
                    second.map { n -> async { runCatching { MusicVideos.of(n) }.getOrDefault(emptyList()).take(1).map { Tune(it.id, it.title, it.artist, null, 0, from, name, at = it.at) } } }
                        .awaitAll().flatten()
                }
            }.awaitAll().flatten()
        }.filter { fine(c, it.id, it.title, it.artist) }.distinctBy { it.id }.let(::clean)
    }

    /** Deep cuts: the older videos (a year and more) of artists `of`, a couple each, oldest first. */
    suspend fun deep(c: Context, of: List<String>, each: Int = 2, from: String = "deep"): List<Tune> = withContext(LANE) {
        coroutineScope {
            of.map { name -> async { runCatching { MusicVideos.older(name) }.getOrDefault(emptyList()).take(each).map { Tune(it.id, it.title, it.artist, null, 0, from, name, at = it.at) } } }
                .awaitAll().flatten()
        }.filter { fine(c, it.id, it.title, it.artist) }.distinctBy { it.id }.let(::clean)
    }

    /**
     * The decades: YouTube Music's decade lists (its moods & genres page, "Decades": the 60s to the 2000s, by genre),
     * six of them read, the two whose artists are nearest your taste kept.
     */
    suspend fun decades(c: Context): List<Tune> = withContext(LANE) {
        val taste = Fresh.taste.value
        val tube = InnerTube()
        val mood = runCatching { tube.moods() }.getOrDefault(emptyList()).firstOrNull { it.title.equals("decades", true) } ?: return@withContext emptyList()
        val lists = runCatching { tube.mood(mood.params) }.getOrDefault(emptyList()).flatMap { it.items }
            .filter { it.kind == InnerTube.Kind.PLAYLIST }.distinctBy { it.id }.shuffled().take(6)
        val read = coroutineScope { lists.map { l -> async { l.title to runCatching { tube.tracks(if (l.id.startsWith("VL")) l.id else "VL${l.id}", 40) }.getOrDefault(emptyList()) } }.awaitAll() }
        read.filter { it.second.isNotEmpty() }.sortedByDescending { (_, songs) -> songs.sumOf { t -> t.artists.maxOfOrNull(taste::of) ?: 0.0 } }.take(2)
            .flatMap { (list, songs) -> songs.map { t -> Tune(t.videoId, plain(t.title, t.artists), t.artists.joinToString(" & "), YouTubeMusic.songOf(t), t.seconds, "decades", list.lowercase(), still = t.albumId != null) } }
            .filter { fine(c, it.id, it.title, it.artist) }.distinctBy { it.id }.let(::clean)
    }

    /**
     * A rabbit hole (2026-11-18): two likes close together (one found through the other, or the same artist): further in
     * round them, their fans' favorites, their fans' fans' and their deep cuts, all found through the hole's artist.
     */
    suspend fun hole(c: Context, artist: String, have: Set<String>): List<Tune> = withContext(LANE) {
        val a = async { fans(c, listOf(artist), have, each = 6, from = "hole") }
        val b = async { far(c, listOf(artist), have, from = "hole") }
        val d = async { deep(c, listOf(artist), each = 3, from = "hole") }
        (a.await() + b.await() + d.await()).distinctBy { it.id }
    }

    /** YouTube's up next for a clip (its song's radio, as videos), found through its artist. */
    suspend fun related(c: Context, t: Tune): List<Tune> = withContext(LANE) {
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
