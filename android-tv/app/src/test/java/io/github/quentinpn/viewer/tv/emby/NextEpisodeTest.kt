package io.github.quentinpn.viewer.tv.emby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextEpisodeTest {
    private fun episode(id: String, series: String? = "ser1", played: Boolean = false, position: Long = 0) =
        BaseItemDto(id = id, seriesId = series, userData = UserItemData(played = played, playbackPositionTicks = position))

    @Test
    fun theFollowingEpisodeOfTheSameSeries() {
        // AdjacentTo: a few episodes before, then the rest of the series, across seasons
        val answer = listOf(episode("e1"), episode("x1", series = "other"), episode("e2"), episode("e3"))
        assertEquals("e3", nextEpisode(answer, "e2", "ser1")?.id)
        assertEquals("e2", nextEpisode(answer, "e1", "ser1")?.id)
    }

    @Test
    fun noneAfterTheLastOrAnUnknownEpisode() {
        val answer = listOf(episode("e1"), episode("e2"))
        assertNull(nextEpisode(answer, "e2", "ser1"))
        assertNull(nextEpisode(answer, "e9", "ser1"))
        assertNull(nextEpisode(emptyList(), "e1", "ser1"))
    }

    @Test
    fun aStartedEpisodeResumesAWatchedOneRestarts() {
        assertEquals(42L, episode("e1", position = 42).startTicks())
        assertEquals(0L, episode("e1", played = true, position = 42).startTicks())
        assertEquals(0L, BaseItemDto(id = "e1").startTicks())
    }
}
