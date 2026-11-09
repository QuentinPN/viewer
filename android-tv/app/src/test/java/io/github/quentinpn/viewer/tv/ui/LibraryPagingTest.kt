package io.github.quentinpn.viewer.tv.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryPagingTest {
    @Test
    fun firstPageLoadsBeforeAnyCount() {
        assertTrue(shouldLoadMore(lastVisible = -1, loaded = 0, total = -1))
    }

    @Test
    fun nextPageLoadsNearTheEnd() {
        assertFalse(shouldLoadMore(lastVisible = 30, loaded = 60, total = 200, margin = 18))
        assertTrue(shouldLoadMore(lastVisible = 42, loaded = 60, total = 200, margin = 18))
    }

    @Test
    fun pagingStopsAtTheCount() {
        assertFalse(shouldLoadMore(lastVisible = 119, loaded = 120, total = 120))
        // An empty library
        assertFalse(shouldLoadMore(lastVisible = -1, loaded = 0, total = 0))
    }
}
