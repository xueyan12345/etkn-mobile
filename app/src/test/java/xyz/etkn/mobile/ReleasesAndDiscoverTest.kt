package xyz.etkn.mobile

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.etkn.mobile.data.discover.CloudProvider
import xyz.etkn.mobile.data.discover.CloudResource
import xyz.etkn.mobile.data.discover.CloudResources
import xyz.etkn.mobile.data.discover.DiscoverFilter
import xyz.etkn.mobile.data.discover.DiscoverKind
import xyz.etkn.mobile.data.discover.DiscoverOption
import xyz.etkn.mobile.data.discover.DiscoverSort
import xyz.etkn.mobile.data.discover.PersonMatch
import xyz.etkn.mobile.data.discover.best
import xyz.etkn.mobile.data.net.EtknJson
import xyz.etkn.mobile.data.net.int
import xyz.etkn.mobile.data.net.str
import xyz.etkn.mobile.data.update.Releases
import xyz.etkn.mobile.data.update.UpdateSource

class ReleasesAndDiscoverTest {
    private fun json(text: String) = EtknJson.parseToJsonElement(text) as JsonObject

    @Test
    fun versionOrdering() {
        assertTrue(Releases.isNewer("0.2.0", "0.1.1"))
        assertTrue(Releases.isNewer("v0.10.0", "0.9.9"))
        assertTrue(Releases.isNewer("1.0.0", "1.0.0-rc1"))
        assertFalse(Releases.isNewer("0.1.1", "0.1.1"))
        assertFalse(Releases.isNewer("0.1.0", "0.1.1"))
    }

    @Test
    fun apiReleasePicksDeviceAbi() {
        val release = Releases.fromApi(
            json(
                """{"tag_name":"v0.2.0","body":"notes","assets":[
                {"name":"ETKN-Mobile-0.2.0-arm64-v8a.apk","browser_download_url":"https://github.com/a/b/releases/download/v0.2.0/ETKN-Mobile-0.2.0-arm64-v8a.apk","size":10,"digest":"sha256:${"a".repeat(64)}"},
                {"name":"ETKN-Mobile-0.2.0-universal.apk","browser_download_url":"https://github.com/u.apk","size":40},
                {"name":"SHA256SUMS.txt","browser_download_url":"https://github.com/s.txt","size":1}]}""",
            ),
        )!!
        assertEquals("0.2.0", release.version)
        assertEquals("a".repeat(64), release.assetFor(listOf("arm64-v8a", "armeabi-v7a"))!!.sha256)
        assertEquals("ETKN-Mobile-0.2.0-universal.apk", release.assetFor(listOf("riscv64"))!!.name)
        assertNull(release.assetFor(listOf("riscv64"))!!.sha256)
    }

    @Test
    fun sumsFallbackBuildsAssets() {
        val release = Releases.fromSums(
            "${"b".repeat(64)}  ETKN-Mobile-0.2.0-arm64-v8a.apk\n${"c".repeat(64)}  ETKN-Mobile-0.2.0-universal.apk\n",
        )!!
        assertEquals("v0.2.0", release.tag)
        val asset = release.assetFor(listOf("arm64-v8a"))!!
        assertEquals("https://github.com/xueyan12345/etkn-mobile/releases/download/v0.2.0/ETKN-Mobile-0.2.0-arm64-v8a.apk", asset.url)
        // 用户口径（2026-11-23）：加速源已弃用 gh-proxy.com（实测 403），
        // 现在首选 ghfast.top，并带多个镜像回落。
        assertEquals("https://ghfast.top/" + asset.url, UpdateSource.Proxy.wrap(asset.url))
        assertEquals(
            listOf(
                "https://ghfast.top/" + asset.url,
                "https://gh-proxy.net/" + asset.url,
                asset.url,
            ),
            UpdateSource.Proxy.candidates(asset.url),
        )
        assertEquals("b".repeat(64), asset.sha256)
    }

