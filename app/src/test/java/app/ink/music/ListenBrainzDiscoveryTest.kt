package app.ink.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ListenBrainz's fresh releases and Labs' similar artists read, from answers as they came (2026-11-24). */
class ListenBrainzDiscoveryTest {
    // 2026-11-24, midday UTC
    private val now = 1_795_521_600_000L

    // /1/user/<name>/fresh_releases (everyone's /1/explore/fresh-releases has the same shape, without the secondary type)
    private val fresh = """
        {"payload":{"releases":[
          {"artist_credit_name":"Erasure","artist_mbids":["43b58c98-3779-4b04-9a23-1c95cca3a145"],"caa_id":46732648428,
           "caa_release_mbid":"ded253d7-dfad-43ef-ac77-276a308bde41","confidence":396,"listen_count":0,"release_date":"2026-11-10",
           "release_group_mbid":"8f239408-4239-41de-a4d1-cdbc2babe161","release_group_primary_type":"Single",
           "release_group_secondary_type":null,"release_mbid":"ded253d7-dfad-43ef-ac77-276a308bde41",
           "release_name":"Wings Of Love (Ladytron Remix)","release_tags":[]},
          {"artist_credit_name":"John Williams; Simone Pedroni, Maria Grazia Aschei","artist_mbids":["0524cb8c-872b-4202-80f1-7b90432aa9da"],
           "caa_id":46626121251,"caa_release_mbid":"98ca0a5b-7345-47ce-862c-f5f0fe9d9636","confidence":33,"listen_count":0,
           "release_date":"2026-11-20","release_group_mbid":"ac9301bb-e94b-4b89-8d1f-d64c729b4137","release_group_primary_type":"Album",
           "release_group_secondary_type":null,"release_mbid":"98ca0a5b-7345-47ce-862c-f5f0fe9d9636",
           "release_name":"Seven for Luck & Other Music for Film and the Concert Stage","release_tags":[]},
          {"artist_credit_name":"JB Dunckel","artist_mbids":["f3e3682f-5851-463e-95d5-a243939545ca"],"caa_id":46686010963,
           "caa_release_mbid":"ae519176-7a6b-41ac-9aa9-d59e242885d5","confidence":544,"listen_count":0,"release_date":"2026-11-11",
           "release_group_mbid":"d141873c-ce9a-4552-9f23-78534978d498","release_group_primary_type":"Album",
           "release_group_secondary_type":"Soundtrack","release_mbid":"ae519176-7a6b-41ac-9aa9-d59e242885d5",
           "release_name":"Eldorado (Bande originale de la série)","release_tags":[]},
          {"artist_credit_name":"2sdxrt3all","artist_mbids":["90b5ff16-8b5f-47df-a509-3a52aa20d43e"],"caa_id":null,"caa_release_mbid":null,
           "listen_count":0,"release_date":"2026-11-22","release_group_mbid":"89db1fa4-cef7-4afe-81e7-6d549343b7ce",
           "release_group_primary_type":"EP","release_mbid":"9e0d979a-6ca6-4cf2-8faf-d55e914d23ed","release_name":"2023","release_tags":[]},
          {"artist_credit_name":"Too Soon","artist_mbids":[],"caa_id":null,"caa_release_mbid":null,"listen_count":0,"release_date":"2026-12-04",
           "release_group_mbid":"x","release_group_primary_type":"Album","release_name":"Not Out Yet","release_tags":[]},
          {"artist_credit_name":"Long Ago","artist_mbids":[],"caa_id":null,"caa_release_mbid":null,"listen_count":0,"release_date":"2026-10-01",
           "release_group_mbid":"y","release_group_primary_type":"Album","release_name":"Old News","release_tags":[]},
          {"artist_credit_name":"Some DJ","artist_mbids":[],"caa_id":null,"caa_release_mbid":null,"listen_count":0,"release_date":"2026-11-20",
           "release_group_mbid":"z","release_group_primary_type":"Other","release_group_secondary_type":"DJ-mix","release_name":"Mix","release_tags":[]}
        ],"user_id":"rob"}}
    """.trimIndent()

