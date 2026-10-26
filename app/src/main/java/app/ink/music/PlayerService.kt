package app.ink.music

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionError
import android.os.Bundle
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The player, in its own process (`:player`): ExoPlayer behind a media session, so the
 * notification, the lock screen, headphones and Bluetooth buttons, other apps' controls (ink's
 * home among them) and audio focus all come with it. The screens talk to it as a controller; Android
 * Auto browses it as a library ([Auto]).
 */
class PlayerService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var clock: History.Clock? = null
    private var onAir: OnAir? = null

    /** A future answered by a coroutine (the car's asks read the library, which may still be loading). */
    private fun <T> later(block: suspend () -> T): ListenableFuture<T> {
        val f = SettableFuture.create<T>()
        work.launch { runCatching { block() }.onSuccess { f.set(it) }.onFailure { f.setException(it) } }
        return f
    }

    /**
     * Items as they come to be played: a screen's carry their address in the request (Media3 keeps
     * it from crossing over); the car's are ids, made into songs from the library.
     */
    private suspend fun resolve(items: List<MediaItem>): MutableList<MediaItem> = items.mapNotNull { item ->
        item.requestMetadata.mediaUri?.let { return@mapNotNull item.buildUpon().setUri(it).build() }
        val song = Auto.play(this, item.mediaId)?.let { (songs, at) -> songs[at] }
        song?.let(Playback::item) ?: item.takeIf { it.localConfiguration != null }
    }.toMutableList()
    private var levelling: Levelling? = null
    private val prefs by lazy { getSharedPreferences("queue", MODE_PRIVATE) }
    private val main = Handler(Looper.getMainLooper())
    // while it plays, where it is is kept every 15 s (and on every pause and stop)
    private val keepPlace = object : Runnable {
        override fun run() {
            session?.player?.let { if (it.isPlaying) { place(it); heard(it) } }
            main.postDelayed(this, 15_000)
        }
    }
    // a server song heard far enough (half of it, or four minutes, as Last.fm counts) is told to
    // the server once; "playing now" as it starts
    private val net = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var told: String? = null

    private fun heard(p: Player) {
        val id = p.currentMediaItem?.mediaId ?: return
        if (!id.startsWith("n") || told == id) return
        val length = p.duration.takeIf { it > 0 } ?: return
        if (p.currentPosition >= minOf(length / 2, 240_000L)) {
            told = id
            net.execute { Server.scrobble(this, id, done = true) }
        }
    }

    // the sleep timer: when it ends (wall clock ms), or SLEEP_END_OF_SONG; told to the screens
    // through the session's extras
    private var sleepAt = 0L
    private val sleep = Runnable { session?.player?.let(::fadeOut) }

    private fun setSleep(p: Player, ms: Long) {
        main.removeCallbacks(sleep)
        (p as? ExoPlayer)?.pauseAtEndOfMediaItems = ms == SLEEP_END_OF_SONG
        sleepAt = when {
            ms == SLEEP_END_OF_SONG -> SLEEP_END_OF_SONG
            ms > 0 -> System.currentTimeMillis() + ms
            else -> 0L
        }
        if (ms > 0) main.postDelayed(sleep, ms)
        tell()
    }

    /** What the screens are told through the session's extras: sleep's end, and radio. */
    private fun tell() {
        session?.setSessionExtras(Bundle().apply { putLong(SLEEP_AT, sleepAt); putBoolean(RADIO, radio) })
    }

    // radio: on, the queue's last songs bring more like them (Radio); asked once per seed
    private var radio = true
    private var radioFrom: String? = null

    /** One song or none left (repeat off): ten more like the last, added at the end. */
    private fun topUp(p: Player) {
        if (!radio || p.repeatMode != Player.REPEAT_MODE_OFF || p.mediaItemCount == 0) return
        val next = p.nextMediaItemIndex
        val after = if (next == C.INDEX_UNSET) C.INDEX_UNSET else p.currentTimeline.getNextWindowIndex(next, p.repeatMode, p.shuffleModeEnabled)
        if (after != C.INDEX_UNSET) return
        val last = p.getMediaItemAt(if (next != C.INDEX_UNSET) next else p.currentMediaItemIndex)
        // a station plays on forever: nothing to follow it
        if (radioFrom == last.mediaId || last.mediaId.startsWith("s")) return
        radioFrom = last.mediaId
        val md = last.mediaMetadata
        val title = md.title?.toString().orEmpty()
        val artist = md.artist?.toString().orEmpty()
        val queued = HashSet<String>()
        for (i in 0 until p.mediaItemCount) p.getMediaItemAt(i).let {
            queued += it.mediaId
            queued += Radio.key(it.mediaMetadata.title?.toString().orEmpty(), it.mediaMetadata.artist?.toString().orEmpty())
        }
        net.execute {
            val more = Radio.after(this, last.mediaId, title, artist, queued)
            android.util.Log.d("ink-radio", "after $title - $artist: ${more.size} (${more.joinToString { it.title + " - " + it.artist }})")
            if (more.isEmpty()) return@execute
            main.post {
                if (!radio) return@post
                val ended = p.playbackState == Player.STATE_ENDED
                p.addMediaItems(more.map { Playback.item(it) })
                // it ran out while asking: on into the first of them
                if (ended) { p.seekTo(p.mediaItemCount - more.size, 0); p.prepare(); p.play() }
            }
        }
    }

    /** Sleep's end: the music eases out over 6 s, pauses, and its volume is put back for next time. */
    private fun fadeOut(p: Player) {
        val steps = 30
        var i = 0
        main.post(object : Runnable {
            override fun run() {
                i++
                val level = levelling?.level ?: 1f
                p.volume = level * (1f - i.toFloat() / steps).coerceAtLeast(0f)
                if (i < steps) main.postDelayed(this, 200)
                else { p.pause(); p.volume = level; setSleep(p, 0) }
            }
        })
    }

    override fun onCreate() {
        super.onCreate()
        // this process's WebView keeps its own data (YouTube's poToken runs one here, and the
        // screens' process may run another: two processes sharing one data folder crash)
        runCatching { android.webkit.WebView.setDataDirectorySuffix("player") }
        YouTubeMusic.attach(this)
        Stations.curated(this)   // ink radio's stations, known by id (the car, the queue kept)
        // a YouTube Music song plays from "ytm://<id>": its stream found as it starts (the full build only)
        val sources = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
            androidx.media3.datasource.ResolvingDataSource.Factory(androidx.media3.datasource.DefaultDataSource.Factory(this)) { spec ->
                if (spec.uri.scheme != "ytm") spec
                else spec.withUri(Uri.parse(YouTubeMusic.stream("y" + spec.uri.host, false)?.first ?: throw java.io.IOException("no stream")))
            },
        )
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(sources)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)   // headphones out: pause
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        // the queue as it was left: there, paused, ready to go on
        kept()?.let { (items, index, at) -> player.setMediaItems(items, index, at); player.prepare() }
        player.shuffleModeEnabled = prefs.getBoolean("shuffle", false)
        player.repeatMode = prefs.getInt("repeat", Player.REPEAT_MODE_OFF)
        levelling = Levelling(player, this).also { it.on = prefs.getBoolean("levelling", true) }
        clock = History.Clock(this).also { it.song(player.currentMediaItem, false) }
        // the widget shows the queue as it was left (paused), till the music says otherwise
        player.currentMediaItem?.let { Widget.show(this, it.mediaId, it.mediaMetadata.title?.toString().orEmpty(), it.mediaMetadata.artist?.toString().orEmpty(), false) }
        radio = prefs.getBoolean("radio", true)
        onAir = OnAir(player, main, net)
        player.addListener(object : Player.Listener {
            // a station's song, as its stream says it
            override fun onMetadata(metadata: androidx.media3.common.Metadata) { onAir?.metadata(metadata) }

            override fun onEvents(p: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED)) onAir?.changed()
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) keep(p)
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                    told = null
                    p.currentMediaItem?.mediaId?.takeIf { it.startsWith("n") }?.let { id -> net.execute { Server.scrobble(this@PlayerService, id, done = false) } }
                }
                if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) { place(p); clock?.playing(p.isPlaying) }
                // the widget: what plays, and whether
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_MEDIA_METADATA_CHANGED, Player.EVENT_PLAY_WHEN_READY_CHANGED)) {
                    val md = p.mediaMetadata
                    Widget.show(this@PlayerService, p.currentMediaItem?.mediaId, md.title?.toString().orEmpty(), md.artist?.toString().orEmpty(), p.playWhenReady)
                }
                // the song before written down for your year, if it was heard
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) clock?.song(p.currentMediaItem, p.isPlaying)
                // a new song, its tags read, the songs round it changed (a record in order or not)
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TRACKS_CHANGED, Player.EVENT_TIMELINE_CHANGED,
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_AUDIO_SESSION_ID)) levelling?.apply()
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED,
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) topUp(p)
                if (events.containsAny(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED))
                    prefs.edit().putBoolean("shuffle", p.shuffleModeEnabled).putInt("repeat", p.repeatMode).apply()
            }

            // sleeping at the song's end: it paused there, so the timer's done
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && sleepAt == SLEEP_END_OF_SONG)
                    session?.player?.let { setSleep(it, 0) }
            }
        })
        main.post(keepPlace)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, Main::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, object : MediaLibrarySession.Callback {
                // Android Auto's library: records, artists, lists ([Auto])
                override fun onGetLibraryRoot(
                    session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?,
                ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(Auto.root(), params))

                override fun onGetChildren(
                    session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?,
                ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = later {
                    val all = Auto.children(this@PlayerService, parentId)
                    val shown = if (pageSize in 1 until Int.MAX_VALUE) all.drop(page * pageSize).take(pageSize) else all
                    LibraryResult.ofItemList(shown, params)
                }

                override fun onGetItem(
                    session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String,
                ): ListenableFuture<LibraryResult<MediaItem>> = later {
                    val song = Auto.play(this@PlayerService, mediaId)?.let { (songs, at) -> songs[at] }
                    if (song != null) LibraryResult.ofItem(Playback.item(song), null) else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                }

                override fun onSearch(
                    session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?,
                ): ListenableFuture<LibraryResult<Void>> = later {
                    Shelf.ready(this@PlayerService)
                    session.notifySearchResultChanged(browser, query, Auto.searchResults(query).size, params)
                    LibraryResult.ofVoid()
                }

                override fun onGetSearchResult(
                    session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int, params: LibraryParams?,
                ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = later {
                    Shelf.ready(this@PlayerService)
                    val all = Auto.searchResults(query)
                    LibraryResult.ofItemList(if (pageSize in 1 until Int.MAX_VALUE) all.drop(page * pageSize).take(pageSize) else all, params)
                }

                // a song tapped in the car plays its record (or artist, or list) from there; "play ... on
                // ink music" plays what's found; a screen's queue as it comes
                override fun onSetMediaItems(
                    mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long,
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = later {
                    val one = mediaItems.singleOrNull()
                    val asked = one?.requestMetadata?.searchQuery
                    val tapped = one?.takeIf { it.requestMetadata.mediaUri == null }?.let { Auto.play(this@PlayerService, it.mediaId) }
                    when {
                        asked != null -> { Shelf.ready(this@PlayerService); Auto.found(asked).let { MediaSession.MediaItemsWithStartPosition(it.map(Playback::item), 0, 0) } }
                        tapped != null -> MediaSession.MediaItemsWithStartPosition(tapped.first.map(Playback::item), tapped.second, startPositionMs.coerceAtLeast(0))
                        else -> MediaSession.MediaItemsWithStartPosition(resolve(mediaItems), startIndex, startPositionMs)
                    }
                }

                // the sleep timer, a command of ink music's own
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                    MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(
                            MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon().add(SessionCommand(SLEEP, Bundle.EMPTY))
                                .add(SessionCommand(LEVEL, Bundle.EMPTY)).add(SessionCommand(RADIO, Bundle.EMPTY)).build(),
                        )
                        .build()

                override fun onCustomCommand(
                    session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle,
                ): ListenableFuture<SessionResult> {
                    if (customCommand.customAction == SLEEP) setSleep(session.player, args.getLong(SLEEP_MS))
                    if (customCommand.customAction == LEVEL) {
                        val on = args.getBoolean(LEVEL, true)
                        prefs.edit().putBoolean("levelling", on).apply()
                        levelling?.on = on
                    }
                    if (customCommand.customAction == RADIO) {
                        radio = args.getBoolean(RADIO, true)
                        prefs.edit().putBoolean("radio", radio).apply()
                        radioFrom = null
                        tell()
                        topUp(session.player)
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                // a controller's items arrive without their address (Media3 keeps it from crossing
                // over): it rides in the request's metadata, put back here
                override fun onAddMediaItems(
                    session: MediaSession, controller: MediaSession.ControllerInfo, items: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> = later { resolve(items) }

                // a headset's play, the system's resume card after a restart: the queue as it was left
                override fun onPlaybackResumption(
                    mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    val (items, index, at) = kept() ?: return Futures.immediateFailedFuture(UnsupportedOperationException("nothing kept"))
                    return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(items, index, at))
                }
            })
            .setSessionActivity(open)
            .build()
        tell()
    }

    companion object {
        const val SLEEP = "app.ink.music.SLEEP"
        const val SLEEP_MS = "ms"
        const val SLEEP_AT = "sleepAt"
        const val SLEEP_END_OF_SONG = -1L
        const val LEVEL = "app.ink.music.LEVEL"
        const val RADIO = "app.ink.music.RADIO"
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    /** The queue: each song's id, address and words, which is playing, and where. */
    private fun keep(p: Player) {
        val list = JSONArray()
        for (i in 0 until p.mediaItemCount) {
            val item = p.getMediaItemAt(i)
            val md = onAir?.plain(item.mediaId, item.mediaMetadata) ?: item.mediaMetadata
            val uri = item.localConfiguration?.uri ?: item.requestMetadata.mediaUri ?: continue
            list.put(JSONObject().put("id", item.mediaId).put("uri", uri.toString())
                .put("title", md.title?.toString().orEmpty()).put("artist", md.artist?.toString().orEmpty())
                .put("album", md.albumTitle?.toString().orEmpty()).put("albumArtist", md.albumArtist?.toString().orEmpty())
                .put("art", md.artworkUri?.toString().orEmpty())
                .apply {
                    md.extras?.let { x ->
                        x.getFloat(Levelling.TRACK_GAIN, Float.NaN).takeIf { !it.isNaN() }?.let { put("gain", it.toDouble()) }
                        x.getFloat(Levelling.ALBUM_GAIN, Float.NaN).takeIf { !it.isNaN() }?.let { put("albumGain", it.toDouble()) }
                        put("record", x.getString(Levelling.RECORD).orEmpty())
                    }
                })
        }
        prefs.edit().putString("items", list.toString()).putInt("index", p.currentMediaItemIndex).putLong("at", p.currentPosition).apply()
    }

    private fun place(p: Player) {
        if (p.mediaItemCount > 0) prefs.edit().putInt("index", p.currentMediaItemIndex).putLong("at", p.currentPosition).apply()
    }

    /** The queue as kept, or null (none, or unreadable). */
    private fun kept(): Triple<List<MediaItem>, Int, Long>? = runCatching {
        val a = JSONArray(prefs.getString("items", null) ?: return null)
        val items = (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val uri = Uri.parse(o.getString("uri"))
            MediaItem.Builder().setMediaId(o.getString("id")).setUri(uri)
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
                .setMediaMetadata(MediaMetadata.Builder().setTitle(o.optString("title")).setArtist(o.optString("artist"))
                    .setAlbumTitle(o.optString("album")).setAlbumArtist(o.optString("albumArtist")).setIsPlayable(true)
                    .setExtras(Bundle().apply {
                        o.optDouble("gain").takeIf { !it.isNaN() }?.let { putFloat(Levelling.TRACK_GAIN, it.toFloat()) }
                        o.optDouble("albumGain").takeIf { !it.isNaN() }?.let { putFloat(Levelling.ALBUM_GAIN, it.toFloat()) }
                        putString(Levelling.RECORD, o.optString("record"))
                    })
                    .apply { o.optString("art").takeIf { it.isNotEmpty() }?.let { setArtworkUri(Uri.parse(it)) } }.build())
                .build()
        }
        if (items.isEmpty()) null
        else Triple(items, prefs.getInt("index", 0).coerceIn(items.indices), prefs.getLong("at", 0L).coerceAtLeast(0))
    }.getOrNull()

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        net.shutdown()
        session?.player?.let(::place)
        clock?.song(null, false)
        work.cancel()
        levelling?.release()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
