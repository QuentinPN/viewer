package io.github.quentinpn.viewer.tv.emby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SearchHistoryTest {
    @Test
    fun newestFirst() {
        val history = listOf("alien", "dune").let { addSearch(it, "matrix") }
        assertEquals(listOf("matrix", "alien", "dune"), history)
    }

    @Test
    fun cleanedAsSentToTheServer() {
        assertEquals(listOf("le seigneur"), addSearch(emptyList(), "  le\tseigneur  "))
    }

    @Test
    fun tooShortLeavesTheHistory() {
        val history = listOf("dune")
        assertEquals(history, addSearch(history, "a"))
        assertEquals(history, addSearch(history, "   "))
        assertEquals(history, addSearch(history, ""))
    }

    @Test
    fun sameTermMovesFirstWithTheNewSpelling() {
        val history = listOf("dune", "Amélie", "alien")
        assertEquals(listOf("AMELIE", "dune", "alien"), addSearch(history, "AMELIE"))
        assertEquals(listOf("dune", "Amélie", "alien"), addSearch(history, "dune"))
    }

    @Test
    fun keepsAtMostEight() {
        val history = (1..8).map { "titre $it" }
        val next = addSearch(history, "nouveau")
        assertEquals(SEARCH_HISTORY_MAX, next.size)
        assertEquals("nouveau", next.first())
        assertFalse("titre 8" in next)
    }

    @Test
    fun roundTrip() {
        val history = listOf("matrix", "Amélie", "le seigneur des anneaux")
        assertEquals(history, decodeSearches(encodeSearches(history)))
    }

    @Test
    fun decodingCleansStoredValues() {
        assertEquals(emptyList<String>(), decodeSearches(null))
        assertEquals(emptyList<String>(), decodeSearches(""))
        // Newest line first: an older duplicate, a blank and a one-letter line are dropped
        assertEquals(listOf("Dune", "alien"), decodeSearches("Dune\n\nx\n  alien \ndune"))
        assertEquals(SEARCH_HISTORY_MAX, decodeSearches((1..12).joinToString("\n") { "titre $it" }).size)
        assertEquals("titre 1", decodeSearches((1..12).joinToString("\n") { "titre $it" }).first())
    }
}
