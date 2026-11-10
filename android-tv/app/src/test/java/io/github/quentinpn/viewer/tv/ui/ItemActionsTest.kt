package io.github.quentinpn.viewer.tv.ui

import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import io.github.quentinpn.viewer.tv.emby.EmbyError
import io.github.quentinpn.viewer.tv.emby.UserItemData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemActionsTest {
    private fun item(id: String, type: String = "Movie", data: UserItemData? = null) =
        BaseItemDto(id = id, name = "Titre $id", type = type, userData = data)

    private val resume = Shelf(RESUME_SHELF, listOf(item("a"), item("b"), item("c")), landscape = true, resume = true)
    private val favorites = Shelf(FAVORITES_SHELF, listOf(item("b", data = UserItemData(isFavorite = true)), item("d")), landscape = false, resume = false)
    private val latest = Shelf("Derniers ajouts · Films", listOf(item("b"), item("e")), landscape = false, resume = false)
    private val shelves = listOf(resume, favorites, latest)

    @Test
    fun theResumeShelfAlsoOffersToLeaveIt() {
        assertEquals(
            listOf(ItemAction.RemoveFromResume, ItemAction.MarkPlayed, ItemAction.AddFavorite),
            cardActions(item("a"), resumeShelf = true),
        )
        assertEquals(listOf(ItemAction.MarkPlayed, ItemAction.AddFavorite), cardActions(item("a", "Series"), resumeShelf = false))
    }

    @Test
    fun eachActionIsTheOppositeOfTheCurrentState() {
        val watched = item("a", "Episode", UserItemData(played = true, isFavorite = true))
        assertEquals(listOf(ItemAction.MarkUnplayed, ItemAction.RemoveFavorite), cardActions(watched, resumeShelf = false))
        assertEquals("Marquer comme non vu", playedAction(watched).label)
        assertEquals("Retirer des favoris", favoriteAction(watched).label)
        assertEquals("Ajouter aux favoris", favoriteAction(item("x")).label)
    }

    @Test
    fun aPlaylistHasNoMenu() {
        assertTrue(cardActions(item("p", "Playlist"), resumeShelf = false).isEmpty())
        assertTrue(cardActions(item("f", "Folder"), resumeShelf = true).isEmpty())
    }

    @Test
    fun leavingResumeRemovesTheCardThereOnly() {
        val data = UserItemData(playbackPositionTicks = 10, isFavorite = true)
        val after = shelvesAfter(shelves, "b", ItemAction.RemoveFromResume, data)
        assertEquals(listOf("a", "c"), after[0].items.map { it.id })
        assertEquals(listOf("b", "d"), after[1].items.map { it.id })
        assertEquals(data, after[2].items.first { it.id == "b" }.userData)
    }

    @Test
    fun aWatchedItemLeavesResumeAndShowsItsMarkElsewhere() {
        val data = UserItemData(played = true, isFavorite = true)
        val after = shelvesAfter(shelves, "b", ItemAction.MarkPlayed, data)
        assertEquals(listOf("a", "c"), after[0].items.map { it.id })
        assertTrue(after[2].items.first { it.id == "b" }.userData!!.played)
    }

    @Test
    fun anUnwatchedMarkKeepsTheCardInResume() {
        val after = shelvesAfter(shelves, "a", ItemAction.MarkUnplayed, UserItemData(played = false))
        assertEquals(listOf("a", "b", "c"), after[0].items.map { it.id })
    }

    @Test
    fun aFavouriteRemovedLeavesTheFavouritesRow() {
        val after = shelvesAfter(shelves, "b", ItemAction.RemoveFavorite, UserItemData(isFavorite = false))
        assertEquals(listOf("d"), after[1].items.map { it.id })
        assertEquals(listOf("a", "b", "c"), after[0].items.map { it.id })
        // A new favourite waits for the reload: the row is not reordered here
        val added = shelvesAfter(shelves, "e", ItemAction.AddFavorite, UserItemData(isFavorite = true))
        assertEquals(listOf("b", "d"), added[1].items.map { it.id })
        assertTrue(added[2].items.first { it.id == "e" }.userData!!.isFavorite)
    }

    @Test
    fun anEmptiedShelfGoes() {
        val one = listOf(Shelf(RESUME_SHELF, listOf(item("a")), landscape = true, resume = true), latest)
        val after = shelvesAfter(one, "a", ItemAction.RemoveFromResume, UserItemData())
        assertEquals(listOf(latest.title), after.map { it.title })
        assertNull(focusAfter(one, after, RESUME_SHELF, "a"))
    }

    @Test
    fun focusStaysOnTheCardOrGoesToTheOneThatTookItsPlace() {
        val kept = shelvesAfter(shelves, "b", ItemAction.AddFavorite, UserItemData(isFavorite = true))
        assertEquals(RESUME_SHELF to "b", focusAfter(shelves, kept, RESUME_SHELF, "b"))
        val removed = shelvesAfter(shelves, "b", ItemAction.RemoveFromResume, UserItemData())
        assertEquals(RESUME_SHELF to "c", focusAfter(shelves, removed, RESUME_SHELF, "b"))
        // The last card: the one before it
        val last = shelvesAfter(shelves, "c", ItemAction.RemoveFromResume, UserItemData())
        assertEquals(RESUME_SHELF to "b", focusAfter(shelves, last, RESUME_SHELF, "c"))
    }

    @Test
    fun aFailureSaysSoInFrench() {
        assertEquals("Action impossible. Serveur injoignable. Vérifiez l'adresse et le réseau.", actionFailed(EmbyError.Unreachable))
    }

    @Test
    fun seriesAndSeasonButtonsNameWhatTheyMark() {
        assertEquals("Marquer la série comme vue", seriesPlayedLabel(item("s", "Series")))
        assertEquals("Marquer la série comme non vue", seriesPlayedLabel(item("s", "Series", UserItemData(played = true))))
        assertEquals("Marquer la saison comme vue", seasonPlayedLabel(item("s1", "Season", UserItemData(unplayedItemCount = 3))))
        assertEquals("Marquer la saison comme non vue", seasonPlayedLabel(item("s1", "Season", UserItemData(played = true))))
    }
}
