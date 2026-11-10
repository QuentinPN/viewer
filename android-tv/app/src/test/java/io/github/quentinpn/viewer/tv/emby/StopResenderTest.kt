package io.github.quentinpn.viewer.tv.emby

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** In memory, as the preferences would hold it: encoded text per account key. */
private class MemoryStorage : PendingStopStorage {
    val texts = mutableMapOf<String, String>()

    override fun keys() = texts.keys.toSet()

    override fun load(key: String) = decodeStops(texts[key])

    override fun save(key: String, stops: List<PendingStop>) {
        if (stops.isEmpty()) texts.remove(key) else texts[key] = encodeStops(stops)
    }
}

class StopResenderTest {
    private val server = MockWebServer()
    private lateinit var client: EmbyClient
    private lateinit var url: ServerUrl
    private val storage = MemoryStorage()
    private var clock = 1_800_000_000_000L
    private val resender = StopResender(storage) { clock }
    private lateinit var key: String

    @Before
    fun setUp() {
        server.start()
        url = ServerUrl.parse("http://127.0.0.1:${server.port}")
        client = EmbyClient(url, DeviceIdentity("Shield", "dev-1", "0.1.0"), EmbyClient.defaultHttp()).withToken("secret-token")
        key = accountKey(url, "u1")
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun respond(code: Int) = server.enqueue(MockResponse.Builder().code(code).build())

    private fun report(itemId: String, position: Long = 7_583_000_000) = PlaybackReport(
        itemId = itemId,
        mediaSourceId = "ms_$itemId",
        playSessionId = "ps1",
        positionTicks = position,
        runTimeTicks = 60_000_000_000,
        playMethod = "Transcode",
        failed = false,
    )

    private fun kept(k: String = key) = storage.load(k).map { it.report.itemId }

    @Test
    fun aKeptStopIsSentAtSignInAfterAProgressWithItsPosition() = runBlocking {
        // Kept by a previous run of the app: due as soon as the account signs in
        storage.save(key, listOf(PendingStop(report("30207"), clock - 60_000)))
        resender.use(key, client)
        assertTrue(resender.due())
        respond(204)
        respond(204)

        assertEquals(ResendOutcome(sent = 1), resender.resend())
        assertEquals(emptyList<String>(), kept())
        val progress = server.takeRequest()
        assertEquals("/emby/Sessions/Playing/Progress", progress.url.encodedPath)
        assertTrue("\"PositionTicks\":7583000000" in progress.body!!.utf8())
        val stop = server.takeRequest()
        assertEquals("/emby/Sessions/Playing/Stopped", stop.url.encodedPath)
        assertTrue("\"Failed\":false" in stop.body!!.utf8())
        for (request in listOf(progress, stop)) {
            assertNull("no token in the URL", request.url.query)
            assertTrue("Token=\"secret-token\"" in request.headers["X-Emby-Authorization"]!!)
        }
        assertFalse("secret-token" in storage.texts.values.joinToString())
    }

    @Test
    fun aNetworkFailureBacksOffAndKeepsTheStop() = runBlocking {
        resender.use(key, client)
        server.close()
        resender.keep(key, report("30207"))
        assertFalse("first try after the first wait", resender.due())
        clock += STOP_RESEND_FIRST_MS
        assertTrue(resender.due())

        val outcome = resender.resend()
        assertEquals(EmbyError.Unreachable, outcome.error)
        assertEquals(listOf("30207"), kept())
        // Second failure in a row: twice the wait
        clock += resendDelay(2) - 1
        assertFalse(resender.due())
        clock += 1
        assertTrue(resender.due())
    }

    @Test
    fun a5xxIsRetriedAnd4xxDropsTheStop() = runBlocking {
        storage.save(key, listOf(PendingStop(report("a"), clock), PendingStop(report("b"), clock)))
        resender.use(key, client)
        respond(503)
        assertEquals(EmbyError.Server::class, resender.resend().error!!::class)
        assertEquals("kept, in order", listOf("a", "b"), kept())

        clock += resendDelay(1)
        respond(404) // progress of "a" refused for good
        respond(400) // progress of "b"
        val outcome = resender.resend()
        assertEquals(2, outcome.dropped)
        assertEquals(emptyList<String>(), kept())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun anExpiredSessionStopsAndKeepsTheStopForTheSameUserOnly() = runBlocking {
        storage.save(key, listOf(PendingStop(report("30207"), clock)))
        resender.use(key, client)
        respond(401)
        assertEquals(EmbyError.SessionExpired, resender.resend().error)
        assertEquals(listOf("30207"), kept())
        assertFalse("nothing more until signed in again", resender.due())
        assertEquals(ResendOutcome(), resender.resend())
        assertEquals(1, server.requestCount)

        // Another user on the same server: its own stops only, none of u1's
        resender.signedOut()
        val other = accountKey(url, "u2")
        resender.use(other, client.withToken("other-token"))
        assertEquals(ResendOutcome(), resender.resend())
        assertEquals(1, server.requestCount)
        assertEquals(listOf("30207"), kept())

        // The same user signs in again: sent
        resender.use(key, client.withToken("new-token"))
        respond(204)
        respond(204)
        assertEquals(1, resender.resend().sent)
        server.takeRequest()
        assertTrue("Token=\"new-token\"" in server.takeRequest(1, TimeUnit.SECONDS)!!.headers["X-Emby-Authorization"]!!)
    }

    @Test
    fun nothingIsSentWhenSignedOut() = runBlocking {
        resender.keep(key, report("30207"))
        clock += PENDING_STOP_MAX_AGE_MS / 2
        assertFalse(resender.due())
        assertEquals(ResendOutcome(), resender.resend())
        assertEquals(0, server.requestCount)
        assertEquals(listOf("30207"), kept())
    }

    @Test
    fun aNewerReportOfTheItemCancelsItsKeptStop() = runBlocking {
        resender.use(key, client)
        resender.keep(key, report("a"))
        resender.keep(key, report("b"))
        resender.supersede(key, "a")
        assertEquals(listOf("b"), kept())
        // Another report went through: the server is back, no need to wait
        assertFalse(resender.due())
        resender.reachable()
        assertTrue(resender.due())
        respond(204)
        respond(204)
        assertEquals(1, resender.resend().sent)
        assertTrue("\"ItemId\":\"b\"" in server.takeRequest().body!!.utf8())
    }

    @Test
    fun staleStopsAreGivenUpWithoutARequest() = runBlocking {
        storage.save(key, listOf(PendingStop(report("old"), clock - PENDING_STOP_MAX_AGE_MS)))
        val gone = accountKey(url, "u9")
        storage.save(gone, listOf(PendingStop(report("x"), clock - PENDING_STOP_MAX_AGE_MS - 1)))
        resender.use(key, client)
        assertEquals("pruned for every account", emptySet<String>(), storage.keys())
        assertEquals(ResendOutcome(), resender.resend())
        assertEquals(0, server.requestCount)
    }
}
