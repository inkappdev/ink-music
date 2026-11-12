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
    data class Mine(val id: String, val name: String, val songs: List<Song>)

    const val PREFIX = "here:"
    private val file get() = File(Home.dir, "lists.json")
    val all = MutableStateFlow(read())

    private fun read(): List<Mine> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            val songs = o.optJSONArray("songs")?.let { s -> (0 until s.length()).mapNotNull { runCatching { songOf(s.getJSONObject(it)) }.getOrNull() } }.orEmpty()
            Mine(o.getString("id"), o.getString("name"), songs)
        }
    }.getOrDefault(emptyList())

    private fun save() = runCatching {
        file.writeText(JSONArray(all.value.map { m ->
            JSONObject().put("id", m.id).put("name", m.name).put("songs", JSONArray(m.songs.map { it.json() }))
        }).toString())
    }

    fun of(id: String): Mine? = all.value.firstOrNull { it.id == id }

    /** A new list with these songs in it. */
    fun make(name: String, songs: List<Song>): Mine {
        val m = Mine(PREFIX + System.currentTimeMillis(), name, songs.filter(::fits).distinctBy { it.id })
        all.value = all.value + m; save()
        return m
    }

    /** These songs onto the end of a list (each once). */
    fun add(id: String, songs: List<Song>) {
        all.value = all.value.map { m -> if (m.id != id) m else m.copy(songs = (m.songs + songs.filter(::fits)).distinctBy { it.id }) }
        save()
    }

    /** The song at `index` out of a list. */
    fun remove(id: String, index: Int) {
        all.value = all.value.map { m -> if (m.id != id) m else m.copy(songs = m.songs.filterIndexed { i, _ -> i != index }) }
        save()
    }

    fun delete(id: String) { all.value = all.value.filter { it.id != id }; save() }

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

    /** Written to a file chosen with the system's own save dialog (named for the list). */
    fun save(name: String, ext: String, body: String) {
        val d = java.awt.FileDialog(null as java.awt.Frame?, "Save $name", java.awt.FileDialog.SAVE)
        d.file = name.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().ifEmpty { "playlist" } + "." + ext
        d.isVisible = true
        val f = d.file ?: return
        runCatching { File(d.directory, if (f.endsWith(".$ext")) f else "$f.$ext").writeText(body) }
    }

    fun copy(body: String) = runCatching {
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(body), null)
    }
}
