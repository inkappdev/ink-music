package app.ink.music

import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Playlists kept on this computer (2026-11-11, a tester: "create one local playlist for Rock, another for Pop ... select
 * multiple tracks at once and move them into newly created local playlists"; and lists of online songs kept inside ink
 * music): no server or account needed, any song in them (your files, your server's, YouTube Music's), each kept whole,
 * so a list plays with no signal for what's on the computer. In lists.json; a list's id "here:" and a number.
 */
object HereLists {
    /** A list: `at` when it last changed (its name, its songs, their order), the latest change winning in a sync with the phone. */
    data class Mine(val id: String, val name: String, val songs: List<Song>, val at: Long = 0)

    const val PREFIX = "here:"
    private val file get() = File(Home.dir, "lists.json")
    val all = MutableStateFlow(read())

    private fun read(): List<Mine> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            val songs = o.optJSONArray("songs")?.let { s -> (0 until s.length()).mapNotNull { runCatching { songOf(s.getJSONObject(it)) }.getOrNull() } }.orEmpty()
            Mine(o.getString("id"), o.getString("name"), songs, o.optLong("at"))
        }
    }.getOrDefault(emptyList())

    private fun save() = runCatching {
        Keep.write(file, JSONArray(all.value.map { m ->
            JSONObject().put("id", m.id).put("name", m.name).put("songs", JSONArray(m.songs.map { it.json() })).put("at", m.at)
        }).toString())
    }

    /** A change made here: the lists it touched stamped now, a deleted one's id marked (lists-gone.json), for the sync. */
    @Synchronized private fun set(v: List<Mine>) {
        val now = System.currentTimeMillis()
        val (changed, gone) = ListsSync.touched(all.value, v, { it.id }) { a, b -> a.name == b.name && a.songs.map { it.id } == b.songs.map { it.id } }
        ListsSync.Gone(Home.dir).add(gone, now)
        all.value = v.map { if (it.id in changed) it.copy(at = now) else it }
        save()
    }

    fun of(id: String): Mine? = all.value.firstOrNull { it.id == id }

    /** A new list with these songs in it. */
    fun make(name: String, songs: List<Song>): Mine {
        val m = Mine(PREFIX + System.currentTimeMillis(), name, songs.filter(::fits).distinctBy { it.id })
        set(all.value + m)
        return of(m.id) ?: m
    }

    /** The list every track heard in a DJ set goes into (SetHeard), made the first time there's one. */
    const val DJ_SETS = PREFIX + "djsets"

    /** A song heard in a DJ set, onto the end of "from dj sets" (made if it isn't there; each song once). */
    fun fromSet(s: Song) {
        if (all.value.none { it.id == DJ_SETS }) set(all.value + Mine(DJ_SETS, "from dj sets", emptyList()))
        add(DJ_SETS, listOf(s))
    }

    /** These songs onto the end of a list (each once). */
    fun add(id: String, songs: List<Song>) =
        set(all.value.map { m -> if (m.id != id) m else m.copy(songs = (m.songs + songs.filter(::fits)).distinctBy { it.id }) })

    /** The song at `index` out of a list. */
    fun remove(id: String, index: Int) =
        set(all.value.map { m -> if (m.id != id) m else m.copy(songs = m.songs.filterIndexed { i, _ -> i != index }) })

    /** Every song picked out of a list at once (a list holds each song once). */
    fun removeAll(id: String, songs: Collection<String>) =
        set(all.value.map { m -> if (m.id != id) m else m.copy(songs = m.songs.filter { it.id !in songs }) })

    fun delete(id: String) = set(all.value.filter { it.id != id })

    /** A list's name changed (2026-11-17, a tester: lists couldn't be renamed). */
    fun rename(id: String, name: String) {
        val n = name.trim().ifEmpty { return }
        set(all.value.map { m -> if (m.id != id) m else m.copy(name = n) })
    }

    /** A copy of a list, its name "<name> copy" (a second "copy 2" and so on), put just after it. */
    fun duplicate(name: String, songs: List<Song>, after: String? = null): Mine {
        var n = "$name copy"; var k = 2
        while (all.value.any { it.name == n }) n = "$name copy ${k++}"
        var t = System.currentTimeMillis()
        while (all.value.any { it.id == PREFIX + t }) t++
        val m = Mine(PREFIX + t, n, songs.filter(::fits).distinctBy { it.id })
        val at = all.value.indexOfFirst { it.id == after }
        set(if (at < 0) all.value + m else all.value.toMutableList().apply { add(at + 1, m) })
        return of(m.id) ?: m
    }

    // ---- kept in step with the phone's (Sync) ----

    /** These lists as the phone's told them ([ListsSync.part]). */
    fun syncPart(wire: (Song) -> JSONObject): JSONObject =
        ListsSync.part(all.value.map { ListsSync.Wired(it.id, it.name, it.songs.map(wire), it.at) }, ListsSync.Gone(Home.dir).read())

    /**
     * The phone's lists taken in: each the one changed last, as it was. A song of theirs is the same one here where it
     * can be (`song`: a server's or YouTube's by id, a file by its name and artist), else kept as it was told, so the
     * list goes back whole: a file only the phone has shows here and doesn't play.
     */
    @Synchronized fun takeSync(part: JSONObject, song: (JSONObject) -> Song?) {
        val gone = ListsSync.Gone(Home.dir)
        val (theirs, theirGone) = ListsSync.read(part)
        val mine = all.value
        val m = ListsSync.merge(mine.map { ListsSync.Wired(it.id, it.name, it.songs.map(SyncHub::wire), it.at) }, gone.read(), theirs, theirGone)
        if (!m.changed) return
        val byId = mine.associateBy { it.id }
        gone.write(m.gone)
        all.value = m.lists.map { k ->
            val l = k.list
            if (!k.theirs) byId.getValue(l.id)
            else Mine(l.id, l.name, l.songs.mapNotNull { o -> runCatching { song(o) ?: away(o) }.getOrNull() }.filter(::fits).distinctBy { it.id }, l.at)
        }
        save()
    }

    /** A song in a list from the phone that isn't here (its own file): kept by what it's called, with nowhere to play from. */
    private fun away(o: JSONObject): Song? {
        val id = o.optString("id").ifEmpty { return null }
        val artist = o.optString("artist")
        return Song(id, "", o.optString("title"), artist, o.optString("album"), o.optString("albumId").ifEmpty { id },
            o.optString("albumArtist").ifEmpty { artist }, o.optLong("duration"), 0, 0, 0, art = o.optString("art").ifEmpty { null })
    }

    // a station or an episode isn't a song to keep in a list
    private fun fits(s: Song) = !s.station && !s.episode
}

