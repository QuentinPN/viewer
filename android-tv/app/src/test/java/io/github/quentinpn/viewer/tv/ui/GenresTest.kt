package io.github.quentinpn.viewer.tv.ui

import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import io.github.quentinpn.viewer.tv.emby.ItemsOptions
import io.github.quentinpn.viewer.tv.emby.SortKey
import io.github.quentinpn.viewer.tv.emby.WatchFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class GenresTest {
    private val films = Library("1", "Films", "Movie")
    private val docs = Library("2", "Documentaires", "Movie")
    private val series = Library("3", "Séries", "Series")

    private fun item(vararg genres: String) = BaseItemDto(id = "m1", type = "Movie", genres = genres.toList())

    @Test
    fun chipsAreTheYearLineGenresAtMostThree() {
        assertEquals(listOf("Action", "Drame", "Fantastique"), genreChips(item("Action", "Drame", "Fantastique", "Animation")))
        assertEquals(emptyList<String>(), genreChips(item()))
    }

    @Test
    fun aNameTheFilterRefusesHasNoChip() {
        val long = "x".repeat(101)
        val chips = genreChips(item("", "  ", "Action|Drame", "Sci\u0000Fi", long, "Comédie", "Comédie", "Western"))
        assertEquals(listOf("Comédie", "Western"), chips)
        assertEquals(listOf("x".repeat(100)), genreChips(item("x".repeat(100))))
    }

    @Test
    fun theYearLineDropsTheGenresOnlyWhenChipsShowThem() {
        val movie = item("Action", "Drame")
        assertNull(yearLineGenres(movie, genreChips(movie)))
        assertEquals("Action, Drame", yearLineGenres(movie, emptyList()))
    }

    @Test
    fun aFilmOpensTheFilmsLibraryAndASeriesTheSeriesOne() {
        val libraries = listOf(docs, films, series)
        assertEquals(films, genreLibrary(libraries, "Movie"))
        assertEquals(series, genreLibrary(libraries, "Series"))
        assertEquals(docs, genreLibrary(listOf(docs, series), "Movie"))
        assertNull(genreLibrary(listOf(films), "Series"))
        assertNull(genreLibrary(libraries, "Episode"))
        assertNull(genreLibrary(emptyList(), "Movie"))
    }

    @Test
    fun theGridKeepsTheSavedSortButNoOtherFilter() {
        val saved = ItemsOptions(SortKey.Year, descending = true, watch = WatchFilter.Unplayed, favorites = true, genre = "Western")
        assertEquals(ItemsOptions(SortKey.Year, descending = true, genre = "Action"), genreOptions(saved, "Action"))
        assertEquals(ItemsOptions(genre = "Drame"), genreOptions(ItemsOptions(), "Drame"))
    }

    @Test
    fun aRefusedNameOpensNothing() {
        assertNull(genreOptions(ItemsOptions(), ""))
        assertNull(genreOptions(ItemsOptions(), "Action|Drame"))
        assertNull(genreOptions(ItemsOptions(), "a".repeat(101)))
        assertNull(genreOptions(ItemsOptions(), "Sci\nFi"))
    }

    @Test
    fun backFromTheGridFocusesTheChipOfTheFilm() {
        val grid = DetailPage.Grid(LibraryMemory(films, ItemsOptions(genre = "Action")))
        val pages = openGenre(listOf(DetailPage.Movie("m1", person = "p1", collection = "c1")), grid, "Action")
        assertEquals(listOf(DetailPage.Movie("m1", genre = "Action"), grid), pages)
        // What the Back handler does
        assertEquals(DetailPage.Movie("m1", genre = "Action"), pages.dropLast(1).last())
    }

    @Test
    fun backFromTheGridFocusesTheChipOfTheSeries() {
        val grid = DetailPage.Grid(LibraryMemory(series, ItemsOptions(genre = "Drame")))
        val pages = openGenre(listOf(DetailPage.Series(SeriesTarget("s1"), person = "p1")), grid, "Drame")
        assertEquals(listOf(DetailPage.Series(SeriesTarget("s1"), genre = "Drame"), grid), pages)
    }

    @Test
    fun aTitleOpenedFromTheGridStacksOverIt() {
        val grid = DetailPage.Grid(LibraryMemory(films, ItemsOptions(genre = "Action")))
        val pages = openOver(openGenre(listOf(DetailPage.Movie("m1")), grid, "Action"), DetailPage.Movie("m2"))
        assertEquals(listOf(DetailPage.Movie("m1", genre = "Action"), grid, DetailPage.Movie("m2")), pages)
        assertSame(grid.memory, (pages[1] as DetailPage.Grid).memory)
    }

    @Test
    fun anotherWayOutForgetsTheChip() {
        val film = DetailPage.Movie("m1", genre = "Action")
        assertEquals(DetailPage.Movie("m1", person = "p1"), openOver(listOf(film), DetailPage.Person(CastMember("p1", "Zendaya", null, null)), "p1").first())
        assertEquals(DetailPage.Movie("m1"), openOver(listOf(film), DetailPage.Movie("m2")).first())
        assertEquals(DetailPage.Movie("m1", collection = "m3"), openFromCollection(listOf(film), DetailPage.Movie("m3"), "m3").first())
        assertEquals(DetailPage.Series(SeriesTarget("s1")), openOver(listOf(DetailPage.Series(SeriesTarget("s1"), genre = "Drame")), DetailPage.Movie("m2")).first())
    }
}
