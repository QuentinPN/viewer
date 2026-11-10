package io.github.quentinpn.viewer.tv.ui

import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRowsTest {
    /** Preferences in memory, as SharedPreferences keeps them: one set per key. */
    private class MemoryPrefs : StringSetPrefs {
        val values = mutableMapOf<String, Set<String>>()

        override fun get(key: String) = values[key]

        override fun put(key: String, value: Set<String>) {
            values[key] = value
        }
    }

    private val anime = Library("a", "Anime", "Series")
    private val films = Library("f", "Films", "Movie")
    private val filmsAnime = Library("fa", "Films Anime", "Movie")
    private val series = Library("s", "Séries TV", "Series")
    private val libraries = listOf(anime, films, filmsAnime, series)

    private fun item(id: String, type: String = "Movie") = BaseItemDto(id = id, name = "Titre $id", type = type)

    @Test
    fun everyRowIsShownByDefault() {
        val rows = HomeRowStore(MemoryPrefs(), "u1").load()
        for (row in homeRows(libraries)) assertTrue(row.label, rows.shows(row.key))
        assertEquals("Affichée", rowLabel(true))
        assertEquals("Masquée", rowLabel(false))
    }

    @Test
    fun theSettingsListEveryRowInTheHomeOrder() {
        assertEquals(
            listOf(
                "Reprendre",
                "À suivre",
                "Favoris",
                "Derniers ajouts · Anime",
                "Derniers ajouts · Films",
                "Derniers ajouts · Films Anime",
                "Derniers ajouts · Séries TV",
                "Listes de lecture",
            ),
            homeRows(libraries).map { it.label },
        )
        // A library's row is kept by id: a rename keeps the choice
        assertEquals("latest.fa", homeRows(libraries)[5].key)
    }

    @Test
    fun aToggleHidesThenShowsAgainAndIsStored() {
        val prefs = MemoryPrefs()
        val store = HomeRowStore(prefs, "u1")
        assertFalse(store.toggle(FAVORITES_ROW).shows(FAVORITES_ROW))
        assertFalse(store.toggle(latestRowKey("f")).shows(latestRowKey("f")))
        // Read again, as when the home is shown after Réglages
        val reread = HomeRowStore(prefs, "u1").load()
        assertFalse(reread.shows(FAVORITES_ROW))
        assertFalse(reread.shows(latestRowKey("f")))
        assertTrue(reread.shows(latestRowKey("a")))
        assertTrue(store.toggle(FAVORITES_ROW).shows(FAVORITES_ROW))
        assertEquals(setOf(latestRowKey("f")), HomeRowStore(prefs, "u1").load().hidden)
    }

    @Test
    fun eachEmbyUserKeepsItsOwnRows() {
        val prefs = MemoryPrefs()
        HomeRowStore(prefs, "u1").toggle(RESUME_ROW)
        assertFalse(HomeRowStore(prefs, "u1").load().shows(RESUME_ROW))
        assertTrue(HomeRowStore(prefs, "u2").load().shows(RESUME_ROW))
        HomeRowStore(prefs, "u2").toggle(PLAYLISTS_ROW)
        assertEquals(setOf(RESUME_ROW), HomeRowStore(prefs, "u1").load().hidden)
    }

    @Test
    fun unknownKeysHideNothingAndAreKept() {
        val prefs = MemoryPrefs()
        // A library gone from the server, and a value from a later version
        prefs.values["u1.hidden"] = setOf(latestRowKey("gone"), "later.row")
        val store = HomeRowStore(prefs, "u1")
        val rows = store.load()
        for (row in homeRows(libraries)) assertTrue(row.label, rows.shows(row.key))
        // A new library is shown
        assertTrue(rows.shows(latestRowKey("new")))
        assertEquals(libraries, rows.latestLibraries(libraries))
        // Toggling another row keeps them, for a library that comes back
        assertEquals(setOf(latestRowKey("gone"), "later.row", NEXT_UP_ROW), store.toggle(NEXT_UP_ROW).hidden)
    }

    @Test
    fun onlyTheShownLibrariesAreRequested() {
        val rows = HomeRowVisibility(setOf(latestRowKey("f"), latestRowKey("s")))
        assertEquals(listOf(anime, filmsAnime), rows.latestLibraries(libraries))
        assertEquals(libraries, HomeRowVisibility().latestLibraries(libraries))
    }

    private fun shelves(rows: HomeRowVisibility) = homeShelves(
        rows,
        resume = listOf(item("e1", "Episode")),
        nextUp = listOf(item("e1", "Episode"), item("e2", "Episode")),
        favorites = listOf(item("m1")),
        latest = listOf(films to listOf(item("m2")), series to listOf(item("s1", "Series")), anime to emptyList()),
        playlists = listOf(BaseItemDto(id = "p1", name = "Liste", type = "Playlist", mediaType = "Video")),
    )

    @Test
    fun allRowsShowInOrderByDefault() {
        val all = shelves(HomeRowVisibility())
        assertEquals(
            listOf("Reprendre", "À suivre", "Favoris", "Derniers ajouts · Films", "Derniers ajouts · Séries TV", "Listes de lecture"),
            all.map { it.title },
        )
        // "À suivre" without the episode already in "Reprendre"
        assertEquals(listOf("e2"), all[1].items.map { it.id })
        assertEquals(
            listOf(RESUME_ROW, NEXT_UP_ROW, FAVORITES_ROW, latestRowKey("f"), latestRowKey("s"), PLAYLISTS_ROW),
            all.map(::rowKey),
        )
    }

    @Test
    fun hiddenRowsAreSkipped() {
        val rows = HomeRowVisibility(setOf(FAVORITES_ROW, latestRowKey("f"), PLAYLISTS_ROW))
        assertEquals(listOf("Reprendre", "À suivre", "Derniers ajouts · Séries TV"), shelves(rows).map { it.title })
        val none = HomeRowVisibility(homeRows(libraries).map { it.key }.toSet())
        assertTrue(shelves(none).isEmpty())
    }

    @Test
    fun withReprendreHiddenItsEpisodeStaysInASuivre() {
        val shown = shelves(HomeRowVisibility(setOf(RESUME_ROW)))
        assertEquals("À suivre", shown.first().title)
        assertEquals(listOf("e1", "e2"), shown.first().items.map { it.id })
    }

    @Test
    fun shelvesStillOnScreenLeaveAsSoonAsTheirRowIsHidden() {
        val loaded = shelves(HomeRowVisibility())
        val after = HomeRowVisibility(setOf(NEXT_UP_ROW, latestRowKey("s"))).visibleShelves(loaded)
        assertEquals(listOf("Reprendre", "Favoris", "Derniers ajouts · Films", "Listes de lecture"), after.map { it.title })
    }

    @Test
    fun theLibrariesAreThoseOfTheHomeBar() {
        val views = listOf(
            BaseItemDto(id = "f", name = "Films", type = "CollectionFolder", collectionType = "movies"),
            BaseItemDto(id = "m", name = "Musique", type = "CollectionFolder", collectionType = "music"),
            BaseItemDto(id = "s", name = "Séries TV", type = "CollectionFolder", collectionType = "tvshows"),
        )
        assertEquals(listOf(films, series), homeLibraries(views))
    }
}
