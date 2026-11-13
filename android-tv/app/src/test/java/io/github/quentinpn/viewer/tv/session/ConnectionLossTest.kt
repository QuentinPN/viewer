package io.github.quentinpn.viewer.tv.session

import io.github.quentinpn.viewer.tv.emby.EmbyError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionLossTest {
    @Test
    fun `backoff grows to 30 s with jitter between half and full`() {
        assertEquals(1000L, backoffDelayMs(1, 0.0))
        assertEquals(2000L, backoffDelayMs(1, 1.0))
        assertEquals(8000L, backoffDelayMs(3, 1.0))
        assertEquals(30000L, backoffDelayMs(5, 1.0))
        assertEquals(30000L, backoffDelayMs(99, 1.0))
        assertEquals(1000L, backoffDelayMs(0, -3.0))
    }

    @Test
    fun `only transient errors are a lost connection`() {
        assertTrue(isConnectionLoss(EmbyError.Unreachable))
        assertTrue(isConnectionLoss(EmbyError.Server(503)))
        assertFalse(isConnectionLoss(EmbyError.SessionExpired))
        assertFalse(isConnectionLoss(EmbyError.Server(404)))
    }

    @Test
    fun `a first failure plans the first attempt`() {
        val lost = null.failed(EmbyError.Unreachable, now = 100, random = 1.0)
        assertEquals(0, lost.attempts)
        assertEquals(2100L, lost.retryAt)
        assertFalse(lost.gaveUp)
    }

    @Test
    fun `automatic attempts stop after the maximum`() {
        var lost = null.failed(EmbyError.Unreachable, 0, 1.0)
        repeat(MAX_AUTO_RETRIES) {
            lost = lost.autoRetry()
            assertTrue(lost.checking)
            lost = lost.failed(EmbyError.Unreachable, 0, 1.0)
        }
        assertTrue(lost.gaveUp)
        assertNull(lost.retryAt)
    }

    @Test
    fun `retry now starts the backoff over`() {
        val lost = LostConnection(EmbyError.Unreachable, attempts = 6, gaveUp = true).retryNow()
        assertEquals(0, lost.attempts)
        assertFalse(lost.gaveUp)
        assertEquals(2000L, lost.failed(EmbyError.Unreachable, 0, 1.0).retryAt)
    }

    @Test
    fun `status says what happens next`() {
        assertEquals("Nouvelle tentative dans 3 s…", LostConnection(EmbyError.Unreachable, retryAt = 3000).status(500))
        assertEquals("Vérification de la connexion…", LostConnection(EmbyError.Unreachable, checking = true).status(0))
        assertTrue(LostConnection(EmbyError.Unreachable, gaveUp = true).status(0).startsWith("Tentatives automatiques suspendues"))
    }
}
