package app.ink.music

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * YouTube Music's own playlists, the cool ones only (2026-11-08, Bronson: "i just dont want a bunch of top 40 bullshit,
 * its still gotta feel curated, cool playlists only"). Not its front page, where the top 40 lives: the editorial lists
 * on the moods' and genres' pages that sit near your taste (and the genres you open most). Each list's artists (the four
 * its card names, else its first 25 songs) scored: up for each one you play, love or keep and each the critics picked
 * (INK's sources); out with a chart word in its name ("hits", "top", "viral"...) or a fifth of its artists on this
 * week's charts (Today's Top Hits, Today's Hits, RapCaviar, Hot Country, Viva Latino); out too if none of its artists
 * is known either way (no reason to show it). The best 30, each said why. Read once a day, kept in
 * files/cool-lists.json (its artists by list kept a week).
 */
object CoolLists {
    /** A list as shown: the list, why it's here. */
    data class Cool(val list: TubeList, val why: String, val score: Double)

    private val _lists = MutableStateFlow<List<Cool>>(emptyList())
    val lists: StateFlow<List<Cool>> = _lists

    /** The moods and genres read from: near the music ink's for, and the genres you open most join them. */
    private val NEAR = setOf("indie & alternative", "dance & electronic", "folk & acoustic", "rock", "r&b & soul", "jazz", "chill", "focus",
        "feel good", "commute", "sad", "romance", "songwriters & producers")

    /** Words a chart list goes by. */
    private val CHART = Regex("""\b(hits?|hitlist|top|charts?|trending|viral|biggest|today'?s|hot ?\d*|billion|greatest|best of|throwback|essentials|party|workout|gym|tiktok|anthems)\b""", RegexOption.IGNORE_CASE)

    private var readAt = 0L
    /** Raised when the scoring changes, so the kept lists are read again at once. */
    private const val VERSION = 2

    /** Kept ones shown at once; read again once a day (blocking a minute or so the first time; on IO). */
    suspend fun read(c: Context) = withContext(Dispatchers.IO) {
        val file = File(c.filesDir, "cool-lists.json")
        val kept = runCatching { JSONObject(file.readText()) }.getOrNull()
        if (_lists.value.isEmpty()) kept?.optJSONArray("lists")?.let { a -> _lists.value = (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::cool) } }
        val now = System.currentTimeMillis()
        if ((kept?.optInt("v") == VERSION && now - kept.optLong("at") < 86_400_000L) || now - readAt < 600_000L) return@withContext
        readAt = now
        val artistsOf = HashMap<String, List<String>>()
        kept?.optJSONObject("artists")?.let { o -> if (now - kept.optLong("artistsAt") < 7 * 86_400_000L) o.keys().forEach { k -> artistsOf[k] = o.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) } } } }

        // where they're read from
        val yt = InnerTube()
        val opened = Moods.counts.value.filterValues { it > 0 }.keys.map { it.lowercase() }
        val moods = runCatching { yt.moods() }.getOrDefault(emptyList()).filter { it.title.lowercase() in NEAR || it.title.lowercase() in opened }
        val gate = Semaphore(4)
        val found = coroutineScope {
            moods.map { m -> async { gate.withPermit { runCatching { yt.mood(m.params) }.getOrDefault(emptyList()) } } }.awaitAll().flatten()
        }.flatMap { it.items }
            // YouTube Music's own lists (people's own are a lottery), not a mix, no chart word in the name
            .filter { it.kind == InnerTube.Kind.PLAYLIST && it.id.removePrefix("VL").startsWith("RDCLAK") && !CHART.containsMatchIn(it.title) }
            .distinctBy { it.id }

        // each one's artists: the card's four, else its first songs'
        coroutineScope {
            found.map { it ->
                async {
                    gate.withPermit {
                        if (artistsOf.containsKey(it.id)) return@withPermit
                        val named = it.subtitle.takeIf { s -> !s.startsWith("Playlist") }?.split(",")?.map(String::trim)?.filter(String::isNotEmpty)
                        artistsOf[it.id] = named ?: runCatching { yt.tracks(it.id.removePrefix("VL").let { id -> "VL$id" }, 25).flatMap { t -> t.artists } }.getOrDefault(emptyList())
                    }
                }
            }.awaitAll()
        }

        // who you play, love or keep; who the critics picked; who's on the charts
        fun norm(a: String) = Curation.folded(a).removePrefix("the ").filter { it.isLetterOrDigit() }
        val yours = HashSet<String>().apply {
            Listened.now.value.plays.forEach { (k, n) -> if (k.startsWith("a:") && n >= 2) add(norm(k.removePrefix("a:"))) }
            Library.songs.value.forEach { add(norm(it.albumArtist)) }
        }
        val critics = (Picks.records.value + Picks.songs.value).flatMap { it.pick.artist.split(", ") }.map(::norm).toSet()
        val charts = coroutineScope {
            listOf(
                async { runCatching { SpotifyLists.list("37i9dQZF1DXcBWIGoYBM5M", "", 50, now) }.getOrDefault(emptyList()) },
                async { runCatching { SpotifyLists.list("37i9dQZF1DX0XUsuxWHRQd", "", 50, now) }.getOrDefault(emptyList()) },
                async { runCatching { SpotifyLists.list("37i9dQZF1DX1lVhptIYRda", "", 50, now) }.getOrDefault(emptyList()) },
                async { runCatching { SpotifyLists.list("37i9dQZF1DX10zKzsJ2jva", "", 50, now) }.getOrDefault(emptyList()) },
                async { runCatching { GenreLists.apple("pl.f4d106fed2bd41149aaacabb233eb5eb", "", 100, now) }.getOrDefault(emptyList()) },
            ).awaitAll().flatten().flatMap { it.artist.split(", ") }.map(::norm).filter { it.isNotEmpty() }.toSet()
        }

        val scored = found.mapNotNull { it ->
            val artists = artistsOf[it.id].orEmpty().map(String::trim).filter { a -> a.isNotEmpty() && a != "@" }.distinctBy(::norm)
            if (artists.isEmpty()) return@mapNotNull null
            val n = artists.map(::norm)
            val chartShare = n.count { it in charts }.toDouble() / n.size
            if (chartShare >= .2) return@mapNotNull null
            val mine = artists.filter { a -> norm(a) in yours }
            val picked = artists.filter { a -> norm(a) in critics && norm(a) !in yours }
            if (mine.isEmpty() && picked.isEmpty()) return@mapNotNull null
            val score = mine.size * 2.0 + picked.size * 1.5 - chartShare * 4
            // why: your artists first ("big thief, men i trust and 5 more of yours"), else the critics'
            val why = if (mine.isNotEmpty()) mine.take(2).joinToString(", ") { a -> a.lowercase() } + (if (mine.size > 2) " and ${mine.size - 2} more of yours" else ", yours")
                else "the critics' " + picked.take(2).joinToString(", ") { a -> a.lowercase() } + if (picked.size > 2) " and ${picked.size - 2} more" else ""
            Cool(TubeList(it.id.removePrefix("VL"), it.title, it.subtitle, it.art), why, score)
        }.sortedByDescending { it.score }.take(30)

        android.util.Log.d("ink-picks", "cool lists: ${scored.size} of ${found.size} read from ${moods.size} moods (charts ${charts.size} artists, yours ${yours.size}, critics ${critics.size})")
        if (scored.isNotEmpty()) _lists.value = scored
        runCatching {
            file.writeText(JSONObject().put("v", VERSION).put("at", now).put("lists", JSONArray(scored.map(::json)))
                .put("artistsAt", if (now - (kept?.optLong("artistsAt") ?: 0L) < 7 * 86_400_000L) kept!!.optLong("artistsAt") else now)
                .put("artists", JSONObject().apply { artistsOf.forEach { (k, v) -> put(k, JSONArray(v)) } }).toString())
        }
    }

    private fun json(c: Cool) = JSONObject().put("id", c.list.id).put("name", c.list.name).put("said", c.list.said).put("art", c.list.art ?: "")
        .put("why", c.why).put("score", c.score)
    private fun cool(o: JSONObject) = Cool(TubeList(o.getString("id"), o.getString("name"), o.optString("said"), o.optString("art").ifEmpty { null }),
        o.optString("why"), o.optDouble("score"))
}
