package app.ink.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * What a receiver says of itself, for the signal path while casting (Bronson, 2026-10-30): any DLNA renderer the
 * formats it takes (its ConnectionManager's GetProtocolInfo); some, live, what they're doing to the sound, each in
 * its maker's own language, only asked, never set:
 * - Denon, Marantz: the control port (23; MS? sound mode, PSMULTEQ/PSDYNEQ Audyssey, PSDYNVOL, SSINFAISFSV the rate
 *   in), the volume from port 8080's status. Tried on the house's AVR-S970H.
 * - Yamaha (MusicCast): its Extended Control (main/getStatus: sound program, pure direct, enhancer, tone, volume in
 *   dB; main/getSignalInfo: what's coming in). From its spec and libraries; untried.
 * - Onkyo, Integra, Pioneer (2016 on): eISCP (port 60128; LMD listening mode, IFA the audio in, ADY Audyssey, ADQ
 *   Dynamic EQ, ADV Dynamic Volume, MVL volume). From the protocol's lists; untried.
 * - Sonos: its RenderingControl (loudness, bass, treble, Trueplay, night mode, speech). From its libraries; untried.
 * - Bluesound and NAD (BluOS), Cambridge Audio (StreamMagic), Sony (its audio service), WiiM, Arylic and Audio Pro
 *   (LinkPlay): what each says is playing, and its sound settings where it says them. From their interfaces; untried.
 * - Google Cast: its own page (eureka_info), Google's list of what Cast plays. Tried on a Chromecast Audio, a Nest Audio.
 */
object Receiver {
    /** A speaker as the apps' casting found it (each app's Casting.Speaker, the same fields). */
    data class Device(
        val kind: String, val address: InetSocketAddress, val maker: String = "", val model: String = "",
        val control: String = "", val volumeControl: String = "", val group: Boolean = false,
    )

    /** Where each app says what went wrong (the phone's log, the computer's). */
    @Volatile var log: (String) -> Unit = {}

    data class Said(
        val takes: List<String> = emptyList(),          // the formats it plays ("flac", "wav", "aac", "mp3")
        val lines: List<Pair<String, String>> = emptyList(), // its live state, as steps: "sound mode" to "stereo"
        val touching: List<String> = emptyList(),       // what it's doing to the sound ("audyssey multeq"); empty: nothing
        val mode: String = "",                          // its sound mode, said in the verdict ("stereo", "pure direct")
        val tells: Boolean = true,                      // it says what it does to the sound (else only what it's getting)
    ) {
        /** It said how it's playing (a maker read live), not only what it takes. */
        val live get() = lines.isNotEmpty()
    }

    suspend fun read(s: Device): Said? = withContext(Dispatchers.IO) {
        if (s.kind == "cast") return@withContext runCatching { cast(s) }.onFailure { log("cast: $it") }.getOrNull()
        if (s.kind != "dlna") return@withContext null
        val host = s.address.hostString
        val takes = runCatching { protocols(s.control.replace("AVTransport", "ConnectionManager")) }.getOrDefault(emptyList())
        val maker = (s.maker + " " + s.model).lowercase()
        val live = runCatching {
            when {
                "denon" in maker || "marantz" in maker -> denon(host)
                "yamaha" in maker -> yamaha(host)
                "onkyo" in maker || "integra" in maker || "pioneer" in maker -> onkyo(host)
                "sonos" in maker -> sonos(s)
                "bluesound" in maker || "nad" in maker.split(' ', ',') || "bluos" in maker -> bluos(host)
                "cambridge" in maker -> streamMagic(host)
                "sony" in maker -> sony(host)
                "wiim" in maker || "linkplay" in maker || "arylic" in maker || "audio pro" in maker -> linkplay(host)
                else -> null
            }
        }.onFailure { log("${s.maker}: $it") }.getOrNull()
        (live ?: Said()).copy(takes = takes)
    }

    private fun onOff(b: Boolean?) = if (b == true) "on" else "off"
    private fun khz(hz: Int) = (if (hz % 1000 == 0) "${hz / 1000}" else "%.1f".format(java.util.Locale.ROOT, hz / 1000f)) + " khz"

    // ---- Denon, Marantz ----

    private fun denon(host: String): Said {
        val codes = telnet(host, listOf("MS?", "PSDYNEQ ?", "PSMULTEQ: ?", "PSDYNVOL ?", "SSINFAISFSV ?"))
        fun code(prefix: String) = Regex("(?m)^${Regex.escape(prefix)}\\s*(.+)$").find(codes)?.groupValues?.get(1)?.trim().orEmpty()
        val mode = code("MS").lowercase()
        val multEq = code("PSMULTEQ:").lowercase().takeUnless { it.isEmpty() || it == "off" }.orEmpty()
        val dynEq = code("PSDYNEQ").takeIf { it.isNotEmpty() }?.let { it == "ON" }
        val dynVol = code("PSDYNVOL").takeIf { it.isNotEmpty() }?.let { it != "OFF" }
        // in hundreds of hertz: 441, 480, 960, 1764
        val rate = code("SSINFAISFSV").toIntOrNull()?.let { it * 100 } ?: 0
        val signal = code("SYSDA").lowercase()
        val db = runCatching {
            Regex("<MasterVolume><value>([^<]+)</value>").find(get("http://$host:8080/goform/formMainZone_MainZoneXmlStatusLite.xml"))?.groupValues?.get(1)
        }.getOrNull()
        val direct = "direct" in mode
        return Said(
            lines = listOfNotNull(
                (listOfNotNull(signal.takeIf { it.isNotEmpty() }, rate.takeIf { it > 0 }?.let(::khz)).joinToString(" · ")).takeIf { it.isNotEmpty() }?.let { "it's getting" to it },
                "sound mode" to mode,
                "audyssey" to if (direct) "bypassed by $mode" else "multeq ${multEq.ifEmpty { "off" }}\ndynamic eq ${onOff(dynEq)}\ndynamic volume ${onOff(dynVol)}",
                db?.let { "volume" to "$it db" },
            ),
            touching = listOfNotNull(
                "audyssey multeq".takeIf { multEq.isNotEmpty() && !direct },
                "dynamic eq".takeIf { dynEq == true && !direct },
                "dynamic volume".takeIf { dynVol == true && !direct },
                "its $mode sound mode".takeIf { mode.isNotEmpty() && !direct && mode != "stereo" },
            ),
            mode = mode,
        )
    }

    /** A Denon's control port: each code asked, every line it answers (it says more than it's asked; all kept). */
    private fun telnet(host: String, codes: List<String>): String = Socket().use { s ->
        s.connect(InetSocketAddress(host, 23), 2_000); s.soTimeout = 700
        val out = StringBuilder(); val buf = ByteArray(2048)
        codes.forEach { c ->
            s.getOutputStream().write((c + "\r").toByteArray()); s.getOutputStream().flush()
            Thread.sleep(250)
            runCatching { val n = s.getInputStream().read(buf); if (n > 0) out.append(String(buf, 0, n)) }
        }
        out.toString().replace('\r', '\n')
    }

    // ---- Yamaha (MusicCast) ----

    private fun yamaha(host: String): Said {
        val st = JSONObject(get("http://$host/YamahaExtendedControl/v1/main/getStatus"))
        val program = st.optString("sound_program").replace('_', ' ')
        val pure = st.optBoolean("pure_direct", false)
        val enhancer = st.optBoolean("enhancer", false)
        val tone = st.optJSONObject("tone_control")
        val toned = tone != null && tone.optString("mode") != "bypass" && (tone.optInt("bass") != 0 || tone.optInt("treble") != 0)
        val db = st.optJSONObject("actual_volume")?.takeIf { it.optString("unit").equals("dB", true) }?.optDouble("value")
        // what's coming in, where the receiver says (not every model)
        val sig = runCatching { JSONObject(get("http://$host/YamahaExtendedControl/v1/main/getSignalInfo")).optJSONObject("audio") }.getOrNull()
        val coming = sig?.let { listOfNotNull(it.optString("format").takeIf { f -> f.isNotBlank() }?.lowercase(), it.optString("fs").takeIf { f -> f.isNotBlank() }?.lowercase()) }
            .orEmpty().joinToString(" · ")
        val mode = if (pure) "pure direct" else program
        return Said(
            lines = listOfNotNull(
                coming.takeIf { it.isNotEmpty() }?.let { "it's getting" to it },
                "sound program" to mode,
                "enhancer" to onOff(enhancer),
                tone?.let { "tone" to (if (toned) "bass ${it.optInt("bass")} · treble ${it.optInt("treble")}" else "flat") },
                db?.let { "volume" to "%.1f db".format(java.util.Locale.ROOT, it) },
            ),
            touching = if (pure) emptyList() else listOfNotNull(
                "enhancer".takeIf { enhancer },
                "tone controls".takeIf { toned },
                "its $program program".takeIf { program.isNotEmpty() && program !in setOf("straight", "2ch stereo") },
            ),
            mode = mode,
        )
    }

    // ---- Onkyo, Integra, Pioneer (eISCP) ----

    private val LISTENING = mapOf("00" to "stereo", "01" to "direct", "02" to "surround", "03" to "film", "04" to "thx", "05" to "action",
        "06" to "musical", "08" to "orchestra", "09" to "unplugged", "0A" to "studio-mix", "0B" to "tv logic", "0C" to "all ch stereo",
        "0D" to "theater-dimensional", "0E" to "enhance", "0F" to "mono", "11" to "pure audio", "12" to "multiplex", "13" to "full mono",
        "14" to "dolby virtual", "40" to "straight decode", "80" to "dolby pl ii movie", "81" to "dolby pl ii music")

    private fun onkyo(host: String): Said {
        val got = iscp(host, listOf("LMDQSTN", "IFAQSTN", "ADYQSTN", "ADQQSTN", "ADVQSTN", "MVLQSTN"))
        val lm = got["LMD"]?.let { LISTENING[it.uppercase()] ?: it.lowercase() }.orEmpty()
        // IFA: input port, format, rate, channels, listening mode, out channels (commas)
        val ifa = got["IFA"]?.split(',').orEmpty()
        val coming = listOfNotNull(ifa.getOrNull(1)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }, ifa.getOrNull(2)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
            ifa.getOrNull(3)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }).joinToString(" · ")
        val ady = got["ADY"]?.let { it != "00" }
        val adq = got["ADQ"]?.let { it != "00" }
        val adv = got["ADV"]?.let { it != "00" }
        // MVL: hex steps, half a dB each on newer models, from -82 dB (said as the receiver's own number where unsure)
        val vol = got["MVL"]?.toIntOrNull(16)
        val direct = lm == "direct" || lm == "pure audio"
        return Said(
            lines = listOfNotNull(
                coming.takeIf { it.isNotEmpty() }?.let { "it's getting" to it },
                lm.takeIf { it.isNotEmpty() }?.let { "listening mode" to it },
                if (ady != null || adq != null || adv != null) "audyssey" to (if (direct) "bypassed by $lm" else "room eq ${onOff(ady)}\ndynamic eq ${onOff(adq)}\ndynamic volume ${onOff(adv)}") else null,
                vol?.let { "volume" to "$it" },
            ),
            touching = if (direct) emptyList() else listOfNotNull(
                "audyssey room eq".takeIf { ady == true },
                "dynamic eq".takeIf { adq == true },
                "dynamic volume".takeIf { adv == true },
                "its $lm mode".takeIf { lm.isNotEmpty() && lm != "stereo" },
            ),
            mode = lm,
        )
    }

    /** eISCP: each question in its packet ("ISCP", the header's size, the data's, version 1), each answer by its code. */
    private fun iscp(host: String, asks: List<String>): Map<String, String> = Socket().use { s ->
        s.connect(InetSocketAddress(host, 60128), 2_000); s.soTimeout = 800
        val out = HashMap<String, String>(); val buf = ByteArray(4096)
        asks.forEach { a ->
            val data = "!1$a\r".toByteArray()
            val head = java.nio.ByteBuffer.allocate(16).put("ISCP".toByteArray()).putInt(16).putInt(data.size).put(1).put(0).put(0).put(0).array()
            s.getOutputStream().write(head + data); s.getOutputStream().flush()
            Thread.sleep(250)
            runCatching {
                val n = s.getInputStream().read(buf)
                if (n > 0) Regex("!1([A-Z]{3})([^\\u001a\\r\\n]*)").findAll(String(buf, 0, n, Charsets.ISO_8859_1)).forEach { out[it.groupValues[1]] = it.groupValues[2] }
            }
        }
        out
    }

    // ---- Sonos ----

    private fun sonos(s: Device): Said {
        val rc = s.volumeControl.ifEmpty { "http://${s.address.hostString}:1400/MediaRenderer/RenderingControl/Control" }
        fun ask(action: String, args: String, out: String) = runCatching {
            Regex("<$out>([^<]*)</$out>").find(soap(rc, "urn:schemas-upnp-org:service:RenderingControl:1", action, args))?.groupValues?.get(1)
        }.getOrNull()
        val loud = ask("GetLoudness", "<InstanceID>0</InstanceID><Channel>Master</Channel>", "CurrentLoudness")?.let { it == "1" }
        val bass = ask("GetBass", "<InstanceID>0</InstanceID>", "CurrentBass")?.toIntOrNull()
        val treble = ask("GetTreble", "<InstanceID>0</InstanceID>", "CurrentTreble")?.toIntOrNull()
        val trueplay = ask("GetRoomCalibrationStatus", "<InstanceID>0</InstanceID>", "RoomCalibrationEnabled")?.let { it == "1" }
        val night = ask("GetEQ", "<InstanceID>0</InstanceID><EQType>NightMode</EQType>", "CurrentValue")?.let { it == "1" }
        val speech = ask("GetEQ", "<InstanceID>0</InstanceID><EQType>DialogLevel</EQType>", "CurrentValue")?.let { it == "1" }
        val toned = (bass ?: 0) != 0 || (treble ?: 0) != 0
        return Said(
            lines = listOfNotNull(
                trueplay?.let { "trueplay" to onOff(it) },
                loud?.let { "loudness" to onOff(it) },
                if (bass != null || treble != null) "tone" to (if (toned) "bass ${bass ?: 0} · treble ${treble ?: 0}" else "flat") else null,
                night?.let { "night sound" to onOff(it) },
                speech?.let { "speech" to onOff(it) },
            ),
            touching = listOfNotNull(
                "trueplay".takeIf { trueplay == true }, "loudness".takeIf { loud == true }, "tone".takeIf { toned },
                "night sound".takeIf { night == true }, "speech enhancement".takeIf { speech == true },
            ),
            mode = "",
        )
    }

    // ---- Bluesound, NAD (BluOS) ----

    /** BluOS's own interface (port 11000, Status): the stream's format and quality, the volume in dB. Untried. */
    private fun bluos(host: String): Said {
        val x = get("http://$host:11000/Status")
        fun tag(t: String) = Regex("<$t[^>]*>([^<]*)</$t>").find(x)?.groupValues?.get(1)?.trim().orEmpty()
        val format = tag("streamFormat").lowercase()
        val quality = tag("quality").lowercase()
        val db = Regex("<volume[^>]*\\bdb=\"([^\"]+)\"").find(x)?.groupValues?.get(1)
        return Said(lines = listOfNotNull(
            format.takeIf { it.isNotEmpty() }?.let { "it's getting" to it },
            quality.takeIf { it.isNotEmpty() }?.let { "quality" to it },
            db?.let { "volume" to "$it db" },
        ), tells = false)
    }

    // ---- Cambridge Audio (StreamMagic) ----

    /** StreamMagic's own interface (smoip): what plays (codec, rate, depth) and the zone's volume. Untried. */
    private fun streamMagic(host: String): Said {
        val play = runCatching { JSONObject(get("http://$host/smoip/zone/play_state")).optJSONObject("data") }.getOrNull()
        val meta = play?.optJSONObject("metadata")
        val zone = runCatching { JSONObject(get("http://$host/smoip/zone/state")).optJSONObject("data") }.getOrNull()
        val coming = listOfNotNull(
            meta?.optString("codec")?.takeIf { it.isNotBlank() }?.lowercase(),
            meta?.optInt("bit_depth", 0)?.takeIf { it > 0 }?.let { "$it bit" },
            meta?.optInt("sample_rate", 0)?.takeIf { it > 0 }?.let(::khz),
        ).joinToString(" · ")
        return Said(lines = listOfNotNull(
            coming.takeIf { it.isNotEmpty() }?.let { "it's getting" to it },
            zone?.optString("source")?.takeIf { it.isNotBlank() }?.let { "input" to it.lowercase() },
            zone?.optInt("volume_percent", -1)?.takeIf { it >= 0 }?.let { "volume" to "$it%" },
        ), tells = false)
    }

    // ---- Sony ----

    /** Sony's own interface (port 10000's audio service): its sound field, Pure Direct, the volume. Untried. */
    private fun sony(host: String): Said {
        fun call(method: String, params: String) = runCatching {
            val c = URL("http://$host:10000/sony/audio").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 2_000; c.readTimeout = 3_000
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write("{\"method\":\"$method\",\"params\":[$params],\"id\":1,\"version\":\"1.1\"}".toByteArray()) }
            JSONObject(c.inputStream.use { it.bufferedReader().readText() }).optJSONArray("result")?.optJSONArray(0)
        }.getOrNull()
        val settings = call("getSoundSettings", "{\"target\":\"\"}")
        fun setting(t: String) = settings?.let { a -> (0 until a.length()).map { a.optJSONObject(it) }.firstOrNull { it?.optString("target") == t }?.optString("currentValue") }
        val field = setting("soundField").orEmpty()
        val pure = setting("pureDirect")?.let { it == "on" }
        val vol = call("getVolumeInformation", "{\"output\":\"\"}")?.optJSONObject(0)?.optInt("volume", -1)?.takeIf { it >= 0 }
        val readable = field.replace(Regex("([a-z])([A-Z0-9])"), "$1 $2").lowercase()
        return Said(
            lines = listOfNotNull(
                readable.takeIf { it.isNotEmpty() }?.let { "sound field" to it },
                pure?.let { "pure direct" to onOff(it) },
                vol?.let { "volume" to "$it" },
            ),
            touching = if (pure == true) emptyList() else listOfNotNull("its $readable sound field".takeIf { readable.isNotEmpty() && "stereo" !in readable }),
            mode = if (pure == true) "pure direct" else readable,
        )
    }

    // ---- WiiM, Arylic, Audio Pro (LinkPlay) ----

    /** LinkPlay's own interface (httpapi.asp, getMetaInfo): the rate, depth and bitrate playing. Untried. */
    private fun linkplay(host: String): Said {
        val m = runCatching { JSONObject(get("http://$host/httpapi.asp?command=getMetaInfo")).optJSONObject("metaData") }.getOrNull()
        val rate = m?.optString("sampleRate")?.toIntOrNull() ?: 0
        val bits = m?.optString("bitDepth")?.toIntOrNull() ?: 0
        val kbps = m?.optString("bitRate")?.toIntOrNull() ?: 0
        val coming = listOfNotNull(bits.takeIf { it > 0 }?.let { "$it bit" }, rate.takeIf { it > 0 }?.let(::khz), kbps.takeIf { it > 0 }?.let { "$it kbps" }).joinToString(" · ")
        return Said(lines = listOfNotNull(coming.takeIf { it.isNotEmpty() }?.let { "it's getting" to it }), tells = false)
    }

    // ---- Google Cast ----

    /** Google Cast's own list of what every Cast device plays (its "supported media" page). */
    private val CAST_TAKES = listOf("flac (to 24/96)", "wav", "aac", "mp3", "opus", "ogg")

    /**
     * A Cast speaker's own page (port 8008's eureka_info, open on the network): its software, and how it's connected,
     * the wifi's strength where it says (a Chromecast Audio does; a Nest doesn't). Its sound settings (full dynamic
     * range, a Nest's bass and treble) it doesn't tell: kept to Google's app. Tried on the house's Chromecast Audio
     * (1.56, -59 dBm) and Nest Audio (3.78).
     */
    private fun cast(s: Device): Said {
        val o = JSONObject(get("http://${s.address.hostString}:8008/setup/eureka_info?params=version,name,build_info,net,wifi"))
        val signal = o.optInt("signal_level", 0).takeIf { it < 0 }
        val link = when {
            o.optBoolean("ethernet_connected") -> "ethernet"
            signal != null -> "wifi · $signal dbm, " + when { signal >= -60 -> "strong"; signal >= -70 -> "good"; signal >= -78 -> "fair"; else -> "weak" }
            else -> "wifi"
        }
        val build = o.optString("cast_build_revision").split('.').take(2).joinToString(".")
        return Said(
            takes = CAST_TAKES,
            lines = listOfNotNull(
                "a group".takeIf { s.group }?.let { it to "every speaker in it plays together, in step" },
                "connected" to link,
                build.takeIf { it.isNotBlank() }?.let { "software" to "google cast $it" },
            ),
        )
    }

    // ---- the standard part ----

    /** The formats a DLNA renderer says it plays (GetProtocolInfo's sink list), by name. */
    private fun protocols(control: String): List<String> {
        val xml = soap(control, "urn:schemas-upnp-org:service:ConnectionManager:1", "GetProtocolInfo", "")
        val sink = Regex("<Sink>([^<]*)</Sink>").find(xml)?.groupValues?.get(1).orEmpty()
        val names = mapOf("flac" to "flac", "x-flac" to "flac", "wav" to "wav", "x-wav" to "wav", "l16" to "wav", "aiff" to "aiff", "x-aiff" to "aiff",
            "mp4" to "aac", "aac" to "aac", "x-m4a" to "aac", "mpeg" to "mp3", "mp3" to "mp3", "ogg" to "ogg", "x-ms-wma" to "wma", "dsd" to "dsd", "x-dsd" to "dsd")
        return sink.split(',').mapNotNull { p -> p.split(':').getOrNull(2)?.takeIf { it.startsWith("audio/") }?.substringAfter('/')?.lowercase()?.substringBefore(';')?.let { names[it] } }
            .distinct().sortedBy { listOf("flac", "wav", "aiff", "dsd", "aac", "mp3", "ogg", "wma").indexOf(it) }
    }

    private fun soap(control: String, service: String, action: String, args: String): String {
        val c = URL(control).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 2_000; c.readTimeout = 3_000
        c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        c.setRequestProperty("SOAPACTION", "\"$service#$action\"")
        c.outputStream.use { it.write(("<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:$action xmlns:u=\"$service\">$args</u:$action></s:Body></s:Envelope>").toByteArray()) }
        return c.inputStream.use { it.bufferedReader().readText() }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 2_000; c.readTimeout = 3_000
        return c.inputStream.use { it.bufferedReader().readText() }
    }
}
