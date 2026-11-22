package io.github.quentinpn.viewer.tv.emby

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// Real answers of 2026-11-07, as in src-tauri/src/segments/tests.rs.
private const val INTRODB_TV = """{"imdb_id":"tt0903747","media_type":"tv","is_movie":false,"season":1,"episode":1,
  "intro":null,"recap":null,
  "outro":{"start_sec":3431,"end_sec":3500,"start_ms":3431000,"end_ms":3500000,"confidence":1,"submission_count":1,"updated_at":"2026-04-14T12:21:01.935Z"},
  "post_credits":null,"no_intro":true,"no_recap":true,"no_outro":false}"""
private const val INTRODB_EMPTY = """{"imdb_id":"tt9999998","media_type":"movie","is_movie":true,"season":0,"episode":0,
  "intro":null,"recap":null,"outro":null,"post_credits":null,"no_intro":false,"no_recap":false,"no_outro":false}"""
private const val INTRODB_INTRO = """{"imdb_id":"tt0903747","media_type":"tv","is_movie":false,"season":1,"episode":1,
  "intro":{"start_sec":2.5,"end_sec":58,"start_ms":2500,"end_ms":58000,"confidence":0.92,"submission_count":14,"updated_at":"2026-09-12T21:03:17.409Z"},
  "recap":null,"outro":null,"post_credits":null,"no_intro":false,"no_recap":true,"no_outro":false}"""
private const val THEINTRODB_TV = """{"tvdb_id":71663,"type":"tv","season":1,"episode":1,
  "intro":[{"start_ms":null,"end_ms":24724}],
  "credits":[{"start_ms":1332112,"end_ms":1441100}]}"""
private const val THEINTRODB_MOVIE = """{"tvdb_id":169,"type":"movie",
  "intro":[{"start_ms":null,"end_ms":23000}],
  "recap":[{"start_ms":25000,"end_ms":134000}],
  "credits":[{"start_ms":5801777,"end_ms":6371111},{"start_ms":6408000,"end_ms":null}],
  "preview":[{"start_ms":1680000,"end_ms":1740000}]}"""
private const val THEINTRODB_404 = """{"error":"no submissions for this media","code":"no_submissions"}"""

class SegmentsTest {
    private val server = MockWebServer()
    private lateinit var sources: SegmentSources

