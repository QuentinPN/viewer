package io.github.quentinpn.viewer.tv.emby

import io.github.quentinpn.viewer.tv.ui.ancestorIdsOrNull
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
    fun localTrailersAreListedWithTheTokenInTheHeaderOnly() = runBlocking {
        respond(200, """[{"Id":"t1","Name":"Bande-annonce","Type":"Trailer"}]""")
        val trailers = client.withToken("secret-token").localTrailers("u1", "m1")
        assertEquals("t1", trailers.single().id)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/emby/Users/u1/Items/m1/LocalTrailers", request.url.encodedPath)
        assertEquals("false", request.url.queryParameter("EnableImages"))
        assertFalse("no token in the URL", "secret-token" in request.url.toString())
        assertTrue("Token=\"secret-token\"" in request.headers["X-Emby-Authorization"]!!)
    }

    @Test
    fun localTrailersRejectAnIdThatWouldChangeThePath() = expectError(EmbyError.InvalidResponse::class.java) {
        client.withToken("t").localTrailers("u1", "../Users")
    }

    @Test
    fun ancestorsAreAskedForTheUserWithTheTokenInTheHeaderOnly() = runBlocking {
        respond(200, """[{"Id":"f9","Name":"Saison 1","Type":"Folder"},{"Id":"lib2","Name":"Anime","Type":"CollectionFolder","CollectionType":"tvshows"},{"Id":"root","Type":"AggregateFolder"}]""")
        val parents = client.withToken("secret-token").ancestors("u1", "s1")
        assertEquals(listOf("f9", "lib2", "root"), parents.map { it.id })
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/emby/Items/s1/Ancestors", request.url.encodedPath)
        assertEquals("UserId=u1", request.url.query)
        assertFalse("no token in the URL", "secret-token" in request.url.toString())
        assertTrue("Token=\"secret-token\"" in request.headers["X-Emby-Authorization"]!!)
    }

    @Test
    fun ancestorsRejectAnIdThatWouldChangeThePath() = expectError(EmbyError.InvalidResponse::class.java) {
        client.withToken("t").ancestors("u1", "../Users")
    }

    @Test
    fun unreadableAncestorsAreNoneSoTheChipsKeepTheBarChoice() = runBlocking {
        val signedIn = client.withToken("t")
        respond(200, """[{"Id":"lib2"}]""")
        assertEquals(listOf("lib2"), ancestorIdsOrNull(signedIn, "u1", "s1"))
        respond(500)
        assertNull(ancestorIdsOrNull(signedIn, "u1", "s1"))
        respond(404)
        assertNull(ancestorIdsOrNull(signedIn, "u1", "s1"))
        respond(200, "not json")
        assertNull(ancestorIdsOrNull(signedIn, "u1", "s1"))
        respond(401)
        assertNull("the page handles an expired session", ancestorIdsOrNull(signedIn, "u1", "s1"))
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
    fun qualityChangeAsksFromThePlayingPosition() = runBlocking {
        respond(200, """{"MediaSources":[],"PlaySessionId":"ps"}""")
        respond(200, """{"MediaSources":[],"PlaySessionId":"ps"}""")
        client.withToken("t").playbackInfo("u1", "i1", deviceProfile())
        assertFalse(server.takeRequest().body!!.utf8().contains("StartTimeTicks"))
        client.withToken("t").playbackInfo("u1", "i1", deviceProfile(), "mediasource_30501", 1_200_000_000)
        val request = server.takeRequest()
        assertNull(request.url.queryParameter("StartTimeTicks"))
        val body = request.body!!.utf8()
        assertTrue(body.contains("\"StartTimeTicks\":1200000000"))
        assertTrue(body.contains("\"MediaSourceId\":\"mediasource_30501\""))
    }

    @Test
    fun theAudioToCarryGoesInTheBodyWithTheVersion() = runBlocking {
        repeat(3) { respond(200, """{"MediaSources":[],"PlaySessionId":"ps"}""") }
        client.withToken("secret-token").playbackInfo("u1", "i1", deviceProfile(), "mediasource_30501", 1_200_000_000, 2)
        val request = server.takeRequest()
        assertNull(request.url.queryParameter("AudioStreamIndex"))
        assertNull(request.url.queryParameter("api_key"))
        assertFalse("secret-token" in request.url.toString())
        val body = request.body!!.utf8()
        assertTrue(body.contains("\"AudioStreamIndex\":2"))
        assertTrue(body.contains("\"MediaSourceId\":\"mediasource_30501\""))
        assertFalse("secret-token" in body)
        assertTrue(request.headers["X-Emby-Authorization"]!!.contains("secret-token"))
        // Ignored by the server without a version (ADR 0035): never sent alone, nor a negative index
        client.withToken("t").playbackInfo("u1", "i1", deviceProfile(), audioStreamIndex = 2)
        assertFalse(server.takeRequest().body!!.utf8().contains("AudioStreamIndex"))
        client.withToken("t").playbackInfo("u1", "i1", deviceProfile(), "mediasource_30501", audioStreamIndex = -1)
        assertFalse(server.takeRequest().body!!.utf8().contains("AudioStreamIndex"))
    }

    @Test
    fun noAudioIsAskedForByDefault() = runBlocking {
        respond(200, """{"MediaSources":[],"PlaySessionId":"ps"}""")
        client.withToken("t").playbackInfo("u1", "i1", deviceProfile(), "mediasource_30501", 1_200_000_000)
        val body = server.takeRequest().body!!.utf8()
        assertFalse(body.contains("AudioStreamIndex"))
        assertFalse(body.contains("SubtitleStreamIndex"))
    }

    @Test
    fun stopEncodingDeletesTheReplacedTranscode() = runBlocking {
        respond(204)
        client.withToken("secret-token").stopEncoding("ps-old1")
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/emby/Videos/ActiveEncodings", request.url.encodedPath)
        assertEquals("dev-1", request.url.queryParameter("DeviceId"))
        assertEquals("ps-old1", request.url.queryParameter("PlaySessionId"))
        assertNull(request.url.queryParameter("api_key"))
        assertFalse(request.url.toString().contains("secret-token"))
        assertTrue(request.headers["X-Emby-Authorization"]!!.endsWith(", Token=\"secret-token\""))
    }

    @Test
    fun invalidSessionIsNeverStopped() {
        expectError(EmbyError.InvalidResponse::class.java) { client.withToken("t").stopEncoding("a/b") }
        expectError(EmbyError.NotSignedIn::class.java) { client.stopEncoding("ps1") }
        assertEquals(0, server.requestCount)
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
    fun similarAsksForFilmsAndSeriesWithPosters() = runBlocking {
        respond(200, """{"Items":[{"Id":"m2","Name":"Akira","Type":"Movie"},{"Id":"s1","Type":"Series"}],"TotalRecordCount":2}""")
        val similar = client.withToken("secret-token").similar("u1", "m1", 12)
        assertEquals(listOf("m2", "s1"), similar.items.map { it.id })
        val request = server.takeRequest()
        val url = request.url
        assertEquals("/emby/Items/m1/Similar", url.encodedPath)
        for ((name, value) in listOf(
            "UserId" to "u1", "Limit" to "12", "IncludeItemTypes" to "Movie,Series",
            "Fields" to "PrimaryImageAspectRatio,ProductionYear", "EnableUserData" to "true",
            "ImageTypeLimit" to "1", "EnableImageTypes" to "Primary",
        )) {
            assertEquals(name, value, url.queryParameter(name))
        }
        assertFalse("no token in the URL", "secret-token" in url.toString())
        assertTrue("Token=\"secret-token\"" in request.headers["X-Emby-Authorization"]!!)
    }

    @Test
    fun personItemsAsksForTheFilmsAndSeriesOfOnePerson() = runBlocking {
        respond(200, """{"Items":[{"Id":"m2","Name":"Dune","Type":"Movie","ProductionYear":2021}],"TotalRecordCount":1}""")
        val titles = client.withToken("secret-token").personItems("u1", "30209")
        assertEquals(listOf("m2"), titles.items.map { it.id })
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        val url = request.url
        assertEquals("/emby/Users/u1/Items", url.encodedPath)
        for ((name, value) in listOf(
            "PersonIds" to "30209", "Recursive" to "true", "IncludeItemTypes" to "Movie,Series",
            "SortBy" to "ProductionYear,SortName", "SortOrder" to "Descending", "Limit" to "100",
            "EnableUserData" to "true", "ImageTypeLimit" to "1", "EnableImageTypes" to "Primary",
        )) {
            assertEquals(name, value, url.queryParameter(name))
        }
        assertFalse("no token in the URL", "secret-token" in url.toString())
        assertNull(url.queryParameter("api_key"))
        assertNull(request.headers["X-Emby-Token"])
        assertTrue("Token=\"secret-token\"" in request.headers["X-Emby-Authorization"]!!)
    }

    @Test
    fun personItemsRejectsAnIdThatWouldChangeThePath() {
        expectError(EmbyError.InvalidResponse::class.java) { client.withToken("t").personItems("u1", "../Users") }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun collectionItemsAsksForTheTitlesOfOneBoxSet() = runBlocking {
        respond(200, """{"Items":[{"Id":"m1","Name":"Dune","Type":"Movie","PremiereDate":"2021-09-15T00:00:00.0000000Z"}],"TotalRecordCount":1}""")
        val titles = client.withToken("secret-token").collectionItems("u1", "120034")
        assertEquals(listOf("m1"), titles.items.map { it.id })
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        val url = request.url
        assertEquals("/emby/Users/u1/Items", url.encodedPath)
        for ((name, value) in listOf(
            "ParentId" to "120034", "SortBy" to "PremiereDate,ProductionYear,SortName", "SortOrder" to "Ascending",
            "Limit" to "100", "EnableUserData" to "true", "ImageTypeLimit" to "1", "EnableImageTypes" to "Primary",
        )) {
            assertEquals(name, value, url.queryParameter(name))
        }
        assertFalse("no token in the URL", "secret-token" in url.toString())
        assertNull(url.queryParameter("api_key"))
        assertNull(request.headers["X-Emby-Token"])
        assertTrue("Token=\"secret-token\"" in request.headers["X-Emby-Authorization"]!!)
    }

    @Test
    fun collectionItemsRejectsAnIdThatWouldChangeThePath() {
        expectError(EmbyError.InvalidResponse::class.java) { client.withToken("t").collectionItems("u1", "../Users") }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun peopleOfAnItemCarryTheirIdRoleAndPhoto() = runBlocking {
        respond(
            200,
            """{"Id":"m1","Type":"Movie","People":[{"Name":"Jacob Elordi","Id":"30209","Role":"Chris","Type":"Actor","PrimaryImageTag":"4b8a"},""" +
                """{"Name":"Lance Hool","Id":30300,"Type":"Director"},{"Name":"Sans id","Type":"Actor"}]}""",
        )
        val people = client.withToken("t").fullItem("u1", "m1").people
        assertEquals(listOf("30209", "30300", null), people.map { it.id })
        assertEquals("Chris", people[0].role)
        assertEquals("4b8a", people[0].primaryImageTag)
        assertNull(people[1].primaryImageTag)
    }

    @Test
    fun userConfigurationReadsTheTrackPreferences() = runBlocking {
        respond(
            200,
            """{"Id":"u1","Name":"claude","Configuration":{"AudioLanguagePreference":"jpn","PlayDefaultAudioTrack":false,""" +
                """"SubtitleLanguagePreference":"fre","SubtitleMode":"Smart","ProfilePin":"1234","OrderedViews":[]}}""",
        )
        val config = client.withToken("secret-token").userConfiguration("u1")
        assertEquals(UserConfigurationDto("jpn", false, "fre", "Smart"), config)
        assertFalse("the pin is never decoded", "1234" in config.toString())
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/emby/Users/u1", request.url.encodedPath)
        assertNull(request.url.query)
        assertTrue("Token=\"secret-token\"" in request.headers["X-Emby-Authorization"]!!)
    }

    @Test
    fun userConfigurationAcceptsTheDocumentedArrayAndItsAbsence() = runBlocking {
        respond(200, """{"Id":"u1","Configuration":[{"SubtitleMode":"None"}]}""")
        respond(200, """{"Id":"u1","Name":"claude"}""")
        respond(200, """{"Id":"u1","Configuration":[]}""")
        val withToken = client.withToken("t")
        assertEquals(UserConfigurationDto(subtitleMode = "None"), withToken.userConfiguration("u1"))
        assertNull(withToken.userConfiguration("u1"))
        assertNull(withToken.userConfiguration("u1"))
    }

    @Test
    fun userConfigurationErrorsAreTyped() {
        respond(200, """{"Configuration":{"PlayDefaultAudioTrack":"maybe"}}""")
        expectError(EmbyError.InvalidResponse::class.java) { client.withToken("t").userConfiguration("u1") }
        expectError(EmbyError.InvalidResponse::class.java) { client.withToken("t").userConfiguration("../admin") }
        respond(401)
        expectError(EmbyError.SessionExpired::class.java) { client.withToken("t").userConfiguration("u1") }
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
    fun letterIndexFollowsTheGridQuery() = runBlocking {
        respond(200, """{"Items":[{"Id":"m1","Name":"Le Mépris","SortName":"mépris","ImageTags":{}},{"Id":"m2","Name":"Akira"}],"TotalRecordCount":2}""")
        val options = ItemsOptions(SortKey.Name, descending = true, watch = WatchFilter.Unplayed, favorites = true, genre = "Drame")
        val names = client.withToken("secret-token").sortNames("u1", "lib1", "Movie", options)
        assertEquals(listOf("mépris", "Akira"), names.map { it.key })
        val request = server.takeRequest()
        val url = request.url
        assertEquals("/emby/Users/u1/Items", url.encodedPath)
        for ((name, value) in listOf(
            "ParentId" to "lib1", "Recursive" to "true", "IncludeItemTypes" to "Movie",
            "SortBy" to "SortName", "SortOrder" to "Descending", "Filters" to "IsUnplayed,IsFavorite", "Genres" to "Drame",
            "Fields" to "SortName", "EnableImages" to "false", "EnableUserData" to "false",
        )) {
            assertEquals(name, value, url.queryParameter(name))
        }
        // The whole list in one answer: no paging
        assertNull(url.queryParameter("StartIndex"))
        assertNull(url.queryParameter("Limit"))
        // The token travels in the header only
        assertFalse("secret-token" in url.toString())
        assertTrue("Token=\"secret-token\"" in request.headers["X-Emby-Authorization"]!!)
    }

    @Test
    fun randomPickFollowsTheGridFilters() = runBlocking {
        respond(200, """{"Items":[{"Id":"s1","Name":"Dark","Type":"Series"}],"TotalRecordCount":42}""")
        val options = ItemsOptions(SortKey.Rating, descending = true, watch = WatchFilter.Unplayed, favorites = true, genre = "Drame")
        val picked = client.withToken("t").randomLibraryItem("u1", "lib1", "Series", options)
        assertEquals("s1", picked?.id)
        assertEquals("Series", picked?.type)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        val url = request.url
        assertEquals("/emby/Users/u1/Items", url.encodedPath)
        for ((name, value) in listOf(
            "ParentId" to "lib1", "Recursive" to "true", "IncludeItemTypes" to "Series",
            "Filters" to "IsUnplayed,IsFavorite", "Genres" to "Drame", "SortBy" to "Random", "Limit" to "1",
        )) {
            assertEquals(name, value, url.queryParameter(name))
        }
        assertNull(url.queryParameter("SortOrder"))
    }

    @Test
    fun randomPickWithNoMatchIsNull() = runBlocking {
        respond(200, """{"Items":[],"TotalRecordCount":0}""")
        assertNull(client.withToken("t").randomLibraryItem("u1", "lib1", "Movie", ItemsOptions(favorites = true)))
    }

    @Test
    fun studioFiltersTheGridItsLettersAndItsRandomPickByName() = runBlocking {
        val options = ItemsOptions(studio = "Les Films du Losange")
        respond(200, """{"Items":[],"TotalRecordCount":0}""")
        client.withToken("t").libraryItems("u1", "lib1", "Movie", 0, 60, options)
        respond(200, """{"Items":[],"TotalRecordCount":0}""")
        client.withToken("t").sortNames("u1", "lib1", "Movie", options)
        respond(200, """{"Items":[],"TotalRecordCount":0}""")
        client.withToken("t").randomLibraryItem("u1", "lib1", "Movie", options)
        repeat(3) {
            val url = server.takeRequest().url
            assertEquals("Les Films du Losange", url.queryParameter("Studios"))
            assertNull(url.queryParameter("StudioIds"))
        }
    }

    @Test
    fun aStudioWithTheSeparatorIsNeverSent() = runBlocking {
        respond(200, """{"Items":[],"TotalRecordCount":0}""")
        client.withToken("t").libraryItems("u1", "lib1", "Series", 0, 60, ItemsOptions(studio = "HBO|Max"))
        assertNull(server.takeRequest().url.queryParameter("Studios"))
    }

    @Test
    fun numericStudioIdsLeaveTheItemReadable() = runBlocking {
        // Emby 4.10 sends `Studios[].Id` as a number: only the name is read
        respond(200, """{"Items":[{"Id":"m1","Name":"Dune","Type":"Movie","Studios":[{"Name":"Legendary","Id":4321}]}],"TotalRecordCount":1}""")
        val page = client.withToken("t").libraryItems("u1", "lib1", "Movie", 0, 60)
        assertEquals(listOf("Legendary"), page.items.single().studios.map { it.name })
    }

    @Test
    fun decadeFiltersTheGridItsLettersAndItsRandomPick() = runBlocking {
        val options = ItemsOptions(genre = "Drame", decade = 1990)
        val years = "1990,1991,1992,1993,1994,1995,1996,1997,1998,1999"
        respond(200, """{"Items":[],"TotalRecordCount":0}""")
        client.withToken("t").libraryItems("u1", "lib1", "Movie", 0, 60, options)
        respond(200, """{"Items":[],"TotalRecordCount":0}""")
        client.withToken("t").sortNames("u1", "lib1", "Movie", options)
        respond(200, """{"Items":[],"TotalRecordCount":0}""")
        client.withToken("t").randomLibraryItem("u1", "lib1", "Movie", options)
        repeat(3) {
            val url = server.takeRequest().url
            assertEquals(years, url.queryParameter("Years"))
            assertEquals("Drame", url.queryParameter("Genres"))
        }
    }

    @Test
    fun noDecadeOrAnOddOneSendsNoYears() = runBlocking {
        respond(200, """{"Items":[],"TotalRecordCount":0}""")
        client.withToken("t").libraryItems("u1", "lib1", "Movie", 0, 60, ItemsOptions())
        respond(200, """{"Items":[],"TotalRecordCount":0}""")
        client.withToken("t").libraryItems("u1", "lib1", "Movie", 0, 60, ItemsOptions(decade = 1995))
        repeat(2) { assertNull(server.takeRequest().url.queryParameter("Years")) }
    }

    @Test
    fun decadesAreReadFromProductionYears() = runBlocking {
        respond(
            200,
            """{"Items":[{"Id":"m1","ProductionYear":1994},{"Id":"m2"},{"Id":"m3","ProductionYear":2021},{"Id":"m4","ProductionYear":1999}],"TotalRecordCount":4}""",
        )
        assertEquals(listOf(2020, 1990), client.withToken("secret-token").decades("u1", "lib1", "Movie"))
        val request = server.takeRequest()
        val url = request.url
        assertEquals("/emby/Users/u1/Items", url.encodedPath)
        for ((name, value) in listOf(
            "ParentId" to "lib1", "Recursive" to "true", "IncludeItemTypes" to "Movie",
            "Fields" to "ProductionYear", "EnableImages" to "false", "EnableUserData" to "false",
        )) {
            assertEquals(name, value, url.queryParameter(name))
        }
        // The whole library, every filter aside
        assertNull(url.queryParameter("Limit"))
        assertNull(url.queryParameter("Years"))
        assertFalse("secret-token" in url.toString())
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

    /** The request of a write action (ADR 0097): its method and path, no body, the token in the header only. */
    private fun assertWrite(method: String, path: String, query: String?) {
        val request = server.takeRequest()
        assertEquals(method, request.method)
        assertEquals(path, request.url.encodedPath)
        assertEquals(query, request.url.query)
        assertFalse("no token in the URL", "secret-token" in request.url.toString())
        assertTrue(request.headers["X-Emby-Authorization"]!!.endsWith(", Token=\"secret-token\""))
        assertEquals(0, request.body?.size ?: 0)
    }

    @Test
    fun markingPlayedPostsAndReadsTheNewUserData() = runBlocking {
        respond(200, """{"Played":true,"PlaybackPositionTicks":0,"IsFavorite":false,"PlayCount":1}""")
        val data = client.withToken("secret-token").setPlayed("u1", "m1", played = true)
        assertTrue(data.played)
        assertEquals(0L, data.playbackPositionTicks)
        assertWrite("POST", "/emby/Users/u1/PlayedItems/m1", null)
    }

    @Test
    fun markingUnplayedDeletes() = runBlocking {
        respond(200, """{"Played":false,"UnplayedItemCount":12}""")
        val data = client.withToken("secret-token").setPlayed("u1", "s1", played = false)
        assertFalse(data.played)
        assertEquals(12, data.unplayedItemCount)
        assertWrite("DELETE", "/emby/Users/u1/PlayedItems/s1", null)
    }

    @Test
    fun favouritesArePostedAndDeleted() = runBlocking {
        respond(200, """{"IsFavorite":true}""")
        respond(200, """{"IsFavorite":false}""")
        val c = client.withToken("secret-token")
        assertTrue(c.setFavorite("u1", "m1", favorite = true).isFavorite)
        assertWrite("POST", "/emby/Users/u1/FavoriteItems/m1", null)
        assertFalse(c.setFavorite("u1", "m1", favorite = false).isFavorite)
        assertWrite("DELETE", "/emby/Users/u1/FavoriteItems/m1", null)
    }

    @Test
    fun hidingFromResumePostsTheHideFlag() = runBlocking {
        respond(200, """{"Played":false,"PlaybackPositionTicks":420000000}""")
        respond(200, """{"Played":false}""")
        val c = client.withToken("secret-token")
        assertEquals(420000000L, c.hideFromResume("u1", "e1", hide = true).playbackPositionTicks)
        assertWrite("POST", "/emby/Users/u1/Items/e1/HideFromResume", "Hide=true")
        c.hideFromResume("u1", "e1", hide = false)
        assertWrite("POST", "/emby/Users/u1/Items/e1/HideFromResume", "Hide=false")
    }

    @Test
    fun writeActionsNeedASession() = expectError(EmbyError.NotSignedIn::class.java) {
        client.setPlayed("u1", "m1", played = true)
    }

    @Test
    fun writeActionsRejectAnIdThatWouldChangeThePath() {
        expectError(EmbyError.InvalidResponse::class.java) { client.withToken("t").setPlayed("u1", "../Users", played = true) }
        expectError(EmbyError.InvalidResponse::class.java) { client.withToken("t").setFavorite("../u1", "m1", favorite = true) }
        expectError(EmbyError.InvalidResponse::class.java) { client.withToken("t").hideFromResume("u1", "e1/x", hide = true) }
        assertEquals("nothing sent", 0, server.requestCount)
    }

    @Test
    fun writeActionErrorsMapToCategories() {
        respond(401)
        expectError(EmbyError.SessionExpired::class.java) { client.withToken("t").setPlayed("u1", "m1", played = true) }
        respond(404)
        expectError(EmbyError.NotFound::class.java) { client.withToken("t").setFavorite("u1", "m1", favorite = false) }
        respond(500)
        expectError(EmbyError.Server::class.java) { client.withToken("t").hideFromResume("u1", "e1", hide = true) }
        respond(200, "pas du json")
        expectError(EmbyError.InvalidResponse::class.java) { client.withToken("t").setPlayed("u1", "m1", played = false) }
    }
}