package app.ink.music

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The play build has no extensions: none kept, none asked (the setting never shows). */
object Extensions {
    const val available = false

    data class Source(val url: String, val name: String = "", val on: Boolean = false)
    data class Track(val title: String, val artist: String, val album: String, val durationMs: Long, val id: String)

    val sources: StateFlow<List<Source>> = MutableStateFlow(emptyList())
    fun start(ctx: Context) {}
    fun clean(url: String): String? = null
    fun add(ctx: Context, url: String, name: String) = false
    fun edit(ctx: Context, old: String, url: String, name: String) = false
    fun turn(ctx: Context, url: String, on: Boolean) {}
    fun remove(ctx: Context, url: String) {}
    fun anyOn(ctx: Context) = false
    fun answered(ctx: Context, key: String) = true
    fun stream(ctx: Context, key: String, t: Track): Uri? = null
    fun by(key: String): String? = null
}
