package io.github.quentinpn.viewer.tv.ui

import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistScreenTest {
    private val minute = 600_000_000L
    private fun playlist(id: String, mediaType: String? = "Video") = BaseItemDto(id = id, name = "Liste $id", type = "Playlist", mediaType = mediaType)
    private fun film(id: String, length: Long? = 100 * minute) = BaseItemDto(id = id, name = "Film $id", type = "Movie", runTimeTicks = length)
    private val episode = BaseItemDto(id = "e1", name = "Pilote", type = "Episode", seriesId = "s1", seasonId = "se1", parentIndexNumber = 1, indexNumber = 1, runTimeTicks = 42 * minute)

    @Test
    fun theRowShowsOnlyWithAVideoPlaylist() {
        assertNull(playlistShelf(emptyList()))
        assertNull(playlistShelf(listOf(playlist("p1", "Audio"))))
        val shelf = playlistShelf(listOf(playlist("p1"), playlist("p2", "Audio")))!!
        assertEquals("Listes de lecture", shelf.title)
        assertEquals(listOf("p1"), shelf.items.map { it.id })
        assertEquals(false, shelf.landscape)
        assertNull(shelf.library)
    }

    @Test
    fun theSummaryGivesTheLengthOnlyWhenEveryEntryHasOne() {
        assertEquals("1 titre", titlesLabel(1))
        assertEquals("0 titres", titlesLabel(0))
        assertEquals("2 titres · 2 h 22", playlistSummary(listOf(film("m1"), episode)))
        assertEquals("1 titre · 42 min", playlistSummary(listOf(episode)))
        assertEquals("2 titres", playlistSummary(listOf(film("m1"), film("m2", null))))
        assertEquals("0 titres", playlistSummary(emptyList()))
    }

    @Test
    fun anEntryOpensTheFilmOrTheEpisodePage() {
        assertEquals(DetailPage.Movie("m1"), playlistEntryPage(film("m1")))
        assertEquals(DetailPage.Episode(SeriesTarget("s1", seasonId = "se1", seasonNumber = 1, episodeId = "e1")), playlistEntryPage(episode))
        assertNull(playlistEntryPage(episode.copy(seriesId = null)))
        assertNull(playlistEntryPage(playlist("p1")))
    }

    @Test
    fun backFromAnEntryFocusesItOnThePlaylist() {
        val pages = listOf<DetailPage>(DetailPage.Playlist("p1", "Soirée"))
        val opened = openOver(pages, playlistEntryPage(episode)!!, episode.id)
        assertEquals(DetailPage.Playlist("p1", "Soirée", opened = "e1"), opened.first())
        // Back from the episode returns to the playlist, not to its series page
        val target = SeriesTarget("s1", seasonId = "se1", seasonNumber = 1, episodeId = "e1")
        assertEquals(listOf(DetailPage.Playlist("p1", "Soirée", opened = "e1")), backFromEpisode(opened, target))
        // Elsewhere an episode still goes back to its series
        assertEquals(listOf(DetailPage.Series(target)), backFromEpisode(listOf(DetailPage.Episode(target)), target))
    }
}
