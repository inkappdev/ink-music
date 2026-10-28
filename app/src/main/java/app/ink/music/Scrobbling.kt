package app.ink.music

import android.content.Context

// ListenBrainz and Last.fm live in the core module (the desktop's too), keeping their files in a folder: here, the
// app's own (both processes read them), told this phone's version, the build's Last.fm key, and the log

private fun Context.scrobbling(): java.io.File {
    ListenBrainz.version = BuildConfig.VERSION_NAME
    ListenBrainz.log = { android.util.Log.d("ink-listenbrainz", it) }
    LastFm.key = BuildConfig.LASTFM_KEY; LastFm.secret = BuildConfig.LASTFM_SECRET
    LastFm.log = { android.util.Log.d("ink-lastfm", it) }
    return filesDir
}

fun ListenBrainz.start(c: Context) = start(c.scrobbling())
suspend fun ListenBrainz.connect(c: Context, token: String) = connect(c.scrobbling(), token)
fun ListenBrainz.disconnect(c: Context) = disconnect(c.scrobbling())
fun ListenBrainz.playingNow(c: Context, s: ListenBrainz.Said) = playingNow(c.scrobbling(), s)
fun ListenBrainz.listened(c: Context, s: ListenBrainz.Said, at: Long) = listened(c.scrobbling(), s, at)

fun LastFm.start(c: Context) = start(c.scrobbling())
suspend fun LastFm.connect(c: Context, name: String, password: String) = connect(c.scrobbling(), name, password)
fun LastFm.disconnect(c: Context) = disconnect(c.scrobbling())
fun LastFm.playingNow(c: Context, s: ListenBrainz.Said) = playingNow(c.scrobbling(), s)
fun LastFm.listened(c: Context, s: ListenBrainz.Said, at: Long) = listened(c.scrobbling(), s, at)
