package app.ink.music

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.webkit.CookieManager
import android.webkit.WebStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Your YouTube Music, signed in (the full build only): your playlists, the records and artists in
 * your library, what you played there lately, and your liked songs, which are the heart's: loving a
 * YouTube song likes it there. Signed in on YouTube's own page in a WebView; its cookies are kept on
 * the phone, sealed by a key in Android's keystore, and go only to music.youtube.com. The like is
 * the one thing told to YouTube (no history). Signing out forgets the cookies and the WebView's data.
 */
object YouTubeAccount {
    const val available = true
    private val _who = MutableStateFlow<TubeWho?>(null)
    val who: StateFlow<TubeWho?> = _who
    val playlists = MutableStateFlow<List<TubeList>>(emptyList())
    val records = MutableStateFlow<List<TubeAlbum>>(emptyList())
    val history = MutableStateFlow<List<Song>>(emptyList())
    /** Your liked songs (liked music), newest first: loved here too. */
    val liked = MutableStateFlow<List<Song>>(emptyList())
    val artists = MutableStateFlow<List<TubeArtist>>(emptyList())
    val reading = MutableStateFlow(false)
    private var tube: InnerTube? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun prefs(c: Context) = c.getSharedPreferences("youtube", Context.MODE_PRIVATE)

    fun start(c: Context) {
        if (tube != null) return
        val p = prefs(c)
        val auth = p.getString("auth", null)?.let { runCatching { JSONObject(open(it)) }.getOrNull() } ?: return
        tube = InnerTube(auth.optString("cookie"), auth.optString("visitor").ifEmpty { null },
            auth.optString("sync").ifEmpty { null }, auth.optString("version").ifEmpty { InnerTube.VERSION })
        _who.value = TubeWho(p.getString("name", "").orEmpty(), p.getString("handle", "").orEmpty())
        sync()
    }

    /** Signed in on YouTube's page: these cookies tried (who are they?), kept if they work. */
    suspend fun signIn(c: Context, cookie: String, visitor: String?, dataSyncId: String?, version: String?): Boolean = withContext(Dispatchers.IO) {
        // "<brand>||<you>": a brand account speaks on behalf of its id; your own has nothing before the bars
        val brand = dataSyncId?.split("||")?.firstOrNull()?.takeIf { it.isNotBlank() && dataSyncId.contains("||") && dataSyncId.substringAfter("||").isNotBlank() }
        val t = InnerTube(cookie, visitor, brand, version ?: InnerTube.VERSION)
        val w = t.who() ?: return@withContext false
        val auth = JSONObject().put("cookie", cookie).put("visitor", visitor.orEmpty()).put("sync", brand.orEmpty()).put("version", version.orEmpty())
        prefs(c).edit().putString("auth", seal(auth.toString())).putString("name", w.name).putString("handle", w.handle).apply()
        tube = t; _who.value = w
        sync()
        true
    }

    fun signOut(c: Context) {
        prefs(c).edit().clear().apply()
        tube = null; _who.value = null
        playlists.value = emptyList(); records.value = emptyList(); history.value = emptyList()
        liked.value = emptyList(); artists.value = emptyList()
        forgetWeb()
    }

