package io.github.quentinpn.viewer.tv.emby

import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class SearchTest {
    private val server = MockWebServer()
    private lateinit var client: EmbyClient

    @Before
    fun setUp() {
        server.start()
        val url = ServerUrl.parse("http://127.0.0.1:${server.port}")
        client = EmbyClient(url, DeviceIdentity("Shield", "dev-1", "0.1.0"), EmbyClient.defaultHttp()).withToken("secret-token")
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun items(type: String, names: List<String>, seriesName: String? = null) = names.mapIndexed { i, name ->
        """{"Id":"$type$i","Name":"$name","Type":"$type"${seriesName?.let { ",\"SeriesName\":\"$it\",\"SeriesId\":\"s1\"" } ?: ""}}"""
    }.joinToString(",", """{"Items":[""", """],"TotalRecordCount":0}""")

    /** Answers each type apart, as the three requests travel at once. */
    private fun answer(movies: String, series: String, episodes: String, failSeries: Int? = null) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val type = request.url.queryParameter("IncludeItemTypes")
                if (type == "Series" && failSeries != null) return MockResponse.Builder().code(failSeries).build()
                val body = when (type) {
                    "Movie" -> movies
                    "Series" -> series
                    else -> episodes
                }
                return MockResponse.Builder().code(200).body(body).build()
            }
        }
    }

    @Test
    fun searchSendsTheWindowsQuery() = runBlocking {
        answer(items("Movie", listOf("Heat")), items("Series", emptyList()), items("Episode", emptyList()))
        client.search("u1", "chien flic", "Movie", 25)
        val request = server.takeRequest()
        val url = request.url
        assertEquals("/emby/Users/u1/Items", url.encodedPath)
        for ((name, value) in listOf(
            "SearchTerm" to "chien flic", "Recursive" to "true", "IncludeItemTypes" to "Movie", "Limit" to "25",
            "Fields" to "PrimaryImageAspectRatio,ProductionYear", "EnableImageTypes" to "Primary",
            "ImageTypeLimit" to "1", "EnableUserData" to "true",
        )) {
            assertEquals(name, value, url.queryParameter(name))
        }
        assertEquals(8, url.querySize)
        assertFalse("token never in the URL", "secret-token" in url.toString())
        assertTrue("Token=\"secret-token\"" in request.headers["X-Emby-Authorization"]!!)
    }

    @Test
    fun episodesAskTheHomeCardImages() = runBlocking {
        answer(items("Movie", emptyList()), items("Series", emptyList()), items("Episode", emptyList()))
        client.search("u1", "shizu", "Episode", 100)
        val url = server.takeRequest().url
        assertEquals("Episode", url.queryParameter("IncludeItemTypes"))
        assertEquals("100", url.queryParameter("Limit"))
        assertEquals("PrimaryImageAspectRatio", url.queryParameter("Fields"))
        assertEquals("Primary,Backdrop,Thumb", url.queryParameter("EnableImageTypes"))
    }

    @Test
    fun searchTitlesAsksThreeTypesAndFlagsMore() = runBlocking {
        answer(
            items("Movie", (1..25).map { "Heure $it" }),
            items("Series", listOf("24 Heures chrono")),
            // The series name matches, not these titles: only "Une heure de trop" stays
            items("Episode", listOf("Shizu", "Une heure de trop", "Charybde"), seriesName = "Heure"),
        )
        val results = searchTitles(client, "u1", "  heure\u0007 ")
        assertEquals("heure", results.query)
        assertEquals(SEARCH_SHOWN, results.movies.size)
        assertTrue(results.moreMovies)
        assertEquals(listOf("24 Heures chrono"), results.series.map { it.name })
        assertFalse(results.moreSeries)
        assertEquals(listOf("Une heure de trop"), results.episodes.map { it.name })
        assertFalse(results.moreEpisodes)
        val asked = List(3) { server.takeRequest().url }.associateBy { it.queryParameter("IncludeItemTypes") }
        assertEquals(setOf("Movie", "Series", "Episode"), asked.keys)
        assertEquals("25", asked["Movie"]!!.queryParameter("Limit"))
        assertEquals("25", asked["Series"]!!.queryParameter("Limit"))
        assertEquals("100", asked["Episode"]!!.queryParameter("Limit"))
        assertTrue(asked.values.all { it.queryParameter("SearchTerm") == "heure" })
    }

    @Test
    fun aFullEpisodePageMayHideMore() = runBlocking {
        answer(items("Movie", emptyList()), items("Series", emptyList()), items("Episode", List(100) { "Autre" }, seriesName = "Heure"))
        val results = searchTitles(client, "u1", "heure")
        assertTrue(results.episodes.isEmpty())
        assertTrue(results.moreEpisodes)
    }

    @Test
    fun shortTermReachesNoServer() = runBlocking {
        val results = searchTitles(client, "u1", " a ")
        assertEquals("a", results.query)
        assertTrue(results.isEmpty)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun oneFailedTypeFailsTheSearch() {
        answer(items("Movie", emptyList()), "", items("Episode", emptyList()), failSeries = 401)
        try {
            runBlocking { searchTitles(client, "u1", "heure") }
            fail("expected SessionExpired")
        } catch (e: EmbyException) {
            assertEquals(EmbyError.SessionExpired, e.error)
        }
    }

    @Test
    fun cleanSearchKeepsSingleSpacesAndBoundsLength() {
        assertEquals("chien flic", cleanSearch("  chien \t\n flic  "))
        assertEquals("ab", cleanSearch("a\u0000b"))
        assertEquals(SEARCH_MAX_CHARS, cleanSearch("x".repeat(150)).length)
        // Code points, not UTF-16 units, as Rust's chars()
        assertEquals(SEARCH_MAX_CHARS, cleanSearch("😀".repeat(150)).codePointCount(0, 200))
        // Cut after a space: no space left at the end
        assertEquals("a".repeat(99), cleanSearch("a".repeat(99) + " bc"))
        assertFalse(isSearchable("a"))
        assertTrue(isSearchable("ab"))
        assertFalse("one emoji is one character", isSearchable("😀"))
    }

    @Test
    fun titleMatchesWordStartsIgnoringCaseAndAccents() {
        assertTrue(titleMatches("Heureux Retours", "heureux"))
        assertTrue(titleMatches("Moins d'une heure", "HÉURE"))
        assertTrue(titleMatches("L'arnaque", "arnaq"))
        assertTrue(titleMatches("Rex, chien flic", "chien flic"))
        assertTrue(titleMatches("Él Niño", "el nino"))
        assertFalse("series name only", titleMatches("Shizu", "reincarne"))
        assertFalse("inside a word", titleMatches("Les heures", "eures"))
        assertFalse("the ligature is not folded, as on the server", titleMatches("Nos cœurs", "coeur"))
        assertFalse(titleMatches("Titre", ""))
    }
}
