package app.ink.music

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * ink music's easter eggs (Bronson, 2026-10-29: "I really love easter eggs in software, even when in plain sight"),
 * the rules of them shared by the phone and the computer; each app draws its own: the heart after "made by ink" that
 * beats (and, clicked seven times, sends hearts up), the radio tower's lightning (three clicks: a station by chance),
 * 1-bit mode (the Konami code on the computer, the clickwheel three turns backwards on the phone), and these.
 */
object Eggs {
    /** The greeting's own words at odd hours, or null for the usual: after three in the morning, a Friday night. */
    fun greeting(at: LocalDateTime = LocalDateTime.now()): String? = when {
        at.hour in 3..4 -> "STILL UP?"
        at.dayOfWeek == DayOfWeek.FRIDAY && at.hour in 17..23 -> "FRIDAY. FINALLY."
        else -> null
    }

    /** The same song a fifth time running: the four heard before it (the newest last) all it. */
    fun onRepeat(heardIds: List<String>, id: String): Boolean = heardIds.size >= 4 && heardIds.takeLast(4).all { it == id }

    /** Its 100th play today: the day a song reaches a hundred, its heart's gold (the 100th listen's start, if it was today). */
    fun golden(plays: List<Long>): Boolean = plays.size >= 100 && day(plays.sorted()[99]) == LocalDate.now()

    /** Years since your first listen, if today's its day (a year in, two...); 0 otherwise. */
    fun anniversary(firstAt: Long, today: LocalDate = LocalDate.now()): Int {
        if (firstAt <= 0) return 0
        val first = day(firstAt)
        return if (first.month == today.month && first.dayOfMonth == today.dayOfMonth && today.year > first.year) today.year - first.year else 0
    }

    /** With private listening on, a song we promised not to tell about. */
    fun shy(artist: String): Boolean = artist.contains("taylor swift", ignoreCase = true)

    /** The Konami code, as keys' names: ↑ ↑ ↓ ↓ ← → ← → B A. */
    val KONAMI = listOf("up", "up", "down", "down", "left", "right", "left", "right", "b", "a")

    private fun day(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
}
