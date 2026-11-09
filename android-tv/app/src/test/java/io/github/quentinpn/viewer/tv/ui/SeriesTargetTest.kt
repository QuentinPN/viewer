package io.github.quentinpn.viewer.tv.ui

import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import io.github.quentinpn.viewer.tv.emby.UserItemData
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesTargetTest {
    private fun season(id: String, index: Int?, unplayed: Int = 0, played: Boolean = false) =
        BaseItemDto(id = id, type = "Season", indexNumber = index, userData = UserItemData(played = played, unplayedItemCount = unplayed))

    private fun episode(id: String, played: Boolean = false) =
        BaseItemDto(id = id, type = "Episode", userData = UserItemData(played = played))

    // Specials, a finished season 1, season 2 with episodes left: the default is season 2
    private val seasons = listOf(season("s0", 0, 1), season("s1", 1, played = true), season("s2", 2, 4))

    @Test
    fun episodeFieldsAsTheWindowsFixtureSendsThem() {
        // Shape of `src-tauri/src/emby/tests.rs` (Emby 4.10.1.0): a special aired within season 1
        val json = """{"Name":"Date to Date","Id":"80490","IndexNumber":1,"ParentIndexNumber":0,"Type":"Episode",
            "SeriesId":"80473","SeasonId":"80480","SeasonName":"Épisodes spéciaux"}"""
        val item = Json { ignoreUnknownKeys = true }.decodeFromString(BaseItemDto.serializer(), json)
        assertEquals(SeriesTarget("80473", seasonId = "80480", seasonNumber = 0, episodeId = "80490"), episodeTarget(item))
    }

    @Test
    fun episodeWithoutSeriesOpensNothing() {
        assertNull(episodeTarget(BaseItemDto(id = "e1", type = "Episode", seasonId = "s1")))
    }

    @Test
    fun theTargetSeasonWinsOverTheDefault() {
        assertEquals("s1", openingSeason(seasons, SeriesTarget("x", seasonId = "s1", seasonNumber = 1))?.id)
        assertEquals("s0", openingSeason(seasons, SeriesTarget("x", seasonId = "s0", seasonNumber = 0))?.id)
    }

    @Test
    fun withoutSeasonIdTheNumberIsUsed() {
        assertEquals("s1", openingSeason(seasons, SeriesTarget("x", seasonNumber = 1))?.id)
        // An unknown id still falls back to the number
        assertEquals("s1", openingSeason(seasons, SeriesTarget("x", seasonId = "gone", seasonNumber = 1))?.id)
    }

    @Test
    fun missingSeasonFallsBackToTheDefault() {
        assertEquals("s2", openingSeason(seasons, SeriesTarget("x"))?.id)
        assertEquals("s2", openingSeason(seasons, SeriesTarget("x", seasonId = "gone", seasonNumber = 9))?.id)
        assertNull(openingSeason(emptyList(), SeriesTarget("x", seasonId = "s1")))
    }

    @Test
    fun theTargetEpisodeIsFocused() {
        val episodes = listOf(episode("e1", played = true), episode("e2"), episode("e3"))
        assertEquals(2, initialEpisodeIndex(episodes, "e3"))
        assertEquals(0, initialEpisodeIndex(episodes, "e1"))
    }

    @Test
    fun otherwiseTheFirstEpisodeNotWatched() {
        val episodes = listOf(episode("e1", played = true), episode("e2"), episode("e3"))
        assertEquals(1, initialEpisodeIndex(episodes, null))
        assertEquals(1, initialEpisodeIndex(episodes, "gone"))
        assertEquals(0, initialEpisodeIndex(listOf(episode("e1", played = true)), null))
        assertEquals(0, initialEpisodeIndex(emptyList(), "e1"))
    }
}
