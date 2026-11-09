package io.github.quentinpn.viewer.tv.emby

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class EmbyClientTest {
    private val server = MockWebServer()
    private lateinit var client: EmbyClient
    private val device = DeviceIdentity("Shield", "dev-1", "0.1.0")

    @Before
    fun setUp() {
        server.start()
        // Built from the literal address: MockWebServer's host name may not look local
        val url = ServerUrl.parse("http://127.0.0.1:${server.port}")
        client = EmbyClient(url, device, EmbyClient.defaultHttp())
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun respond(code: Int, body: String = "") =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private fun expectError(expected: Class<out EmbyError>, block: suspend () -> Unit) {
        try {
            runBlocking { block() }
            fail("expected ${expected.simpleName}")
        } catch (e: EmbyException) {
            assertEquals(expected, e.error::class.java)
        }
    }

    @Test
    fun authenticateSendsPasswordInBodyAndHeaderWithoutToken() = runBlocking {
        respond(200, """{"User":{"Id":"u1","Name":"Quentin"},"AccessToken":"secret-token","ServerId":"s1","Extra":1}""")
        val result = client.authenticate("Quentin", "p@ss")
        assertEquals("u1", result.user.id)
        assertEquals("secret-token", result.accessToken)
        assertFalse("token redacted in logs", "secret-token" in result.toString())

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/emby/Users/AuthenticateByName", request.url.encodedPath)
        assertNull(request.url.query)
        assertEquals("""{"Username":"Quentin","Pw":"p@ss"}""", request.body?.utf8())
        val auth = request.headers["X-Emby-Authorization"]!!
        assertTrue(auth.startsWith("Emby Client=\"Viewer TV\", Device=\"Shield\", DeviceId=\"dev-1\""))
        assertFalse("Token=" in auth)
    }

    @Test
    fun fullItemAsksWithoutFields() = runBlocking {
        respond(200, """{"Id":"e1","Type":"Episode","ProviderIds":{"Imdb":"tt0903747"},"Chapters":[{"StartPositionTicks":300000000,"MarkerType":"IntroStart"}]}""")
        val item = client.withToken("secret-token").fullItem("u1", "e1")
        assertEquals("IntroStart", item.chapters.single().markerType)
        val request = server.takeRequest()
        assertEquals("/emby/Users/u1/Items/e1", request.url.encodedPath)
        assertNull("no Fields, no token in the URL", request.url.query)
        assertTrue("Token=\"secret-token\"" in request.headers["X-Emby-Authorization"]!!)
    }

    @Test
    fun wrongPasswordIsInvalidCredentials() = expectError(EmbyError.InvalidCredentials::class.java) {
        respond(401)
        client.authenticate("Quentin", "bad")
    }

    @Test
    fun rejectedTokenIsSessionExpired() = expectError(EmbyError.SessionExpired::class.java) {
        respond(401)
        client.withToken("old").views("u1")
    }

    @Test
    fun statusCodesMapToCategories() {
        val authed = client.withToken("t")
        expectError(EmbyError.Forbidden::class.java) { respond(403); authed.views("u1") }
        expectError(EmbyError.NotFound::class.java) { respond(404); authed.views("u1") }
        expectError(EmbyError.Server::class.java) { respond(500); authed.views("u1") }
        expectError(EmbyError.InvalidResponse::class.java) { respond(200, "<html>"); authed.views("u1") }
    }

    @Test
    fun redirectIsNotFollowed() = expectError(EmbyError.InvalidResponse::class.java) {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "http://evil.example/").build())
        client.withToken("t").views("u1")
    }

    @Test
    fun unreachableServer() {
        server.close()
        expectError(EmbyError.Unreachable::class.java) { client.publicInfo() }
    }

    @Test
    fun tokenCallsNeedASession() = expectError(EmbyError.NotSignedIn::class.java) { client.views("u1") }

    @Test
    fun tokenGoesInHeaderOnly() = runBlocking {
        respond(200, """{"Items":[{"Id":"v1","Name":"Films","CollectionType":"movies"}],"TotalRecordCount":1}""")
        val views = client.withToken("tok\"en,x").views("u1")
        assertEquals("movies", views.items.single().collectionType)
        val request = server.takeRequest()
        assertEquals("/emby/Users/u1/Views", request.url.encodedPath)
        assertNull(request.url.query)
        assertTrue(request.headers["X-Emby-Authorization"]!!.endsWith(", Token=\"tokenx\""))
    }

    @Test
    fun resumeAsksForVideosWithUserData() = runBlocking {
        respond(200, """{"Items":[{"Id":"e1","Type":"Episode","UserData":{"PlaybackPositionTicks":6000000000}}]}""")
        val items = client.withToken("t").resumeItems("u1", 12)
        assertEquals(6_000_000_000L, items.items.single().userData?.playbackPositionTicks)
        val url = server.takeRequest().url
        assertEquals("/emby/Users/u1/Items/Resume", url.encodedPath)
        assertEquals("12", url.queryParameter("Limit"))
        assertEquals("Movie,Episode", url.queryParameter("IncludeItemTypes"))
        assertEquals("true", url.queryParameter("EnableUserData"))
        // The hero reads the same items (ADR 0059): their overview and year come along
        assertEquals("Overview,ProductionYear,PrimaryImageAspectRatio", url.queryParameter("Fields"))
    }

    @Test
    fun playbackInfoPostsTheDeviceProfile() = runBlocking {
        respond(200, """{"MediaSources":[],"PlaySessionId":"ps"}""")
        client.withToken("t").playbackInfo("u1", "i1", deviceProfile())
        val request = server.takeRequest()
        assertEquals("/emby/Items/i1/PlaybackInfo", request.url.encodedPath)
        assertEquals("u1", request.url.queryParameter("UserId"))
        val body = request.body!!.utf8()
        assertTrue(body.contains("\"IsPlayback\":true"))
        assertTrue(body.contains("\"MaxStreamingBitrate\":400000000"))
        assertTrue(body.contains("\"Format\":\"ass\",\"Method\":\"External\""))
        // No version chosen: the server picks, as before ADR 0058
        assertFalse(body.contains("MediaSourceId"))
    }

    @Test
    fun playbackInfoNamesTheChosenVersionInTheBody() = runBlocking {
        respond(200, """{"MediaSources":[],"PlaySessionId":"ps"}""")
        client.withToken("t").playbackInfo("u1", "i1", deviceProfile(), "mediasource_30501")
        val request = server.takeRequest()
        assertNull(request.url.queryParameter("MediaSourceId"))
        assertTrue(request.body!!.utf8().contains("\"MediaSourceId\":\"mediasource_30501\""))
    }

    @Test
    fun invalidVersionIsNeverSent() {
        expectError(EmbyError.InvalidResponse::class.java) { client.withToken("t").playbackInfo("u1", "i1", deviceProfile(), "a/b") }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun itemComesWithOverviewLengthAndUserData() = runBlocking {
        respond(200, """{"Id":"m1","Type":"Movie","Overview":"Résumé","RunTimeTicks":67200000000,"UserData":{"Played":false,"PlaybackPositionTicks":27670000000}}""")
        val movie = client.withToken("t").item("u1", "m1")
        assertEquals("Résumé", movie.overview)
        assertEquals(67_200_000_000L, movie.runTimeTicks)
        assertEquals(27_670_000_000L, movie.userData?.playbackPositionTicks)
        assertEquals(emptyList<MediaSourceDto>(), movie.mediaSources)
        val url = server.takeRequest().url
        assertEquals("/emby/Users/u1/Items/m1", url.encodedPath)
        assertEquals("Overview", url.queryParameter("Fields"))
    }

    @Test
    fun seasonsAreReadWithUserData() = runBlocking {
        respond(200, """{"Items":[{"Id":"s0","Type":"Season","IndexNumber":0},{"Id":"s1","Type":"Season","IndexNumber":1}],"TotalRecordCount":2}""")
        val seasons = client.withToken("t").seasons("u1", "ser1")
        assertEquals(listOf("s0", "s1"), seasons.items.map { it.id })
        val url = server.takeRequest().url
        assertEquals("/emby/Shows/ser1/Seasons", url.encodedPath)
        assertEquals("u1", url.queryParameter("UserId"))
        assertEquals("true", url.queryParameter("EnableUserData"))
    }

    @Test
    fun episodesArePagedAndOtherSeriesDropped() = runBlocking {
        respond(200, """{"Items":[{"Id":"e1","SeriesId":"ser1","Overview":"Début"},{"Id":"e2","SeriesId":"ser1"}],"TotalRecordCount":3}""")
        respond(200, """{"Items":[{"Id":"x9","SeriesId":"other"}],"TotalRecordCount":3}""")
        val episodes = client.withToken("t").episodes("u1", "ser1", "s1", pageSize = 2)
        assertEquals(listOf("e1", "e2"), episodes.map { it.id })
        assertEquals("Début", episodes.first().overview)
        val first = server.takeRequest().url
        assertEquals("/emby/Shows/ser1/Episodes", first.encodedPath)
        assertEquals("s1", first.queryParameter("SeasonId"))
        assertEquals("0", first.queryParameter("StartIndex"))
        assertEquals("2", first.queryParameter("Limit"))
        assertEquals("Overview", first.queryParameter("Fields"))
        assertEquals("2", server.takeRequest().url.queryParameter("StartIndex"))
    }

    @Test
    fun adjacentEpisodesComeWithUserDataWithoutImages() = runBlocking {
        respond(200, """{"Items":[{"Id":"e2","SeriesId":"ser1"},{"Id":"e3","SeriesId":"ser1","UserData":{"PlaybackPositionTicks":5}}],"TotalRecordCount":2}""")
        val episodes = client.withToken("t").adjacentEpisodes("u1", "ser1", "e2")
        assertEquals(listOf("e2", "e3"), episodes.map { it.id })
        assertEquals(5L, episodes[1].userData?.playbackPositionTicks)
        val url = server.takeRequest().url
        assertEquals("/emby/Shows/ser1/Episodes", url.encodedPath)
        assertEquals("e2", url.queryParameter("AdjacentTo"))
        assertEquals("u1", url.queryParameter("UserId"))
        assertEquals("false", url.queryParameter("EnableImages"))
        assertEquals("true", url.queryParameter("EnableUserData"))
    }

    @Test
    fun libraryPageIsSortedByTitle() = runBlocking {
        respond(200, """{"Items":[{"Id":"m1","Name":"Akira","Type":"Movie"}],"TotalRecordCount":120}""")
        val page = client.withToken("t").libraryItems("u1", "lib1", "Movie", 60, 60)
        assertEquals(listOf("m1"), page.items.map { it.id })
        assertEquals(120, page.totalRecordCount)
        val url = server.takeRequest().url
        assertEquals("/emby/Users/u1/Items", url.encodedPath)
        for ((name, value) in listOf(
            "ParentId" to "lib1", "Recursive" to "true", "IncludeItemTypes" to "Movie",
            "SortBy" to "SortName", "SortOrder" to "Ascending", "StartIndex" to "60", "Limit" to "60",
        )) {
            assertEquals(name, value, url.queryParameter(name))
        }
    }

    @Test
    fun libraryPageCarriesSortAndFilters() = runBlocking {
        respond(200, """{"Items":[],"TotalRecordCount":0}""")
        val options = ItemsOptions(SortKey.Year, descending = true, watch = WatchFilter.Unplayed, favorites = true, genre = "Science-Fiction")
        client.withToken("t").libraryItems("u1", "lib1", "Movie", 0, 60, options)
        val url = server.takeRequest().url
        for ((name, value) in listOf(
            "SortBy" to "ProductionYear,SortName", "SortOrder" to "Descending",
            "Filters" to "IsUnplayed,IsFavorite", "Genres" to "Science-Fiction",
        )) {
            assertEquals(name, value, url.queryParameter(name))
        }
    }

    @Test
    fun noFilterLeavesTheQueryBare() = runBlocking {
        respond(200, """{"Items":[],"TotalRecordCount":0}""")
        client.withToken("t").libraryItems("u1", "lib1", "Series", 0, 60, ItemsOptions(genre = "A|B"))
        val url = server.takeRequest().url
        assertEquals(null, url.queryParameter("Filters"))
        // A genre with the separator is never sent
        assertEquals(null, url.queryParameter("Genres"))
    }

    @Test
    fun genresAreReadByName() = runBlocking {
        respond(200, """{"Items":[{"Id":"g1","Name":"Action"},{"Id":"g2","Name":"Drame"},{"Id":"g3","Name":"x|y"}],"TotalRecordCount":3}""")
        assertEquals(listOf("Action", "Drame"), client.withToken("t").genres("u1", "lib1", "Movie"))
        val url = server.takeRequest().url
        assertEquals("/emby/Genres", url.encodedPath)
        for ((name, value) in listOf("UserId" to "u1", "ParentId" to "lib1", "IncludeItemTypes" to "Movie", "Recursive" to "true")) {
            assertEquals(name, value, url.queryParameter(name))
        }
    }

    @Test
    fun emptyEpisodePageStopsPaging() = runBlocking {
        respond(200, """{"Items":[],"TotalRecordCount":5}""")
        assertTrue(client.withToken("t").episodes("u1", "ser1", "s1").isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun stopReportIsPostedWithoutNullFields() = runBlocking {
        respond(204)
        val body = PlaybackReport(itemId = "i1", mediaSourceId = "ms1", playSessionId = "ps", positionTicks = 42, playMethod = "DirectPlay", failed = false)
        client.withToken("t").reportPlayback(Report(ReportKind.Stopped, body))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/emby/Sessions/Playing/Stopped", request.url.encodedPath)
        assertNull(request.url.query)
        assertEquals(
            """{"ItemId":"i1","MediaSourceId":"ms1","PlaySessionId":"ps","PositionTicks":42,"PlayMethod":"DirectPlay","Failed":false}""",
            request.body?.utf8(),
        )
        assertTrue(request.headers["X-Emby-Authorization"]!!.endsWith(", Token=\"t\""))
    }

    @Test
    fun reportsNeedASession() = expectError(EmbyError.NotSignedIn::class.java) {
        client.reportPlayback(Report(ReportKind.Start, PlaybackReport(itemId = "i1", mediaSourceId = "ms1", positionTicks = 0)))
    }

    @Test
    fun idsAreCheckedBeforeGoingIntoAPath() = expectError(EmbyError.InvalidResponse::class.java) {
        client.withToken("t").views("../System")
    }

    @Test
    fun imageUrlHoldsNoToken() {
        val url = client.withToken("t").imageUrl("i1", "Primary", "abc", 300)
        assertEquals("/emby/Items/i1/Images/Primary", url.encodedPath)
        assertEquals("tag=abc&maxWidth=300", url.query)
    }
}
