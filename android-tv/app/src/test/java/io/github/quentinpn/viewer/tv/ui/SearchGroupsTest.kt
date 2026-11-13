package io.github.quentinpn.viewer.tv.ui

import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import io.github.quentinpn.viewer.tv.emby.SearchResults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchGroupsTest {
    private fun item(id: String, type: String) = BaseItemDto(id = id, name = id, type = type)

    @Test
    fun filmsSeriesThenEpisodesWithoutEmptyGroups() {
        val groups = searchGroups(
            SearchResults("heure", movies = listOf(item("m", "Movie")), episodes = listOf(item("e", "Episode")), moreEpisodes = true),
        )
        assertEquals(listOf("Films", "Épisodes"), groups.map { it.title })
        assertEquals(listOf(false, true), groups.map { it.landscape })
        assertNull(groups[0].note)
        assertEquals("D'autres épisodes peuvent correspondre : précise la recherche.", groups[1].note)
    }

    @Test
    fun moreFilmsAndSeriesAskToNarrow() {
        val groups = searchGroups(
            SearchResults("le", movies = listOf(item("m", "Movie")), series = listOf(item("s", "Series")), moreMovies = true, moreSeries = true),
        )
        assertEquals("Seuls les 24 premiers films sont affichés : précise la recherche.", groups[0].note)
        assertEquals("Seules les 24 premières séries sont affichées : précise la recherche.", groups[1].note)
    }

    @Test
    fun noResultsNoGroups() {
        assertTrue(searchGroups(SearchResults("zz")).isEmpty())
    }
}
