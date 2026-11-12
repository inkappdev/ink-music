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
    val status = MutableStateFlow("waiting for a song")
    val lastError = MutableStateFlow("")
    fun set(to: Boolean) { on.value = to; runCatching { if (to) file.delete() else file.writeText("") }; nudge() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pipe: Pipe? = null
    @Volatile private var dirty = true
    @Volatile private var forceReconnect = false
    fun nudge() { dirty = true }
    fun reconnect() {
        forceReconnect = true
        dirty = true
        runCatching { pipe?.close() }
        pipe = null
        status.value = "reconnecting"
    }

    fun start() = scope.launch {
        // Keep running even in local-only mode: changing the setting must not leave a dead RPC loop.
        var said: String? = null
        var lastTry = 0L
        var lastAt = 0L
        while (true) {
            delay(1_000)
            val n = Player.now.value
            val show = on.value && !Local.on && !Private.on.value && n != null && n.playing &&
                !n.song.station && !n.song.episode
            val key = if (show) n!!.song.id else null
            if (Local.on) status.value = "disabled by local-files-only mode (restart after changing it)"
            else if (!on.value) status.value = "off"
            else if (Private.on.value) status.value = "paused by private listening"
            else if (n == null || !n.playing) status.value = "waiting for playback"
            // a jump: where it is against where it should be
            val start = if (show) System.currentTimeMillis() - Player.position.value else 0L
            if (show && kotlin.math.abs(start - lastAt) > 3_000) dirty = true
            if (key == said && !dirty) continue
            if (pipe == null) {
                if (key == null) { said = null; continue }
                if (!forceReconnect && System.currentTimeMillis() - lastTry < 5_000) continue
                forceReconnect = false
                lastTry = System.currentTimeMillis()
                status.value = "looking for Discord desktop IPC"
                pipe = runCatching { connect() }.getOrNull()
                if (pipe == null) {
                    status.value = "Discord not found — open the desktop app"
                    continue
                }
            }
            val ok = runCatching { send(if (show) activity(n!!, start) else null) }
                .onFailure { lastError.value = it.message ?: it.javaClass.simpleName }
                .isSuccess
            if (!ok) {
                runCatching { pipe?.close() }
                pipe = null
                status.value = "connection lost; retrying"
                continue
            }
            lastError.value = ""
            status.value = when {
                Local.on -> "local-files-only mode · Discord hidden"
                !on.value -> "off · Discord connected"
                Private.on.value -> "private listening · Discord hidden"
                show -> "connected · showing playback"
                else -> "connected · idle"
            }
            said = key; lastAt = start; dirty = false
            delay(1_000)
        }
    }

    private fun activity(n: Player.Now, start: Long): JSONObject = JSONObject().apply {
        val s = n.song
        put("type", 2)   // listening
        put("details", s.title.take(128).padEnd(2))
        if (s.artist.isNotBlank() && s.artist != "unknown") put("state", s.artist.take(128).padEnd(2))
        if (n.duration > 0) put("timestamps", JSONObject().put("start", start).put("end", start + n.duration))
        // Do not send arbitrary cover URLs as asset keys: Discord only accepts assets uploaded to this app.
        put("buttons", JSONArray().put(JSONObject().put("label", "get ink music").put("url", "https://inkhome.app/music")))
    }

    private fun send(activity: JSONObject?) {
        val args = JSONObject().put("pid", ProcessHandle.current().pid()).put("activity", activity ?: JSONObject.NULL)
        frame(1, JSONObject().put("cmd", "SET_ACTIVITY").put("args", args).put("nonce", java.util.UUID.randomUUID().toString()))
        val response = read()
        if (response.optString("evt") == "ERROR") {
            val data = response.optJSONObject("data")
            throw java.io.IOException(data?.optString("message")?.takeIf { it.isNotBlank() } ?: response.toString())
        }
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
        lastError.value = ""
        for (i in 0..9) {
            val p = open(i) ?: continue
            pipe = p
            try {
                frame(0, JSONObject().put("v", 1).put("client_id", APP))
                val r = read()
                if (r.optString("evt") == "READY") return p
            } catch (e: Exception) { lastError.value = e.message ?: e.javaClass.simpleName }
            runCatching { p.close() }; pipe = null
        }
        return null
    }

    private fun open(i: Int): Pipe? {
        if (Home.windows) return runCatching {
            val f = RandomAccessFile("\\\\.\\pipe\\discord-ipc-$i", "rw")
            object : Pipe {
                override fun write(b: ByteArray) = f.write(b)
                override fun read(n: Int) = ByteArray(n).also { f.readFully(it) }
                override fun close() = f.close()
            }
        }.getOrNull()

        val bases = listOfNotNull(
            System.getenv("XDG_RUNTIME_DIR"),
            System.getenv("UID")?.let { "/run/user/$it" },
            System.getenv("TMPDIR"), System.getenv("TMP"), System.getenv("TEMP"), "/tmp",
        ).distinct()
        val inner = listOf(
            "", "app/com.discordapp.Discord/", "snap.discord/",
            ".flatpak/dev.vencord.Vesktop/xdg-run/", "app/dev.vencord.Vesktop/",
        )
        val candidates = bases.flatMap { base -> inner.map { File(base, "${it}discord-ipc-$i") } }
        for (file in candidates.filter { it.exists() }) {
            val connected = runCatching {
                val ch = SocketChannel.open(java.net.StandardProtocolFamily.UNIX)
                ch.connect(java.net.UnixDomainSocketAddress.of(file.path))
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
            if (connected != null) return connected
        }
        return null
    }
