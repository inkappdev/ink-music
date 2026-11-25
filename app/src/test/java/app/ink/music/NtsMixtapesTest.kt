package app.ink.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** NTS's infinite mixtapes read, from its list as it came (2026-11-24; three of sixteen, their credits cut to two). */
class NtsMixtapesTest {
    private val sample = """
        {"metadata": {"subtitle": "Music-only themed radio streams, made from NTS resident and guest shows"}, "results": [{"mixtape_alias": "poolside", "title": "Poolside", "subtitle": "Balearic, boogie, and sophisti-pop for poolsides, beaches and car stereos.", "description": "Whisk yourself away with an unlimited supply of NTS’ most sun-kissed mixes, crossing all borders and genres.", "description_html": "<p>Whisk yourself away with an unlimited supply of NTS’ most sun-kissed mixes, crossing all borders and genres.</p>", "audio_stream_endpoint": "https://stream-mixtape-geo.ntslive.net/mixtape4", "audio_stream_endpoint_hls_aac": "https://streams.radiomast.io/e7bc1d1a-da3a-492f-8ecd-d5b4d503799f/hls.m3u8", "audio_stream_endpoint_hls_mp3": "https://streams.radiomast.io/052d2e3c-389c-44cf-a91a-8f5e1854f4c6/hls.m3u8", "credits": [{"name": "All Styles All Smiles", "path": "/shows/all-styles-all-smiles"}, {"name": "Altered Soul Experiment w/ Amila", "path": "/shows/amila"}], "media": {"picture_large": "https://media2.ntslive.co.uk/resize/1600x1600/cf5afb01-5a68-4fa0-a1c6-415b35d09ed6_1542931200.jpeg", "picture_medium_large": "https://media2.ntslive.co.uk/resize/800x800/cf5afb01-5a68-4fa0-a1c6-415b35d09ed6_1542931200.jpeg"}, "now_playing_topic": "", "links": [{"rel": "self", "href": "https://www.nts.live/api/v2/mixtapes/poolside", "type": "application/vnd.mixtape+json;charset=utf-8"}]},
          {"mixtape_alias": "slow-focus", "title": "Slow Focus", "subtitle": "Meditative, relaxing and beatless: ambient, drone and ragas.", "description": "Tune in and zone out with NTS’ compendium of the beatless and transcendental. Calming sounds to help you focus or drift away.", "description_html": "<p>Tune in and zone out with NTS’ compendium of the beatless and transcendental. Calming sounds to help you focus or drift away.</p>", "audio_stream_endpoint": "https://stream-mixtape-geo.ntslive.net/mixtape", "audio_stream_endpoint_hls_aac": "https://streams.radiomast.io/66d3acd5-7e07-4354-9c40-41266abfb7a2/hls.m3u8", "audio_stream_endpoint_hls_mp3": "https://streams.radiomast.io/dfc76352-cda6-4a95-85dd-6f6609f83ba2/hls.m3u8", "credits": [{"name": "Aboutface", "path": "/shows/aboutface"}, {"name": "Are You Before", "path": "/shows/are-you-before"}], "media": {"picture_large": "https://media2.ntslive.co.uk/resize/1600x1600/01f7cbe6-235f-4e33-8f2f-70152c91edf1_1542931200.jpeg", "picture_medium_large": "https://media2.ntslive.co.uk/resize/800x800/01f7cbe6-235f-4e33-8f2f-70152c91edf1_1542931200.jpeg"}, "now_playing_topic": "", "links": [{"rel": "self", "href": "https://www.nts.live/api/v2/mixtapes/slow-focus", "type": "application/vnd.mixtape+json;charset=utf-8"}]},
          {"mixtape_alias": "sheet-music", "title": "Sheet Music", "subtitle": "The best of classical and contemporary composition.", "description": "Refined, austere, and stirring sounds spanning the long history of classical music - from the baroque era to post-modernity.", "description_html": "<p>Refined, austere, and stirring sounds spanning the long history of classical music - from the baroque era to post-modernity.</p>", "audio_stream_endpoint": "https://stream-mixtape-geo.ntslive.net/mixtape35", "audio_stream_endpoint_hls_aac": "https://streams.radiomast.io/01eb0780-0cc8-4761-99f2-7cc1cc38f3f1/hls.m3u8", "audio_stream_endpoint_hls_mp3": "https://streams.radiomast.io/976b6107-859e-4bc5-8d63-d6992431c414/hls.m3u8", "credits": [{"name": "150 Session", "path": "/shows/150session"}, {"name": "Black Opera", "path": "/shows/black-opera"}], "media": {"picture_large": "https://media2.ntslive.co.uk/resize/1600x1600/fe3dc346-2549-44cc-96c7-c3117056aa74_1668038400.jpeg", "picture_medium_large": "https://media2.ntslive.co.uk/resize/800x800/fe3dc346-2549-44cc-96c7-c3117056aa74_1668038400.jpeg"}, "now_playing_topic": "", "links": [{"rel": "self", "href": "https://www.nts.live/api/v2/mixtapes/sheet-music", "type": "application/vnd.mixtape+json;charset=utf-8"}]}], "links": [{"rel": "self", "href": "https://www.nts.live/api/v2/mixtapes", "type": "application/vnd.mixtape-list+json;charset=utf-8"}]}
    """.trimIndent()

    @Test fun readsEachMixtape() {
        val all = NtsMixtapes.parse(sample)
        assertEquals(listOf("poolside", "slow-focus", "sheet-music"), all.map { it.alias })
        val slow = all[1]
        assertEquals("snts-slow-focus", slow.id)
        assertEquals("NTS Slow Focus", slow.name)
        assertEquals("https://stream-mixtape-geo.ntslive.net/mixtape", slow.stream)
        assertEquals("meditative, relaxing and beatless: ambient, drone and ragas", slow.said)
        assertTrue(slow.about.startsWith("Tune in and zone out"))
        assertEquals("https://media2.ntslive.co.uk/resize/800x800/01f7cbe6-235f-4e33-8f2f-70152c91edf1_1542931200.jpeg", slow.art)
    }

    @Test fun leavesOutWhatCantPlay() {
        // no direct stream (only HLS), no alias, and the same one twice: one Poolside, nothing else
        val odd = """{"results":[
            {"mixtape_alias":"hls-only","title":"HLS","audio_stream_endpoint":"https://x.example/hls.m3u8"},
            {"mixtape_alias":"","title":"Nameless","audio_stream_endpoint":"https://x.example/a"},
            {"mixtape_alias":"poolside","title":"Poolside","description":"Sun-kissed mixes.","audio_stream_endpoint":"https://stream-mixtape-geo.ntslive.net/mixtape4"},
            {"mixtape_alias":"poolside","title":"Poolside","audio_stream_endpoint":"https://stream-mixtape-geo.ntslive.net/mixtape4"}]}"""
        assertEquals(listOf("poolside"), NtsMixtapes.parse(odd).map { it.alias })
        // its line falls back to the description when there's no subtitle
        assertEquals("sun-kissed mixes", NtsMixtapes.parse(odd).single().said)
        assertTrue(NtsMixtapes.parse("not json").isEmpty())
        assertTrue(NtsMixtapes.parse("{}").isEmpty())
    }

    @Test fun keptReadsTheFile() {
        val f = java.io.File.createTempFile("nts-mixtapes", ".json")
        try {
            assertTrue(NtsMixtapes.kept(java.io.File(f.path + ".none")).isEmpty())
            f.writeText(sample)
            assertEquals(3, NtsMixtapes.kept(f).size)
        } finally { f.delete() }
    }
}
