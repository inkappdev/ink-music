package app.ink.music

import com.sun.net.httpserver.HttpServer
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import java.net.InetSocketAddress

/** An extension asked against a stand-in on this machine: audio counts, anything else falls back. */
class ExtensionsTest {
    companion object {
        private lateinit var server: HttpServer
        private val base get() = "http://127.0.0.1:${server.address.port}"

        @BeforeClass @JvmStatic fun up() {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            fun answer(path: String, code: Int, type: String?, wait: Long = 0) = server.createContext(path) { x ->
                Thread.sleep(wait)
                type?.let { x.responseHeaders.add("Content-Type", it) }
                x.sendResponseHeaders(code, 1); x.responseBody.use { it.write(0) }
            }
            answer("/audio", 206, "audio/flac")
            answer("/octet", 200, "application/octet-stream; charset=binary")
            answer("/html", 200, "text/html")
            answer("/missing", 404, "audio/flac")
            answer("/slow", 200, "audio/flac", wait = 1_500)
            // JSON answers: the body given whole, or (when asked for a range and [ranged]) its first byte only
            fun json(path: String, body: String, type: String? = "application/json", ranged: Boolean = false) = server.createContext(path) { x ->
                val all = body.toByteArray()
                val part = ranged && x.requestHeaders.getFirst("Range") != null
                type?.let { x.responseHeaders.add("Content-Type", it) }
                val out = if (part) all.copyOf(1) else all
                x.sendResponseHeaders(if (part) 206 else 200, out.size.toLong()); x.responseBody.use { it.write(out) }
            }
            val audio = { "http://127.0.0.1:${server.address.port}/audio" }
            json("/json", """{"url": "${audio()}"}""")
            json("/wrapped", """{"data": {"url": "${audio()}"}}""")
            json("/untyped", """ {"url": "${audio()}"}""", type = "text/plain")
            json("/ranged", """{"url": "${audio()}"}""", ranged = true)
            json("/badlink", """{"url": "http://127.0.0.1:${server.address.port}/html"}""")
            json("/nourl", """{"stream": "${audio()}"}""")
            json("/manifest", """{"data": {"manifest": "aHR0cHM6Ly9leGFtcGxlLnRlc3Q="}}""")
            json("/notlink", """{"url": "ftp://example.test/a.flac"}""")
            // the search-and-stream kind, as Echo Music's extensions answer
            json("/echo/manifest.json", """{"id":"x","resources":["search","stream"],"settings":[{"key":"quality","default":"lossless"}]}""")
            json("/echo/search", """{"tracks":[{"id":"live","title":"Hey Jude (Live)","artist":"The Beatles","duration":500},
                {"id":"cover","title":"Hey Jude","artist":"Someone Else","duration":431},
                {"id":"it","title":"Hey Jude - Remastered 2015","artist":"The Beatles","duration":430}]}""")
            server.createContext("/echo/stream/") { x ->
                val ok = x.requestURI.path.endsWith("/it") && x.requestURI.query == "quality=lossless&atmos=none"
                val out = (if (ok) """{"url":"${audio()}","format":"flac"}""" else """{"error":"no"}""").toByteArray()
                x.responseHeaders.add("Content-Type", "application/json"); x.sendResponseHeaders(if (ok) 200 else 404, out.size.toLong()); x.responseBody.use { it.write(out) }
            }
            json("/direct", """{"directUrl": "${audio()}", "format": "flac"}""")
            json("/locked", """{"url": "${audio()}", "encrypted": true}""")
            // a search whose result carries its stream's link, and one whose stream is encrypted
            json("/inline/search", """{"tracks":[{"id":"x","title":"Hey Jude","artist":"The Beatles","duration":431,"url":"http://127.0.0.1:1/page","streamURL":"${audio()}"}]}""")
            json("/drm/search", """{"tracks":[{"id":"x","title":"Hey Jude","artist":"The Beatles","duration":431}]}""")
            json("/drm/stream/x", """{"url":"${audio()}","encrypted":true}""")
            server.start()
        }

        @AfterClass @JvmStatic fun down() = server.stop(0)
    }

