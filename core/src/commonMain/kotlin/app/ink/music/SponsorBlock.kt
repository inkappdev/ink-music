package app.ink.music

/**
 * SponsorBlock's marks of where a YouTube video isn't music (2026-11-11, after SimpMusic): its community's "music_offtopic"
 * segments (a music video's talking intro, a skit, the credits after), skipped as they come so a video plays as the song.
 * Nothing else of theirs (sponsors, intros) is skipped. Asked the private way: by the first four characters of the
 * video id's SHA-256, the answer every video sharing them, ours picked out here; so the id itself never leaves.
 * Blocking; kept for the run.
 */
object SponsorBlock {
    private val kept = HashMap<String, List<LongRange>>()

    /** The parts of `video` (a YouTube id, no "y") that aren't music, in ms, in order; none where nobody's marked any. */
    fun notMusic(video: String): List<LongRange> {
        synchronized(kept) { kept[video]?.let { return it } }
        val hash = sha256(video.encodeToByteArray()).hex()
        val got = runCatching {
            val r = http("https://sponsor.ajay.app/api/skipSegments/${hash.take(4)}?categories=" + urlEncode("[\"music_offtopic\"]"),
                headers = mapOf("User-Agent" to "ink music"), connectMs = 5_000, readMs = 8_000)
            // 404: nothing marked for any of them
            if (r.code == 404) return@runCatching emptyList()
            if (r.code !in 200..299) return emptyList()   // a bad moment: not kept, asked again next time
            val all = JSONArray(r.text)
            (0 until all.length()).map { all.getJSONObject(it) }.firstOrNull { it.optString("videoID") == video }
                ?.optJSONArray("segments")?.let { a ->
                    (0 until a.length()).map { a.getJSONObject(it) }
                        .filter { it.optString("category") == "music_offtopic" && it.optString("actionType", "skip") == "skip" }
                        .mapNotNull { s ->
                            val seg = s.optJSONArray("segment") ?: return@mapNotNull null
                            val from = (seg.optDouble(0) * 1000).toLong(); val to = (seg.optDouble(1) * 1000).toLong()
                            // a mark under a second and a half isn't worth a jump
                            if (to - from >= 1_500) from..to else null
                        }.sortedBy { it.first }
                }.orEmpty()
        }.getOrElse { return emptyList() }
        synchronized(kept) { kept[video] = got }
        return got
    }
}
