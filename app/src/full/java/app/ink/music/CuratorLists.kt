package app.ink.music

import android.content.Context
import java.io.File

/**
 * INK's whole lists, through a Spotify sign-in (2026-11-23): a list's public page shows only its first 100 songs (an
 * archive of 700), but a phone signed in to Spotify reads all of it as Spotify's own player does ([Spotify.songs]), so
 * signed in, INK takes from the whole list itself. And the curator's phone (Bronson's: the only one given the key, by
 * `adb shell am start -n app.ink.music/.Main --es curatorKey <key>`) sends each whole list on to inkhome.app, at most
 * once a day, so every INK, signed in or not, has all of it.
 */
object CuratorLists {
    private const val DAY = 24 * 3600 * 1000L
    private fun keyFile(c: Context) = File(c.filesDir, "curator-key")

    fun start(c: Context) {
        val app = c.applicationContext
        SpotifyLists.reader = reader@{ id ->
            if (!Spotify.signedIn()) return@reader null
            kotlinx.coroutines.runBlocking { if (!Spotify.fresh(app)) null else Spotify.songs(id)?.map { it.title to it.artist } }
        }
        SpotifyLists.readWhole = { id, songs -> send(app, id, songs) }
    }

    /** The curator's key, given by adb; blank takes it away. */
    fun setKey(c: Context, key: String) {
        runCatching { if (key.isBlank()) keyFile(c).delete() else keyFile(c).writeText(key.trim()) }
        android.util.Log.i("ink-picks", "curator key ${if (key.isBlank()) "taken away" else "kept"}")
    }

    /** A whole list sent to inkhome.app, with the key, once a day at most; no key, nothing sent. */
    private fun send(c: Context, id: String, songs: List<Pair<String, String>>) {
        val key = runCatching { keyFile(c).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return
        val sent = File(c.filesDir, "curator-sent-$id")
        if (sent.exists() && System.currentTimeMillis() - sent.lastModified() < DAY) return
        val body = org.json.JSONObject().put("songs", org.json.JSONArray(songs.map { (t, a) -> org.json.JSONObject().put("t", t).put("a", a) })).toString()
        runCatching {
            val conn = java.net.URL("https://inkhome.app/lists/$id.json").openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"; conn.doOutput = true; conn.connectTimeout = 10_000; conn.readTimeout = 20_000
            conn.setRequestProperty("Content-Type", "application/json"); conn.setRequestProperty("Authorization", "Bearer $key")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            conn.disconnect()
            android.util.Log.i("ink-picks", "sent $id whole (${songs.size} songs): http $code")
            if (code == 200 || code == 409) sent.writeText(code.toString())
        }.onFailure { android.util.Log.w("ink-picks", "sending $id: $it") }
    }
}