    @Test fun audioPlays() = assertEquals(Extensions.Answer.AUDIO, Extensions.probe("$base/audio").answer)
    @Test fun octetStreamPlays() = assertEquals(Extensions.Answer.AUDIO, Extensions.probe("$base/octet").answer)
    @Test fun notAudioFallsBack() = assertEquals(Extensions.Answer.NOTHING, Extensions.probe("$base/html").answer)
    @Test fun errorFallsBack() = assertEquals(Extensions.Answer.NOTHING, Extensions.probe("$base/missing").answer)
    @Test fun tooSlowIsDown() = assertEquals(Extensions.Answer.UNREACHABLE, Extensions.probe("$base/slow", timeout = 500).answer)
    @Test fun refusedIsDown() = assertEquals(Extensions.Answer.UNREACHABLE, Extensions.probe("http://127.0.0.1:1/x").answer)

    private fun linkPlays(path: String) = Extensions.probe("$base$path").let {
        assertEquals(Extensions.Answer.AUDIO, it.answer); assertEquals("$base/audio", it.stream)
    }
    @Test fun jsonLinkPlays() = linkPlays("/json")
    @Test fun wrappedLinkPlays() = linkPlays("/wrapped")
    @Test fun untypedJsonBodyPlays() = linkPlays("/untyped")
    @Test fun rangedJsonAskedAgainWhole() = linkPlays("/ranged")
    @Test fun audioItselfIsTheStream() = assertEquals("$base/audio", Extensions.probe("$base/audio").stream)
    @Test fun linkNotAudioFallsBack() = assertEquals(Extensions.Answer.NOTHING, Extensions.probe("$base/badlink").answer)
    @Test fun otherShapesFallBack() = listOf("/nourl", "/manifest", "/notlink").forEach {
        assertEquals(it, Extensions.Answer.NOTHING, Extensions.probe("$base$it").answer)
    }

    private val song = Extensions.Track("Hey Jude", "The Beatles", "1 & 2", 431_000, "abc")

    @Test fun placeholdersFilled() = assertEquals("https://x.test/s/abc?q=Hey%20Jude-The%20Beatles&d=431",
        Extensions.address("https://x.test/s/{id}?q={title}-{artist}&d={duration}", song))

    @Test fun queryAddedOtherwise() = assertEquals("https://x.test/s?k=1&title=Hey%20Jude&artist=The%20Beatles&album=1%20%26%202&duration=431&id=abc",
        Extensions.address("https://x.test/s?k=1", song))

    @Test fun manifestRead() = Extensions.kindIn("""{"resources":["search","stream"],"settings":[{"key":"quality","default":"lossless"}]}""").let {
        assertEquals(true, it.searches); assertEquals("lossless", it.quality)
    }
    @Test fun otherManifestPlain() = assertEquals(false, Extensions.kindIn("""{"resources":["search"]}""").searches)

