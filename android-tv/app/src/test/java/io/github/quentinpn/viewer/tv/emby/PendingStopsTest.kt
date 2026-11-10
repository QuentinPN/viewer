package io.github.quentinpn.viewer.tv.emby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingStopsTest {
    private val now = 1_800_000_000_000L

    private fun stop(itemId: String, savedAt: Long = now, position: Long = 1) = PendingStop(
        PlaybackReport(itemId = itemId, mediaSourceId = "ms_$itemId", playSessionId = "ps", positionTicks = position, failed = false),
        savedAt,
    )

    private fun ids(queue: List<PendingStop>) = queue.map { it.report.itemId }

    @Test
    fun theWaitDoublesUpToTheCap() {
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L), (1..6).map(::resendDelay))
        assertEquals("never below the first wait", STOP_RESEND_FIRST_MS, resendDelay(0))
        assertEquals("no overflow", STOP_RESEND_CAP_MS, resendDelay(Int.MAX_VALUE))
    }

    @Test
    fun theQueueKeepsTheNewestStopsOldestFirst() {
        var queue = emptyList<PendingStop>()
        for (i in 1..7) queue = enqueueStop(queue, stop("i$i", now + i), now + i)
        assertEquals(MAX_PENDING_STOPS, queue.size)
        assertEquals(listOf("i3", "i4", "i5", "i6", "i7"), ids(queue))
    }

    @Test
    fun aNewerStopOfTheSameItemReplacesTheOlderOne() {
        var queue = enqueueStop(emptyList(), stop("a", now, position = 10), now)
        queue = enqueueStop(queue, stop("b", now + 1), now + 1)
        queue = enqueueStop(queue, stop("a", now + 2, position = 20), now + 2)
        assertEquals(listOf("b", "a"), ids(queue))
        assertEquals(20L, queue.last().report.positionTicks)
        assertEquals(listOf("b"), ids(withoutItem(queue, "a")))
    }

    @Test
    fun oldStopsAndStopsFromALaterClockAreGivenUp() {
        assertFalse(isStale(stop("a", now - PENDING_STOP_MAX_AGE_MS + 1), now))
        assertTrue(isStale(stop("a", now - PENDING_STOP_MAX_AGE_MS), now))
        // A clock set back slightly is tolerated, a large jump is not
        assertFalse(isStale(stop("a", now + 30_000), now))
        assertTrue(isStale(stop("a", now + 3_600_000), now))

        val queue = listOf(stop("old", now - PENDING_STOP_MAX_AGE_MS), stop("new", now))
        assertEquals(listOf("new"), ids(pruneStops(queue, now)))
        assertEquals("enqueue prunes too", listOf("new", "c"), ids(enqueueStop(queue, stop("c"), now)))
    }

    @Test
    fun storedTextRoundTripsWithoutTokenAndBadTextReadsEmpty() {
        val queue = listOf(stop("a"), stop("b", now + 5))
        val text = encodeStops(queue)
        assertEquals(queue, decodeStops(text))
        assertTrue("PascalCase report body", "\"ItemId\":\"a\"" in text)
        assertFalse("Token" in text)
        assertEquals(emptyList<PendingStop>(), decodeStops(null))
        assertEquals(emptyList<PendingStop>(), decodeStops(""))
        assertEquals(emptyList<PendingStop>(), decodeStops("{not json"))
        assertEquals(emptyList<PendingStop>(), decodeStops("""[{"SavedAt":1}]"""))
    }

    @Test
    fun onlyNetworkAnd5xxMayPass() {
        assertTrue(EmbyError.Unreachable.isTransient())
        assertTrue(EmbyError.Server(500).isTransient())
        assertTrue(EmbyError.Server(503).isTransient())
        assertFalse(EmbyError.Server(400).isTransient())
        assertFalse(EmbyError.Server(429).isTransient())
        assertFalse(EmbyError.SessionExpired.isTransient())
        assertFalse(EmbyError.NotFound.isTransient())
        assertFalse((null as EmbyError?).isTransient())
    }

    @Test
    fun theAccountKeyNamesServerAndUser() {
        val server = ServerUrl.parse("http://192.168.1.14:8096/")
        assertEquals("http://192.168.1.14:8096|u1", accountKey(server, "u1"))
        assertFalse(accountKey(server, "u1") == accountKey(server, "u2"))
        assertFalse(accountKey(server, "u1") == accountKey(ServerUrl.parse("http://192.168.1.15:8096"), "u1"))
    }
}
