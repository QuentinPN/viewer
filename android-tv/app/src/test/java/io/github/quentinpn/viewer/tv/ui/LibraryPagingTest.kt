package io.github.quentinpn.viewer.tv.ui

import org.junit.Assert.assertEquals
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

    @Test
    fun aJumpLoadsItsPageWithoutScrolling() {
        assertTrue(shouldLoadMore(lastVisible = 10, loaded = 60, total = 606, wanted = 360))
        assertFalse(shouldLoadMore(lastVisible = 10, loaded = 360, total = 606, wanted = 360))
        // Never past the count, whatever the jump asked
        assertFalse(shouldLoadMore(lastVisible = 10, loaded = 606, total = 606, wanted = 660))
    }

    @Test
    fun lettersAreDescribedInFrench() {
        assertEquals("Aller à la lettre M", letterDescription('M', available = true))
        assertEquals("Aucun titre à la lettre Q", letterDescription('Q', available = false))
        assertEquals("Aller aux titres commençant par un chiffre ou un symbole", letterDescription('#', available = true))
    }
}
