package app.ink.music

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

/**
 * Fresh releases from ListenBrainz (2026-11-24): what came out in the last two weeks, as MusicBrainz knows it, for
 * listen's "new from your artists". Two reads, no key needed: everyone's (`/explore/fresh-releases`, thousands a
 * fortnight, kept to your artists by Curation.yours like the critics' picks are), and, with a ListenBrainz account, your
 * own (`/user/<name>/fresh_releases`: the artists you listen to there, so theirs count as yours here). Each a pick
 * like the critics' (a record, or a single as a song), its cover the Cover Art Archive's where there is one. Kept a day
 * in `dir`; quiet on failure (ListenBrainz's log). Blocking.
 */
object FreshReleases {
    /** The picks, and the artists (lower case) ListenBrainz found them for: yours there. */
    data class Fresh(val picks: List<Curation.Pick>, val theirs: Set<String>)

    private const val API = "https://api.listenbrainz.org/1"
    private const val DAY = 24L * 3600 * 1000
    private const val DAYS = 14

    private fun file(dir: File) = File(dir, "listenbrainz-fresh.json")

    /** The kept ones when a day old at most and read for the same name; else read again (and kept). */
    fun read(dir: File, user: String?): Fresh? {
        val who = user?.trim().orEmpty()
        val kept = runCatching { JSONObject(file(dir).readText()) }.getOrNull()
        if (kept != null && kept.optString("user") == who && nowMs() - kept.optLong("at") < DAY) return fromJson(kept)
        val now = nowMs()
        val everyone = runCatching { parse(get("$API/explore/fresh-releases/?days=$DAYS"), now) }
            .onFailure { ListenBrainz.log("fresh releases: $it") }.getOrNull()
        val mine = if (who.isEmpty()) null else runCatching { parse(get("$API/user/${urlEncode(who)}/fresh_releases?past=true&future=false"), now) }
            .onFailure { ListenBrainz.log("fresh releases for $who: $it") }.getOrNull()
        // neither answered: what was kept, however old
        if (everyone == null && mine == null) return kept?.let(::fromJson)
        val picks = (mine.orEmpty() + everyone.orEmpty()).distinctBy(Curation::key)
        val f = Fresh(picks, mine.orEmpty().map { it.artist.lowercase() }.toSet())
        runCatching { dir.mkdirs(); file(dir).writeText(toJson(f, who, now).toString()) }
        ListenBrainz.log("fresh releases: ${everyone?.size ?: 0} everyone's, ${mine?.size ?: 0} yours")
        return f
    }

    /**
     * A fresh-releases answer (everyone's and yours have one shape: `payload.releases`) as picks: out in the last two
     * weeks and not yet to come, albums, EPs and singles of their own (no compilations, live records, remixes'
     * collections), a single as a song. `now` for "out yet".
     */
    fun parse(text: String, now: Long): List<Curation.Pick> {
        val a = JSONObject(text).optJSONObject("payload")?.optJSONArray("releases") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val r = a.optJSONObject(i) ?: return@mapNotNull null
            val title = r.optString("release_name").trim()
            // several credited are "a; b" there, read here as "a, b" (Curation.yours splits on commas)
            val artist = r.optString("artist_credit_name").replace("; ", ", ").trim()
            if (title.isEmpty() || artist.isEmpty()) return@mapNotNull null
            val type = r.optString("release_group_primary_type").lowercase()
            if (type !in setOf("album", "ep", "single")) return@mapNotNull null
            if (!r.isNull("release_group_secondary_type") && r.optString("release_group_secondary_type").isNotBlank()) return@mapNotNull null
            val at = runCatching { LocalDate.parse(r.optString("release_date")).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds() }.getOrNull()
                ?: return@mapNotNull null
            // a day's release counted from its midnight (UTC): the fortnight's first day whole
            if (at > now || now - at > (DAYS + 1) * DAY) return@mapNotNull null
            val group = r.optString("release_group_mbid")
            val link = if (group.isNotBlank()) "https://musicbrainz.org/release-group/$group" else ""
            val caa = r.optString("caa_release_mbid").takeIf { !r.isNull("caa_release_mbid") && !r.isNull("caa_id") && it.isNotBlank() }
            Curation.Pick(type != "single", title, artist, "listenbrainz", "", link, at, caa?.let { "https://coverartarchive.org/release/$it/front-250" })
        }
    }

    private fun get(url: String): String {
        val r = http(url, headers = mapOf("User-Agent" to AGENT, "Accept" to "application/json"), connectMs = 8_000, readMs = 20_000)
        if (r.code != 200) throw IOException("${r.code} $url")
        return r.text
    }

    private fun toJson(f: Fresh, user: String, at: Long) = JSONObject().put("at", at).put("user", user)
        .put("theirs", JSONArray(f.theirs.toList()))
        .put("picks", JSONArray(f.picks.map { p ->
            JSONObject().put("record", p.record).put("title", p.title).put("artist", p.artist).put("link", p.link).put("at", p.at).put("art", p.art ?: "")
        }))

    private fun fromJson(o: JSONObject): Fresh? = runCatching {
        val p = o.getJSONArray("picks")
        val t = o.optJSONArray("theirs")
        Fresh(
            (0 until p.length()).map { p.getJSONObject(it) }.map { j ->
                Curation.Pick(j.getBoolean("record"), j.getString("title"), j.getString("artist"), "listenbrainz", "", j.optString("link"), j.optLong("at"),
                    j.optString("art").ifEmpty { null })
            },
            t?.let { (0 until it.length()).map { i -> it.getString(i) }.toSet() }.orEmpty(),
        )
    }.getOrNull()
}
