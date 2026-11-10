package io.github.quentinpn.viewer.tv.emby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LettersTest {
    @Test
    fun hashThenTheAlphabet() {
        assertEquals(27, LETTERS.size)
        assertEquals('#', LETTERS.first())
        assertEquals('Z', LETTERS.last())
    }

    @Test
    fun accentsAndCaseFileUnderTheBareLetter() {
        assertEquals('E', letterOf("Été 85"))
        assertEquals('E', letterOf("être et avoir"))
        assertEquals('A', letterOf("À bout de souffle"))
        assertEquals('C', letterOf("ça"))
        assertEquals('O', letterOf("Œdipe roi"))
        assertEquals('M', letterOf("  matrix"))
    }

    @Test
    fun digitsSymbolsAndOtherScriptsGoToHash() {
        assertEquals('#', letterOf("2001 : l'odyssée de l'espace"))
        assertEquals('#', letterOf("(500) jours ensemble"))
        assertEquals('#', letterOf("千と千尋の神隠し"))
        assertEquals('#', letterOf(""))
    }

    @Test
    fun theServerSortNameWinsOverTheName() {
        // « Le Fabuleux Destin… » sorts under F once the server drops the article
        assertEquals('F', letterOf(SortNameDto("m1", "Le Fabuleux Destin d'Amélie Poulain", "fabuleux destin d'amélie poulain").key))
        assertEquals('L', letterOf(SortNameDto("m2", "Léon", null).key))
        assertEquals('L', letterOf(SortNameDto("m3", "Léon", " ").key))
    }

    @Test
    fun eachLetterStartsAtItsFirstTitle() {
        val starts = letterStarts(
            listOf(
                SortNameDto("a", sortName = "12 hommes en colère"),
                SortNameDto("b", sortName = "akira"),
                SortNameDto("c", sortName = "alien"),
                SortNameDto("d", sortName = "étrange festin"),
                SortNameDto("e", sortName = "matrix"),
            ),
        )
        assertEquals(LetterStart(0, "a"), starts['#'])
        assertEquals(LetterStart(1, "b"), starts['A'])
        assertEquals(LetterStart(3, "d"), starts['E'])
        assertEquals(LetterStart(4, "e"), starts['M'])
        assertFalse('B' in starts)
    }

    @Test
    fun descendingOrderStartsAtTheFirstTitleShown() {
        // Z → A: the grid reads « matrix » before « mad max », so M starts there
        val starts = letterStarts(listOf(SortNameDto("z", sortName = "zodiac"), SortNameDto("m2", sortName = "matrix"), SortNameDto("m1", sortName = "mad max")))
        assertEquals(LetterStart(1, "m2"), starts['M'])
    }

    @Test
    fun aPositionMapsToTheEndOfItsPage() {
        assertEquals(60, pageEnd(0, 60))
        assertEquals(60, pageEnd(59, 60))
        assertEquals(120, pageEnd(60, 60))
        assertEquals(360, pageEnd(301, 60))
    }

    @Test
    fun aJumpReadsUpToItsPageInOneRequest() {
        assertEquals(60, pageLimit(fetched = 60, wanted = 0, pageSize = 60))
        assertEquals(300, pageLimit(fetched = 60, wanted = 360, pageSize = 60))
        // Already past it: back to plain pages
        assertEquals(60, pageLimit(fetched = 420, wanted = 360, pageSize = 60))
    }
}