/**
 * A list written out (2026-11-11, a tester: "export them in formats such as TXT, CSV ... or copy link applied to entire
 * playlists"): as words, a song a line ("artist - title"); as a spreadsheet's CSV (title, artist, record, length, link);
 * or every song's link, a line each, onto the clipboard.
 */
object ListOut {
    fun text(songs: List<Song>) = songs.joinToString("\n") { "${it.artist} - ${it.title}" }

    fun csv(songs: List<Song>): String {
        fun q(v: String) = "\"" + v.replace("\"", "\"\"") + "\""
        return (listOf("title,artist,record,length,link") + songs.map { s ->
            listOf(q(s.title), q(s.artist), q(s.album), q(clock(s.duration)), q(SongLink.long(s).orEmpty())).joinToString(",")
        }).joinToString("\n")
    }

    fun links(songs: List<Song>) = songs.mapNotNull { SongLink.long(it) }.joinToString("\n")

    /** Several lists as .inkmusic files into a folder chosen once (a list's name its file's; one there already kept). */
    fun saveAll(lists: List<HereLists.Mine>) = chooseFolder { dir ->
        lists.forEach { m ->
            val base = m.name.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().ifEmpty { "playlist" }
            var f = File(dir, "$base.inkmusic"); var k = 2
            while (f.exists()) f = File(dir, "$base ${k++}.inkmusic")
            runCatching { f.writeText(inkmusic(m.name, m.songs)) }
        }
    }

    /** Written to a file chosen with the system's own save dialog (named for the list). */
    fun save(name: String, ext: String, body: String) {
        val d = java.awt.FileDialog(null as java.awt.Frame?, "Save $name", java.awt.FileDialog.SAVE)
        d.file = name.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().ifEmpty { "playlist" } + "." + ext
        d.isVisible = true
        val f = d.file ?: return
        runCatching { File(d.directory, if (f.endsWith(".$ext")) f else "$f.$ext").writeText(body) }
    }

    private fun esc(v: String) = v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun q(v: String) = java.net.URLEncoder.encode(v, "UTF-8")

