package io.github.quentinpn.viewer.tv.ui

import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import io.github.quentinpn.viewer.tv.emby.ItemsOptions
import io.github.quentinpn.viewer.tv.emby.LetterStart
import io.github.quentinpn.viewer.tv.emby.UserItemData
import io.github.quentinpn.viewer.tv.emby.WatchFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryGridEditTest {
    private fun item(id: String, type: String = "Movie", data: UserItemData? = null) =
        BaseItemDto(id = id, name = "Titre $id", type = type, userData = data)

    private val grid = listOf(item("a"), item("b"), item("c"))

    @Test
    fun theWatchFilterAndFavouritesFollowTheNewData() {
        val played = UserItemData(played = true)
        val started = UserItemData(playbackPositionTicks = 10)
        assertTrue(stillMatches(played, ItemsOptions()))
        assertFalse(stillMatches(played, ItemsOptions(watch = WatchFilter.Unplayed)))
        assertTrue(stillMatches(played, ItemsOptions(watch = WatchFilter.Played)))
        assertFalse(stillMatches(UserItemData(), ItemsOptions(watch = WatchFilter.Played)))
        assertTrue(stillMatches(started, ItemsOptions(watch = WatchFilter.InProgress)))
        assertFalse(stillMatches(played, ItemsOptions(watch = WatchFilter.InProgress)))
        assertFalse(stillMatches(played, ItemsOptions(favorites = true)))
        assertTrue(stillMatches(UserItemData(isFavorite = true), ItemsOptions(favorites = true)))
        // Genre, decade and studio do not depend on the user's data
        assertTrue(stillMatches(UserItemData(), ItemsOptions(genre = "Drame", decade = 1990, studio = "HBO")))
    }

    @Test
    fun aCardStillMatchingIsUpdatedInPlace() {
        val data = UserItemData(played = true, isFavorite = true)
        val change = gridChange(grid, "b", data, ItemsOptions())
        assertEquals(GridChange.Updated(1, item("b", data = data)), change)
    }

    @Test
    fun aSeriesKeepsItsEpisodeCountWhenTheAnswerHasNone() {
        val series = item("s", "Series", UserItemData(unplayedItemCount = 7))
        val change = gridChange(listOf(series), "s", UserItemData(isFavorite = true), ItemsOptions()) as GridChange.Updated
        assertEquals(7, change.item.userData?.unplayedItemCount)
        assertEquals(PosterBadge.Unplayed(7), badge(change.item))
        // The server's own count wins
        val watched = gridChange(listOf(series), "s", UserItemData(played = true, unplayedItemCount = 0), ItemsOptions()) as GridChange.Updated
        assertEquals(0, watched.item.userData?.unplayedItemCount)
        assertEquals(PosterBadge.Played, badge(watched.item))
    }

    @Test
    fun aCardNoLongerMatchingLeavesAndItsNextCardTakesTheFocus() {
        val unplayed = ItemsOptions(watch = WatchFilter.Unplayed)
        assertEquals(GridChange.Removed(1, "c"), gridChange(grid, "b", UserItemData(played = true), unplayed))
        // The last card: the one before it
        assertEquals(GridChange.Removed(2, "b"), gridChange(grid, "c", UserItemData(played = true), unplayed))
        // The only card: none left
        assertEquals(GridChange.Removed(0, null), gridChange(listOf(item("a")), "a", UserItemData(), ItemsOptions(favorites = true)))
    }

    @Test
    fun anItemNotLoadedChangesNothing() {
        assertEquals(GridChange.Absent, gridChange(grid, "z", UserItemData(played = true), ItemsOptions()))
    }

    @Test
    fun laterLetterStartsMoveUpByOne() {
        val letters = mapOf('A' to LetterStart(0, "a"), 'B' to LetterStart(3, "b"), 'C' to LetterStart(5, "c"))
        // A title of « A » that did not start it
        val after = lettersAfterRemoval(letters, 1, "a2", "a3", total = 9)
        assertEquals(mapOf('A' to LetterStart(0, "a"), 'B' to LetterStart(2, "b"), 'C' to LetterStart(4, "c")), after)
    }

    @Test
    fun aLetterStartsOnItsNextTitleOrGoes() {
        val letters = mapOf('A' to LetterStart(0, "a"), 'B' to LetterStart(3, "b"), 'C' to LetterStart(5, "c"))
        // « B » had more titles: the next one starts it
        assertEquals(LetterStart(3, "b2"), lettersAfterRemoval(letters, 3, "b", "b2", total = 9)['B'])
        // Not loaded yet: the position finds it on a jump
        assertEquals(LetterStart(3, "b"), lettersAfterRemoval(letters, 3, "b", null, total = 9)['B'])
        // « B » had one title only: « C » starts right after, so « B » has none left
        val single = mapOf('A' to LetterStart(0, "a"), 'B' to LetterStart(3, "b"), 'C' to LetterStart(4, "c"))
        val after = lettersAfterRemoval(single, 3, "b", "c", total = 9)
        assertNull(after['B'])
        assertEquals(LetterStart(3, "c"), after['C'])
        // The very last title
        assertNull(lettersAfterRemoval(letters, 5, "c", null, total = 5)['C'])
    }
}
