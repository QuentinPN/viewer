package io.github.quentinpn.viewer.tv.ui

import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import io.github.quentinpn.viewer.tv.emby.ItemsOptions
import io.github.quentinpn.viewer.tv.emby.LetterStart
import io.github.quentinpn.viewer.tv.emby.SortKey
import io.github.quentinpn.viewer.tv.emby.WatchFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRailsTest {
    @Test
    fun resetShowsOnlyWhenSomethingDiffers() {
        assertFalse(RailAction.Reset in railActions(ItemsOptions()))
        assertTrue(RailAction.Reset in railActions(ItemsOptions(favorites = true)))
        assertTrue(RailAction.Reset in railActions(ItemsOptions(descending = true)))
        assertTrue(RailAction.Reset in railActions(ItemsOptions(SortKey.Year, descending = true)))
        // A studio grid keeps its studio on reset: the studio alone is nothing to reset (ADR 0087)
        assertFalse(canReset(ItemsOptions(studio = "HBO")))
        // Reset stays last, so the other icons never move when it comes and goes
        assertEquals(RailAction.Reset, railActions(ItemsOptions(decade = 1990)).last())
        assertEquals(RailAction.entries - RailAction.Reset, railActions(ItemsOptions()))
    }

    @Test
    fun activeIconsFollowTheirFilter() {
        val plain = ItemsOptions()
        assertTrue(RailAction.entries.none { isActive(it, plain) })
        assertTrue(isActive(RailAction.Watch, ItemsOptions(watch = WatchFilter.Unplayed)))
        assertTrue(isActive(RailAction.Favorites, ItemsOptions(favorites = true)))
        assertTrue(isActive(RailAction.Filters, ItemsOptions(genre = "Drame")))
        assertTrue(isActive(RailAction.Filters, ItemsOptions(decade = 1980)))
        assertTrue(isActive(RailAction.Sort, ItemsOptions(descending = true)))
        assertTrue(isActive(RailAction.Sort, ItemsOptions(SortKey.DateAdded, descending = true)))
        assertFalse(isActive(RailAction.Random, ItemsOptions(favorites = true)))
        assertFalse(isActive(RailAction.Filters, ItemsOptions(favorites = true)))
    }

    @Test
    fun railLabelsAreFrench() {
        assertEquals("Tri : Titre, A → Z", railLabel(RailAction.Sort, ItemsOptions()))
        assertEquals("Tri : Date d'ajout, Décroissant", railLabel(RailAction.Sort, ItemsOptions(SortKey.DateAdded, descending = true)))
        assertEquals("Visionnage : Non vus", railLabel(RailAction.Watch, ItemsOptions(watch = WatchFilter.Unplayed)))
        assertEquals("Favoris seulement : non", railLabel(RailAction.Favorites, ItemsOptions()))
        assertEquals("Filtres : aucun", railLabel(RailAction.Filters, ItemsOptions()))
        assertEquals("Filtres : Drame, années 1990", railLabel(RailAction.Filters, ItemsOptions(genre = "Drame", decade = 1990)))
        assertEquals("Aléatoire", railLabel(RailAction.Random, ItemsOptions()))
    }

    @Test
    fun counterShowsTheFocusedPositionOutOfTheTotal() {
        assertEquals("12 | 239", positionLabel(11, 239))
        assertEquals("1 | 1", positionLabel(0, 1))
        assertNull(positionLabel(-1, 239))
        assertNull(positionLabel(3, -1))
        assertNull(positionLabel(0, 0))
        // A count that shrank under a focused card never reads past the end
        assertEquals("5 | 5", positionLabel(9, 5))
    }

    @Test
    fun sortChoicesKeepTheCurrentKeyAndGiveOthersTheirDirection() {
        val current = ItemsOptions(SortKey.Name, descending = true, favorites = true)
        val keys = sortKeyChoices(current)
        assertEquals(listOf("Titre", "Date d'ajout", "Année", "Note"), keys.map { it.label })
        assertEquals(listOf(true, false, false, false), keys.map { it.selected })
        assertEquals(current, keys[0].options)
        // Another key reads newest first, and the filters stay
        assertEquals(ItemsOptions(SortKey.DateAdded, descending = true, favorites = true), keys[1].options)
        val directions = directionChoices(current)
        assertEquals(listOf("A → Z", "Z → A"), directions.map { it.label })
        assertEquals(listOf(false, true), directions.map { it.selected })
        assertEquals(listOf("Croissant", "Décroissant"), directionChoices(ItemsOptions(SortKey.Year)).map { it.label })
    }

    @Test
    fun lettersWaitForTheIndexAndRetryAfterAFailure() {
        val letters = mapOf('A' to LetterStart(0, "a"), 'M' to LetterStart(40, "m"))
        assertTrue(letterEnabled('M', letters, failed = false))
        assertFalse(letterEnabled('Q', letters, failed = false))
        // Loading: every letter waits
        assertFalse(letterEnabled('A', null, failed = false))
        // Failed: every letter can take the focus, OK reads the index again
        assertTrue(letterEnabled('Q', null, failed = true))
    }

    @Test
    fun cardFactsJoinYearAndRating() {
        assertEquals("2021 · ★ 7,4", cardFacts(BaseItemDto(id = "a", productionYear = 2021, communityRating = 7.44)))
        assertEquals("2021", cardFacts(BaseItemDto(id = "b", productionYear = 2021)))
        assertNull(cardFacts(BaseItemDto(id = "c")))
        assertEquals("99+", badgeText(PosterBadge.Unplayed(120)))
        assertEquals("✓", badgeText(PosterBadge.Played))
    }
}
