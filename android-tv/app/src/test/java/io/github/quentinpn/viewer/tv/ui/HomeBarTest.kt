package io.github.quentinpn.viewer.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeBarTest {
    private val films = Library("1", "Films", "Movie")
    private val anime = Library("2", "Animés", "Series")
    private val series = Library("3", " séries ", "Series")

    @Test
    fun aSectionPrefersTheLibraryNamedLikeIt() {
        val libraries = listOf(films, anime, series)
        assertEquals(films, sectionLibrary(libraries, "Movie", "Films"))
        assertEquals(series, sectionLibrary(libraries, "Series", "Séries"))
    }

    @Test
    fun elseItTakesTheFirstOfItsTypeOrNone() {
        assertEquals(anime, sectionLibrary(listOf(films, anime), "Series", "Séries"))
        assertNull(sectionLibrary(listOf(anime), "Movie", "Films"))
        assertNull(sectionLibrary(emptyList(), "Series", "Séries"))
    }

    @Test
    fun theBarListsEveryFilmAndSeriesLibraryInOrder() {
        val music = Library("4", "Musique", "Audio")
        assertEquals(listOf(films, anime, series), barLibraries(listOf(films, anime, music, series, films)))
        assertEquals(emptyList<Library>(), barLibraries(emptyList()))
    }

    @Test
    fun theAvatarShowsTheFirstLetterOrDigit() {
        assertEquals("C", initial("claude"))
        assertEquals("É", initial(" élodie"))
        assertEquals("7", initial("_7up"))
        assertEquals("?", initial(""))
    }

    @Test
    fun theAccountMenuNamesTheServerAndSaysWhenItIsNotEncrypted() {
        assertEquals("HomeNAS · non chiffré", accountServerLine("HomeNAS", https = false))
        assertEquals("HomeNAS", accountServerLine("HomeNAS", https = true))
    }
}
