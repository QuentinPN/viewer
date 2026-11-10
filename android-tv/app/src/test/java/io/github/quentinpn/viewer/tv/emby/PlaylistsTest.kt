package io.github.quentinpn.viewer.tv.emby

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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

class PlaylistsTest {
    private val server = MockWebServer()
    private lateinit var client: EmbyClient
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server.start()
        client = EmbyClient(ServerUrl.parse("http://127.0.0.1:${server.port}"), DeviceIdentity("Shield", "dev-1", "0.1.0"), EmbyClient.defaultHttp())
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun respond(body: String) = server.enqueue(MockResponse.Builder().code(200).body(body).build())

    private fun item(id: String, type: String, mediaType: String? = null) = BaseItemDto(id = id, name = "Item $id", type = type, mediaType = mediaType)

    @Test
    fun playlistsQueryAsksForPlaylistsWithoutMediaTypes() {
        val query = playlistsQuery().toMap()
        assertEquals("Playlist", query["IncludeItemTypes"])
        assertEquals("true", query["Recursive"])
        assertEquals("SortName", query["SortBy"])
        assertEquals("50", query["Limit"])
        assertEquals("ChildCount,PrimaryImageAspectRatio", query["Fields"])
        // MediaTypes=Video made Emby 4.10.2.0 answer every video instead of the playlists
        assertNull(query["MediaTypes"])
    }

    @Test
    fun onlyVideoPlaylistsAreKept() {
        val items = listOf(
            item("p1", "Playlist", "Video"),
            item("p2", "Playlist", "Audio"),
            item("p3", "Playlist"),
            item("m1", "Movie", "Video"),
            item("p1", "Playlist", "Video"),
        )
        assertEquals(listOf("p1", "p3"), videoPlaylists(items).map { it.id })
        assertEquals(emptyList<BaseItemDto>(), videoPlaylists(emptyList()))
    }

    @Test
    fun entriesKeepThePlaylistOrderWithFilmsAndEpisodesOnly() {
        val items = listOf(item("e2", "Episode"), item("m1", "Movie"), item("a1", "Audio"), item("e1", "Episode"), item("m1", "Movie"), item("t1", "Trailer"))
        assertEquals(listOf("e2", "m1", "e1"), playlistEntries(items).map { it.id })
    }

    @Test
    fun aPlaylistAndItsEntriesDecodeFromTheDocumentedShapes() {
        // BaseItemDto as documented: MediaType string, ChildCount int?, PlaylistItemId string (ignored)
        val playlists = json.decodeFromString(
            QueryResult.serializer(BaseItemDto.serializer()),
            """{"Items":[{"Id":"88001","Name":"Soirée","Type":"Playlist","MediaType":"Video","ChildCount":3,"IsFolder":true,"ImageTags":{"Primary":"ab"}}],"TotalRecordCount":1}""",
        )
        val playlist = playlists.items.single()
        assertEquals("Video", playlist.mediaType)
        assertEquals(3, playlist.childCount)
        val entries = json.decodeFromString(
            QueryResult.serializer(BaseItemDto.serializer()),
            """{"Items":[{"Id":"7022","Name":"Épisode","Type":"Episode","MediaType":"Video","SeriesName":"La casa de papel","SeriesId":"7018","SeasonId":"7019",""" +
                """"ParentIndexNumber":5,"IndexNumber":2,"RunTimeTicks":31327040000,"PlaylistItemId":"12"},""" +
                """{"Id":"m1","Name":"Dune","Type":"Movie","MediaType":"Video","ProductionYear":2021,"PlaylistItemId":"13"}],"TotalRecordCount":2}""",
        )
        assertEquals(listOf("7022", "m1"), playlistEntries(entries.items).map { it.id })
        assertEquals("La casa de papel", entries.items[0].seriesName)
    }

    @Test
    fun playlistsAsksTheItemsOfTheUserWithTheTokenInTheHeaderOnly() = runBlocking {
        respond("""{"Items":[{"Id":"88001","Name":"Soirée","Type":"Playlist","MediaType":"Video","ChildCount":3}],"TotalRecordCount":1}""")
        val result = client.withToken("secret-token").playlists("u1")
        assertEquals(listOf("88001"), result.items.map { it.id })
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/emby/Users/u1/Items", request.url.encodedPath)
        assertEquals("Playlist", request.url.queryParameter("IncludeItemTypes"))
        assertEquals("true", request.url.queryParameter("Recursive"))
        assertFalse("no token in the URL", "secret-token" in request.url.toString())
        assertNull(request.url.queryParameter("api_key"))
        assertNull(request.headers["X-Emby-Token"])
        assertTrue("Token=\"secret-token\"" in request.headers["X-Emby-Authorization"]!!)
    }

    @Test
    fun playlistItemsAsksThePlaylistServiceForOnePlaylist() = runBlocking {
        respond("""{"Items":[{"Id":"m1","Name":"Dune","Type":"Movie"}],"TotalRecordCount":1}""")
        val result = client.withToken("secret-token").playlistItems("u1", "88001")
        assertEquals(listOf("m1"), result.items.map { it.id })
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        val url = request.url
        assertEquals("/emby/Playlists/88001/Items", url.encodedPath)
        for ((name, value) in listOf(
            "UserId" to "u1", "Limit" to "300", "EnableUserData" to "true", "ImageTypeLimit" to "1", "EnableImageTypes" to "Primary,Backdrop,Thumb",
        )) {
            assertEquals(name, value, url.queryParameter(name))
        }
        assertNull(url.queryParameter("SortBy"))
        assertFalse("no token in the URL", "secret-token" in url.toString())
        assertNull(url.queryParameter("api_key"))
        assertTrue("Token=\"secret-token\"" in request.headers["X-Emby-Authorization"]!!)
    }

    @Test
    fun playlistItemsRejectsAnIdThatWouldChangeThePath() {
        for (call in listOf<suspend () -> Unit>(
            { client.withToken("t").playlistItems("u1", "../Users") },
            { client.withToken("t").playlistItems("../u1", "88001") },
            { client.withToken("t").playlists("u1/../x") },
        )) {
            try {
                runBlocking { call() }
                fail("expected InvalidResponse")
            } catch (e: EmbyException) {
                assertEquals(EmbyError.InvalidResponse::class.java, e.error::class.java)
            }
        }
        assertEquals(0, server.requestCount)
    }
}
