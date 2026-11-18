package app.ink.music

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * TUNER's clips, in order (2026-11-18): new songs from YouTube Music's editors' lists nearest your taste (EditorsNew,
 * a video each), every fourth one of your own artists' new videos (MusicVideos); none shown in the last month, none of
 * an artist said not for you, blocked or on the AI list; never one artist twice running. Nearest you first among the
 * editors' songs, with a stretch every few, as your appetite for the new says.
 */
object TunerFeed {
    suspend fun of(c: Context): List<Tune> = withContext(Dispatchers.IO) {
        val taste = Fresh.taste.value
        val tuned = Fresh.tuned ?: return@withContext emptyList()
        val refused = Fresh.turnedDown(c)
        fun fine(id: String, title: String, artist: String) =
            !tuned.shown(id) && Curation.folded(artist) !in refused && !Blocked.hides(title, artist) && !taste.refused(artist)
        val editors = EditorsNew.songs(YouTubeMusic.editorsLists(), taste::of)
            .filter { fine(it.videoId, it.title, it.artists.joinToString(" & ")) }
            .map { t -> val who = t.artists.joinToString(" & ")
                // a video's own title ("Fontaines D.C. - Marianne (Official Video)") as its song's
                Tune(t.videoId, plain(t.title, t.artists), who, YouTubeMusic.songOf(t), t.seconds) }
        val yours = MusicVideos.clips.value.filter { fine(it.id, it.title, it.artist) }.map { Tune(it.id, it.title, it.artist, null) }
        // nearest first, but a stretch (the rest's next) every so often: one in five, more with a bigger appetite
        val (near, far) = editors.partition { taste.of(it.artist) >= .2 }
        val every = if (taste.appetite > .6) 3 else 5
        val mixed = ArrayList<Tune>()
        val n = near.sortedByDescending { taste.of(it.artist) }.iterator(); val f = far.iterator(); val y = yours.iterator()
        var i = 0
        while (n.hasNext() || f.hasNext() || y.hasNext()) {
            i++
            val next = when {
                i % 4 == 0 && y.hasNext() -> y.next()
                i % every == 0 && f.hasNext() -> f.next()
                n.hasNext() -> n.next()
                f.hasNext() -> f.next()
                else -> y.next()
            }
            mixed += next
        }
        val ai = Fresh.ai
        val clean = if (ai == null) mixed else mixed.filterNot { ai.made(it.artist, InnerTube()::onWikipedia) }
        // each artist once before anyone's twice (Fontaines D.C. came round twice in four, 2026-11-18)
        fun who(t: Tune) = Curation.folded(t.artist.substringBefore(" & "))
        val each = clean.distinctBy { it.id }
        val firsts = each.distinctBy(::who)
        Curation.apart(firsts + (each - firsts.toSet())) { who(it) }.take(80)
    }

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

    /**
     * A clip's streams, kept small so it starts at once (2026-11-18, Bronson: capped if that helps): 480p on wifi, 360p
     * on someone's data, YouTube's smallest sound (blocking).
     */
    fun stream(id: String, metered: Boolean): Pair<String, String?>? =
        Pipe.streamInfo(id)?.let { Pipe.video(it, if (metered) 360 else 480, smallSound = true) }

    /** Where its clip starts: YouTube's most replayed moment, null when it has none (blocking). */
    fun replayed(id: String): Long? = Hooks.replayed(id)

    /** Where its chorus starts, from its words' times (null: none repeats). */
    fun chorus(lines: List<Line>): Long? = Hooks.chorus(lines.map { it.at to it.words })
}
