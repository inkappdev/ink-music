package app.ink.music

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * INK's line for the day, under the issue's number (2026-11-09: "3 critics agree on diiv's zirp!" had led for days,
 * the most agreed-on record staying the most agreed-on). Two parts, each from its own pool, the personal first:
 *  - yours: a year (else a month, else a week) ago today what you were playing, or who you've played most this week
 *    (on a Monday, last week in a line);
 *  - the world's: Friday's new records by your artists, the critics' new picks since you were here, a festival soon with
 *    your artists in it, a station that played one of yours, critics agreeing, the indie chart's top, one of yours picked.
 * On a Sunday the second part is a kindness line instead, from the changelogs' own closing lines.
 * A line once used (its key: the artist, the record, the festival) isn't used again for two weeks; critics agreeing and the
 * chart's top lead two issues at most for one record. Chosen once an issue and kept all day (files/day-note.json).
 */
object DayNote {
    private const val REST = 14   // issues before a key comes round again

    private val kind = listOf(
        "do something nice for someone today. ask them what they've been listening to",
        "do something nice for someone today. burn someone a mix like it's the computer room again",
        "do something nice for someone today. call your grandma, ask her what her favorite record is",
        "do something nice for someone today. go see a small band play",
        "do something nice for someone today. hand them the aux",
        "do something nice for someone today. hold a door, or a playlist",
        "do something nice for someone today. hold the door a second longer",
        "do something nice for someone today. lend someone a record. get it back",
        "do something nice for someone today. lend someone your good headphones for one whole song",
        "do something nice for someone today. let them pick the next song",
        "do something nice for someone today. make them a mix; we do the fades",
        "do something nice for someone today. play their favorite song in the next room",
        "do something nice for someone today. play them the song you can't stop playing",
        "do something nice for someone today. tell a musician their song got you through something",
        "do something nice for someone today. tell them about a band they've never heard of",
        "do something nice for someone today. text a friend a song",
        "do something nice for someone today. text a friend you've lost touch with",
    )

    private class Line(val key: String, val text: String, val most: Int = 0)

    /**
     * Today's line: kept if chosen this issue, else chosen and kept (only once INK's picks are in, so a first look before
     * they come doesn't fix the day without them). Blocking: reads the listening log.
     */
    fun of(
        c: Context, records: List<Picks.Shown>, chart: List<IndieChart.Entry>, friday: Curation.Week?, air: List<Curation.Aired>,
        festivals: List<Festivals.Festival>, since: Long?, t: Taste,
    ): String? {
        val f = File(c.filesDir, "day-note.json")
        val o = runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
        val issue = Moment.issue()
        if (o.optInt("issue") == issue) o.optString("note").takeIf { it.isNotEmpty() }?.let { return it }
        val used = o.optJSONObject("used") ?: JSONObject()
        fun free(l: Line): Boolean {
            val at = used.optJSONArray(l.key) ?: return true
            val issues = (0 until at.length()).map { at.getInt(it) }
            if (l.most > 0) return issues.size < l.most && issues.none { it == issue }
            return issues.none { issue - it < REST }
        }
        val mine = yours(c, t).firstOrNull(::free)
        val now = Moment.now()
        val world = if (now.sunday) Line("kind:$issue", kind[issue % kind.size])
            else world(records, chart, friday, air, festivals, since, t, now).firstOrNull(::free)
        val chosen = listOfNotNull(mine, world)
        val note = chosen.joinToString(". ") { it.text }.ifEmpty { null }?.let { "$it." } ?: return null
        if (records.isEmpty()) return note   // INK's picks not in yet: said, not kept
        chosen.forEach { l -> used.put(l.key, (used.optJSONArray(l.key) ?: JSONArray()).put(issue)) }
        // keys long past their rest forgotten, so the file stays small
        used.keys().asSequence().toList().forEach { k ->
            val a = used.getJSONArray(k)
            if ((0 until a.length()).all { issue - a.getInt(it) > 60 }) used.remove(k)
        }
        runCatching { f.writeText(JSONObject().put("issue", issue).put("note", note).put("used", used).toString()) }
        return note
    }