    @Test fun searchPicksTheSong() = Extensions.searchProbe("$base/echo/", Extensions.Kind(true, "lossless"), song).let {
        assertEquals(it.why, Extensions.Answer.AUDIO, it.answer); assertEquals("$base/audio", it.stream)
    }
    @Test fun searchFromManifestAddress() = assertEquals(Extensions.Answer.AUDIO,
        Extensions.searchProbe("$base/echo/manifest.json", Extensions.Kind(true, "lossless"), song).answer)
    @Test fun otherLinkNamesPlay() = linkPlays("/direct")
    @Test fun encryptedNeverPlays() = assertEquals(Extensions.Answer.NOTHING, Extensions.probe("$base/locked").answer)
    @Test fun searchResultsOwnLinkPlays() = Extensions.searchProbe("$base/inline", Extensions.Kind(true), song).let {
        assertEquals(it.why, Extensions.Answer.AUDIO, it.answer); assertEquals("$base/audio", it.stream)
    }
    @Test fun encryptedStreamFallsBack() = assertEquals(Extensions.Answer.NOTHING,
        Extensions.searchProbe("$base/drm", Extensions.Kind(true), song).answer)
    @Test fun endingsTakenOff() = listOf("https://a.test/t/manifest.json", "https://a.test/t/manifest", "https://a.test/t/search",
        "https://a.test/t/stream/", "https://a.test/t/").forEach { assertEquals(it, "https://a.test/t", Extensions.base(it)) }
    @Test fun manifestNameKept() = assertEquals("Lossless", Extensions.kindIn("""{"name":"Lossless","resources":["search","stream"]}""").name)
    private val echo = """{"name":"Lossless","resources":["search","stream"],"settings":[{"key":"quality","default":"lossless",
        "options":[{"label":"Lossless","value":"lossless"},{"label":"High","value":"high"},{"label":"Low","value":"low"}]}]}"""
    private val tido = """{"resources":["search","stream"],"settings":[{"key":"quality","default":"HIRES",
        "options":[{"value":"HIRES"},{"value":"MAX"},{"value":"LOSSLESS"},{"value":"HIGH"},{"value":"LOW"}]}]}"""

    @Test fun qualityFollowsTheNetwork() {
        val k = Extensions.kindIn(echo)
        assertEquals(listOf("lossless", "high", "low"), k.options)
        assertEquals("lossless", k.at(0).quality); assertEquals("high", k.at(1).quality); assertEquals("low", k.at(2).quality)
        val t = Extensions.kindIn(tido)
        assertEquals("HIRES", t.at(0).quality); assertEquals("HIGH", t.at(1).quality); assertEquals("LOW", t.at(2).quality)
        // none to choose: its own default
        assertEquals("lossless", Extensions.kindIn("""{"resources":["search","stream"],"settings":[{"key":"quality","default":"lossless"}]}""").at(2).quality)
    }

    @Test fun losslessKnown() {
        assertEquals(true, Extensions.kindIn(echo).lossless); assertEquals(true, Extensions.kindIn(tido).lossless)
        assertEquals(false, Extensions.kindIn("""{"resources":["search","stream"],"settings":[{"key":"quality","default":"high"}]}""").lossless)
    }

    @Test fun losslessPassedOverWhenSmallerChosen() {
        assertEquals(true, Extensions.losslessIn("""{"url":"https://x.test/a","format":"flac","quality":"LOSSLESS"}"""))
        assertEquals(false, Extensions.losslessIn("""{"url":"https://x.test/a","format":"mp3","quality":"HIGH"}"""))
    }

    @Test fun searchWithoutItFallsBack() = assertEquals(Extensions.Answer.NOTHING,
        Extensions.searchProbe("$base/echo", Extensions.Kind(true, "lossless"), song.copy(title = "Let It Be")).answer)
    @Test fun searchRefusedIsDown() = assertEquals(Extensions.Answer.UNREACHABLE,
        Extensions.searchProbe("http://127.0.0.1:1", Extensions.Kind(true), song).answer)

    @Test fun pickWantsTitleArtistAndLength() {
        val body = """{"tracks":[{"id":"a","title":"Holocene","artist":"Bon Iver","duration":337},{"id":"b","title":"Holocene","artist":"Bon Iver","duration":360}]}"""
        assertEquals("a", Extensions.pick(body, Extensions.Track("Holocene", "Bon Iver", "", 336_000, "")))
        assertEquals("b", Extensions.pick(body, Extensions.Track("Holocene", "Bon Iver, Someone", "", 359_000, "")))
        assertEquals(null, Extensions.pick(body, Extensions.Track("Holocene", "Bon Iver", "", 200_000, "")))
        assertEquals("a", Extensions.pick(body, Extensions.Track("Holocene", "bon iver", "", 0, "")))
        assertEquals(null, Extensions.pick(body, Extensions.Track("Holocene", "Someone", "", 0, "")))
    }
}
