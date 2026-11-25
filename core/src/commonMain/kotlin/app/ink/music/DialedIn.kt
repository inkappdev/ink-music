package app.ink.music

import kotlin.io.encoding.Base64

/**
 * DIALED IN (2026-11-17, Bronson's name for it; YouTube Music's speed dial, as Metrolist has it too): nine things you go
 * back to, numbered, at the top of listen. Yours pinned keep their numbers; the rest are what you play at this hour of
 * the week, so a Tuesday afternoon's nine aren't a Friday night's. Shared by the phone and the computer: each hands its
 * listens in and draws the nine its own way (a contact sheet: nine pictures touching, black and white, words over them).
 */
object DialedIn {
    /**
     * A listen as each app keeps it: when, the song, its artist, its record's id ("" or the song's own for a single), and
     * the playlist it was played from ([list]: "kind:id", [listName]; empty when it wasn't).
     */
    data class Listen(val at: Long, val id: String, val artist: String, val record: String, val minute: Int,
                      val list: String = "", val listName: String = "")

    /** A playlist's slot holds its name as the artist and its "kind:id" as the record. */
    enum class Kind(val word: String) { SONG("song"), RECORD("record"), ARTIST("artist"), LIST("playlist") }

    /** A slot: its number (1 to 9), what's in it, and the song that stands for it (its most heard). */
    data class Slot(val n: Int, val key: String, val kind: Kind, val song: String, val artist: String, val record: String, val pinned: Boolean)

    /** One pinned: its number, what it is, and the song that stands for it (kept, so it shows when it isn't heard lately). */
    data class Pin(val n: Int, val key: String, val kind: Kind, val song: String, val artist: String, val record: String)

    const val SLOTS = 9

    /**
     * How much a listen belongs to now ([now], the minute of the week): the same hour 1, an hour off .6, two .25 (as the
     * "this hour" order counts), more on the same kind of day (weekday, weekend); halved every 60 days, so it follows what
     * you play now; and a little for every listen, so the nine fill when this hour has few.
     */
    fun weight(l: Listen, now: Int, nowMs: Long): Double {
        val h = now % (24 * 60) / 60; val lh = l.minute % (24 * 60) / 60
        val d = kotlin.math.abs(lh - h).let { minOf(it, 24 - it) }
        val near = when (d) { 0 -> 1.0; 1 -> .6; 2 -> .25; else -> 0.0 } * (if ((l.minute / (24 * 60) >= 5) == (now / (24 * 60) >= 5)) 1.0 else .4)
        val age = (nowMs - l.at).coerceAtLeast(0) / 86_400_000.0
        return (near + .04) * kotlin.math.exp(-age * kotlin.math.ln(2.0) / 60)
    }

    private fun artistKey(a: String) = "a:" + a.substringBefore(", ").trim().lowercase()

    /**
     * The nine for now. A record heard across two songs or more is the record, else each song's itself; an artist heard
     * across three or more, two of them records, is the artist (all of theirs under them); one slot an artist at most, and
     * three artists at most, so records and songs keep their place (tried on a real year's listens, 2026-11-17: by three
     * records or singles alike, seven of the nine were artists). Pinned ones keep their numbers; [hidden] (said "not
     * here") never come.
     */
    fun pick(listens: List<Listen>, now: Int, nowMs: Long, pins: List<Pin>, hidden: Set<String>): List<Slot> {
        class Group(val key: String, var kind: Kind, val artist: String, val record: String) {
            var score = 0.0
            val songs = HashMap<String, Double>()
            fun top() = songs.maxByOrNull { it.value }?.key.orEmpty()
        }
        // a playlist's songs count for the playlist (2026-11-25, Bronson: "can playlists appear in dialed in?"), not their
        // records; one with a single song heard from it is that song
        val lists = HashMap<String, Group>()
        val artistOf = HashMap<String, String>()
        for (l in listens) {
            if (l.id.isEmpty() || l.list.isEmpty()) continue
            val g = lists.getOrPut("l:" + l.list) { Group("l:" + l.list, Kind.LIST, l.listName.ifEmpty { l.list }, l.list) }
            val w = weight(l, now, nowMs)
            g.score += w; g.songs[l.id] = (g.songs[l.id] ?: 0.0) + w; artistOf[l.id] = l.artist
        }
        // by record first (a single, or none: the song itself)
        val records = HashMap<String, Group>()
        for (l in listens) {
            if (l.id.isEmpty() || l.list.isNotEmpty()) continue
            val single = l.record.isEmpty() || l.record == l.id || l.record == "y" + l.id.removePrefix("y")
            val key = if (single) "s:" + l.id else "r:" + l.record
            val g = records.getOrPut(key) { Group(key, if (single) Kind.SONG else Kind.RECORD, l.artist, if (single) "" else l.record) }
            val w = weight(l, now, nowMs)
            g.score += w; g.songs[l.id] = (g.songs[l.id] ?: 0.0) + w
        }
        // a record with one song heard is that song
        val parts = records.values.map { g ->
            if (g.kind == Kind.RECORD && g.songs.size < 2) Group("s:" + g.top(), Kind.SONG, g.artist, g.record).also { it.score = g.score; it.songs.putAll(g.songs) } else g
        }
        // an artist across three and more, two of them records: the artist too, all of theirs under them (their records and
        // songs stay in the running: one slot an artist takes the first of them, and an artist passed over at three leaves
        // their best record to come)
        val heardLists = lists.values.map { g ->
            if (g.songs.size < 2) Group("s:" + g.top(), Kind.SONG, artistOf[g.top()].orEmpty(), "").also { it.score = g.score; it.songs.putAll(g.songs) } else g
        }
        val byArtist = (parts + heardLists.filter { it.kind == Kind.SONG }).groupBy { artistKey(it.artist) }
        val candidates = byArtist.flatMap { (key, theirs) ->
            if (theirs.size >= 3 && theirs.count { it.kind == Kind.RECORD } >= 2) theirs + Group(key, Kind.ARTIST, theirs.first().artist.substringBefore(", ").trim(), "").also { a ->
                a.score = theirs.sumOf { it.score } * .7
                theirs.forEach { t -> t.songs.forEach { (id, w) -> a.songs[id] = (a.songs[id] ?: 0.0) + w } }
            } else theirs
        }.plus(heardLists.filter { it.kind == Kind.LIST }).sortedByDescending { it.score }
        val pinned = pins.filter { it.n in 1..SLOTS }.associateBy { it.n }
        val taken = pinned.values.map { it.key }.toSet()
        val usedArtists = pinned.values.map { artistKey(it.artist) }.toMutableSet()
        // an artist said not here: none of theirs either
        val free = ArrayDeque(candidates.filter { it.key !in taken && it.key !in hidden && (it.kind == Kind.LIST || artistKey(it.artist) !in hidden) && it.score > 0 })
        val out = ArrayList<Slot>()
        var artists = pinned.values.count { it.kind == Kind.ARTIST }
        for (n in 1..SLOTS) {
            pinned[n]?.let { p -> out += Slot(n, p.key, p.kind, p.song, p.artist, p.record, pinned = true); continue }
            while (free.isNotEmpty()) {
                val g = free.removeFirst()
                if (g.kind == Kind.ARTIST && artists >= 3) continue
                // a playlist's name isn't an artist: it never keeps one out
                if (g.kind != Kind.LIST && !usedArtists.add(artistKey(g.artist))) continue
                if (g.kind == Kind.ARTIST) artists++
                out += Slot(n, g.key, g.kind, g.top(), g.artist, g.record, pinned = false)
                break
            }
        }
        return out
    }

