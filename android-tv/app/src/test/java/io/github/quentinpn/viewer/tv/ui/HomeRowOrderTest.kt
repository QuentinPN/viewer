package io.github.quentinpn.viewer.tv.ui

import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeRowOrderTest {
    private class MemoryPrefs : StringSetPrefs {
        val strings = mutableMapOf<String, String>()

        override fun get(key: String): Set<String>? = null

        override fun put(key: String, value: Set<String>) = Unit

        override fun getString(key: String) = strings[key]

        override fun putString(key: String, value: String) {
            strings[key] = value
        }
    }

    private val films = Library("f", "Films", "Movie")
    private val series = Library("s", "Séries", "Series")
    private val defaults = listOf(RESUME_ROW, NEXT_UP_ROW, FAVORITES_ROW, "latest.f", "latest.s", PLAYLISTS_ROW)

    @Test
    fun nothingStoredKeepsTheDefaultOrder() {
        assertEquals(defaults, arrangedKeys(emptyList(), defaults))
    }

    @Test
    fun theStoredOrderWins() {
        val stored = listOf(PLAYLISTS_ROW, "latest.s", "latest.f", FAVORITES_ROW, NEXT_UP_ROW, RESUME_ROW)
        assertEquals(stored, arrangedKeys(stored, defaults))
    }

    @Test
    fun unknownAndDuplicateKeysAreIgnored() {
        val stored = listOf("latest.gone", NEXT_UP_ROW, RESUME_ROW, NEXT_UP_ROW, FAVORITES_ROW, "latest.f", "latest.s", PLAYLISTS_ROW)
        assertEquals(
            listOf(NEXT_UP_ROW, RESUME_ROW, FAVORITES_ROW, "latest.f", "latest.s", PLAYLISTS_ROW),
            arrangedKeys(stored, defaults),
        )
    }

    @Test
    fun aNewLibraryGoesAfterItsDefaultNeighbour() {
        // "latest.s" is new: it follows "latest.f", wherever the user put it
        val stored = listOf("latest.f", RESUME_ROW, NEXT_UP_ROW, FAVORITES_ROW, PLAYLISTS_ROW)
        assertEquals(
            listOf("latest.f", "latest.s", RESUME_ROW, NEXT_UP_ROW, FAVORITES_ROW, PLAYLISTS_ROW),
            arrangedKeys(stored, defaults),
        )
    }

    @Test
    fun aMissingFirstRowGoesBeforeItsFollower() {
        val stored = listOf(PLAYLISTS_ROW, NEXT_UP_ROW, FAVORITES_ROW, "latest.f", "latest.s")
        assertEquals(
            listOf(PLAYLISTS_ROW, RESUME_ROW, NEXT_UP_ROW, FAVORITES_ROW, "latest.f", "latest.s"),
            arrangedKeys(stored, defaults),
        )
    }

    @Test
    fun movingStopsAtTheEnds() {
        val keys = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), movedKey(keys, "b", -1))
        assertEquals(listOf("a", "c", "b"), movedKey(keys, "b", 1))
        assertEquals(keys, movedKey(keys, "a", -1))
        assertEquals(keys, movedKey(keys, "c", 1))
        assertEquals(keys, movedKey(keys, "x", 1))
    }

    @Test
    fun theOrderRoundTrips() {
        assertEquals(defaults, decodeOrder(encodeOrder(defaults)))
        assertEquals(emptyList<String>(), decodeOrder(null))
        assertEquals(emptyList<String>(), decodeOrder(""))
    }

    @Test
    fun theStoreMovesPerUserAndKeepsGoneLibraries() {
        val prefs = MemoryPrefs()
        val rows = homeRows(listOf(films, series))
        val store = HomeRowStore(prefs, "u1")
        assertEquals(emptyList<String>(), store.loadOrder())
        store.move(rows, PLAYLISTS_ROW, -1)
        val order = store.move(rows, PLAYLISTS_ROW, -1)
        assertEquals(listOf(RESUME_ROW, NEXT_UP_ROW, FAVORITES_ROW, PLAYLISTS_ROW, "latest.f", "latest.s"), order)
        assertEquals(order, HomeRowStore(prefs, "u1").loadOrder())
        assertEquals(emptyList<String>(), HomeRowStore(prefs, "u2").loadOrder())
        // "Séries" gone: its key stays stored, after the others, for when it comes back
        val after = store.move(homeRows(listOf(films)), RESUME_ROW, 1)
        assertEquals(listOf(NEXT_UP_ROW, RESUME_ROW, FAVORITES_ROW, PLAYLISTS_ROW, "latest.f"), after)
        assertEquals(after + "latest.s", store.loadOrder())
        assertEquals(
            listOf(NEXT_UP_ROW, RESUME_ROW, FAVORITES_ROW, PLAYLISTS_ROW, "latest.f", "latest.s"),
            arrangedRows(rows, store.loadOrder()).map { it.key },
        )
    }

    @Test
    fun theHomeShowsShelvesInTheStoredOrder() {
        fun item(id: String) = BaseItemDto(id = id, name = "Titre $id", type = "Movie")
        val shelves = homeShelves(
            HomeRowVisibility(setOf(NEXT_UP_ROW)),
            resume = listOf(item("r")),
            nextUp = listOf(item("n")),
            favorites = emptyList(),
            latest = listOf(films to listOf(item("f1")), series to listOf(item("s1"))),
            playlists = emptyList(),
            order = listOf("latest.s", RESUME_ROW, "latest.f"),
        )
        assertEquals(listOf(latestShelfTitle(series), RESUME_SHELF, latestShelfTitle(films)), shelves.map { it.title })
    }
}
