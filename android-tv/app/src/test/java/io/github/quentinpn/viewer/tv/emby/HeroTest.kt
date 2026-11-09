package io.github.quentinpn.viewer.tv.emby

import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

class HeroTest {
    private val started = UserItemData(playbackPositionTicks = 6_000_000_000)

    private fun movie(id: String, backdrop: Boolean = true, userData: UserItemData? = null) = BaseItemDto(
        id = id,
        name = "Film $id",
        type = "Movie",
        productionYear = 2021,
        runTimeTicks = 93_000_000_000,
        overview = "  Un résumé.  ",
        backdropImageTags = if (backdrop) listOf("b-$id") else emptyList(),
        userData = userData,
    )

    private fun series(id: String, backdrop: Boolean = true) = BaseItemDto(
        id = id,
        name = "Série $id",
        type = "Series",
        productionYear = 2019,
        runTimeTicks = 27_000_000_000,
        backdropImageTags = if (backdrop) listOf("b-$id") else emptyList(),
    )

    private fun episode(id: String, seriesId: String, backdrop: Boolean = true) = BaseItemDto(
        id = id,
        name = " L'épisode ",
        type = "Episode",
        seriesId = seriesId,
        seriesName = "Série $seriesId",
        parentIndexNumber = 2,
        indexNumber = 5,
        overview = "Ce qui se passe.",
        parentBackdropItemId = seriesId,
        parentBackdropImageTags = if (backdrop) listOf("sb-$seriesId") else emptyList(),
        userData = started,
    )

    @Test
    fun aResumedEpisodeStandsForItsSeries() {
        val title = resumedHero(episode("e1", "s1"))!!
        assertEquals("s1", title.id)
        assertEquals("Series", title.type)
        assertEquals("Série s1", title.name)
        assertEquals(HeroImage("s1", "sb-s1"), title.backdrop)
        assertEquals(HeroEpisode("L'épisode", 2, 5), title.episode)
        assertEquals("e1", title.playId)
        assertEquals(6_000_000_000, title.startTicks)
        assertTrue(title.resumed)
        assertNull(title.year)
    }

    @Test
    fun aResumedFilmPlaysItselfFromItsPosition() {
        val title = resumedHero(movie("m1", userData = started))!!
        assertEquals("m1", title.playId)
        assertEquals(6_000_000_000, title.startTicks)
        assertEquals(93_000_000_000, title.runTimeTicks)
        assertEquals("Un résumé.", title.overview)
        assertTrue(title.resumed)
    }

    @Test
    fun titlesWithoutBackdropOrValidIdsAreDropped() {
        assertNull(resumedHero(movie("m1", backdrop = false)))
        assertNull(resumedHero(episode("e1", "s1", backdrop = false)))
        assertNull(resumedHero(episode("e1", "../s1")))
        assertNull(resumedHero(BaseItemDto(id = "x", type = "Audio")))
        assertNull(latestHero(series("s1", backdrop = false)))
        assertNull(latestHero(movie("a/b")))
        assertNull(latestHero(BaseItemDto(id = "x", type = "Episode", backdropImageTags = listOf("b"))))
        assertNull(latestHero(movie("m1").copy(name = "  ")))
    }

    @Test
    fun logosComeFromTheSeriesOrTheTitleItself() {
        val ep = episode("e1", "s1").copy(parentLogoItemId = "s1", parentLogoImageTag = "l1", runTimeTicks = 14_400_000_000)
        assertEquals(HeroImage("s1", "l1"), resumedHero(ep)!!.logo)
        assertEquals(14_400_000_000, resumedHero(ep)!!.playLength)
        assertNull(resumedHero(episode("e1", "s1"))!!.logo)
        assertNull(resumedHero(ep.copy(parentLogoItemId = "../s1"))!!.logo)
        assertNull(resumedHero(ep.copy(parentLogoImageTag = " "))!!.logo)
        val film = movie("m1", userData = started).copy(imageTags = mapOf("Logo" to "l2"))
        assertEquals(HeroImage("m1", "l2"), resumedHero(film)!!.logo)
        assertEquals(93_000_000_000, resumedHero(film)!!.playLength)
        // A series plays nothing until its episode is known
        assertNull(latestHero(series("s1"))!!.playLength)
        assertEquals(1_000L, latestHero(series("s1"))!!.withEpisode(BaseItemDto(id = "e9", type = "Episode", runTimeTicks = 1_000)).playLength)
    }

    @Test
    fun aLatestSeriesWaitsForItsEpisode() {
        val title = latestHero(series("s1"))!!
        assertNull(title.playId)
        assertNull(title.runTimeTicks)
        assertEquals(2019, title.year)
        val withEpisode = title.withEpisode(BaseItemDto(id = "e9", name = "Pilote", type = "Episode", parentIndexNumber = 1, indexNumber = 1))
        assertEquals("e9", withEpisode.playId)
        assertEquals(0, withEpisode.startTicks)
        assertEquals(HeroEpisode("Pilote", 1, 1), withEpisode.episode)
        // A first episode not watched but started: the button resumes it
        assertEquals(6_000_000_000, title.withEpisode(BaseItemDto(id = "e9", type = "Episode", userData = started)).startTicks)
    }

