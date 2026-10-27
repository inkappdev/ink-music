package app.ink.music

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import java.io.File

/**
 * How good a stream is, on wifi and on mobile data (a tester's ask, 2026-10-26): original (the file as
 * it is, YouTube's best), high (Navidrome's mp3 320), or data saver (Navidrome's opus 96, YouTube's
 * smallest). Chosen as each song starts, in the player's process, so it's kept in a file both
 * processes read (prefs are cached per process). Not for a speaker, which has its own rules, nor the
 * phone's own files.
 */
object Quality {
    const val ORIGINAL = 0
    const val HIGH = 1
    const val SAVER = 2
    val words = listOf("original", "high", "data saver")

    private fun file(c: Context) = File(c.filesDir, "quality")

    /** (on wifi, on mobile data) */
    fun read(c: Context): Pair<Int, Int> = runCatching {
        file(c).readText().trim().split(",").let { it[0].toInt() to it[1].toInt() }
    }.getOrDefault(ORIGINAL to ORIGINAL)

    fun set(c: Context, wifi: Int, data: Int) = runCatching { file(c).writeText("$wifi,$data") }

    /** On mobile data (a metered network) now. */
    fun metered(c: Context): Boolean {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        return cm?.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
    }

    /** The one for the network the phone is on now: mobile data's when it's metered. */
    fun now(c: Context): Int = read(c).let { if (metered(c)) it.second else it.first }

    /** A Navidrome stream as the quality asks: transcoded (its length estimated, so it seeks), or the file. */
    fun server(uri: Uri, q: Int): Uri = when (q) {
        HIGH -> uri.buildUpon().appendQueryParameter("format", "mp3").appendQueryParameter("maxBitRate", "320")
            .appendQueryParameter("estimateContentLength", "true").build()
        SAVER -> uri.buildUpon().appendQueryParameter("format", "opus").appendQueryParameter("maxBitRate", "96")
            .appendQueryParameter("estimateContentLength", "true").build()
        else -> uri
    }
}
