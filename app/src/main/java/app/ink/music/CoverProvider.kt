package app.ink.music

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Covers for whatever shows a song outside ink music: Android Auto's screens, the notification and
 * the lock screen. "content://<app id>.covers/<song id>": the song's record's cover as a JPEG,
 * made once a record and kept in the cache; a station with no cover, its sleeve ([StationSleeve]).
 * "…/art?u=<link>": the cover a station gave for the song it's playing ([OnAir]), only a link it
 * gave. In the player's process, where the library is read for the car. Covers only: nothing else
 * is told (no query answers).
 */
class CoverProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val c = context ?: return null
        val id = uri.lastPathSegment ?: return null
        val dir = File(c.cacheDir, "covers").apply { mkdirs() }
        if (id == "art") {
            val link = uri.getQueryParameter("u")?.takeIf { it in OnAir.arts } ?: return null
            return kept(File(dir, "art-" + link.hashCode().toUInt().toString(16) + ".jpg")) { runCatching { Covers.fetch(link) }.getOrNull() }
        }
        Shelf.load(c)
        val song = Library.song(id) ?: return null
        return kept(File(dir, song.albumId.filter { it.isLetterOrDigit() || it == '-' || it == '_' } + ".jpg")) {
            runBlocking { Covers.of(c, song, 480) }?.image?.asAndroidBitmap() ?: if (song.station) StationSleeve.draw(c, song.id, song.title, 480) else null
        }
    }

    /** The JPEG kept at `file`, made once. */
    private fun kept(file: File, make: () -> Bitmap?): ParcelFileDescriptor? {
        if (!file.exists()) {
            val bmp = make() ?: return null
            val tmp = File(file.parentFile, file.name + ".part")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            tmp.renameTo(file)
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/jpeg"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = BuildConfig.APPLICATION_ID + ".covers"
        fun of(songId: String): Uri = Uri.parse("content://$AUTHORITY/" + Uri.encode(songId))
        /** A cover a station gave, by its link. */
        fun art(link: String): Uri = Uri.parse("content://$AUTHORITY/art").buildUpon().appendQueryParameter("u", link).build()
        /** The link in an [art] address, or null. */
        fun linkOf(uri: Uri?): String? = uri?.takeIf { it.authority == AUTHORITY && it.lastPathSegment == "art" }?.getQueryParameter("u")
    }
}