    @Test
    fun resumeTicksIgnoreWatchedOrUnstartedItems() {
        assertEquals(0, resumeTicks(BaseItemDto(id = "a")))
        assertEquals(0, resumeTicks(BaseItemDto(id = "a", userData = UserItemData(played = true, playbackPositionTicks = 5))))
        assertEquals(0, resumeTicks(BaseItemDto(id = "a", userData = UserItemData(playbackPositionTicks = -3))))
        assertEquals(5, resumeTicks(BaseItemDto(id = "a", userData = UserItemData(playbackPositionTicks = 5))))
    }

    @Test
    fun resumedFirstThenLatestOncePerTitleAtMostFive() {
        val resumed = listOf(episode("e1", "s1"), episode("e2", "s1"), movie("m1", userData = started), movie("m9", backdrop = false))
        val latest = listOf(series("s1"), movie("m1"), movie("m2"), series("s2"), movie("m3"), movie("m4"))
        val titles = heroTitles(resumed, latest)
        assertEquals(listOf("s1", "m1", "m2", "s2", "m3"), titles.map { it.id })
        // The series keeps the episode in progress, not the latest entry nor the second episode
        assertEquals("e1", titles[0].playId)
        assertEquals(listOf(true, true, false, false, false), titles.map { it.resumed })
        assertEquals(HERO_SIZE, titles.size)
    }

    @Test
    fun emptyWhenNothingQualifies() {
        assertEquals(emptyList<HeroTitle>(), heroTitles(listOf(movie("m1", backdrop = false)), listOf(series("s1", backdrop = false))))
        assertEquals(listOf("m1"), heroTitles(emptyList(), listOf(movie("m1"), movie("m2")), size = 1).map { it.id })
    }

    private val server = MockWebServer()
    private val seen = ConcurrentLinkedQueue<HttpUrl>()
    private var answers: Map<String, Pair<Int, String>> = emptyMap()
    private lateinit var client: EmbyClient

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request.url
                val (code, body) = answers[request.url.encodedPath] ?: (404 to "")
                return MockResponse.Builder().code(code).body(body).build()
            }
        }
        server.start()
        val url = ServerUrl.parse("http://127.0.0.1:${server.port}")
        client = EmbyClient(url, DeviceIdentity("Shield", "dev-1", "0.1.0"), EmbyClient.defaultHttp()).withToken("t")
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun heroAsksTheWindowsQueriesAndFindsEachSeriesEpisode() = runBlocking {
        answers = mapOf(
            "/emby/Users/u1/Items" to (
                200 to """{"Items":[
                    {"Id":"s1","Name":"Arcane","Type":"Series","BackdropImageTags":["b1"]},
                    {"Id":"s2","Name":"Vue","Type":"Series","BackdropImageTags":["b2"]},
                    {"Id":"m1","Name":"Dune","Type":"Movie","ProductionYear":2021,"BackdropImageTags":["b3"]}
                ],"TotalRecordCount":3}"""
                ),
            "/emby/Shows/s1/Episodes" to (200 to """{"Items":[{"Id":"e1","Name":"Bienvenue","Type":"Episode","ParentIndexNumber":1,"IndexNumber":1}]}"""),
            // A series with nothing left: no episode, only "Plus d'infos"
            "/emby/Shows/s2/Episodes" to (200 to """{"Items":[]}"""),
        )
        val titles = client.homeHero("u1", emptyList())
        assertEquals(listOf("s1", "s2", "m1"), titles.map { it.id })
        assertEquals(listOf("e1", null, "m1"), titles.map { it.playId })
        val latest = seen.single { it.encodedPath == "/emby/Users/u1/Items" }
        for ((name, value) in listOf(
            "Recursive" to "true",
            "IncludeItemTypes" to "Movie,Series",
            "IsPlayed" to "false",
            "ImageTypes" to "Backdrop",
            "SortBy" to "DateCreated",
            "SortOrder" to "Descending",
            "Limit" to "10",
            "Fields" to "Overview,ProductionYear,PrimaryImageAspectRatio",
            "EnableUserData" to "true",
        )) {
            assertEquals(name, value, latest.queryParameter(name))
        }
        val episodes = seen.first { it.encodedPath == "/emby/Shows/s1/Episodes" }
        assertEquals("u1", episodes.queryParameter("UserId"))
        assertEquals("false", episodes.queryParameter("IsPlayed"))
        assertEquals("1", episodes.queryParameter("Limit"))
        // Films get no episode request
        assertTrue(seen.none { it.encodedPath == "/emby/Shows/m1/Episodes" })
        assertTrue(seen.all { it.queryParameter("api_key") == null })
    }

    @Test
    fun anEpisodeThatCannotBeReadLeavesTheSeriesWithoutPlay() = runBlocking {
        answers = mapOf("/emby/Users/u1/Items" to (200 to """{"Items":[{"Id":"s1","Name":"Arcane","Type":"Series","BackdropImageTags":["b1"]}]}"""))
        assertNull(client.homeHero("u1", emptyList()).single().playId)
    }

    @Test
    fun anExpiredSessionIsNotHidden() = runBlocking {
        answers = mapOf(
            "/emby/Users/u1/Items" to (200 to """{"Items":[{"Id":"s1","Name":"Arcane","Type":"Series","BackdropImageTags":["b1"]}]}"""),
            "/emby/Shows/s1/Episodes" to (401 to ""),
        )
        try {
            client.homeHero("u1", emptyList())
            fail("401 swallowed")
        } catch (e: EmbyException) {
            assertEquals(EmbyError.SessionExpired, e.error)
        }
    }
}
