package io.github.quentinpn.viewer.tv.ui

import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import io.github.quentinpn.viewer.tv.emby.UserItemData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultSeasonTest {
    private fun season(id: String, index: Int?, unplayed: Int?, played: Boolean = false) =
        BaseItemDto(id = id, type = "Season", indexNumber = index, userData = UserItemData(played = played, unplayedItemCount = unplayed))

    @Test
    fun firstSeasonWithEpisodesLeft() {
        val seasons = listOf(season("s0", 0, 3), season("s1", 1, 0, played = true), season("s2", 2, 4), season("s3", 3, 12))
        assertEquals("s2", defaultSeason(seasons)?.id)
    }

    @Test
    fun everythingWatchedFallsBackToTheFirstRegularSeason() {
        val seasons = listOf(season("s0", 0, 2), season("s1", 1, 0, played = true), season("s2", 2, 0, played = true))
        assertEquals("s1", defaultSeason(seasons)?.id)
    }

    @Test
    fun specialsOnly() {
        assertEquals("s0", defaultSeason(listOf(season("s0", 0, 1)))?.id)
        assertNull(defaultSeason(emptyList()))
    }
}
