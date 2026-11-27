package app.ink.music

/**
 * What a song has going for it beyond who found it (2026-11-27, FREQUENCY smarter, steps 5 and 6), each app reading
 * the songs and handing them here:
 * - flow: the next song near the one playing in tempo (half and double counted, as a DJ does), in key, and in loudness,
 *   so something new arrives in a way that makes sense. Tempo and key are read from the audio by each app's Tempos (as
 *   automix's); loudness is Deezer's gain (its bpm reads 0 now, measured 2026-11-27);
 * - aim: a new artist's song known to about as many as the new ones that stuck with you were
 *   ([FrequencyMemory.aim]), neither a giant you've heard of nor so obscure it's rough; a hit (1.5 million
 *   listeners on Last.fm) a little less.
 */
object FrequencyFlow {
    /** A song's sound where it begins and ends: tempo (0 not read), key, and loudness (Deezer's gain in dB, null not known). */
    data class Sound(val bpm: Double = 0.0, val key: Key.Found? = null, val outBpm: Double = bpm, val outKey: Key.Found? = key, val gain: Double? = null)

    /** How far apart two tempos are, half and double counted: 0 the same. */
    fun apart(a: Double, b: Double) = listOf(a, a * 2, a / 2).minOf { kotlin.math.abs(it / b - 1) }

    /** How well `next` follows `now` (its end into the next one's start): about -.25 to +.35. */
    fun follows(now: Sound?, next: Sound?): Double {
        if (now == null || next == null) return 0.0
        var b = 0.0
        if (now.outBpm > 0 && next.bpm > 0) {
            val d = apart(next.bpm, now.outBpm)
            b += when { d <= .04 -> .2; d <= .08 -> .1; d <= .15 -> 0.0; else -> -.1 }
        }
        if (Key.fit(now.outKey, next.key)) b += .1 else if (Key.clash(now.outKey, next.key)) b -= .1
        val g = now.gain; val h = next.gain
        if (g != null && h != null) b += when { kotlin.math.abs(g - h) <= 2 -> .05; kotlin.math.abs(g - h) > 6 -> -.05; else -> 0.0 }
        return b
    }

    /** How near a new artist's song is to where new is aimed (`aim`, log10 of listeners): up to +.3, a hit -.15; unknown 0. */
    fun aimed(listeners: Long?, aim: Double): Double {
        if (listeners == null || listeners <= 0) return 0.0
        val x = kotlin.math.log10(listeners.toDouble())
        val near = .3 * kotlin.math.exp(-(x - aim) * (x - aim) / (2 * .55 * .55))
        return near - (if (listeners >= 1_500_000) .15 else 0.0)
    }

    private val gains = HashMap<String, Double?>()
    private val heard = HashMap<String, Long?>()

    /** A song's loudness by Deezer (its gain, dB; blocking, two reads, kept for the run), or null. */
    fun gain(title: String, artist: String): Double? {
        val k = "$title\u0000$artist".lowercase()
        synchronized(gains) { if (gains.containsKey(k)) return gains[k] }
        val g = runCatching {
            val a = JSONObject(Curation.get("https://api.deezer.com/search/track?q=${urlEncode("artist:\"${artist.substringBefore(", ")}\" track:\"$title\"")}&limit=1"))
                .optJSONArray("data")?.optJSONObject(0) ?: return@runCatching null
            JSONObject(Curation.get("https://api.deezer.com/track/${a.optLong("id")}")).optDouble("gain").takeIf { !it.isNaN() && it != 0.0 }
        }.getOrNull()
        synchronized(gains) { gains[k] = g }
        return g
    }

    /** A song's Last.fm listeners (blocking, one read, kept for the run), or null. */
    fun listeners(title: String, artist: String, key: String): Long? {
        val k = "$title\u0000$artist".lowercase()
        synchronized(heard) { if (heard.containsKey(k)) return heard[k] }
        val l = Gems.heard(title, artist, key)?.listeners
        synchronized(heard) { heard[k] = l }
        return l
    }
}
