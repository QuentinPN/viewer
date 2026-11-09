package io.github.quentinpn.viewer.tv.ui

import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimilarTest {
    private fun item(id: String, type: String) = BaseItemDto(id = id, type = type)

    @Test
    fun filmsAndSeriesOnlyWithoutThePageItself() {
        val items = listOf(item("m1", "Movie"), item("m2", "Movie"), item("e1", "Episode"), item("s1", "Series"), item("b1", "BoxSet"))
        assertEquals(listOf("m2", "s1"), similarTitles(items, "m1").map { it.id })
    }

    @Test
    fun duplicatesDroppedAndLimitKept() {
        val items = (1..20).map { item("m$it", "Movie") } + item("m1", "Movie")
        val titles = similarTitles(items, "x")
        assertEquals(SIMILAR_LIMIT, titles.size)
        assertEquals(titles.map { it.id }.distinct(), titles.map { it.id })
        assertEquals(emptyList<BaseItemDto>(), similarTitles(emptyList(), "x"))
    }

    @Test
    fun aSimilarTitleOpensItsFilmOrSeriesPage() {
        assertEquals(DetailPage.Movie("m2"), similarPage(item("m2", "Movie")))
        assertEquals(DetailPage.Series(SeriesTarget("s1")), similarPage(item("s1", "Series")))
        assertNull(similarPage(item("e1", "Episode")))
    }

    @Test
    fun backFromASimilarFilmReturnsToTheFirstOne() {
        val first = listOf<DetailPage>(DetailPage.Movie("m1"))
        val pages = pushPage(pushPage(first, DetailPage.Movie("m2")), DetailPage.Series(SeriesTarget("s1")))
        assertEquals(listOf(DetailPage.Movie("m1"), DetailPage.Movie("m2"), DetailPage.Series(SeriesTarget("s1"))), pages)
        // What the Back handler does, page by page
        assertEquals(DetailPage.Movie("m2"), pages.dropLast(1).last())
        assertEquals(first, pages.dropLast(2))
    }

    @Test
    fun theOldestPageIsForgottenPastTheLimit() {
        val pages = (1..MAX_PAGES + 3).fold(emptyList<DetailPage>()) { acc, n -> pushPage(acc, DetailPage.Movie("m$n")) }
        assertEquals(MAX_PAGES, pages.size)
        assertEquals(DetailPage.Movie("m4"), pages.first())
        assertEquals(DetailPage.Movie("m${MAX_PAGES + 3}"), pages.last())
    }

    @Test
    fun backFromAnEpisodeKeepsThePagesBelow() {
        val pages = listOf(DetailPage.Movie("m1"), DetailPage.Series(SeriesTarget("s1")))
        val back = SeriesTarget("s1", seasonId = "se2", episodeId = "e5")
        assertEquals(listOf(DetailPage.Movie("m1"), DetailPage.Series(back)), replaceTop(pages, DetailPage.Series(back)))
        assertEquals(listOf(DetailPage.Series(back)), replaceTop(emptyList(), DetailPage.Series(back)))
    }
}
