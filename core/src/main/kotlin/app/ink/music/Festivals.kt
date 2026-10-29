package app.ink.music

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * ink's festivals (Bronson, 2026-10-28): the ones worth hearing ahead of, their lineups as announced, kept by hand in
 * ink-festivals.json as ink weekly is (no free feed of lineups exists). The ones on, or starting within two months,
 * become a section on explore: who's playing, and a station of them. Plain Java, shared.
 */
object Festivals {
    data class Festival(val name: String, val city: String, val start: LocalDate, val end: LocalDate, val link: String, val artists: List<String>) {
        /** "on now", "tomorrow", "in 9 days", "in 3 weeks", as of `today`. */
        fun when_(today: LocalDate = LocalDate.now()): String {
            val days = ChronoUnit.DAYS.between(today, start)
            return when {
                days <= 0 -> "on now"
                days == 1L -> "tomorrow"
                days < 14 -> "in $days days"
                else -> "in ${days / 7} weeks"
            }
        }
    }

    fun read(text: String): List<Festival> = runCatching {
        val a = org.json.JSONObject(text).getJSONArray("festivals")
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            val names = o.getJSONArray("artists")
            Festival(o.getString("name"), o.optString("city"), LocalDate.parse(o.getString("start")), LocalDate.parse(o.getString("end")),
                o.optString("link"), (0 until names.length()).map { names.getString(it) })
        }
    }.getOrDefault(emptyList())

    /** Those on now or starting within `days`, the soonest first. */
    fun soon(all: List<Festival>, today: LocalDate = LocalDate.now(), days: Long = 60): List<Festival> =
        all.filter { !it.end.isBefore(today) && !it.start.isAfter(today.plusDays(days)) }.sortedBy { it.start }
}