    /** The WebView's cookies and storage gone, so the next sign-in starts clean; then [done]. */
    fun forgetWeb(done: () -> Unit = {}) {
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush(); done() }
    }

    /** Your library read again: playlists, records, history (each on its own; one failing leaves the others). */
    fun sync() {
        val t = tube ?: return
        reading.value = true
        scope.launch {
            val lists = launch {
                runCatching { t.items("FEmusic_liked_playlists") }.onSuccess { found ->
                    // songs only (not "episodes for later", a podcast list), each saying what isn't already said:
                    // not "YouTube Music" (the row says it), nor your own name
                    val owners = setOf("YouTube Music", _who.value?.name.orEmpty())
                    // liked music is loved, so it's there (and on the heart), not a playlist
                    playlists.value = found.filter { it.id.startsWith("VL") && it.id != "VLSE" && it.id != "VLLM" }.map {
                        TubeList(it.id.removePrefix("VL"), it.title, it.subtitle.split(" • ", " · ").filter { w -> w.isNotBlank() && w !in owners }.joinToString("   "), it.art)
                    }
                }.onFailure { android.util.Log.d("ink-youtube", "playlists: $it") }
            }
            val recs = launch {
                runCatching { t.items("FEmusic_liked_albums") }.onSuccess { found ->
                    records.value = found.filter { it.kind == InnerTube.Kind.RECORD || it.id.startsWith("MPREb") }
                        .map { TubeAlbum("${InnerTube.BASE}/browse/${it.id}", it.title, artistOf(it.subtitle), it.art) }
                }.onFailure { android.util.Log.d("ink-youtube", "records: $it") }
            }
            val heard = launch {
                runCatching { t.tracks("FEmusic_history", max = 100) }.onSuccess { found -> history.value = found.map(::song) }
                    .onFailure { android.util.Log.d("ink-youtube", "history: $it") }
            }
            val likes = launch {
                runCatching { t.tracks("VLLM") }.onSuccess { found -> liked.value = found.map(::song) }
                    .onFailure { android.util.Log.d("ink-youtube", "liked: $it") }
            }
            // the artists you follow, else the ones your library's songs are by
            val people = launch {
                runCatching {
                    fun of(id: String) = t.items(id).filter { it.kind == InnerTube.Kind.ARTIST && it.id.startsWith("UC") }
                    of("FEmusic_library_corpus_artists").ifEmpty { of("FEmusic_library_corpus_track_artists") }
                }.onSuccess { found -> artists.value = found.map { TubeArtist(it.title, it.art, it.id) } }
                    .onFailure { android.util.Log.d("ink-youtube", "artists: $it") }
            }
            lists.join(); recs.join(); heard.join(); likes.join(); people.join()
            android.util.Log.d("ink-youtube", "read ${playlists.value.size} playlists, ${records.value.size} records, " +
                "${history.value.size} heard, ${liked.value.size} liked, ${artists.value.size} artists")
            reading.value = false
        }
    }

    /** A song liked on YouTube Music, or not (the heart): shown at once, put back if YouTube refuses. */
    fun like(s: Song, on: Boolean) {
        val t = tube ?: return
        val before = liked.value
        liked.value = if (on) listOf(s) + before.filter { it.id != s.id } else before.filter { it.id != s.id }
        scope.launch {
            if (!t.like(s.id.removePrefix("y"), on)) {
                android.util.Log.d("ink-youtube", "like ${s.id} $on refused")
                liked.value = before
            }
        }
    }

    /** An artist's page on YouTube Music: their best-known songs, and their records newest first. */
    suspend fun artist(a: TubeArtist): Pair<List<Song>, List<TubeAlbum>> = withContext(Dispatchers.IO) {
        runCatching {
            val (name, tracks, items) = (tube ?: InnerTube()).artist(a.id)
            val who = name.ifEmpty { a.name }
            tracks.map(::song) to items.map { TubeAlbum("${InnerTube.BASE}/browse/${it.id}", it.title, who, it.art) }
        }.onFailure { android.util.Log.d("ink-youtube", "artist ${a.id}: $it") }.getOrDefault(emptyList<Song>() to emptyList())
    }

    /** One of your playlists' songs, in its order ("LM" is liked music). */
    suspend fun playlist(id: String): List<Song> = withContext(Dispatchers.IO) {
        val t = tube ?: return@withContext emptyList()
        runCatching { t.tracks("VL$id").map(::song) }.onFailure { android.util.Log.d("ink-youtube", "playlist $id: $it") }.getOrDefault(emptyList())
    }

    /** A record's artist from its subtitle ("Album • Phoebe Bridgers • 2020"): what isn't the kind or the year. */
    private fun artistOf(subtitle: String) = subtitle.split(" • ").map { it.trim() }
        .firstOrNull { it.isNotEmpty() && it !in setOf("Album", "Single", "EP") && !it.matches(Regex("\\d{4}")) }.orEmpty()

    private fun song(t: InnerTube.Track): Song {
        val artist = t.artists.joinToString(", ").ifEmpty { "unknown" }
        return Song(
            id = "y${t.videoId}", uri = Uri.parse("ytm://${t.videoId}"), title = t.title, artist = artist,
            album = t.album.orEmpty(), albumId = t.albumId?.let { "ya$it" } ?: "y${t.videoId}", albumArtist = t.artists.firstOrNull() ?: artist,
            duration = t.seconds * 1000L, disc = 1, track = 0, year = 0, art = t.art, format = "youtube",
        ).also(YouTubeMusic::remember)
    }

    // the cookies sealed: AES-GCM by a key that never leaves Android's keystore
    private const val KEY = "ink-youtube"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun seal(text: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.getEncoder().encodeToString(c.iv + c.doFinal(text.toByteArray()))
    }

    private fun open(sealed: String): String {
        val b = Base64.getDecoder().decode(sealed)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12)) }
        return String(c.doFinal(b, 12, b.size - 12))
    }

    /** Signing in, on Google's own page: a screen of its own ([TubeSignIn]). */
    fun openSignIn(c: Context) {
        c.startActivity(android.content.Intent(c, TubeSignIn::class.java))
    }
}
