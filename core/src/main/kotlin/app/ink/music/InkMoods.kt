package app.ink.music

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * ink's own moods (Bronson, 2026-10-28: YouTube's signed-out moods "suck"; "just want them to be cooler"): each a few
 * of the tags listeners give songs on Last.fm, blended, a different page of each tag every week so a mood stays fresh,
 * two songs an artist at most, no sped-up or nightcore versions. Right now picks three for the hour and the day. The
 * apps find the songs on YouTube Music to play, and a mood's covers on iTunes for its tile. Plain Java, shared.
 */
object InkMoods {
    /** A mood: its tags blended, and tags whose top artists it leaves out (fans tag their favorites with everything). */
    data class Mood(val key: String, val name: String, val line: String, val tags: List<String>, val not: List<String> = emptyList())

    /** Left out of every mood: the tags whose fans tag their favorites with every genre there is. */
    private val NEVER = listOf("k-pop", "kpop", "korean", "girl group", "j-pop")

    val ALL = listOf(
        Mood("after", "after hours", "for when everyone's gone home", listOf("dream pop", "slowcore", "trip-hop")),
        Mood("golden", "golden hour", "the light going orange", listOf("jangle pop", "sunshine pop", "yacht rock")),
        Mood("sunday", "slow sunday", "coffee, nowhere to be", listOf("indie folk", "bossa nova", "soul jazz")),
        Mood("focus", "deep focus", "no words, just the work", listOf("ambient", "post-rock", "modern classical")),
        Mood("floor", "dance floor", "the night's still young", listOf("nu disco", "french house", "deep house")),
        Mood("drive", "night drive", "headlights and empty roads", listOf("synthwave", "coldwave", "krautrock"), listOf("pop")),
        Mood("bedroom", "bedroom", "made at home, heard at home", listOf("bedroom pop", "lo-fi", "slacker rock"), listOf("hip-hop", "rap")),
        Mood("fuzz", "fuzz", "loud, warm, a little blown out", listOf("shoegaze", "noise pop", "garage rock")),
        Mood("cosmic", "cosmic", "somewhere out past the moon", listOf("psychedelic", "space rock", "neo-psychedelia")),
        Mood("rain", "rainy day", "windows, grey light, a long song", listOf("sadcore", "chamber pop", "singer-songwriter")),
        Mood("soul", "soul kitchen", "warm, low, and a little sweet", listOf("neo-soul", "funk", "soul")),
        Mood("wake", "wake up", "out the door, moving", listOf("indie rock", "dance-punk", "post-punk revival")),
    )

    /** Three for now: the hour, weekday or weekend. */
    fun now(at: LocalDateTime = LocalDateTime.now()): List<Mood> {
        val weekend = at.dayOfWeek == DayOfWeek.SATURDAY || at.dayOfWeek == DayOfWeek.SUNDAY
        val going = at.dayOfWeek == DayOfWeek.FRIDAY || at.dayOfWeek == DayOfWeek.SATURDAY
        val keys = when (at.hour) {
            in 5..8 -> if (weekend) listOf("sunday", "golden", "soul") else listOf("wake", "bedroom", "golden")
            in 9..11 -> if (weekend) listOf("sunday", "soul", "bedroom") else listOf("focus", "bedroom", "wake")
            in 12..16 -> if (weekend) listOf("golden", "fuzz", "soul") else listOf("focus", "golden", "fuzz")
            in 17..20 -> if (going) listOf("floor", "golden", "soul") else listOf("golden", "soul", "rain")
            in 21..23 -> if (going) listOf("floor", "drive", "after") else listOf("after", "drive", "cosmic")
            else -> listOf("after", "drive", "rain")
        }
        return keys.mapNotNull { k -> ALL.firstOrNull { it.key == k } }
    }

    private val versions = Regex("""\b(sped ?up|slowed|nightcore|karaoke|8d audio|bass ?boosted|remaster(ed)?)\b""", RegexOption.IGNORE_CASE)

    /**
     * A mood's songs (title, artist), its tags taken in turn: each tag's top songs on Last.fm from a page that moves each
     * week (so the same mood is a new one next week), two an artist at most. Blocking; empty without a Last.fm key.
     */
    fun songs(m: Mood, key: String, week: Long = LocalDate.now().toEpochDay() / 7): List<Pair<String, String>> {
        if (key.isBlank()) return emptyList()
        val each = m.tags.mapIndexed { i, tag ->
            val page = 1 + ((week + i) % 4).toInt()
            runCatching {
                val q = java.net.URLEncoder.encode(tag, "UTF-8")
                val o = org.json.JSONObject(Curation.get("https://ws.audioscrobbler.com/2.0/?method=tag.gettoptracks&tag=$q&limit=50&page=$page&api_key=$key&format=json"))
                val a = o.optJSONObject("tracks")?.optJSONArray("track") ?: return@runCatching emptyList()
                (0 until a.length()).map { a.getJSONObject(it) }.map { it.optString("name") to (it.optJSONObject("artist")?.optString("name") ?: "") }
            }.getOrDefault(emptyList()).shuffled(java.util.Random(week * 31 + i))
        }
        // the artists those tags are most given to: none of theirs here
        val out = (NEVER + m.not).flatMap { tag ->
            runCatching {
                val q = java.net.URLEncoder.encode(tag, "UTF-8")
                val o = org.json.JSONObject(Curation.get("https://ws.audioscrobbler.com/2.0/?method=tag.gettopartists&tag=$q&limit=300&api_key=$key&format=json"))
                val a = o.optJSONObject("topartists")?.optJSONArray("artist") ?: return@runCatching emptyList()
                (0 until a.length()).map { a.getJSONObject(it).optString("name").lowercase() }
            }.getOrDefault(emptyList())
        }.toHashSet()
        val by = HashMap<String, Int>()
        return (0 until (each.maxOfOrNull { it.size } ?: 0)).flatMap { i -> each.mapNotNull { it.getOrNull(i) } }
            .filter { (t, a) ->
                if (t.isBlank() || a.isBlank() || versions.containsMatchIn(t) || a.lowercase() in out) return@filter false
                val k = a.lowercase()
                if ((by[k] ?: 0) >= 2) return@filter false
                by[k] = (by[k] ?: 0) + 1
                true
            }.distinctBy { (t, a) -> (a + "/" + t).lowercase() }.take(80)
    }
}
