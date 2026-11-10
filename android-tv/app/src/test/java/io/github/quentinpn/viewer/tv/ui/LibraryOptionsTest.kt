package io.github.quentinpn.viewer.tv.ui

import io.github.quentinpn.viewer.tv.emby.ItemsOptions
import io.github.quentinpn.viewer.tv.emby.SortKey
import io.github.quentinpn.viewer.tv.emby.WatchFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryOptionsTest {
    @Test
    fun sortStepsAndTakesItsDirection() {
        val byDate = ItemsOptions().nextSort()
        assertEquals(SortKey.DateAdded, byDate.sort)
        assertTrue(byDate.descending)
        // Back to the title after the last key, A to Z again
        val back = ItemsOptions(SortKey.Rating, descending = true).nextSort()
        assertEquals(SortKey.Name, back.sort)
        assertFalse(back.descending)
    }

    @Test
    fun inProgressIsForFilmsOnly() {
        assertEquals(WatchFilter.InProgress, ItemsOptions(watch = WatchFilter.Played).nextWatch("Movie").watch)
        assertEquals(WatchFilter.All, ItemsOptions(watch = WatchFilter.Played).nextWatch("Series").watch)
    }

    @Test
    fun aChangeStartsOverFromTheFirstPage() {
        val memory = LibraryMemory(Library("l", "Films", "Movie"))
        memory.fetched = 60
        memory.total = 600
        val generation = memory.generation
        memory.change(ItemsOptions(favorites = true))
        assertEquals(0, memory.fetched)
        assertEquals(-1, memory.total)
        assertEquals(generation + 1, memory.generation)
        assertFalse(memory.focusGrid)
        // The same choices again: nothing to reload
        memory.change(ItemsOptions(favorites = true))
        assertEquals(generation + 1, memory.generation)
    }

    @Test
    fun decadeLabelsAreFrench() {
        assertEquals("Années : Toutes", decadePillLabel(null))
        assertEquals("Années : 1990", decadePillLabel(1990))
        assertEquals("Toutes les années", decadeChoiceLabel(null))
        assertEquals("Années 1990", decadeChoiceLabel(1990))
    }

    @Test
    fun aGenreGridStartsWithEveryDecade() {
        // The grid opened from a genre chip keeps the sort, not the saved decade (ADR 0079, 0082)
        val saved = ItemsOptions(SortKey.Year, descending = true, decade = 1980)
        assertNull(genreOptions(saved, "Drame")?.decade)
        // « Réinitialiser » goes back to every decade
        assertNull(ItemsOptions().decade)
    }

    @Test
    fun aDecadeChangeReloadsTheGridButKeepsTheDecadeList() {
        val memory = LibraryMemory(Library("l", "Films", "Movie"))
        memory.decades = listOf(2020, 1990)
        val generation = memory.generation
        memory.change(ItemsOptions(decade = 1990))
        assertEquals(generation + 1, memory.generation)
        assertEquals(listOf(2020, 1990), memory.decades)
    }
}
