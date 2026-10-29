package app.ink.music

/**
 * What reaches your ears (the phone's Heard, as mpv says it): the format, its depth and rate, where it came from,
 * and even volume's gain from the file's tags.
 */
data class Heard(
    val id: String,           // the song it's for
    val codec: String,        // "flac", "mp3", "opus", "aac"
    val bits: Int,            // bit depth (0: not known, or lossy)
    val rate: Int,            // Hz (0: not known)
    val channels: Int,
    val kbps: Int,            // 0: not known
    val from: String,         // "computer", "server", "youtube", "station"
    val gain: Float?,         // even volume's dB, null when the file has none
) {
    val lossless get() = codec in LOSSLESS
    val hires get() = lossless && (bits > 16 || rate > 48_000)

    /** "flac 24/96", "mp3 320", "opus": the player's word. */
    val word: String get() = when {
        lossless && rate > 0 -> codec + " " + (if (bits > 0) "$bits/" else "") + khz(rate)
        kbps > 0 -> "$codec $kbps"
        else -> codec
    }

    companion object {
        val LOSSLESS = setOf("flac", "alac", "wav")

        fun khz(hz: Int): String = if (hz % 1000 == 0) "${hz / 1000}" else "%.1f".format(java.util.Locale.ROOT, hz / 1000f)
    }
}
