package app.ink.music

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.media3.common.Player
import com.discord.socialsdk.rpc.IDiscordRpcCallback
import com.discord.socialsdk.rpc.IDiscordRpcConnection
import com.discord.socialsdk.rpc.IDiscordRpcService
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/** Android Discord Rich Presence over Discord's exported Binder RPC service. */
object DiscordAndroidPresence {
    private const val TAG = "ink-discord"
    private const val APP_ID = "1569768395262533672"
    private const val RPC_ACTION = "com.discord.socialsdk.rpc.IDiscordRpcService"
    private fun disabledFile(ctx: Context) = File(ctx.filesDir, "discord-presence-off")
    private const val RETRY_MS = 15_000L
    private const val PUSH_MS = 15_000L
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "ink-discord-rpc").apply { isDaemon = true } }
    @Volatile private var context: Context? = null
    @Volatile private var player: Player? = null
    @Volatile private var rpc: IDiscordRpcConnection? = null
    @Volatile private var bound = false
    @Volatile private var connecting = false
    private var serviceConnection: ServiceConnection? = null
    private var lastAttempt = 0L
    private var lastPush = 0L
    private var lastKey = ""
    private var lastActive = false

    fun enabled(ctx: Context): Boolean = !disabledFile(ctx).exists()
    fun setEnabled(ctx: Context, on: Boolean) {
        runCatching { if (on) disabledFile(ctx).delete() else disabledFile(ctx).createNewFile() }
        main.post { tick() }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                events.contains(Player.EVENT_MEDIA_METADATA_CHANGED) ||
                events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) ||
                events.contains(Player.EVENT_IS_PLAYING_CHANGED) ||
                events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) ||
                events.contains(Player.EVENT_POSITION_DISCONTINUITY)
            ) main.post { tick() }
        }
    }
    private val ticker = object : Runnable {
        override fun run() { tick(); main.postDelayed(this, 2_000L) }
    }

    fun start(ctx: Context, p: Player) {
        if (player === p) return
        stop()
        context = ctx.applicationContext
        player = p
        p.addListener(listener)
        main.removeCallbacks(ticker)
        main.post(ticker)
        Log.i(TAG, "Android Discord presence started")
    }

    fun stop() {
        main.removeCallbacks(ticker)
        player?.removeListener(listener)
        player = null
        lastActive = false
        lastKey = ""
        val oldContext = context
        val oldConnection = serviceConnection
        val oldRpc = rpc
        rpc = null
        serviceConnection = null
        bound = false
        connecting = false
        if (oldRpc != null) io.execute {
            runCatching { oldRpc.sendFrame(frame(null)) }
            runCatching { oldRpc.disconnect() }
        }
        if (oldContext != null && oldConnection != null) runCatching { oldContext.unbindService(oldConnection) }
        context = null
    }

    private fun tick() {
        val ctx = context ?: return
        val p = player ?: return
        val item = p.currentMediaItem
        val md = p.mediaMetadata
        val title = md.title?.toString()?.trim().orEmpty()
        val artist = md.artist?.toString()?.trim().orEmpty()
        val album = md.albumTitle?.toString()?.trim().orEmpty()
        val artwork = sequenceOf(md.artworkUri?.toString(), Library.song(item?.mediaId.orEmpty())?.art)
            .filterNotNull().firstOrNull { it.startsWith("https://") || it.startsWith("http://") }
        val active = enabled(ctx) && !Private.on(ctx) && p.isPlaying && item != null &&
            !item.mediaId.startsWith("s") && !item.mediaId.startsWith("e") && title.isNotBlank()
        if (!active) {
            if (lastActive || lastKey.isNotEmpty()) clear()
            lastActive = false
            lastKey = ""
            return
        }
        lastActive = true
        val duration = p.duration.takeIf { it > 0 && it != androidx.media3.common.C.TIME_UNSET } ?: 0L
        val position = p.currentPosition.coerceAtLeast(0L)
        val start = System.currentTimeMillis() - position
        val key = "${item!!.mediaId}|$title|$artist|$album|${duration / 1000}"
        val now = android.os.SystemClock.elapsedRealtime()
        if (rpc == null) {
            if (!connecting && now - lastAttempt >= RETRY_MS) connect(ctx)
            return
        }
        if (key == lastKey && now - lastPush < PUSH_MS) return
        val activity = JSONObject().apply {
            put("type", 2)
            put("name", if (title.isBlank()) "Ink Music" else "Ink Music · ${title.take(96)}")
            put("details", title.take(128))
            val byline = listOf(artist.takeIf { it.isNotBlank() && it != "unknown" }, album.takeIf { it.isNotBlank() })
                .filterNotNull().joinToString(" · ")
            if (byline.isNotBlank()) put("state", byline.take(128))
            put("assets", JSONObject().apply {
                put("large_image", artwork ?: "ink")
                put("large_text", if (album.isNotBlank()) album.take(128) else title.take(128))
                put("small_image", "ink")
                put("small_text", "Ink Music")
            })
            if (duration > 0) put("timestamps", JSONObject().put("start", start / 1000L).put("end", (start + duration) / 1000L))
            put("buttons", JSONArray().put(JSONObject().put("label", "get ink music").put("url", "https://inkhome.app/music")))
        }
        val connection = rpc ?: return
        io.execute {
            val ok = runCatching { connection.sendFrame(frame(activity)); true }
                .onFailure { Log.w(TAG, "Discord presence send failed", it) }.getOrDefault(false)
            main.post {
                if (ok && rpc === connection) {
                    lastKey = key
                    lastPush = android.os.SystemClock.elapsedRealtime()
                } else if (rpc === connection) disconnect()
            }
        }
    }

    private fun clear() {
        val connection = rpc ?: return
        io.execute {
            runCatching { connection.sendFrame(frame(null)) }.onFailure { Log.d(TAG, "Discord clear failed", it) }
            main.post { if (rpc === connection) { lastKey = ""; lastPush = 0L } }
        }
    }

    private fun frame(activity: JSONObject?): String = JSONObject()
        .put("cmd", "SET_ACTIVITY")
        .put("args", JSONObject().put("pid", Process.myPid()).put("activity", activity ?: JSONObject.NULL))
        .put("nonce", UUID.randomUUID().toString()).toString()

    private fun connect(ctx: Context) {
        lastAttempt = android.os.SystemClock.elapsedRealtime()
        val component = findRpcComponent(ctx)
        if (component == null) {
            Log.w(TAG, "Discord RPC service not found; install/open the Discord Android app")
            return
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder == null) { disconnect(); return }
                io.execute {
                    val service = IDiscordRpcService.Stub.asInterface(binder)
                    val callback = object : IDiscordRpcCallback.Stub() {
                        override fun onFrame(frame: String?) { Log.v(TAG, "Discord RPC callback received") }
                        override fun onClose(code: Int, message: String?) {
                            Log.w(TAG, "Discord RPC closed: $code ${message.orEmpty()}")
                            main.post { disconnect() }
                        }
                    }
                    val connected = runCatching { service.connect(APP_ID.toLong(), "1", callback) }
                        .onFailure { Log.w(TAG, "Discord RPC handshake failed", it) }.getOrNull()
                    main.post {
                        connecting = false
                        if (connected == null) disconnect()
                        else {
                            rpc = connected
                            Log.i(TAG, "Connected to Discord Android RPC service: $name")
                            tick()
                        }
                    }
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) { Log.w(TAG, "Discord RPC service disconnected"); disconnect() }
            override fun onBindingDied(name: ComponentName?) { disconnect() }
            override fun onNullBinding(name: ComponentName?) { disconnect() }
        }
        serviceConnection = connection
        connecting = true
        val ok = runCatching { ctx.bindService(Intent(RPC_ACTION).setComponent(component), connection, Context.BIND_AUTO_CREATE) }
            .onFailure { Log.w(TAG, "Discord bind failed for $component", it) }.getOrDefault(false)
        bound = ok
        if (!ok) {
            connecting = false
            serviceConnection = null
            Log.w(TAG, "Discord RPC bindService returned false")
        } else {
            main.postDelayed({
                if (connecting && rpc == null && serviceConnection === connection) {
                    Log.w(TAG, "Discord RPC service bind timed out")
                    disconnect()
                }
            }, 5_000L)
        }
    }

    private fun findRpcComponent(ctx: Context): ComponentName? {
        val pm = ctx.packageManager
        for (pkg in listOf("com.discord", "com.discord.canary", "com.discord.ptb", "com.discord.debug")) {
            val services = runCatching {
                if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_SERVICES.toLong())).services
                else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, PackageManager.GET_SERVICES).services
                }
            }.getOrNull()
            services?.firstOrNull { it.exported && it.name.contains("rpc", ignoreCase = true) }?.let {
                return ComponentName(pkg, it.name)
            }
            val intent = Intent(RPC_ACTION).setPackage(pkg)
            val matches = runCatching {
                if (Build.VERSION.SDK_INT >= 33) pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
                else {
                    @Suppress("DEPRECATION")
                    pm.queryIntentServices(intent, PackageManager.MATCH_ALL)
                }
            }.getOrDefault(emptyList())
            matches.firstOrNull()?.serviceInfo?.let { return ComponentName(it.packageName, it.name) }
        }
        return null
    }

    private fun disconnect() {
        val ctx = context
        val connection = serviceConnection
        val old = rpc
        rpc = null
        connecting = false
        serviceConnection = null
        lastKey = ""
        if (old != null) io.execute { runCatching { old.disconnect() } }
        if (bound && ctx != null && connection != null) runCatching { ctx.unbindService(connection) }
        bound = false
    }
}
