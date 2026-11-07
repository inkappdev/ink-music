package app.ink.music

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.jvm.Synchronized
import kotlin.time.Duration.Companion.hours

/**
 * What's been shown, and what came of it (2026-11-07): each pick, record or shelf by a key, the day it was first shown,
 * the last, on how many days, and how often it was opened. So what you've passed over steps back (shown three days
 * unopened it's half as likely; five, it rests a week), what's new since you were here can say so, and a shelf never
 * opened sinks. Kept in the app's own folder, on the device only.
 */
class Seen(private val file: File) {
    private class Entry(var first: Long, var last: Long, var days: Int, var taps: Int, var tappedAt: Long, var hidden: Boolean)

    private val all = HashMap<String, Entry>()
    @Volatile private var dirty = false

    init {
        runCatching {
            val o = JSONObject(file.readText())
            val old = nowMs() - 150 * DAY
            for (k in o.keys()) {
                val a = o.getJSONArray(k)
                val e = Entry(a.getLong(0), a.getLong(1), a.getInt(2), a.getInt(3), a.optLong(4), a.optBoolean(5))
                if (e.last > old || e.hidden) all[k] = e
            }
        }
    }

    /** Shown now (counted once a day). */
    @Synchronized fun shown(key: String, now: Long = nowMs()) {
        val e = all[key]
        if (e == null) { all[key] = Entry(now, now, 1, 0, 0, false); dirty = true; return }
        // back after resting a week and more: shown afresh
        if (now - e.last > 7 * DAY && e.days >= REST) e.days = 0
        if (day(e.last) != day(now)) { e.days++; dirty = true }
        e.last = now
    }

    @Synchronized fun tapped(key: String, now: Long = nowMs()) {
        val e = all.getOrPut(key) { Entry(now, now, 1, 0, 0, false) }
        e.taps++; e.tappedAt = now; dirty = true
    }

    /** Not for me: never shown again. */
    @Synchronized fun hide(key: String) { all.getOrPut(key) { Entry(nowMs(), nowMs(), 1, 0, 0, false) }.hidden = true; dirty = true }

    /** When it was first shown; null never. */
    @Synchronized fun first(key: String): Long? = all[key]?.first

    @Synchronized fun taps(key: String): Int = all[key]?.taps ?: 0
    @Synchronized fun days(key: String): Int = all[key]?.days ?: 0
    @Synchronized fun tappedAt(key: String): Long = all[key]?.tappedAt ?: 0

    /**
     * How fresh it still is to you, 1 to 0: shown on a day or two unopened it's fine, three .55, four .35, five or more it
     * rests (.05) a week from when it was last shown; opened, .8 (it's yours now, not news); said not for you, 0.
     */
    @Synchronized fun fresh(key: String, now: Long = nowMs()): Double {
        val e = all[key] ?: return 1.0
        if (e.hidden) return 0.0
        if (e.taps > 0) return .8
        return when (e.days) {
            0, 1 -> 1.0
            2 -> .85
            3 -> .55
            4 -> .35
            else -> if (now - e.last > 7 * DAY) 1.0 else .05
        }
    }

    /** Written down (now and then: a shelf shown asks often). */
    @Synchronized fun save() {
        if (!dirty) return
        dirty = false
        val o = JSONObject()
        all.forEach { (k, e) -> o.put(k, JSONArray().put(e.first).put(e.last).put(e.days).put(e.taps).put(e.tappedAt).put(e.hidden)) }
        runCatching { file.getParentFile()?.mkdirs(); file.writeText(o.toString()) }
    }

    companion object {
        const val DAY = 86_400_000L
        const val REST = 5
        private fun day(t: Long) = localDay(t).toEpochDays().toLong()
    }
}

/**
 * The moment (2026-11-07): the time of day, the kind of day, the day's number (INK's issue), and how long you've been
 * away, said as a person would ("since last night", "since tuesday"). The listen page's shelves move to fit it.
 */
object Moment {
    enum class Part { MORNING, DAY, EVENING, LATE }

    data class Now(val part: Part, val friday: Boolean, val weekend: Boolean, val sunday: Boolean, val firstOfMonth: Boolean)

    fun now(at: LocalDateTime = localNow()): Now {
        val part = when (at.hour) { in 5..11 -> Part.MORNING; in 12..16 -> Part.DAY; in 17..21 -> Part.EVENING; else -> Part.LATE }
        val dow = at.dayOfWeek
        return Now(part, dow == DayOfWeek.FRIDAY, dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY,
            dow == DayOfWeek.SUNDAY, at.dayOfMonth == 1)
    }

    /** INK's issue: one a day from ink music's first (2026-10-26 is no. 1), turning at six in the morning. */
    fun issue(at: LocalDateTime = localNow()): Int = (LocalDate(2026, 10, 26).daysUntil(issueDay(at)) + 1).coerceAtLeast(1)

    /** The issue's day: today, or yesterday before six. */
    @OptIn(kotlin.time.ExperimentalTime::class)
    fun issueDay(at: LocalDateTime = localNow()): LocalDate =
        TimeZone.currentSystemDefault().let { z -> (at.toInstant(z) - 6.hours).toLocalDateTime(z).date }

    /** Since when, said: "since this morning", "since last night", "since yesterday", "since tuesday", "since last week". */
    fun since(then: Long, now: Long = nowMs()): String {
        val a = localAt(then)
        val b = localAt(now)
        val days = a.date.daysUntil(b.date).toLong()
        return when {
            days == 0L && a.hour < 12 && b.hour >= 12 -> "since this morning"
            days == 0L -> "since earlier"
            days == 1L && a.hour >= 18 -> "since last night"
            days == 1L -> "since yesterday"
            days < 7 -> "since " + a.dayOfWeek.name.lowercase()
            days < 14 -> "since last week"
            else -> "since you were last here"
        }
    }
}
