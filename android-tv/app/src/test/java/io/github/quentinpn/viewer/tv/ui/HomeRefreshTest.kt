package io.github.quentinpn.viewer.tv.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRefreshTest {
    @Test
    fun freshShelvesStay() {
        assertFalse(homeIsStale(1_000, 1_000 + HOME_STALE_MS - 1, HOME_STALE_MS))
    }

    @Test
    fun shelvesAreReadAgainOnceTheDelayIsReached() {
        assertTrue(homeIsStale(1_000, 1_000 + HOME_STALE_MS, HOME_STALE_MS))
    }

    @Test
    fun returningToTheAppUsesTheShorterDelay() {
        val now = 1_000 + HOME_STALE_ON_RETURN_MS
        assertTrue(homeIsStale(1_000, now, HOME_STALE_ON_RETURN_MS))
        assertFalse(homeIsStale(1_000, now, HOME_STALE_MS))
    }

    @Test
    fun neverLoadedIsNotStale() {
        assertFalse(homeIsStale(0, HOME_STALE_MS * 10, HOME_STALE_MS))
    }
}