    /** The line under the heading: "tuesday afternoon, what you play now". */
    fun said(now: Int): String {
        val day = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")[now / (24 * 60) % 7]
        val part = when (now % (24 * 60) / 60) { in 5..11 -> "morning"; in 12..16 -> "afternoon"; in 17..21 -> "evening"; else -> "night" }
        return "$day $part, what you play now"
    }

    // the pins and the hidden, kept as one small JSON file by each app

    fun json(pins: List<Pin>, hidden: Set<String>): String = JSONObject()
        .put("pins", JSONArray(pins.map { JSONObject().put("n", it.n).put("key", it.key).put("kind", it.kind.name)
            .put("song", it.song).put("artist", it.artist).put("record", it.record) }))
        .put("hidden", JSONArray(hidden.toList())).toString()

    fun read(text: String?): Pair<List<Pin>, Set<String>> = runCatching {
        val o = JSONObject(text ?: return@runCatching null)
        val p = o.optJSONArray("pins")
        val pins = (0 until (p?.length() ?: 0)).mapNotNull { i ->
            val x = p!!.optJSONObject(i) ?: return@mapNotNull null
            Pin(x.optInt("n"), x.optString("key"), Kind.entries.firstOrNull { it.name == x.optString("kind") } ?: Kind.SONG,
                x.optString("song"), x.optString("artist"), x.optString("record"))
        }
        val h = o.optJSONArray("hidden")
        pins to (0 until (h?.length() ?: 0)).map { h!!.optString(it) }.toSet()
    }.getOrNull() ?: (emptyList<Pin>() to emptySet())
}

/**
 * YouTube Music's mixes as ink shows them (2026-11-17): each its own mark, big over its lead artist's photo. A "My Mix"
 * is numbered as YouTube numbers it, and the number is in its picture's link (the `r=` protobuf, field 1 its kind, 8, then
 * field 2 the number: "CBAE" is 8, 4, "04"), so it's the one on YouTube's own tile; the supermix SUPER; the rest their
 * first word (DISCOVER, REPLAY, NEW, ARCHIVE).
 */
object TubeMixes {
    fun mark(title: String, art: String?): String {
        if (title.startsWith("My Supermix", ignoreCase = true)) return "SUPER"
        number(art)?.let { return it.toString().padStart(2, '0') }
        return title.substringBefore(' ').uppercase()
    }

    fun number(art: String?): Int? = runCatching {
        val r = art?.substringAfter("r=", "")?.substringBefore('&')?.takeIf { it.isNotEmpty() } ?: return null
        val raw = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(r.replace("%3D", ""))
        for (i in 0 until raw.size - 3) if (raw[i] == 0x08.toByte() && raw[i + 1] == 0x08.toByte() && raw[i + 2] == 0x10.toByte())
            return raw[i + 3].toInt().takeIf { it in 1..99 }
        null
    }.getOrNull()

    /** A mix's lead artist, from its line ("The Japanese House, hard life, After, TAMARYN"). */
    fun lead(said: String): String = said.split(", ", " • ", " · ").map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.equals("mix", true) }.orEmpty()
}
