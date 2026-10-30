package app.ink.music

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.json.JSONObject

/**
 * What reaches your ears, as the player reads it when a song starts (2026-10-26, Bronson's pick of three):
 * the stream's own format, how it came (the file as it is, made smaller by the server, YouTube's best or
 * smallest), at which quality and on which network (Quality), and how even volume moved it. The player
 * tells the screens through the session's extras; the player page says it at its top and, for lossless,
 * types it across the cover once.
 */
data class Heard(
    val id: String,           // the song it's for
    val codec: String,        // "flac", "mp3", "opus", "aac"
    val bits: Int,            // bit depth (0: not known, or lossy)
    val rate: Int,            // Hz (0: not known)
    val channels: Int,
    val kbps: Int,            // 0: not known
    val from: String,         // "phone", "server", "youtube", "station"
    val made: String,         // what the server or YouTube did: "" the file as it is, "mp3 320", "opus 96", "best", "smallest"
    val quality: Int,         // Quality's choice it was played at (-1: not one Quality chooses for)
    val metered: Boolean,     // on mobile data when it started
    val gain: Float?,         // even volume's dB, null when off
    // the signal path (2026-10-30): what decoded it, what went to Android, where it went
    val decoder: String = "", // the decoder's name ("c2.android.flac.decoder")
    val outBits: Int = 0,     // what the player sent Android: bit depth (32: float) and rate (0: not known yet)
    val outRate: Int = 0,
    val device: String = "",  // where it plays: "sony wh-1000xm5", "the phone's speaker"
    val kind: String = "",    // "usb", "bluetooth", "wired", "speaker"
    val mixer: Int = 0,       // the rate Android's mixer runs at
    val eq: String = "",      // the equalizer: "" off, else its preset or headphone
    val exact: String = "",   // exact to a usb dac: "" off, "yes 16/44.1", or why not
) {
    val lossless get() = codec in LOSSLESS
    val hires get() = lossless && (bits > 16 || rate > 48_000)

    /** "flac 24/96", "mp3 320", "opus": the player's word. */
    val word: String get() = when {
        lossless && rate > 0 -> codec + " " + (if (bits > 0) "$bits/" else "") + khz(rate)
        kbps > 0 -> "$codec $kbps"
        else -> codec
    }

    fun json(): String = JSONObject().put("id", id).put("codec", codec).put("bits", bits).put("rate", rate).put("channels", channels)
        .put("kbps", kbps).put("from", from).put("made", made).put("quality", quality).put("metered", metered)
        .put("decoder", decoder).put("outBits", outBits).put("outRate", outRate).put("device", device).put("kind", kind)
        .put("mixer", mixer).put("eq", eq).put("exact", exact)
        .apply { gain?.let { put("gain", it.toDouble()) } }.toString()

    companion object {
        private val LOSSLESS = setOf("flac", "alac", "wav")

        fun of(json: String?): Heard? = json?.takeIf { it.isNotEmpty() }?.let { s ->
            runCatching {
                val o = JSONObject(s)
                Heard(o.optString("id"), o.getString("codec"), o.optInt("bits"), o.optInt("rate"), o.optInt("channels"), o.optInt("kbps"),
                    o.optString("from"), o.optString("made"), o.optInt("quality", -1), o.optBoolean("metered"),
                    if (o.has("gain")) o.getDouble("gain").toFloat() else null,
                    o.optString("decoder"), o.optInt("outBits"), o.optInt("outRate"), o.optString("device"), o.optString("kind"),
                    o.optInt("mixer"), o.optString("eq"), o.optString("exact"))
            }.getOrNull()
        }

        /** 44100 -> "44.1", 96000 -> "96". */
        fun khz(hz: Int): String = if (hz % 1000 == 0) "${hz / 1000}" else "%.1f".format(java.util.Locale.ROOT, hz / 1000f)

        fun codecOf(mime: String?): String = when (mime) {
            MimeTypes.AUDIO_FLAC -> "flac"
            MimeTypes.AUDIO_ALAC -> "alac"
            MimeTypes.AUDIO_RAW, MimeTypes.AUDIO_WAV -> "wav"
            MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L2 -> "mp3"
            MimeTypes.AUDIO_OPUS -> "opus"
            MimeTypes.AUDIO_AAC -> "aac"
            MimeTypes.AUDIO_VORBIS -> "ogg"
            else -> mime?.substringAfter('/')?.substringBefore(';')?.lowercase() ?: "audio"
        }

        fun bitsOf(f: Format): Int = when (f.pcmEncoding) {
            C.ENCODING_PCM_8BIT -> 8
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 32
            else -> 0
        }
    }
}
