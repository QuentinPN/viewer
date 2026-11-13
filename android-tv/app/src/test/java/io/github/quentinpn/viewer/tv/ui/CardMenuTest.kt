package io.github.quentinpn.viewer.tv.ui

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import io.github.quentinpn.viewer.tv.emby.EmbyError
import io.github.quentinpn.viewer.tv.emby.UserItemData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardMenuTest {
    private fun item(id: String, type: String = "Movie", data: UserItemData? = null) =
        BaseItemDto(id = id, name = "Titre $id", type = type, userData = data)

    private fun labels(actions: List<MenuAction>) = actions.map { it.label }

    @Test
    fun aFilmPlaysThenAddsToAPlaylistThenMarksAndFavours() {
        assertEquals(
            listOf("Lire", "Ajouter à une playlist", "Marquer comme vu", "Ajouter aux favoris"),
            labels(menuActions(item("m"))),
        )
        assertEquals(MenuAction.Play("m", 0), menuActions(item("m")).first())
    }

    @Test
    fun aResumePointMakesItReprendreFromThere() {
        val started = item("e", "Episode", UserItemData(playbackPositionTicks = 420))
        val actions = menuActions(started)
        assertEquals(MenuAction.Play("e", 420), actions.first())
        assertEquals("Reprendre", actions.first().label)
        // Watched to the end: played from the start, as the hero and the detail pages do
        val watched = item("e", "Episode", UserItemData(played = true, playbackPositionTicks = 420))
        assertEquals(MenuAction.Play("e", 0), menuActions(watched).first())
        assertEquals("Lire", menuActions(watched).first().label)
    }

    @Test
    fun theResumeShelfAddsItsRemovalLast() {
        val actions = menuActions(item("e", "Episode"), MenuContext(resumeShelf = true))
        assertEquals(MenuAction.Write(ItemAction.RemoveFromResume), actions.last())
        assertEquals(5, actions.size)
        assertTrue(menuActions(item("e", "Episode")).none { it == MenuAction.Write(ItemAction.RemoveFromResume) })
    }

    @Test
    fun theMarksAreTheOppositeOfTheCurrentState() {
        val both = item("m", data = UserItemData(played = true, isFavorite = true))
        assertEquals(
            listOf(MenuAction.Write(ItemAction.MarkUnplayed), MenuAction.Write(ItemAction.RemoveFavorite)),
            menuActions(both).drop(2),
        )
    }

    @Test
    fun aSeriesPlaysItsNextEpisodeWithoutPlayAllOrShuffle() {
        assertEquals(
            listOf("Lire le prochain épisode", "Ajouter à une playlist", "Marquer comme vu", "Ajouter aux favoris"),
            labels(menuActions(item("s", "Series"))),
        )
    }

    @Test
    fun aSeasonHasNoPlayRow() {
        assertEquals(
            listOf(MenuAction.AddToPlaylist, MenuAction.Write(ItemAction.MarkPlayed), MenuAction.Write(ItemAction.AddFavorite)),
            menuActions(item("s1", "Season")),
        )
    }

    @Test
    fun aPlaylistOrAFolderHasNoMenu() {
        assertTrue(menuActions(item("p", "Playlist")).isEmpty())
        assertTrue(menuActions(item("f", "Folder"), MenuContext(resumeShelf = true)).isEmpty())
    }

    @Test
    fun eachRowHasItsIcon() {
        assertEquals(
            listOf(MenuIcon.Play, MenuIcon.PlaylistAdd, MenuIcon.Check, MenuIcon.Heart, MenuIcon.Trash),
            menuActions(item("e", "Episode"), MenuContext(resumeShelf = true)).map { it.icon },
        )
        assertEquals(MenuIcon.Play, MenuAction.PlayNextEpisode.icon)
    }

    @Test
    fun theNextEpisodeResumesWhereItStopped() {
        val episode = item("e2", "Episode", UserItemData(playbackPositionTicks = 99))
        assertEquals(MenuAction.Play("e2", 99), nextEpisodePlay(listOf(item("x", "Season"), episode)))
        assertNull(nextEpisodePlay(emptyList()))
        assertNull(nextEpisodePlay(listOf(item("", "Episode"))))
    }

    @Test
    fun backGoesUpOnePageThenCloses() {
        assertEquals(MenuPage.Playlists, MenuPage.NewPlaylist.previous)
        assertEquals(MenuPage.Actions, MenuPage.Playlists.previous)
        assertNull(MenuPage.Actions.previous)
    }

    @Test
    fun aPlaylistNameIsTrimmedAndCut() {
        assertEquals("Soirée", playlistName("  Soirée  "))
        assertNull(playlistName("   "))
        assertEquals(PLAYLIST_NAME_MAX, playlistName("a".repeat(150))!!.length)
    }

    @Test
    fun theNameStartsAsTheSeriesOfAnEpisode() {
        assertEquals("Titre m", defaultPlaylistName(item("m")))
        val episode = BaseItemDto(id = "e", name = "Pilote", type = "Episode", seriesName = "Lost")
        assertEquals("Lost", defaultPlaylistName(episode))
    }

    @Test
    fun theHeaderShowsTheYearOrTheEpisode() {
        assertEquals("2009", menuSubtitle(BaseItemDto(id = "m", name = "Avatar", type = "Movie", productionYear = 2009)))
        assertNull(menuSubtitle(item("m")))
        val episode = BaseItemDto(id = "e", name = "Pilote", type = "Episode", seriesName = "Lost", parentIndexNumber = 1, indexNumber = 2)
        assertEquals("Lost", menuTitle(episode))
        assertEquals("S1 · É2 — Pilote", menuSubtitle(episode))
    }

    @Test
    fun messagesAreInFrench() {
        assertEquals("Ajouté à Soirée", addedTo("Soirée"))
        assertEquals("Ajout impossible. Session expirée. Reconnecte-toi.", playlistFailed(EmbyError.SessionExpired))
    }

    @Test
    fun thePanelOpensOverItsCardAndStaysOnScreen() {
        val window = IntSize(1920, 1080)
        val panel = IntSize(600, 500)
        // A card in the middle: centred over it, top on its top
        assertEquals(IntOffset(700, 300), menuOffset(IntRect(900, 300, 1100, 600), window, panel, 48))
        // The first card of a row: at the left edge
        assertEquals(IntOffset(48, 300), menuOffset(IntRect(96, 300, 456, 600), window, panel, 48))
        // The last card: at the right edge; a low card: moved up
        assertEquals(IntOffset(1272, 532), menuOffset(IntRect(1700, 900, 1900, 1200), window, panel, 48))
        // Larger than the window: on the margin, not off screen
        assertEquals(IntOffset(48, 48), menuOffset(IntRect(0, 0, 100, 100), IntSize(500, 400), panel, 48))
    }
}
