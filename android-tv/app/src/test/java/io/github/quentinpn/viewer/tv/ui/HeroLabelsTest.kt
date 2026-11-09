package io.github.quentinpn.viewer.tv.ui

import io.github.quentinpn.viewer.tv.emby.HeroEpisode
import io.github.quentinpn.viewer.tv.emby.HeroImage
import io.github.quentinpn.viewer.tv.emby.HeroTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeroLabelsTest {
    private fun title(id: String) = HeroTitle(id = id, type = "Movie", name = id, backdrop = HeroImage(id, "t"), playId = id)

    @Test
    fun stepsWrapAroundBothEnds() {
        assertEquals(1, stepIndex(0, 1, 5))
        assertEquals(0, stepIndex(4, 1, 5))
        assertEquals(4, stepIndex(0, -1, 5))
        assertEquals(0, stepIndex(0, 1, 1))
        assertEquals(0, stepIndex(3, 1, 0))
    }

    @Test
    fun theShownTitleIsKeptAfterARefresh() {
        val titles = listOf(title("a"), title("b"), title("c"))
        assertEquals(2, keptIndex(titles, "c", 0))
        // Gone (watched to the end): the same place, within bounds
        assertEquals(1, keptIndex(titles, "z", 1))
        assertEquals(2, keptIndex(titles, "z", 7))
        assertEquals(0, keptIndex(titles, null, -2))
        assertEquals(0, keptIndex(emptyList(), "a", 3))
    }

    @Test
    fun playLabelSaysWhereItResumes() {
        assertEquals("Lecture", heroPlayLabel(title("a")))
        assertEquals("Reprendre à 1:02:15", heroPlayLabel(title("a").copy(startTicks = 37_350_000_000)))
        assertNull(heroPlayLabel(title("a").copy(playId = null)))
    }

    @Test
    fun factsOfAFilmASeriesAndAnEpisode() {
        val film = title("a").copy(year = 2021, runTimeTicks = 93_000_000_000, officialRating = "PG-13")
        assertEquals("2021  ·  2 h 35  ·  PG-13", heroFacts(film))
        assertEquals("2019", heroFacts(title("s").copy(type = "Series", year = 2019)))
        assertEquals("", heroFacts(title("x")))
        val episode = title("s").copy(episode = HeroEpisode("La Rançon", 4, 1))
        assertEquals("S4 · É1 · La Rançon", heroFacts(episode))
        assertEquals("La Rançon", heroFacts(title("s").copy(episode = HeroEpisode("La Rançon", null, 1))))
    }
}
