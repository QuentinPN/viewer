package io.github.quentinpn.viewer.tv.ui

import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import io.github.quentinpn.viewer.tv.emby.PersonDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset

class EpisodePageTest {
    private fun ep(id: String, season: Int?, index: Int?, series: String? = "ser1", name: String = "", seasonId: String? = null) =
        BaseItemDto(id = id, type = "Episode", seriesId = series, parentIndexNumber = season, indexNumber = index, name = name, seasonId = seasonId)

    // AdjacentTo: a few episodes before, then the rest of the series, across seasons
    private val adjacent = listOf(ep("e1", 1, 9), ep("e2", 1, 10), ep("x", 3, 1, series = "other"), ep("e3", 2, 1), ep("e4", 2, 2))

    @Test
    fun neighboursCrossSeasonsAndSkipOtherSeries() {
        val around = neighbours(adjacent, "e2", "ser1")
        assertEquals("e1", around.previous?.id)
        assertEquals("e3", around.next?.id)
    }

    @Test
    fun firstAndLastEpisodesHaveOneNeighbour() {
        assertNull(neighbours(adjacent, "e1", "ser1").previous)
        assertEquals("e2", neighbours(adjacent, "e1", "ser1").next?.id)
        assertEquals("e3", neighbours(adjacent, "e4", "ser1").previous?.id)
        assertNull(neighbours(adjacent, "e4", "ser1").next)
    }

    @Test
    fun anEpisodeMissingFromTheAnswerHasNoNeighbours() {
        assertEquals(Neighbours(null, null), neighbours(adjacent, "gone", "ser1"))
        assertEquals(Neighbours(null, null), neighbours(emptyList(), "e1", "ser1"))
    }

    @Test
    fun positionAsOnWindows() {
        assertEquals("Saison 2 · Épisode 14", episodePosition(2, 14, null))
        assertEquals("Saison 1 · Épisodes 2–3", episodePosition(1, 2, 3))
        assertEquals("Saison 1 · Épisode 2", episodePosition(1, 2, 2))
        assertEquals("Spécial 3", episodePosition(0, 3, null))
        assertEquals("Spécial", episodePosition(0, null, null))
        assertEquals("Épisode 4", episodePosition(null, 4, null))
        assertNull(episodePosition(null, null, null))
    }

    @Test
    fun neighbourButtonsShowCodeAndTitle() {
        assertEquals("S1 · É3 · Le forgeron", neighbourLabel(ep("e", 1, 3, name = " Le forgeron ")))
        assertEquals("S1 · É2–3", neighbourLabel(BaseItemDto(id = "e", parentIndexNumber = 1, indexNumber = 2, indexNumberEnd = 3)))
        assertEquals("Spécial 1 · Bêtisier", neighbourLabel(ep("e", 0, 1, name = "Bêtisier")))
        assertEquals("Pilote", neighbourLabel(ep("e", null, null, name = "Pilote")))
    }

    @Test
    fun airDateInTheDeviceZone() {
        val paris = ZoneId.of("Europe/Paris")
        // 22:00 UTC is the next day in Paris, as Windows shows it
        assertEquals("5 juillet 2024", airDate("2024-07-04T22:00:00.0000000Z", paris))
        assertEquals("4 juillet 2024", airDate("2024-07-04T22:00:00.0000000Z", ZoneOffset.UTC))
        assertEquals("1 mars 2021", airDate("2021-03-01T00:00:00.0000000", paris))
        assertNull(airDate(null, paris))
        assertNull(airDate("", paris))
        assertNull(airDate("demain", paris))
    }

    @Test
    fun episodeImageElseSeriesBackdrop() {
        val own = BaseItemDto(id = "e", imageTags = mapOf("Primary" to "p1"), parentBackdropItemId = "ser1", parentBackdropImageTags = listOf("b1"))
        assertEquals(Artwork("e", "Primary", "p1"), episodeArtwork(own))
        assertEquals(Artwork("ser1", "Backdrop", "b1"), episodeArtwork(own.copy(imageTags = emptyMap())))
        assertNull(episodeArtwork(BaseItemDto(id = "e", parentBackdropItemId = "ser1")))
    }

    @Test
    fun backKeepsTheCardSeasonElseTheEpisodeOne() {
        val card = SeriesTarget("ser1", seasonId = "s2", seasonNumber = 0, episodeId = "e")
        val special = ep("e", 0, 1, seasonId = "s0")
        assertEquals(card, returnTarget(card, special))
        // Reached by previous / next without SeasonId: the loaded episode gives it
        val moved = neighbourTarget("ser1", ep("e", null, 1))
        assertEquals(SeriesTarget("ser1", seasonId = "s0", seasonNumber = 0, episodeId = "e"), returnTarget(moved, special))
        assertEquals(moved, returnTarget(moved, null))
    }

    @Test
    fun cardOpensItsPageOnTheSeasonShown() {
        assertEquals(SeriesTarget("ser1", seasonId = "s1", seasonNumber = 1, episodeId = "e2"), cardTarget("ser1", "s1", ep("e2", 1, 10)))
    }

    @Test
    fun episodeCreditsLeaveGuestStarsToTheCastRow() {
        val people = listOf(
            PersonDto("Réalisatrice", "Director"),
            PersonDto("Acteur", "Actor", rawId = JsonPrimitive("1")),
            PersonDto("Invitée", "GuestStar", rawId = JsonPrimitive("2")),
        )
        val episode = BaseItemDto(id = "e", type = "Episode", people = people)
        // The « Invités » line is the « Distribution » row now
        assertEquals(listOf(Fact("Réalisation", "Réalisatrice")), facts(episode))
        assertEquals(listOf("Invitée"), castMembers(episode).map { it.name })
        assertEquals(listOf("Acteur"), castMembers(BaseItemDto(id = "m", type = "Movie", people = people)).map { it.name })
    }

    @Test
    fun episodeFieldsAsTheWindowsFixtureSendsThem() {
        // Fields of `src-tauri/src/emby/tests.rs` (Emby 4.10.1.0)
        val json = """{"Name":"Ep","Id":"80475","Type":"Episode","IndexNumber":2,"IndexNumberEnd":3,"ParentIndexNumber":1,
            "PremiereDate":"2024-07-04T22:00:00.0000000Z","SeriesName":"La série"}"""
        val item = Json { ignoreUnknownKeys = true }.decodeFromString(BaseItemDto.serializer(), json)
        assertEquals(3, item.indexNumberEnd)
        assertEquals("2024-07-04T22:00:00.0000000Z", item.premiereDate)
    }
}
