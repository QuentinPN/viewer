package io.github.quentinpn.viewer.tv.player

import io.github.quentinpn.viewer.tv.emby.DeviceIdentity
import io.github.quentinpn.viewer.tv.emby.EmbyClient
import io.github.quentinpn.viewer.tv.emby.ServerUrl
import io.github.quentinpn.viewer.tv.emby.TooLargeException
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

private fun jpeg(tag: Int) = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), tag.toByte(), 0xFF.toByte(), 0xD9.toByte())

class SeekThumbnailsTest {
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

    @Test
    fun downloadsTheItemsBifWithTheTokenInTheHeaderOnly() = runBlocking {
        val file = bif(listOf(jpeg(1), jpeg(2)))
        server.enqueue(MockResponse.Builder().body(Buffer().write(file)).build())
        val result = downloadThumbnails(client, "item1")
        assertArrayEquals(file, (result as SeekThumbnails.Result.Bytes).bytes)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/emby/Videos/item1/index.bif", request.url.encodedPath)
        assertEquals("Width=$THUMBNAIL_WIDTH", request.url.query)
        assertTrue("Token=\"secret-token\"" in request.headers["X-Emby-Authorization"]!!)
        assertNull(request.headers["X-Emby-Token"])
        assertFalse("secret-token" in request.url.toString())
    }

    @Test
    fun notFoundMeansNoThumbnails() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).build())
        assertNull(client.thumbnailBif("item1", THUMBNAIL_WIDTH, MAX_BIF_BYTES))
        server.enqueue(MockResponse.Builder().code(404).build())
        assertEquals(SeekThumbnails.Result.None(NoThumbnails.Missing), downloadThumbnails(client, "item1"))
    }

    @Test
    fun serverErrorMeansNoThumbnailsForThisFile() = runBlocking {
        server.enqueue(MockResponse.Builder().code(500).build())
        assertEquals(SeekThumbnails.Result.None(NoThumbnails.Failed), downloadThumbnails(client, "item1"))
    }

    @Test
    fun announcedSizeOverTheCapIsRefused() = runBlocking {
        server.enqueue(MockResponse.Builder().body(Buffer().write(ByteArray(64))).build())
        try {
            client.thumbnailBif("item1", THUMBNAIL_WIDTH, 32)
            fail("over the cap")
        } catch (_: TooLargeException) {
        }
    }

    @Test
    fun streamedSizeOverTheCapIsRefused() = runBlocking {
        // Chunked: no length announced, the read stops past the cap
        server.enqueue(MockResponse.Builder().chunkedBody(Buffer().write(ByteArray(200_000)), 1024).build())
        try {
            client.thumbnailBif("item1", THUMBNAIL_WIDTH, 100_000)
            fail("over the cap")
        } catch (_: TooLargeException) {
        }
    }

    @Test
    fun fetchesOnceAndAnswersFromMemory() = runBlocking {
        val file = bif(List(4) { jpeg(it) })
        var fetches = 0
        val outcomes = mutableListOf<ThumbnailsOutcome>()
        val thumbs = SeekThumbnails(this, { fetches++; SeekThumbnails.Result.Bytes(file) }) { outcomes += it }
        val first = thumbs.frameAt(25.0)!!
        assertEquals(2, first.index)
        assertArrayEquals(jpeg(2), first.bytes.copyOfRange(first.offset, first.offset + first.length))
        assertEquals(3, thumbs.frameAt(31.0)!!.index)
        assertEquals(0, thumbs.frameAt(0.0)!!.index)
        assertEquals(1, fetches)
        assertEquals(listOf(ThumbnailsOutcome.Loaded(4, 10_000, file.size)), outcomes)
        assertEquals("seek thumbnails: 4 images every 10000 ms (0 KB)", thumbnailsLog(outcomes.single()))
    }

    @Test
    fun noThumbnailsIsAskedOnceAndLoggedOnce() = runBlocking {
        var fetches = 0
        val outcomes = mutableListOf<ThumbnailsOutcome>()
        val thumbs = SeekThumbnails(this, { fetches++; SeekThumbnails.Result.None(NoThumbnails.Missing) }) { outcomes += it }
        assertNull(thumbs.frameAt(10.0))
        assertNull(thumbs.frameAt(20.0))
        assertEquals(1, fetches)
        assertEquals("seek thumbnails: none (not on the server)", thumbnailsLog(outcomes.single()))
    }

    @Test
    fun unreadableFileGivesNoThumbnails() = runBlocking {
        val outcomes = mutableListOf<ThumbnailsOutcome>()
        val thumbs = SeekThumbnails(this, { SeekThumbnails.Result.Bytes("<html></html>".toByteArray()) }) { outcomes += it }
        assertNull(thumbs.frameAt(10.0))
        assertEquals(listOf(ThumbnailsOutcome.None(NoThumbnails.Unreadable, "13 bytes starting 3c 68 74 6d 6c 3e 3c 2f 68 74 6d 6c 3e")), outcomes)
    }

    @Test
    fun closedBeforeTheFirstSeekFetchesNothing() = runBlocking {
        var fetches = 0
        val thumbs = SeekThumbnails(this, { fetches++; SeekThumbnails.Result.None(NoThumbnails.Missing) }) {}
        thumbs.close()
        assertNull(thumbs.frameAt(10.0))
        assertEquals(0, fetches)
    }
}
