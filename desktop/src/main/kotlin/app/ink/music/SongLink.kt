package app.ink.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * A song as a link to send (the computer's "copy link", 2026-11-09): the phone's own (app's ShareCard.link and short),
 * made the same way so a song is the same link from either. inkhome.app/s/<its Apple id>.<its Deezer id> (found by its
 * artist and title, a few seconds at most; Deezer's alone where Apple hasn't it), else /s/y/<its YouTube id> for a
 * YouTube song, else the long inkhome.app/song/<artist-title>?… the site finds the rest from. A station's or an
 * episode's has none. Keep in step with the phone's ShareCard.
 */
object SongLink {
    fun long(s: Song): String? {
        if (s.station || s.episode || s.title.isBlank() || s.artist.isBlank()) return null
        val artist = s.artist.substringBefore(", ")
        val words = java.text.Normalizer.normalize("$artist ${s.title}", java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "").lowercase().replace(Regex("\\s*[(\\[].*?[)\\]]"), "")
            .replace(Regex("[^a-z0-9]+"), "-").trim('-').take(80).ifEmpty { "song" }
        fun q(v: String) = java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20")
        val params = buildList {
            add("t=" + q(s.title)); add("a=" + q(artist))
            if (s.album.isNotBlank()) add("r=" + q(s.album))
            if (s.duration > 0) add("d=${s.duration / 1000}")
            if (s.tube) add("y=" + q(s.id.removePrefix("y")))
        }
        return "https://inkhome.app/song/$words?" + params.joinToString("&")
    }

    suspend fun short(s: Song): String? = withContext(Dispatchers.IO) { coroutineScope {
        val long = long(s) ?: return@coroutineScope null
        val artist = s.artist.substringBefore(", ")
        fun fold(v: String) = java.text.Normalizer.normalize(v, java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            .lowercase().replace(Regex("\\s*[(\\[].*?[)\\]]"), "").replace(Regex("[^a-z0-9]+"), " ").trim()
        fun same(a: String, b: String) = fold(a).let { x -> fold(b).let { y -> x.isNotEmpty() && (x == y || x.startsWith("$y ") || y.startsWith("$x ")) } }
        fun get(url: String): String? {
            val c = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 2_500; c.readTimeout = 3_000
            return try { if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null } finally { c.disconnect() }
        }
        val term = java.net.URLEncoder.encode("$artist ${s.title}", "UTF-8")
        val deezer = async {
            runCatching {
                val r = org.json.JSONObject(get("https://api.deezer.com/search?q=$term&limit=10") ?: return@runCatching null).optJSONArray("data") ?: return@runCatching null
                (0 until r.length()).map { r.getJSONObject(it) }
                    .firstOrNull { same(it.optJSONObject("artist")?.optString("name").orEmpty(), artist) && same(it.optString("title"), s.title) }?.optLong("id")?.takeIf { it > 0 }
            }.getOrNull()
        }
        val apple = runCatching {
            val shop = java.util.Locale.getDefault().country.lowercase().takeIf { it.length == 2 } ?: "us"
            val r = org.json.JSONObject(get("https://itunes.apple.com/search?term=$term&entity=song&limit=10&country=$shop") ?: return@runCatching null)
                .optJSONArray("results") ?: return@runCatching null
            (0 until r.length()).map { r.getJSONObject(it) }
                .firstOrNull { same(it.optString("artistName"), artist) && same(it.optString("trackName"), s.title) }?.optLong("trackId")?.takeIf { it > 0 }
        }.getOrNull()
        val dz = deezer.await()
        when {
            // never Apple's id alone: Apple turns the site away (the phone's, 2026-11-09); Deezer's always goes with it
            apple != null && dz != null -> "https://inkhome.app/s/$apple.$dz"
            dz != null -> "https://inkhome.app/s/d/$dz"
            s.tube -> "https://inkhome.app/s/y/" + s.id.removePrefix("y")
            else -> long
        }
    } }

    /** The link onto the clipboard; whether there was one. */
    suspend fun copy(s: Song): Boolean {
        val url = short(s) ?: return false
        withContext(Dispatchers.Main) { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(url), null) }
        return true
    }

    /** A file of yours on this computer (not the server's, YouTube's, a station's, an episode's), where it is. */
    fun file(s: Song): java.io.File? =
        s.path.takeIf { !s.server && !s.tube && !s.station && !s.episode && it.isNotEmpty() }?.let { java.io.File(it) }?.takeIf { it.isFile }

    /** The file shown where it is, picked out: Explorer, the Finder; on Linux its folder opened. */
    fun reveal(f: java.io.File) {
        runCatching {
            when {
                Home.windows -> ProcessBuilder("explorer.exe", "/select,", f.absolutePath).start()
                Home.mac -> ProcessBuilder("open", "-R", f.absolutePath).start()
                else -> ProcessBuilder("xdg-open", f.parentFile.absolutePath).start()
            }
        }
    }
}
