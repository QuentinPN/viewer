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

class NextUpTest {
    private val server = MockWebServer()
    private lateinit var client: EmbyClient
    private val seen = ConcurrentLinkedQueue<HttpUrl>()

    /** Answers by path: the row reads `/Shows/NextUp` and the home sections at once. */
    private var answers: Map<String, Pair<Int, String>> = emptyMap()

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

    private fun url(path: String) = seen.single { it.encodedPath == path }

    @Test
    fun nextUpAsksTheWindowsQuery() = runBlocking {
        answers = mapOf("/emby/Shows/NextUp" to (200 to NEXT_UP))
        val page = client.nextUp("u1", 16)
        val episode = page.items.single()
        assertEquals("La casa de papel", episode.seriesName)
        assertEquals(5, episode.parentIndexNumber)
        assertEquals(4, episode.indexNumber)
        assertEquals("70185", episode.parentBackdropItemId)
        val url = url("/emby/Shows/NextUp")
        for ((name, value) in listOf(
            "UserId" to "u1",
            "Limit" to "16",
            "Fields" to "PrimaryImageAspectRatio",
            "EnableUserData" to "true",
            "ImageTypeLimit" to "1",
            "EnableImageTypes" to "Primary,Backdrop,Thumb",
        )) {
            assertEquals(name, value, url.queryParameter(name))
        }
        assertEquals(6, url.querySize)
    }

    @Test
    fun homeSectionsAreABareArray() = runBlocking {
        answers = mapOf("/emby/Users/u1/HomeSections" to (200 to HOME_SECTIONS))
        val sections = client.homeSections("u1")
        assertEquals(listOf("smalllibrarytiles", "resume", "latestmedia_5"), sections.map { it.id })
        assertEquals("resume", resumeSectionId(sections))
        assertNull(url("/emby/Users/u1/HomeSections").query)
    }

    @Test
    fun rowMergesShowsThenTheResumeSection() = runBlocking {
        answers = mapOf(
            "/emby/Shows/NextUp" to (200 to NEXT_UP),
            "/emby/Users/u1/HomeSections" to (200 to HOME_SECTIONS),
            "/emby/Users/u1/Sections/resume/Items" to (200 to RESUME_SECTION),
        )
        val row = client.nextUpRow("u1")
        // The film in progress and the started episode stay in "Reprendre"
        assertEquals(listOf("70229", "44443"), row.map { it.id })
        val section = url("/emby/Users/u1/Sections/resume/Items")
        assertEquals("16", section.queryParameter("Limit"))
        assertEquals("PrimaryImageAspectRatio", section.queryParameter("Fields"))
        assertEquals("Primary,Backdrop,Thumb", section.queryParameter("EnableImageTypes"))
    }

    @Test
    fun serverWithoutHomeSectionsKeepsShowsNextUp() = runBlocking {
        answers = mapOf("/emby/Shows/NextUp" to (200 to NEXT_UP))
        assertEquals(listOf("70229"), client.nextUpRow("u1").map { it.id })
        assertTrue(seen.none { it.encodedPath.contains("/Sections/") })
    }

    @Test
    fun expiredSessionIsNotSwallowed() {
        answers = mapOf("/emby/Shows/NextUp" to (401 to ""), "/emby/Users/u1/HomeSections" to (200 to "[]"))
        try {
            runBlocking { client.nextUpRow("u1") }
            fail("expected SessionExpired")
        } catch (e: EmbyException) {
            assertEquals(EmbyError.SessionExpired, e.error)
        }
    }

    @Test
    fun onlyUnstartedEpisodesUpToTheLimit() {
        fun ep(id: String, played: Boolean = false, position: Long = 0, type: String = "Episode") =
            BaseItemDto(id = id, type = type, userData = UserItemData(played = played, playbackPositionTicks = position))
        val shows = listOf(ep("a"), ep("b", played = true), ep("c", position = 10))
        val section = listOf(ep("a"), ep("m", type = "Movie"), ep("d"), BaseItemDto(id = "e", type = "Episode"), ep(""), ep("f"))
        assertEquals(listOf("a", "d", "e", "f"), mergeNextUp(shows, section, 16).map { it.id })
        assertEquals(listOf("a", "d"), mergeNextUp(shows, section, 2).map { it.id })
    }