    /** A page of the list, each song a link to its page on inkhome.app (2026-11-12, a tester). */
    fun html(name: String, songs: List<Song>): String = page(name, songs.joinToString("\n") { s ->
        val link = SongLink.long(s)
        val words = "${esc(s.artist)} &mdash; ${esc(s.title)}"
        "<li>${if (link != null) "<a href=\"${esc(link)}\">$words</a>" else words}</li>"
    }.let { "<ol>\n$it\n</ol>" })

    /**
     * The whole list on one page to scroll (2026-11-12, a tester's "super HTML"): each song with its cover and where to
     * find it: Spotify, Apple Music, YouTube Music, YouTube, Tidal, Deezer, Amazon Music, SoundCloud, Bandcamp, ink
     * music. Searches by artist and title (a YouTube song its own video), so each opens on the song or near it.
     */
    fun htmlAll(name: String, songs: List<Song>): String = page(name, songs.joinToString("\n") { s ->
        val term = "${s.artist.substringBefore(", ")} ${s.title}"
        val yt = s.id.removePrefix("y").takeIf { s.tube }
        val places = listOfNotNull(
            "spotify" to "https://open.spotify.com/search/${q(term).replace("+", "%20")}",
            "apple music" to "https://music.apple.com/us/search?term=${q(term)}",
            "youtube music" to (yt?.let { "https://music.youtube.com/watch?v=$it" } ?: "https://music.youtube.com/search?q=${q(term)}"),
            "youtube" to (yt?.let { "https://www.youtube.com/watch?v=$it" } ?: "https://www.youtube.com/results?search_query=${q(term)}"),
            "tidal" to "https://listen.tidal.com/search?q=${q(term)}",
            "deezer" to "https://www.deezer.com/search/${q(term).replace("+", "%20")}",
            "amazon music" to "https://music.amazon.com/search/${q(term).replace("+", "%20")}",
            "soundcloud" to "https://soundcloud.com/search?q=${q(term)}",
            "bandcamp" to "https://bandcamp.com/search?q=${q(term)}",
            SongLink.long(s)?.let { "ink music" to it },
        )
        val art = s.art?.takeIf { it.startsWith("http") }?.let { "<img src=\"${esc(it)}\" alt=\"\" loading=\"lazy\">" } ?: "<div class=\"none\"></div>"
        "<section>$art<div><h2>${esc(s.title)}</h2><p>${esc(s.artist)}${if (s.album.isNotBlank()) " &middot; " + esc(s.album) else ""}</p>" +
            "<nav>${places.joinToString(" ") { (w, u) -> "<a href=\"${esc(u)}\">$w</a>" }}</nav></div></section>"
    })

    private fun page(name: String, body: String) = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(name)}</title>
<style>
body { background: #000; color: #f1f0ec; font: 15px/1.5 system-ui, sans-serif; margin: 0 auto; max-width: 900px; padding: 40px 20px; }
h1 { font-size: 44px; text-transform: uppercase; margin: 0 0 6px; } .said { color: #aaa; margin: 0 0 32px; }
a { color: #f1f0ec; } nav a { color: #aaa; margin-right: 12px; text-decoration: none; border-bottom: 1px solid #333; } nav a:hover { color: #fff; }
ol li { margin: 6px 0; } section { display: flex; gap: 18px; padding: 16px 0; border-top: 1px solid #1f1f1f; }
section img, section .none { width: 96px; height: 96px; object-fit: cover; flex: none; background: #111; }
h2 { font-size: 18px; margin: 0; text-transform: uppercase; } section p { color: #aaa; margin: 2px 0 8px; }
footer { color: #555; margin-top: 40px; font-size: 13px; }
</style></head><body>
<h1>${esc(name)}</h1><p class="said">made with ink music</p>
$body
<footer>ink music &middot; inkhome.app/music</footer>
</body></html>
"""

    /**
     * The list as an ink music file (.inkmusic, 2026-11-12, a tester: "a dedicated format ... to share and transfer a
     * complete playlist", and a backup): its name and each song as it is, with what finds it again on another computer.
     */
    fun inkmusic(name: String, songs: List<Song>): String = JSONObject().put("ink", "music").put("version", 1).put("name", name)
        .put("songs", JSONArray(songs.map { it.json() })).toString(2)

    /**
     * An .inkmusic file read as a list of yours: each song as it was where it's here (the same file, a YouTube song),
     * else the one in your library by its title and artist, else found on YouTube Music; one found nowhere left out.
     */
    fun open(f: File): HereLists.Mine? = runCatching {
        val o = JSONObject(f.readText())
        if (o.optString("ink") != "music") return null
        val mine = Library.songs.value
        val byKey = mine.associateBy { Radio.key(it.title, it.artist) }
        val a = o.getJSONArray("songs")
        val songs = (0 until a.length()).mapNotNull { i ->
            val s = runCatching { songOf(a.getJSONObject(i)) }.getOrNull() ?: return@mapNotNull null
            when {
                s.tube || s.server && mine.any { it.id == s.id } -> s
                s.path.isNotEmpty() && File(s.path).exists() -> s
                else -> byKey[Radio.key(s.title, s.artist)] ?: runCatching { YouTube.find(s.title, s.artist) }.getOrNull()
            }
        }
        HereLists.make(o.optString("name").ifBlank { f.nameWithoutExtension }, songs)
    }.getOrNull()

    fun copy(body: String) = runCatching {
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(body), null)
    }
}