    @Test fun freshReleasesAsPicks() {
        val picks = FreshReleases.parse(fresh, now)
        assertEquals(listOf("Wings Of Love (Ladytron Remix)", "Seven for Luck & Other Music for Film and the Concert Stage", "2023"), picks.map { it.title })
        val single = picks[0]
        assertEquals(false, single.record)
        assertEquals("Erasure", single.artist)
        assertEquals("listenbrainz", single.from)
        assertEquals("https://coverartarchive.org/release/ded253d7-dfad-43ef-ac77-276a308bde41/front-250", single.art)
        assertEquals("https://musicbrainz.org/release-group/8f239408-4239-41de-a4d1-cdbc2babe161", single.link)
        // several credited, read as Curation.yours splits them
        assertEquals("John Williams, Simone Pedroni, Maria Grazia Aschei", picks[1].artist)
        assertTrue(picks[1].record)
        // no cover in the archive: none
        assertNull(picks[2].art)
        assertTrue(picks[2].record)
    }

    @Test fun freshReleasesKeptToYourArtists() {
        val picks = FreshReleases.parse(fresh, now)
        val yours = Curation.yours(picks, mapOf("john williams" to 3, "air" to 10))
        assertEquals(listOf("Seven for Luck & Other Music for Film and the Concert Stage"), yours.map { it.pick.title })
        assertEquals("john williams", yours[0].artist)
    }

    @Test fun nothingFromNothing() {
        assertEquals(emptyList<Curation.Pick>(), FreshReleases.parse("""{"payload":{"releases":[]}}""", now))
        assertEquals(emptyList<Curation.Pick>(), FreshReleases.parse("""{"code":404,"error":"User x not found"}""", now))
    }

    // labs.api.listenbrainz.org/similar-artists/json for Portishead (8f6bd1e4-fbe1-4f50-aa9b-94c450ec0f11)
    private val similar = """
        [{"artist_mbid": "10adbe5e-a2c0-4bf3-8249-2b4cbf6e6ca8", "name": "Massive Attack", "comment": "", "type": "Group", "gender": null, "score": 7781, "reference_mbid": "8f6bd1e4-fbe1-4f50-aa9b-94c450ec0f11"},
         {"artist_mbid": "a74b1b7f-71a5-4011-9441-d0b5e4122711", "name": "Radiohead", "comment": "", "type": "Group", "gender": null, "score": 7045, "reference_mbid": "8f6bd1e4-fbe1-4f50-aa9b-94c450ec0f11"},
         {"artist_mbid": "87c5dedd-371d-4a53-9f7f-80522fb7f3cb", "name": "Björk", "comment": "", "type": "Person", "gender": "Female", "score": 4936, "reference_mbid": "8f6bd1e4-fbe1-4f50-aa9b-94c450ec0f11"}]
    """.trimIndent()

    @Test fun similarArtistsRead() {
        val l = Similar.parseListenBrainz(similar)
        assertEquals(listOf("Massive Attack", "Radiohead", "Björk"), l.map { it.first })
        assertEquals(1.0, l[0].second, 1e-9)
        assertEquals(7045.0 / 7781, l[1].second, 1e-9)
        assertEquals(emptyList<Pair<String, Double>>(), Similar.parseListenBrainz("[]"))
    }

    @Test fun bothEarsBlended() {
        val lastFm = listOf("Tricky" to 1.0, "Radiohead" to .3, "Beth Gibbons" to .9)
        val lb = Similar.parseListenBrainz(similar)
        val b = Similar.blend(lastFm, lb, 10)
        // named by both first, however weakly one named it; Last.fm's spelling kept; each once
        assertEquals("Radiohead", b[0].first)
        assertTrue(b[0].second >= .6)
        assertEquals(5, b.size)
        assertEquals(b.size, b.map { Curation.folded(it.first) }.distinct().size)
        assertTrue(b.drop(1).all { it.second < .6 })
        // one of them silent: the other's as it is
        assertEquals(lastFm, Similar.blend(lastFm, emptyList(), 10))
        assertEquals(lb, Similar.blend(emptyList(), lb, 10))
    }
}
