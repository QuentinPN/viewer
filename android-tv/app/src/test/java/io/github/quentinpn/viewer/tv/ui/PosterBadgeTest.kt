package io.github.quentinpn.viewer.tv.ui

import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import io.github.quentinpn.viewer.tv.emby.UserItemData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PosterBadgeTest {
    private fun item(type: String, data: UserItemData?) = BaseItemDto(id = "i", type = type, userData = data)

    @Test
    fun watchedShowsTheCheck() {
        assertEquals(PosterBadge.Played, badge(item("Movie", UserItemData(played = true))))
        assertEquals(PosterBadge.Played, badge(item("Series", UserItemData(played = true, unplayedItemCount = 0))))
    }

    @Test
    fun seriesShowTheEpisodesLeft() {
        assertEquals(PosterBadge.Unplayed(18), badge(item("Series", UserItemData(unplayedItemCount = 18))))
    }

    @Test
    fun nothingElseIsMarked() {
        assertNull(badge(item("Movie", UserItemData())))
        assertNull(badge(item("Movie", null)))
        // A count on anything but a series is not shown, as on Windows
        assertNull(badge(item("Movie", UserItemData(unplayedItemCount = 1))))
        assertNull(badge(item("Series", UserItemData(unplayedItemCount = 0))))
    }
}
