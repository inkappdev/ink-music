package app.ink.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel

/**
 * What's playing on your Discord profile, "listening to ink music" (2026-11-10; after Limusic's, asked of on Reddit):
 * through the Discord app's own local connection (its IPC: a named pipe on Windows, a socket elsewhere), the song,
 * who, its cover where there's a link anyone can open, and how far in. Nothing while paused, in private listening, or
 * switched off; Discord not running, it's looked for again every half minute.
 */
object Discord {
    /**
     * ink music's application on Discord (discord.com/developers, "INK Music", made 2026-11-10): its name is what's
     * shown, "Listening to INK Music", its icon beside it; its art asset "ink" (the icon) where a song's cover has no link.
     */
    private const val APP = "1569768395262533672"

    private val file = File(Home.dir, "discord-off")
    val on = MutableStateFlow(!file.exists())
    fun set(to: Boolean) { on.value = to; runCatching { if (to) file.delete() else file.writeText("") }; nudge() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pipe: Pipe? = null
    @Volatile private var dirty = true
    fun nudge() { dirty = true }

    fun start() = scope.launch {
        if (APP.isEmpty()) return@launch
        // what's shown, said again when the song, playing, or the place in it changes (Discord takes five a 20 s)
        var said: String? = null
        var lastTry = 0L
        var lastAt = 0L
        while (true) {
            delay(1_000)
            val n = Player.now.value
            val show = on.value && !Private.on.value && n != null && n.playing && !n.song.station
            // a jump: where it is against where it should be
            val start = if (show) System.currentTimeMillis() - Player.position.value else 0L
            if (show && kotlin.math.abs(start - lastAt) > 3_000) dirty = true
            val key = if (show) n!!.song.id else null
            if (key == said && !dirty) continue
            if (pipe == null) {
                if (key == null || System.currentTimeMillis() - lastTry < 30_000) { if (key == null) said = null; continue }
                lastTry = System.currentTimeMillis()
                pipe = runCatching { connect() }.getOrNull() ?: continue
            }
            val ok = runCatching { send(if (show) activity(n!!, start) else null) }.isSuccess
            if (!ok) { runCatching { pipe?.close() }; pipe = null; continue }
            said = key; lastAt = start; dirty = false
            delay(4_000)
        }
    }

    private fun activity(n: Player.Now, start: Long): JSONObject = JSONObject().apply {
        val s = n.song
        put("type", 2)   // listening
        put("details", s.title.take(128).padEnd(2))
        if (s.artist.isNotBlank() && s.artist != "unknown") put("state", s.artist.take(128).padEnd(2))
        if (n.duration > 0) put("timestamps", JSONObject().put("start", start).put("end", start + n.duration))
        put("assets", JSONObject().apply {
            put("large_image", runCatching { Covers.link(s) }.getOrNull() ?: "ink")
            if (s.album.isNotBlank()) put("large_text", s.album.take(128).padEnd(2))
        })
        put("buttons", JSONArray().put(JSONObject().put("label", "get ink music").put("url", "https://inkhome.app/music")))
    }

    private fun send(activity: JSONObject?) {
        val args = JSONObject().put("pid", ProcessHandle.current().pid()).put("activity", activity ?: JSONObject.NULL)
        frame(1, JSONObject().put("cmd", "SET_ACTIVITY").put("args", args).put("nonce", java.util.UUID.randomUUID().toString()))
        read()
    }

    // ---- Discord's local connection: frames of an op and a length (little-endian), then JSON ----

    private interface Pipe { fun write(b: ByteArray); fun read(n: Int): ByteArray; fun close() }

    private fun frame(op: Int, body: JSONObject) {
        val json = body.toString().toByteArray()
        val b = ByteBuffer.allocate(8 + json.size).order(ByteOrder.LITTLE_ENDIAN).putInt(op).putInt(json.size).put(json)
        pipe!!.write(b.array())
    }

    private fun read(): JSONObject {
        val h = ByteBuffer.wrap(pipe!!.read(8)).order(ByteOrder.LITTLE_ENDIAN)
        val op = h.int; val len = h.int
        val body = JSONObject(String(pipe!!.read(len)))
        if (op == 2) throw IllegalStateException("closed: $body")
        return body
    }

    /** The first of Discord's ten connections answering, its hello said and heard. */
    private fun connect(): Pipe? {
        for (i in 0..9) {
            val p = open(i) ?: continue
            pipe = p
            try {
                frame(0, JSONObject().put("v", 1).put("client_id", APP))
                val r = read()
                if (r.optString("evt") == "READY") return p
            } catch (_: Exception) {}
            runCatching { p.close() }; pipe = null
        }
        return null
    }

    private fun open(i: Int): Pipe? = if (Home.windows) runCatching {
        val f = RandomAccessFile("\\\\.\\pipe\\discord-ipc-$i", "rw")
        object : Pipe {
            override fun write(b: ByteArray) = f.write(b)
            override fun read(n: Int) = ByteArray(n).also { f.readFully(it) }
            override fun close() = f.close()
        }
    }.getOrNull() else {
        // where the socket is: the runtime folder (Linux), the temp folder (the Mac), or a Flatpak's or Snap's inside it
        val bases = listOfNotNull(System.getenv("XDG_RUNTIME_DIR"), System.getenv("TMPDIR"), System.getenv("TMP"), System.getenv("TEMP"), "/tmp").distinct()
        val inner = listOf("", "app/com.discordapp.Discord/", "snap.discord/", ".flatpak/dev.vencord.Vesktop/xdg-run/", "app/dev.vencord.Vesktop/")
        bases.flatMap { b -> inner.map { File(b, "${it}discord-ipc-$i") } }.firstOrNull { it.exists() }?.let { f ->
            runCatching {
                val ch = SocketChannel.open(java.net.StandardProtocolFamily.UNIX)
                ch.connect(java.net.UnixDomainSocketAddress.of(f.path))
                object : Pipe {
                    override fun write(b: ByteArray) { val buf = ByteBuffer.wrap(b); while (buf.hasRemaining()) ch.write(buf) }
                    override fun read(n: Int): ByteArray {
                        val buf = ByteBuffer.allocate(n)
                        while (buf.hasRemaining()) if (ch.read(buf) < 0) throw java.io.EOFException()
                        return buf.array()
                    }
                    override fun close() = ch.close()
                }
            }.getOrNull()
        }
    }
}
