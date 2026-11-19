package io.github.quentinpn.viewer.tv.request

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowseOptionsTest {
    private val genres = listOf(Genre(16, "Animation"), Genre(35, "Comédie"))

    @Test
    fun defaultsReadAsPopularityEverythingAndAllYears() {
        val f = BrowseFilters()
        assertEquals("Popularité", sortLabel(f))
        assertEquals("Tous", genreLabel(f, genres))
        assertEquals("Toutes", yearsLabel(f))
        assertEquals("Toutes", ratingLabel(f))
    }

    @Test
    fun choicesSetOnlyTheirOwnFilter() {
        val start = BrowseFilters(provider = 8, language = "fr")
        val rated = ratingChoices().first { it.label == "7+" }.apply(start)
        assertEquals(7, rated.rating)
        assertEquals(8, rated.provider)
        assertEquals("fr", rated.language)
        val decade = yearChoices().first { it.label == "2010 – 2019" }.apply(rated)
        assertEquals(2010 to 2019, decade.yearFrom to decade.yearTo)
        assertEquals(7, decade.rating)
    }

    @Test
    fun aGenreChoiceReplacesTheGenreAndAllClearsIt() {
        val choices = genreChoices(genres)
        assertEquals(listOf("Tous", "Animation", "Comédie"), choices.map { it.label })
        val picked = choices[1].apply(BrowseFilters())
        assertEquals(listOf(16), picked.genres)
        assertEquals("Animation", genreLabel(picked, genres))
        assertEquals(null, choices[0].apply(picked).genres)
    }

    @Test
    fun theSelectedChoiceIsTheOneTheFiltersHold() {
        val choices = sortChoices()
        assertEquals(0, selectedChoice(choices, BrowseFilters(), ::sameSort))
        assertEquals(2, selectedChoice(choices, BrowseFilters(sort = "release"), ::sameSort))
        // Several genres, as a « Tout voir » may bring: none of the single-genre choices, so « Tous »
        assertEquals(0, selectedChoice(genreChoices(genres), BrowseFilters(genres = listOf(16, 35)), ::sameGenres))
        assertEquals("2 genres", genreLabel(BrowseFilters(genres = listOf(16, 35)), genres))
    }

    @Test
    fun anUnlistedYearRangeStillHasALabel() {
        assertEquals("2005 et après", yearsLabel(BrowseFilters(yearFrom = 2005)))
        assertEquals("Jusqu'à 2005", yearsLabel(BrowseFilters(yearTo = 2005)))
        assertEquals("2001 – 2005", yearsLabel(BrowseFilters(yearFrom = 2001, yearTo = 2005)))
    }
}
