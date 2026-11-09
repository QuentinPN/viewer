package io.github.quentinpn.viewer.tv.emby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemsOptionsTest {
    @Test
    fun defaultsFilterNothing() {
        val options = ItemsOptions()
        assertNull(options.filters())
        assertFalse(options.narrowed)
        assertEquals("SortName", options.sort.sortBy)
    }

    @Test
    fun filtersJoinWithCommas() {
        assertEquals("IsResumable,IsFavorite", ItemsOptions(watch = WatchFilter.InProgress, favorites = true).filters())
        assertEquals("IsPlayed", ItemsOptions(watch = WatchFilter.Played).filters())
        assertTrue(ItemsOptions(genre = "Action").narrowed)
    }

    @Test
    fun onlyTheTitleReadsAscendingByDefault() {
        assertFalse(SortKey.Name.descendingByDefault)
        assertTrue(SortKey.DateAdded.descendingByDefault)
        assertTrue(SortKey.Rating.descendingByDefault)
    }

    @Test
    fun unusableGenresAreRejected() {
        assertTrue(isUsableGenre("Science-Fiction"))
        assertFalse(isUsableGenre(""))
        assertFalse(isUsableGenre("A|B"))
        assertFalse(isUsableGenre("A\nB"))
        assertFalse(isUsableGenre("x".repeat(101)))
    }
}
