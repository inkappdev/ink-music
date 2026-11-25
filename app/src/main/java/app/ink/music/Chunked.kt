package app.ink.music

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener

/**
 * A YouTube song's audio asked for in pieces of [PIECE] (2026-11-25, after LastWave's ChunkedRangeDataSource, GPL-3.0):
 * googlevideo serves an open-ended range for a while and then slows it or turns it away, and a tester's songs took
 * 15-30 s to start on five phones, or never, and wouldn't crossfade (its reading of each song's end waits on the same).
 * Each piece its own bounded ask, one after another as the player reads; the end said only at the file's real end (its
 * size from Content-Range, or a 416 past its last byte), never at a piece's, and a piece that drops partway carried on
 * from the byte it reached. Anything not googlevideo's goes straight through.
 */
class Chunked(private val upstream: DataSource) : DataSource {
    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource() = Chunked(upstream.createDataSource())
    }

    private var spec: DataSpec? = null
    private var chunked = false
    private var position = 0L          // the next byte of the file to give
    private var end = C.LENGTH_UNSET.toLong()   // one past the last byte wanted (the ask's, else the file's), once known
    private var left = 0L              // what's still to come of the piece open now
    private var open = false

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        chunked = dataSpec.uri.host?.endsWith("googlevideo.com") == true &&
            (dataSpec.length == C.LENGTH_UNSET.toLong() || dataSpec.length > PIECE)
        if (!chunked) return upstream.open(dataSpec)
        position = dataSpec.position
        end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.position + dataSpec.length else C.LENGTH_UNSET.toLong()
        if (!openPiece()) return 0
        return if (end != C.LENGTH_UNSET.toLong()) end - dataSpec.position else C.LENGTH_UNSET.toLong()
    }

    /** The next piece opened; false only at the file's real end. */
    private fun openPiece(): Boolean {
        if (open) { runCatching { upstream.close() }; open = false }
        if (end != C.LENGTH_UNSET.toLong() && position >= end) return false
        val s = spec ?: return false
        val length = if (end != C.LENGTH_UNSET.toLong()) minOf(PIECE, end - position) else PIECE
        val piece = s.buildUpon().setPosition(position).setLength(length).build()
        val got = try { upstream.open(piece) } catch (e: HttpDataSource.InvalidResponseCodeException) {
            runCatching { upstream.close() }
            // past the last byte of a file whose size we hadn't: that's its end
            if (e.responseCode == 416 && end == C.LENGTH_UNSET.toLong()) return false
            throw e
        }
        open = true
        // the file's whole size, from the piece's Content-Range ("bytes 0-524287/3912034"), the first time
        if (end == C.LENGTH_UNSET.toLong()) {
            (upstream.responseHeaders.entries.firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }?.value?.firstOrNull())
                ?.substringAfterLast('/')?.toLongOrNull()?.let { end = it }
        }
        left = if (got != C.LENGTH_UNSET.toLong()) got else length
        return true
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!chunked) return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        var empty = 0
        while (true) {
            if (left <= 0 || !open) {
                if (!openPiece()) return C.RESULT_END_OF_INPUT
            }
            val n = upstream.read(buffer, offset, minOf(length.toLong(), left).toInt())
            if (n == C.RESULT_END_OF_INPUT) {
                // the piece closed early (a dropped connection) or at its end: on from the byte we have
                left = 0
                // a size never learned and a piece that gives nothing twice: the end; a size known and pieces that keep
                // giving nothing: broken, said so (an end here would skip to the next song halfway through)
                if (++empty >= 2 && end == C.LENGTH_UNSET.toLong()) return C.RESULT_END_OF_INPUT
                if (empty >= 3) throw java.io.IOException("googlevideo gave nothing at byte $position of $end")
                continue
            }
            position += n; left -= n
            return n
        }
    }

    override fun getUri() = if (chunked) spec?.uri else upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        // always: a piece whose open failed still holds what it began (DefaultDataSource takes its source before)
        spec = null; left = 0; open = false
        upstream.close()
    }

    companion object {
        /** A piece: 512 KB, InnerTube's own limit for these clients (LastWave's measure), ~26 s of a 160k song. */
        const val PIECE = 512L * 1024
    }
}
