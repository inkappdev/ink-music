package app.ink.music

/**
 * NTS's infinite mixtapes (2026-11-24): its themed streams that never end, made from its residents' and guests' shows
 * (Slow Focus, Poolside, Sheet Music...), played as stations beside NTS 1 and 2. Its own list, no key
 * (www.nts.live/api/v2/mixtapes): each one's name, a line on it, its stream (MP3, 256k, by a redirect to radiomast) and
 * its pictures. Its streams name nothing (StreamTitle is empty), so a mixtape's own line is what's said under it.
 * Read once a day into the file each app hands over, the file kept read when NTS doesn't answer; failures silent.
 * Plain Kotlin, shared: the phone and the computer each make their own songs of these. Blocking: off the main thread.
 */
object NtsMixtapes {
    private const val LIST = "https://www.nts.live/api/v2/mixtapes"
    private const val DAY = 86_400_000L

    /** One mixtape: `alias` NTS's own id ("slow-focus"), `line` its subtitle, `about` its longer description. */
    data class Mixtape(val alias: String, val title: String, val line: String, val about: String, val stream: String, val art: String) {
        /** Its station's id: "s" as every station's, then NTS's alias ("snts-slow-focus"). */
        val id: String get() = "snts-$alias"
        /** As NTS's live channels are named: "NTS Slow Focus". */
        val name: String get() = "NTS $title"
        /** Its line as said under it: "meditative, relaxing and beatless: ambient, drone and ragas". */
        val said: String get() = line.ifEmpty { about }.trim().removeSuffix(".").lowercase()
    }

    /** The mixtapes in NTS's answer, in its order: only those with a direct stream (not the HLS ones). */
    fun parse(text: String): List<Mixtape> = runCatching {
        val results = JSONObject(text).optJSONArray("results") ?: return emptyList()
        (0 until results.length()).mapNotNull { results.optJSONObject(it) }.mapNotNull { o ->
            val alias = o.optString("mixtape_alias").trim()
            val title = o.optString("title").trim()
            val stream = o.optString("audio_stream_endpoint").trim()
            if (alias.isEmpty() || title.isEmpty() || !stream.startsWith("http") || ".m3u8" in stream.lowercase()) return@mapNotNull null
            val media = o.optJSONObject("media")
            Mixtape(
                alias = alias, title = title, line = o.optString("subtitle").trim(), about = o.optString("description").trim(),
                stream = stream, art = media?.optString("picture_medium_large").orEmpty().ifEmpty { media?.optString("picture_large").orEmpty() },
            )
        }.distinctBy { it.alias }
    }.getOrDefault(emptyList())

    /** What's kept in `file`, as last read (none if never). */
    fun kept(file: File): List<Mixtape> = runCatching { if (file.isFile()) parse(file.readText()) else emptyList() }.getOrDefault(emptyList())

    /** The mixtapes: NTS asked again once the kept list is a day old, else (or if it doesn't answer) the kept list. */
    fun fresh(file: File): List<Mixtape> {
        val kept = kept(file)
        if (kept.isNotEmpty() && nowMs() - file.lastModified() < DAY) return kept
        val asked = runCatching {
            val r = http(LIST, headers = mapOf("User-Agent" to AGENT, "Accept" to "application/json"), connectMs = 8_000, readMs = 15_000)
            if (r.code == 200) r.text else null
        }.getOrNull()
        val read = asked?.let(::parse).orEmpty()
        if (read.isEmpty()) return kept
        runCatching { file.getParentFile()?.mkdirs(); Keep.write(file, asked!!) }
        return read
    }
}