    @Test
    fun filterBodyUsesTvEncodings() {
        val body = DiscoverFilter(sort = DiscoverSort.DateDesc, genres = setOf("16", "35"), minVote = 7f, hideInLibrary = true)
            .body(DiscoverKind.Series, 3)
        assertEquals(3, body.int("page"))
        assertEquals("first_air_date.desc", body.str("sort_by"))
        assertEquals("16,35", body.str("with_genres"))
        assertEquals("true", body.str("hide_in_library"))
        assertTrue(body.containsKey("keywords") && body.containsKey("studios"))
    }

    @Test
    fun aTypedNameResolvesToThePersonInThatRole() {
        val hits = listOf(
            PersonMatch("1", "Zhang Yimou Jr", "Acting", ""),
            PersonMatch("2", "张艺谋", "Acting", "英雄"),
            PersonMatch("3", "张艺谋", "Directing", "英雄、红高粱"),
            PersonMatch("4", "Some Director", "Directing", ""),
        )
        assertEquals("3", hits.best("张艺谋 ", "Directing")?.id)
        assertEquals("2", hits.best("张艺谋", "Acting")?.id)
        assertEquals("3", hits.best("Nobody", "Directing")?.id)
        assertEquals("1", hits.best("x", "Writing")?.id)
        assertNull(emptyList<PersonMatch>().best("张艺谋", "Directing"))
        assertEquals(DiscoverOption("张艺谋", "3"), hits[2].option)
    }

    @Test
    fun everyWebFilterIsSentTheWebWay() {
        val body = DiscoverFilter(
            genres = setOf("18"), excludeGenres = true, rating = "3", countries = linkedSetOf("CN", "HK"), language = "zh",
            yearFrom = 2010, yearTo = 2019, studios = setOf("HBO"), keywords = setOf("武侠"),
            actors = listOf(DiscoverOption("某演员", "123")), directors = listOf(DiscoverOption("某导演", "456")),
        ).body(DiscoverKind.Movie, 1)
        assertEquals("18", body.str("without_genres"))
        assertTrue(!body.containsKey("with_genres"))
        assertEquals("CN|HK", body.str("with_origin_country"))
        assertEquals("zh", body.str("with_original_language"))
        assertEquals("2010-01-01", body.str("release_date_gte"))
        assertEquals("2019-12-31", body.str("release_date_lte"))
        assertEquals(3, body.int("rating"))
        assertEquals("[\"HBO\"]", body["studios"].toString())
        assertEquals("[\"武侠\"]", body["keywords"].toString())
        assertEquals("[\"123\"]", body["actors"].toString())
        assertEquals("[\"456\"]", body["directors"].toString())
    }

    @Test
    fun sharedPoolVersionsBecomeExactRows() {
        val payload = json(
            """{"results":[{"title":"某剧 第 2 季","tmdb_id":"9","sha1":"PARENT","versions":[
              {"logical_group_id":"g1","sha1":"x1","resolution":"2160p","has_valid_share_channel":true},
              {"logical_group_id":"G1","sha1":"x2","resolution":"2160p"},
              {"logical_group_id":"g2","sha1":"y","season_number":3}
            ]}]}""",
        )
        val rows = CloudResources.parse(CloudProvider.SharedPool, payload, season = null)
        assertEquals(2, rows.size)
        val first = rows[0].source
        assertEquals("某剧 第 2 季", first.str("title"))
        assertEquals("x1", first.str("sha1"))
        assertEquals(2, first.int("_shared_pool_source_count"))
        assertEquals("版本 1/2", CloudResources.versionLabel(rows[0]))
        assertEquals("可转存", CloudResources.transferLabel(rows[0]))
        // Season filter: title says S2, the second group says 3.
        assertEquals(1, CloudResources.parse(CloudProvider.SharedPool, payload, season = 2).size)
    }

    @Test
    fun chipsAndLabels() {
        val row = CloudResource(
            CloudProvider.Re0,
            json("""{"title":"片","pan_type":"magnet","size":1610612736,"frame_rate":"24000/1001","unlock_points":20,"progress_text":"12 / 24"}"""),
        )
        val texts = CloudResources.chips(row).map { it.text }
        assertTrue("磁力" in texts)
        assertTrue("1.50 GB" in texts)
        assertTrue("23.976 fps" in texts)
        assertTrue("20 积分" in texts)
        assertTrue("12/24 集" in texts)
    }
}