    /** What you were playing a year, a month or a week ago today, then who you've played most this week. */
    private fun yours(c: Context, t: Taste): List<Line> {
        val heard = History.read(c)
        if (heard.isEmpty()) return emptyList()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        fun day(h: History.Heard) = Instant.ofEpochMilli(h.at).atZone(zone).toLocalDate()
        fun who(list: List<History.Heard>) = list.groupingBy { it.artist.substringBefore(", ") }.eachCount().maxByOrNull { it.value }?.key
        val lines = ArrayList<Line>()
        for ((then, said) in listOf(today.minusYears(1) to "a year ago today", today.minusMonths(1) to "a month ago today", today.minusWeeks(1) to "a week ago today")) {
            val that = heard.filter { day(it) == then }
            if (that.size < 3) continue
            val a = who(that) ?: continue
            lines += Line("ago:$then", "$said you were playing ${a.lowercase()}")
            break
        }
        val monday = today.dayOfWeek == java.time.DayOfWeek.MONDAY
        if (monday) {
            val last = heard.filter { day(it) in today.minusDays(7)..today.minusDays(1) }
            who(last)?.let { a -> if (last.size >= 10) lines += Line("lastweek:$today", "last week: ${last.size} songs, the most of them ${a.lowercase()}") }
        }
        val week = heard.filter { day(it) > today.minusDays(7) }.groupingBy { it.artist.substringBefore(", ") }.eachCount().maxByOrNull { it.value }
        if (week != null && week.value >= 5) lines += Line("week:${week.key.lowercase()}", "you've played ${week.key.lowercase()} ${week.value} times this week")
        return lines
    }

    private fun world(
        records: List<Picks.Shown>, chart: List<IndieChart.Entry>, friday: Curation.Week?, air: List<Curation.Aired>,
        festivals: List<Festivals.Festival>, since: Long?, t: Taste, now: Moment.Now,
    ): List<Line> {
        val lines = ArrayList<Line>()
        // Friday: out today by artists near you, first
        if (now.friday) friday?.releases?.filter { t.of(it.artist) >= .3 }?.sortedByDescending { t.of(it.artist) }?.let { yours ->
            yours.firstOrNull()?.let { r ->
                val more = yours.size - 1
                lines += Line("out:${r.artist.lowercase()}/${r.title.lowercase()}",
                    "new from ${r.artist.lowercase()} today" + if (more > 0) ", and $more more of yours" else "")
            }
        }
        // the critics' new picks since you were here
        if (since != null) records.count { it.pick.at > since }.takeIf { it >= 3 }?.let { n ->
            lines += Line("since:${Moment.issue()}", "$n new picks from the critics ${Moment.since(since)}")
        }
        // a festival in the next month with two or more of yours in it
        val today = LocalDate.now()
        festivals.filter { it.start >= today.toKotlin() && it.start.toJava().toEpochDay() - today.toEpochDay() <= 30 }.mapNotNull { fest ->
            val theirs = fest.artists.count { t.of(it) >= .3 }
            if (theirs >= 2) fest to theirs else null
        }.maxByOrNull { it.second }?.let { (fest, n) ->
            val days = fest.start.toJava().toEpochDay() - today.toEpochDay()
            lines += Line("fest:${fest.name.lowercase()}", "${fest.name.lowercase()} in ${if (days == 0L) "less than a day" else "$days days"}, $n of its lineup are yours")
        }
        // a station that played one of yours, said for the time of day it was heard
        air.filter { t.of(it.artist) >= .5 }.maxByOrNull { t.of(it.artist) }?.let { a ->
            val `when` = when (now.part) { Moment.Part.MORNING -> "this morning"; Moment.Part.DAY -> "this afternoon"; else -> "tonight" }
            lines += Line("air:${a.artist.lowercase()}", "${a.name.lowercase()} played ${a.artist.lowercase()} ${`when`}")
        }
        // the critics agreeing, the chart's top: each record two issues at most
        records.filter { it.by.size >= 2 }.maxByOrNull { it.by.size * 10 + t.of(it.pick.artist) }?.let {
            lines += Line("agree:${Curation.key(it.pick)}", "${it.by.size} critics agree on ${it.pick.artist.lowercase()}'s ${it.pick.title.lowercase()}", most = 2)
        }
        chart.firstOrNull()?.let { lines += Line("chart:${IndieChart.key(it)}", "kexp, kcrw and wxpn can't stop playing ${it.title.lowercase()}", most = 2) }
        records.firstOrNull { t.of(it.pick.artist) >= .5 && it.by.isNotEmpty() }?.let {
            lines += Line("liked:${Curation.key(it.pick)}", "new from ${it.pick.artist.lowercase()}, and ${it.by.first()} likes it")
        }
        return lines
    }

    private fun LocalDate.toKotlin() = kotlinx.datetime.LocalDate(year, monthValue, dayOfMonth)
    private fun kotlinx.datetime.LocalDate.toJava() = LocalDate.of(year, monthNumber, dayOfMonth)
}
