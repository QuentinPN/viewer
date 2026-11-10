package io.github.quentinpn.viewer.tv.ui

import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import io.github.quentinpn.viewer.tv.emby.ItemsOptions
import io.github.quentinpn.viewer.tv.emby.NameDto
import io.github.quentinpn.viewer.tv.emby.SortKey
import io.github.quentinpn.viewer.tv.emby.WatchFilter
import io.github.quentinpn.viewer.tv.emby.isUsableStudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudiosTest {
    private val films = Library("1", "Films", "Movie")
    private val series = Library("3", "Séries", "Series")

    private fun item(vararg studios: String, type: String = "Movie") =
        BaseItemDto(id = "m1", type = type, studios = studios.map { NameDto(it) })

    @Test
    fun chipsAreTheStudiosInTheServersOrderAtMostThree() {
        assertEquals(listOf("Legendary", "Warner Bros.", "Village Roadshow"), studioChips(item("Legendary", "Warner Bros.", "Village Roadshow", "Syncopy")))
        assertEquals(emptyList<String>(), studioChips(item()))
    }

    @Test
    fun aNameTheFilterRefusesHasNoChip() {
        val chips = studioChips(item("", "  ", "HBO|Max", "A24\u0000", "x".repeat(101), "Canal+", "Canal+", "Arte"))
        // `|` separates studios in `Studios` and cannot be escaped: such a name is skipped
        assertEquals(listOf("Canal+", "Arte"), chips)
        assertTrue(isUsableStudio("Studio Ghibli"))
        assertTrue(isUsableStudio("Les Films du Losange"))
        assertFalse(isUsableStudio("HBO|Max"))
    }

    @Test
    fun theGridKeepsTheSavedSortButNoOtherFilter() {
        val saved = ItemsOptions(SortKey.Year, descending = true, watch = WatchFilter.Unplayed, favorites = true, genre = "Western", decade = 1990)
        assertEquals(ItemsOptions(SortKey.Year, descending = true, studio = "A24"), studioOptions(saved, "A24"))
        assertNull(studioOptions(ItemsOptions(), "HBO|Max"))
        assertNull(studioOptions(ItemsOptions(), " "))
    }

    @Test
    fun theGridIsNamedAfterTheStudioAndResetKeepsIt() {
        assertEquals("A24", gridTitle(films, ItemsOptions(studio = "A24")))
        assertEquals("Films", gridTitle(films, ItemsOptions(genre = "Action")))
        assertEquals(ItemsOptions(studio = "HBO"), resetOptions(ItemsOptions(SortKey.Rating, descending = true, favorites = true, studio = "HBO")))
        assertEquals(ItemsOptions(), resetOptions(ItemsOptions(genre = "Drame")))
        assertTrue(ItemsOptions(studio = "HBO").narrowed)
    }

    @Test
    fun aFilmSaysStudioAndASeriesDiffusion() {
        assertEquals("Studio", studioLabel(item("A24")))
        assertEquals("Diffusion", studioLabel(item("HBO", type = "Series")))
    }

    @Test
    fun theFactsLeaveTheStudiosToTheChips() {
        val film = item("A24")
        assertEquals(listOf(Fact("Studio", "A24")), facts(film))
        assertEquals(emptyList<Fact>(), facts(film, studios = false))
    }

    @Test
    fun backFromTheGridFocusesTheStudioChip() {
        val grid = DetailPage.Grid(LibraryMemory(films, ItemsOptions(studio = "A24")))
        val pages = openStudio(listOf(DetailPage.Movie("m1", person = "p1", collection = "c1", genre = "Drame")), grid, "A24")
        assertEquals(listOf(DetailPage.Movie("m1", studio = "A24"), grid), pages)
        val seriesGrid = DetailPage.Grid(LibraryMemory(series, ItemsOptions(studio = "HBO")))
        val seriesPages = openStudio(listOf(DetailPage.Series(SeriesTarget("s1"), person = "p1", genre = "Drame")), seriesGrid, "HBO")
        assertEquals(listOf(DetailPage.Series(SeriesTarget("s1"), studio = "HBO"), seriesGrid), seriesPages)
    }

    @Test
    fun anotherWayOutForgetsTheStudioChip() {
        val film = DetailPage.Movie("m1", studio = "A24")
        assertEquals(DetailPage.Movie("m1"), openOver(listOf(film), DetailPage.Movie("m2")).first())
        assertEquals(DetailPage.Movie("m1", collection = "m3"), openFromCollection(listOf(film), DetailPage.Movie("m3"), "m3").first())
        val grid = DetailPage.Grid(LibraryMemory(films, ItemsOptions(genre = "Action")))
        assertEquals(DetailPage.Movie("m1", genre = "Action"), openGenre(listOf(film), grid, "Action").first())
        assertEquals(DetailPage.Series(SeriesTarget("s1")), openOver(listOf(DetailPage.Series(SeriesTarget("s1"), studio = "HBO")), DetailPage.Movie("m2")).first())
    }
}
