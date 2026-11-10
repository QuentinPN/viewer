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

class FavoritesTest {
    private val server = MockWebServer()
    private lateinit var client: EmbyClient

    @Before
    fun setUp() {
        server.start()
        client = EmbyClient(ServerUrl.parse("http://127.0.0.1:${server.port}"), DeviceIdentity("Shield", "dev-1", "0.1.0"), EmbyClient.defaultHttp())
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun item(id: String, type: String) = BaseItemDto(id = id, name = "Item $id", type = type)

    @Test
    fun favoritesQueryIsTheWindowsQuery() {
        val query = favoritesQuery().toMap()
        assertEquals("IsFavorite", query["Filters"])
        assertEquals("true", query["Recursive"])
        assertEquals("Movie,Series", query["IncludeItemTypes"])
        assertEquals("SortName", query["SortBy"])
        assertEquals("Ascending", query["SortOrder"])
        assertEquals("16", query["Limit"])
        assertEquals("PrimaryImageAspectRatio,ProductionYear", query["Fields"])
        assertEquals("true", query["EnableUserData"])
        assertEquals("Primary", query["EnableImageTypes"])
        // Across every library: no parent
        assertNull(query["ParentId"])
    }

    @Test
    fun onlyFilmsAndSeriesWithAUsableIdAreKeptOnceInTheServerOrder() {
        val items = listOf(
            item("s1", "Series"),
            item("m1", "Movie"),
            item("b1", "BoxSet"),
            item("e1", "Episode"),
            item("../x", "Movie"),
            item("", "Movie"),
            item("m1", "Movie"),
            item("m2", "Movie"),
        )
        assertEquals(listOf("s1", "m1", "m2"), favoriteTitles(items).map { it.id })
        assertEquals(emptyList<BaseItemDto>(), favoriteTitles(emptyList()))
    }

    @Test
    fun noMoreThanTheLimitIsKept() {
        val items = (1..20).map { item("m$it", "Movie") }
        assertEquals((1..16).map { "m$it" }, favoriteTitles(items).map { it.id })
        assertEquals(listOf("m1", "m2"), favoriteTitles(items, limit = 2).map { it.id })
    }

    @Test
    fun favoritesAsksTheItemsOfTheUserWithTheTokenInTheHeaderOnly() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """{"Items":[{"Id":"30207","Name":"2 Coeurs","Type":"Movie","ProductionYear":2020,"ImageTags":{"Primary":"7b"},""" +
                    """"UserData":{"IsFavorite":true,"Played":false}},{"Id":"80473","Name":"Arcane","Type":"Series",""" +
                    """"UserData":{"IsFavorite":true,"UnplayedItemCount":18}}],"TotalRecordCount":2}""",
            ).build(),
        )
        val result = client.withToken("secret-token").favorites("u1")
        assertEquals(listOf("30207", "80473"), favoriteTitles(result.items).map { it.id })
        assertEquals(18, result.items[1].userData?.unplayedItemCount)
        val request = server.takeRequest()
        // Read only: never the POST or DELETE of /FavoriteItems
        assertEquals("GET", request.method)
        val url = request.url
        assertEquals("/emby/Users/u1/Items", url.encodedPath)
        for ((name, value) in listOf(
            "Filters" to "IsFavorite", "Recursive" to "true", "IncludeItemTypes" to "Movie,Series",
            "SortBy" to "SortName", "SortOrder" to "Ascending", "Limit" to "16", "EnableUserData" to "true",
        )) {
            assertEquals(name, value, url.queryParameter(name))
        }
        assertFalse("no token in the URL", "secret-token" in url.toString())
        assertNull(url.queryParameter("api_key"))
        assertNull(request.headers["X-Emby-Token"])
        assertTrue("Token=\"secret-token\"" in request.headers["X-Emby-Authorization"]!!)
    }

    @Test
    fun favoritesRejectsAUserIdThatWouldChangeThePath() {
        try {
            runBlocking { client.withToken("t").favorites("u1/../x") }
            fail("expected InvalidResponse")
        } catch (e: EmbyException) {
            assertEquals(EmbyError.InvalidResponse::class.java, e.error::class.java)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun favoritesNeedsASignedInClient() {
        try {
            runBlocking { client.favorites("u1") }
            fail("expected NotSignedIn")
        } catch (e: EmbyException) {
            assertEquals(EmbyError.NotSignedIn, e.error)
        }
        assertEquals(0, server.requestCount)
    }
}
