package io.github.quentinpn.viewer.tv.stremio

import io.github.quentinpn.viewer.tv.request.Season
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** « Regarder maintenant » on a Demander series, as the desktop's `episodes.ts`. */
class WatchNowTest {
    private fun season(number: Int, episodes: Int) = Season(seasonNumber = number, episodeCount = episodes)

    @Test
    fun seasonsWithoutEpisodesAreLeftOutAndTheSpecialsComeLast() {
        val picked = pickableSeasons(listOf(season(0, 3), season(2, 8), season(4, 0), season(1, 10)))
        assertEquals(listOf(1, 2, 0), picked.map { it.seasonNumber })
    }

    @Test
    fun thePickerOpensOnTheFirstRegularSeasonElseTheSpecials() {
        assertEquals(1, defaultSeason(pickableSeasons(listOf(season(0, 3), season(1, 10)))))
        assertEquals(0, defaultSeason(pickableSeasons(listOf(season(0, 3)))))
        assertNull(defaultSeason(pickableSeasons(listOf(season(1, 0)))))
    }

    @Test
    fun aFilmIsAskedByItsImdbIdAndAnEpisodeByItsNumbersToo() {
        assertEquals("tt0111161", externalTarget("tt0111161").id)
        assertEquals("tt0944947:1:2", externalTarget("tt0944947", 1, 2).id)
        // The specials are season 0, never mistaken for a film
        assertEquals("tt0944947:0:3", externalTarget("tt0944947", 0, 3).id)
    }
}
