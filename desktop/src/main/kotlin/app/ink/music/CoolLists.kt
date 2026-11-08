package app.ink.music

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
 * YouTube Music's own playlists, the cool ones only (the phone's CoolLists, 2026-11-08: "i just dont want a bunch of top
 * 40 bullshit"): its editorial lists on the moods' and genres' pages near your taste, each one's artists scored, up for
 * yours and the critics', out with a chart word in its name or a fifth of its artists on this week's charts, out if
 * none is known. The best 30, each said why (on its tile, where an album's artist goes). Read daily, kept in
 * ~/.ink-music/cool-lists.json.
 */
object CoolLists {
    data class Cool(val list: TubeAlbum, val score: Double)
    private val _lists = MutableStateFlow<List<Cool>>(emptyList())
    val lists: StateFlow<List<Cool>> = _lists

    private val NEAR = setOf("indie & alternative", "dance & electronic", "folk & acoustic", "rock", "r&b & soul", "jazz", "chill", "focus",
        "feel good", "commute", "sad", "romance", "songwriters & producers")
    private val CHART = Regex("""\b(hits?|hitlist|top|charts?|trending|viral|biggest|today'?s|hot ?\d*|billion|greatest|best of|throwback|essentials|party|workout|gym|tiktok|anthems)\b""", RegexOption.IGNORE_CASE)
    private const val VERSION = 2
    private var readAt = 0L

    suspend fun read() = withContext(Dispatchers.IO) {
        val file = File(Home.dir, "cool-lists.json")
        val kept = runCatching { JSONObject(file.readText()) }.getOrNull()
        if (_lists.value.isEmpty()) kept?.optJSONArray("lists")?.let { a ->
            _lists.value = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o -> Cool(TubeAlbum(o.getString("url"), o.getString("title"), o.optString("why"), o.optString("art").ifEmpty { null }), o.optDouble("score")) }
        }
        val now = System.currentTimeMillis()
        if ((kept?.optInt("v") == VERSION && now - kept.optLong("at") < 86_400_000L) || now - readAt < 600_000L) return@withContext
        readAt = now
        val yt = InnerTube()
        val opened = Moods.counts.value.filterValues { it > 0 }.keys.map { it.lowercase() }
        val moods = runCatching { yt.moods() }.getOrDefault(emptyList()).filter { it.title.lowercase() in NEAR || it.title.lowercase() in opened }
        val gate = Semaphore(4)
        val found = coroutineScope { moods.map { m -> async { gate.withPermit { runCatching { yt.mood(m.params) }.getOrDefault(emptyList()) } } }.awaitAll().flatten() }
            .flatMap { it.items }
            .filter { it.kind == InnerTube.Kind.PLAYLIST && it.id.removePrefix("VL").startsWith("RDCLAK") && !CHART.containsMatchIn(it.title) }
            .distinctBy { it.id }
        val artistsOf = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
        coroutineScope {
            found.map { it -> async { gate.withPermit {
                val named = it.subtitle.takeIf { s -> !s.startsWith("Playlist") }?.split(",")?.map(String::trim)?.filter(String::isNotEmpty)
                artistsOf[it.id] = named ?: runCatching { yt.tracks("VL" + it.id.removePrefix("VL"), 25).flatMap { t -> t.artists } }.getOrDefault(emptyList())
            } } }.awaitAll()
        }
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
            val why = if (mine.isNotEmpty()) mine.take(2).joinToString(", ") { a -> a.lowercase() } + (if (mine.size > 2) " and ${mine.size - 2} more of yours" else ", yours")
                else "the critics' " + picked.take(2).joinToString(", ") { a -> a.lowercase() } + if (picked.size > 2) " and ${picked.size - 2} more" else ""
            Cool(TubeAlbum("${InnerTube.BASE}/playlist?list=${it.id.removePrefix("VL")}", it.title, why, it.art), mine.size * 2.0 + picked.size * 1.5 - chartShare * 4)
        }.sortedByDescending { it.score }.take(30)
        Log.d("cool lists: ${scored.size} of ${found.size} read from ${moods.size} moods")
        if (scored.isNotEmpty()) _lists.value = scored
        runCatching {
            file.writeText(JSONObject().put("v", VERSION).put("at", now).put("lists", JSONArray(scored.map { c ->
                JSONObject().put("url", c.list.url).put("title", c.list.title).put("why", c.list.artist).put("art", c.list.art ?: "").put("score", c.score)
            })).toString())
        }
    }
}
