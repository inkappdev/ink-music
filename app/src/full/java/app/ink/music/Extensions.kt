package app.ink.music

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.StateFlow

/**
 * Extensions (the full build only): addresses of your own that a YouTube or server song's stream is asked of first,
 * the song played from the first that has it, else as it would have. The whole of it is core's ([YourExtensions],
 * the computer's too); this is the phone's hold on it: its files in files/ (the screens write the list, the player
 * reads it: two processes, so a file, never prefs), its log, the stream as a Uri.
 */
object Extensions {
    const val available = true

    typealias Source = YourExtensions.Source
    typealias Track = YourExtensions.Track
    typealias Said = YourExtensions.Said
    typealias Answer = YourExtensions.Answer
    typealias Probe = YourExtensions.Probe
    typealias Kind = YourExtensions.Kind

    private val core = YourExtensions { android.util.Log.d("ink-extension", it) }
    private fun dir(ctx: Context) = ctx.filesDir

    val sources: StateFlow<List<Source>> = core.sources
    val said: StateFlow<Map<String, Said>> = core.said

    fun start(ctx: Context) = core.start(dir(ctx))
    fun refreshSaid(ctx: Context) = core.refreshSaid(dir(ctx))
    fun clean(url: String): String? = YourExtensions.clean(url)
    fun add(ctx: Context, url: String, name: String) = core.add(dir(ctx), url, name)
    fun edit(ctx: Context, old: String, url: String, name: String) = core.edit(dir(ctx), old, url, name)
    fun turn(ctx: Context, url: String, on: Boolean) = core.turn(dir(ctx), url, on)
    fun remove(ctx: Context, url: String) = core.remove(dir(ctx), url)
    fun anyOn(ctx: Context) = core.anyOn(dir(ctx))
    fun answered(ctx: Context, key: String) = core.answered(dir(ctx), key)
    fun by(key: String): String? = core.by(key)

    /** The song's stream from the first extension on that has it, or null; run on the player's loading thread (5 s an address at most). */
    fun stream(ctx: Context, key: String, t: Track): Uri? = core.stream(dir(ctx), key, t)?.let(Uri::parse)

    // what's the same everywhere, here as it was (the tests ask it)
    fun probe(target: String, timeout: Int = 5_000) = YourExtensions.probe(target, timeout)
    fun searchProbe(url: String, kind: Kind, t: Track, timeout: Int = 5_000) = YourExtensions.searchProbe(url, kind, t, timeout)
    fun linkIn(body: String) = YourExtensions.linkIn(body)
    fun encrypted(body: String) = YourExtensions.encrypted(body)
    fun address(url: String, t: Track) = YourExtensions.address(url, t)
    fun base(url: String) = YourExtensions.base(url)
    fun kindIn(body: String) = YourExtensions.kindIn(body)
    fun pick(body: String, t: Track) = YourExtensions.pick(body, t)
}