    @Test
    fun resumeSectionOnlyWhenItIncludesNextUp() {
        assertNull(resumeSectionId(listOf(HomeSectionDto("resume", "resume", includeNextUpInResume = false))))
        assertNull(resumeSectionId(listOf(HomeSectionDto("x", "latestmedia", includeNextUpInResume = true))))
        assertNull(resumeSectionId(listOf(HomeSectionDto("../x", "resume", includeNextUpInResume = true))))
        assertNull(resumeSectionId(emptyList()))
    }

    @Test
    fun episodesInReprendreAreNotShownTwice() {
        val next = listOf(BaseItemDto(id = "e1"), BaseItemDto(id = "e2"), BaseItemDto(id = "e3"))
        val inProgress = listOf(BaseItemDto(id = "e2"), BaseItemDto(id = "m1"))
        assertEquals(listOf("e1", "e3"), withoutDuplicates(next, inProgress).map { it.id })
        assertEquals(next, withoutDuplicates(next, emptyList()))
    }

    private companion object {
        // Fixtures of the Windows client (src-tauri/src/emby/tests.rs), observed on Emby
        // 4.10.1.0; the started episode "44444" is added here to check it is left out
        const val NEXT_UP = """{"Items":[
            {"Name":"Épisode 4","Id":"70229","Type":"Episode","IndexNumber":4,"ParentIndexNumber":5,
             "SeriesName":"La casa de papel","SeriesId":"70185","RunTimeTicks":30000000000,
             "ImageTags":{},"ParentBackdropItemId":"70185","ParentBackdropImageTags":["6da1a59e_6384"],
             "UserData":{"PlaybackPositionTicks":0,"Played":false}}
        ],"TotalRecordCount":1}"""

        const val HOME_SECTIONS = """[
            {"Name":"Mes Médias","Id":"smalllibrarytiles","SectionType":"userviews","IncludeNextUpInResume":true},
            {"Name":"Reprendre la lecture","Id":"resume","SectionType":"resume","IncludeNextUpInResume":true},
            {"Name":"Films : Derniers ajouts","Id":"latestmedia_5","SectionType":"latestmedia","ParentId":"5"}
        ]"""

        const val RESUME_SECTION = """{"Items":[
            {"Name":"Runner","Id":"103617","Type":"Movie","RunTimeTicks":58680640000,"ImageTags":{},
             "UserData":{"PlaybackPositionTicks":6000000000,"Played":false}},
            {"Name":"01h00 - 02h00","Id":"44443","Type":"Episode","IndexNumber":2,"ParentIndexNumber":1,
             "SeriesName":"24 Heures chrono","SeriesId":"44440","RunTimeTicks":24705280000,
             "ImageTags":{"Primary":"df10f940_6381"},"ParentThumbItemId":"44440","ParentThumbImageTag":"858f5468_6381",
             "UserData":{"PlaybackPositionTicks":0,"PlayCount":0,"Played":false}},
            {"Name":"Épisode 4","Id":"70229","Type":"Episode","IndexNumber":4,"ParentIndexNumber":5,
             "SeriesName":"La casa de papel","SeriesId":"70185","ImageTags":{},
             "UserData":{"PlaybackPositionTicks":0,"Played":false}},
            {"Name":"Commencé","Id":"44444","Type":"Episode","IndexNumber":3,"ParentIndexNumber":1,
             "SeriesName":"24 Heures chrono","SeriesId":"44440",
             "UserData":{"PlaybackPositionTicks":1200000000,"Played":false}}
        ],"TotalRecordCount":4}"""
    }
}
