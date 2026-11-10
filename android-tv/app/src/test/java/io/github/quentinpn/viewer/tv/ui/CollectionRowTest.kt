package io.github.quentinpn.viewer.tv.ui

import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import io.github.quentinpn.viewer.tv.emby.CollectionRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CollectionRowTest {
    private val saga = CollectionRef("120034", "Dune - Saga")
    private fun film(id: String, year: Int) = BaseItemDto(id = id, name = "Film $id", type = "Movie", productionYear = year)

    @Test
    fun aShelfKeepsThePageFilmInOrderButNeedsAnother() {
        val shelf = collectionShelf(saga, listOf(film("2", 2024), film("1", 2021)), "1")!!
        assertEquals(listOf("1", "2"), shelf.titles.map { it.id })
        assertNull(collectionShelf(saga, listOf(film("1", 2021)), "1"))
        assertNull(collectionShelf(saga, emptyList(), "1"))
    }

    @Test
    fun theFirstShelfHoldingThePosterTakesTheFocus() {
        val shelves = listOf(
            CollectionShelf(saga, listOf(film("1", 2021), film("2", 2024))),
            CollectionShelf(CollectionRef("9", "Favoris"), listOf(film("1", 2021), film("2", 2024))),
        )
        assertEquals(0, focusedShelf(shelves, "2"))
        assertEquals(-1, focusedShelf(shelves, "x"))
        assertEquals(-1, focusedShelf(shelves, null))
    }

    @Test
    fun aCollectionPosterIsRememberedByTheFilmBelow() {
        val pages = listOf<DetailPage>(DetailPage.Movie("1", person = "30209"))
        val opened = openFromCollection(pages, DetailPage.Movie("2"), "2")
        assertEquals(listOf(DetailPage.Movie("1", collection = "2"), DetailPage.Movie("2")), opened)
        // Back shows the film with the poster remembered; a person card opened later forgets it
        val below = opened.dropLast(1)
        assertEquals(DetailPage.Movie("1", person = "30209"), openOver(below, DetailPage.Movie("3"), "30209")[0])
    }

    @Test
    fun aSeriesOfACollectionOpensItsPage() {
        val series = BaseItemDto(id = "s1", name = "Prophétie", type = "Series")
        val opened = openFromCollection(listOf(DetailPage.Movie("1")), similarPage(series)!!, series.id)
        assertEquals(DetailPage.Series(SeriesTarget("s1")), opened.last())
        assertEquals(DetailPage.Movie("1", collection = "s1"), opened.first())
    }
}