    @Before
    fun setUp() {
        server.start()
        sources = SegmentSources(SegmentSources.http(EmbyClient.defaultHttp(), "0.1.0"), server.url("/introdb/"), server.url("/theintrodb/v4/"))
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun respond(code: Int, body: String = "") = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private fun seg(kind: SegmentKind, start: Double, end: Double?, source: SegmentSource) = Segment.of(kind, start, end, source)!!

    private val episodeQuery = SegmentQuery(
        MediaIds.from(mapOf("Tvdb" to "71663", "IMDB" to "tt0903747", "Tmdb" to "2406")),
        1 to 1,
        1_441_100,
    )

    @Test
    fun embyMarkersGiveIntroAndCreditsToTheEnd() {
        val chapters = listOf(
            ChapterInfo(0, "Chapitre 1", "Chapter"),
            ChapterInfo(300_000_000, null, "IntroStart"),
            ChapterInfo(900_000_000, null, "IntroEnd"),
            ChapterInfo(24_000_000_000, null, "CreditsStart"),
        )
        assertEquals(
            listOf(seg(SegmentKind.Intro, 30.0, 90.0, SegmentSource.Emby), seg(SegmentKind.Credits, 2400.0, null, SegmentSource.Emby)),
            embyMarkers(chapters),
        )
        assertTrue("an intro needs both markers", embyMarkers(listOf(ChapterInfo(300_000_000, null, "IntroStart"))).isEmpty())
        assertTrue(embyMarkers(listOf(ChapterInfo(0, "Chapitre 1", "Chapter"))).isEmpty())
    }

    @Test
    fun segmentsOutOfRangeAreDropped() {
        assertNull(Segment.of(SegmentKind.Intro, -1.0, 10.0, SegmentSource.IntroDb))
        assertNull(Segment.of(SegmentKind.Intro, 10.0, 10.5, SegmentSource.IntroDb))
        assertNull(Segment.of(SegmentKind.Intro, 10.0, null, SegmentSource.IntroDb))
        assertNull(Segment.of(SegmentKind.Credits, 10.0, 7.0 * 3600, SegmentSource.IntroDb))
        assertNull(Segment.of(SegmentKind.Credits, Double.NaN, null, SegmentSource.IntroDb))
    }

    @Test
    fun itemDecodesChaptersAndProviderIds() {
        val json = Json { ignoreUnknownKeys = true }
        val item = json.decodeFromString(
            BaseItemDto.serializer(),
            """{"Id":"e1","Type":"Episode","ProviderIds":{"Tvdb":"1","Imdb":"tt0903747"},
              "Chapters":[{"StartPositionTicks":0,"Name":"Chapitre 1","MarkerType":"Chapter","ChapterIndex":0},
              {"StartPositionTicks":4875700000,"Name":"Chapitre 2","MarkerType":"Chapter","ChapterIndex":1}]}""",
        )
        assertEquals("tt0903747", item.providerIds["Imdb"])
        assertEquals(ChapterInfo(4_875_700_000, "Chapitre 2", "Chapter"), item.chapters[1])
        assertTrue(json.decodeFromString(BaseItemDto.serializer(), """{"Id":"m1"}""").chapters.isEmpty())
    }

    @Test
    fun providerIdsAreValidatedWhateverTheKeyCase() {
        assertEquals(MediaIds("tt0903747", 2406, 71663), MediaIds.from(mapOf("IMDB" to "tt0903747", "tmdb" to " 2406 ", "Tvdb" to "71663")))
        val bad = MediaIds.from(mapOf("Imdb" to "tt123", "Tmdb" to "-5", "Tvdb" to "12a"))
        assertTrue(bad.isEmpty)
        assertNull(MediaIds.from(mapOf("Imdb" to "tt0903747&x=1")).imdb)
        assertNull(MediaIds.from(mapOf("Tvdb" to "0")).tvdb)
    }

    @Test
    fun queryUsesTheSeriesIdsForAnEpisode() {
        val episode = BaseItemDto(
            id = "e1", type = "Episode", seriesId = "s1", parentIndexNumber = 2, indexNumber = 5,
            runTimeTicks = 14_411_000_000, providerIds = mapOf("Imdb" to "tt1111111"),
        )
        val series = BaseItemDto(id = "s1", type = "Series", providerIds = mapOf("IMDB" to "tt0903747"))
        assertEquals(SegmentQuery(MediaIds(imdb = "tt0903747"), 2 to 5, 1_441_100), segmentQuery(episode, series))
        assertNull("series unknown", segmentQuery(episode, null))
        assertNull("another series", segmentQuery(episode, series.copy(id = "s2")))
        assertNull("no numbers", segmentQuery(episode.copy(indexNumber = null), series))
        val movie = BaseItemDto(id = "m1", type = "Movie", providerIds = mapOf("Tmdb" to "550"))
        assertEquals(SegmentQuery(MediaIds(tmdb = 550)), segmentQuery(movie, null))
        assertNull("no ids", segmentQuery(movie.copy(providerIds = emptyMap()), null))
        assertNull(segmentQuery(BaseItemDto(id = "t1", type = "TvChannel"), null))
    }

    @Test
    fun introDbAnswers() {
        assertEquals(Found(listOf(seg(SegmentKind.Credits, 3431.0, 3500.0, SegmentSource.IntroDb)), setOf(SegmentKind.Intro)), parseIntroDb(INTRODB_TV))
        assertEquals(Found(listOf(seg(SegmentKind.Intro, 2.5, 58.0, SegmentSource.IntroDb))), parseIntroDb(INTRODB_INTRO))
        assertEquals(Found(), parseIntroDb(INTRODB_EMPTY))
        assertNull(parseIntroDb("<html>"))
    }

    @Test
    fun theIntroDbAnswersWithOpenEnds() {
        assertEquals(
            Found(listOf(seg(SegmentKind.Intro, 0.0, 24.724, SegmentSource.TheIntroDb), seg(SegmentKind.Credits, 1332.112, 1441.1, SegmentSource.TheIntroDb))),
            parseTheIntroDb(THEINTRODB_TV),
        )
        // Recap and preview are not offered; a null credits end runs to the end
        assertEquals(
            listOf(Triple(SegmentKind.Intro, 0.0, 23.0), Triple(SegmentKind.Credits, 5801.777, 6371.111), Triple(SegmentKind.Credits, 6408.0, null)),
            parseTheIntroDb(THEINTRODB_MOVIE)!!.segments.map { Triple(it.kind, it.start, it.end) },
        )
        // A 0 end is "no segment"
        val zero = parseTheIntroDb("""{"intro":[{"start_ms":null,"end_ms":0}],"credits":[{"start_ms":10,"end_ms":null}]}""")!!
        assertEquals(listOf(seg(SegmentKind.Credits, 0.01, null, SegmentSource.TheIntroDb)), zero.segments)
        assertNull(parseTheIntroDb("[]"))
    }

    @Test
    fun urlsCarryOnlyTheIdsAndNumbers() {
        assertEquals("/introdb/segments?imdb_id=tt0903747&season=1&episode=1", sources.introDbUrl(episodeQuery)!!.let { "${it.encodedPath}?${it.query}" })
        assertEquals(
            "/theintrodb/v4/media?tvdb_id=71663&duration_ms=1441100&season=1&episode=1",
            sources.theIntroDbUrl(episodeQuery)!!.let { "${it.encodedPath}?${it.query}" },
        )
        val movie = SegmentQuery(MediaIds(imdb = "tt0137523", tmdb = 550), null, 8_340_000)
        assertEquals("imdb_id=tt0137523&is_movie=true", sources.introDbUrl(movie)!!.query)
        assertEquals("tmdb_id=550&duration_ms=8340000", sources.theIntroDbUrl(movie)!!.query)
        assertNull("IntroDB takes IMDb only", sources.introDbUrl(SegmentQuery(MediaIds(tvdb = 1))))
    }

    @Test
    fun introDbSettlesBothKindsAndTheIntroDbIsNotAsked() = runBlocking {
        respond(200, INTRODB_TV)
        val markers = listOf(seg(SegmentKind.Intro, 30.0, 90.0, SegmentSource.Emby))
        val found = sources.lookup("k1", markers, episodeQuery)
        assertEquals(listOf(markers[0], seg(SegmentKind.Credits, 3431.0, 3500.0, SegmentSource.IntroDb)), found)
        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertNull("no Emby header", request.headers["X-Emby-Authorization"])
        assertEquals("Viewer TV/0.1.0", request.headers["User-Agent"])
        assertEquals(found, sources.cached("k1"))
    }

    @Test
    fun theIntroDbCompletesWhatIntroDbLacks() = runBlocking {
        respond(200, INTRODB_EMPTY)
        respond(200, THEINTRODB_TV)
        val found = sources.lookup("k2", emptyList(), episodeQuery)
        assertEquals(listOf(SegmentSource.TheIntroDb, SegmentSource.TheIntroDb), found.map { it.source })
        assertEquals("/introdb/segments", server.takeRequest().url.encodedPath)
        assertEquals("/theintrodb/v4/media", server.takeRequest().url.encodedPath)
    }

    @Test
    fun markersAloneAskNobody() = runBlocking {
        val markers = listOf(seg(SegmentKind.Intro, 30.0, 90.0, SegmentSource.Emby), seg(SegmentKind.Credits, 2400.0, null, SegmentSource.Emby))
        assertEquals(markers, sources.lookup("k3", markers, episodeQuery))
        assertEquals(markers, sources.lookup("k4", markers.reversed(), null))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun notFoundIsKeptButFailuresAreRetried() = runBlocking {
        respond(200, INTRODB_EMPTY)
        respond(404, THEINTRODB_404)
        assertTrue(sources.lookup("k5", emptyList(), episodeQuery).isEmpty())
        assertEquals(emptyList<Segment>(), sources.cached("k5"))

        respond(429)
        respond(500)
        assertTrue(sources.lookup("k6", emptyList(), episodeQuery).isEmpty())
        assertNull("429 and 5xx are not kept", sources.cached("k6"))

        respond(200, "<html>")
        respond(302)
        sources.lookup("k7", emptyList(), episodeQuery)
        assertNull("unreadable answer and redirect are not kept", sources.cached("k7"))
    }

    @Test
    fun oversizedAnswerIsRefused() = runBlocking {
        respond(200, """{"intro":null,"pad":"${"x".repeat(70 * 1024)}"}""")
        respond(404)
        sources.lookup("k8", emptyList(), episodeQuery)
        assertNull(sources.cached("k8"))
    }
}
