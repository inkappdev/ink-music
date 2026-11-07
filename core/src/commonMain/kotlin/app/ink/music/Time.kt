@file:OptIn(kotlin.time.ExperimentalTime::class)

package app.ink.music

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** The phone's own time zone's now, today, a moment's day (kotlinx-datetime: the same on every system). */
fun localNow(): LocalDateTime = localAt(nowMs())
fun today(): LocalDate = localNow().date
fun localAt(ms: Long): LocalDateTime = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.currentSystemDefault())
fun localDay(ms: Long): LocalDate = localAt(ms).date

private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
private val ZONES = mapOf("gmt" to 0, "ut" to 0, "utc" to 0, "z" to 0, "est" to -300, "edt" to -240, "cst" to -360, "cdt" to -300,
    "mst" to -420, "mdt" to -360, "pst" to -480, "pdt" to -420)

/**
 * A feed's date, RFC 1123 ("Thu, 05 Nov 2026 08:00:00 +0000", "5 Nov 2026 08:00 GMT", a zone's letters): ms since 1970, or
 * null if it isn't one. No zone: taken as UTC.
 */
fun rfc1123(s: String): Long? {
    val m = Regex("""(\d{1,2})\s+([A-Za-z]{3})[a-z]*\s+(\d{2,4})\s+(\d{1,2}):(\d{2})(?::(\d{2}))?\s*([+-]\d{4}|[A-Za-z]{1,4})?""").find(s.trim()) ?: return null
    val (d, mon, y, h, min, sec, zone) = m.destructured
    val month = MONTHS.indexOf(mon.lowercase()).takeIf { it >= 0 } ?: return null
    val year = y.toInt().let { if (it < 100) it + 2000 else it }
    val offset = when {
        zone.startsWith("+") || zone.startsWith("-") -> (if (zone[0] == '-') -1 else 1) * (zone.substring(1, 3).toInt() * 60 + zone.substring(3, 5).toInt())
        else -> ZONES[zone.lowercase()] ?: 0
    }
    val days = LocalDate(year, month + 1, d.toInt()).toEpochDays().toLong()
    return ((days * 86_400 + h.toInt() * 3_600 + min.toInt() * 60 + (sec.toIntOrNull() ?: 0)) - offset * 60L) * 1000
}

/** An ISO 8601 moment ("2026-11-07T12:34:56-08:00", "…Z", with fractions): ms since 1970, or null. */
fun iso8601(s: String): Long? = runCatching { Instant.parse(s.trim()).toEpochMilliseconds() }.getOrNull()

/** A wall-clock time in a named zone ("America/New_York"), as ms since 1970. */
fun zoned(zone: String, year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long =
    kotlinx.datetime.LocalDateTime(year, month, day, hour, minute, second).toInstant(TimeZone.of(zone)).toEpochMilliseconds()

/** Today in a named zone. */
fun todayIn(zone: String): LocalDate = Instant.fromEpochMilliseconds(nowMs()).toLocalDateTime(TimeZone.of(zone)).date
