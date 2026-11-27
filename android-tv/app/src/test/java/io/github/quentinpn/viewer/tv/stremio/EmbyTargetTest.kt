package io.github.quentinpn.viewer.tv.stremio

import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** The desktop's `stremio.Target`: a film by its IMDb id, an episode by its series' and its numbers. */
class EmbyTargetTest {
    private val series = BaseItemDto(id = "s1", type = "Series", providerIds = mapOf("IMDB" to "tt0903747"))

    private fun episode(season: Int? = 2, number: Int? = 5, seriesId: String? = "s1") = BaseItemDto(
        id = "e1",
        type = "Episode",
        seriesId = seriesId,
        parentIndexNumber = season,
        indexNumber = number,
        // An episode's own id is never the one asked
        providerIds = mapOf("Imdb" to "tt1111111"),
    )

    private fun kindOf(block: () -> Unit): StremioErrorKind? = try {
        block()
        fail("expected a StremioException")
        null
    } catch (e: StremioException) {
        e.error.kind
    }

    @Test
    fun aFilmIsAskedByItsOwnImdbId() {
        val target = embyTarget(BaseItemDto(id = "m1", type = "Movie", providerIds = mapOf("imdb" to "tt0133093")), null)
        assertEquals(ContentType.Movie, target.type)
        assertEquals("tt0133093", target.id)
    }

    @Test
    fun anEpisodeIsAskedByItsSeriesImdbIdAndItsNumbers() {
        val target = embyTarget(episode(), series)
        assertEquals(ContentType.Series, target.type)
        assertEquals("tt0903747:2:5", target.id)
    }

    @Test
    fun withoutAnUsableImdbIdTheListSaysSo() {
        assertEquals(StremioErrorKind.NoImdb, kindOf { embyTarget(BaseItemDto(id = "m1", type = "Movie", providerIds = mapOf("Tmdb" to "550")), null) })
        // Series not found, or another item in its place
        assertEquals(StremioErrorKind.NoImdb, kindOf { embyTarget(episode(), null) })
        assertEquals(StremioErrorKind.NoImdb, kindOf { embyTarget(episode(seriesId = "other"), series) })
        assertEquals(StremioErrorKind.NoImdb, kindOf { embyTarget(episode(number = null), series) })
    }

    @Test
    fun otherItemsAndUnnamableEpisodesAreInvalid() {
        assertEquals(StremioErrorKind.InvalidId, kindOf { embyTarget(series, null) })
        assertEquals(StremioErrorKind.InvalidId, kindOf { embyTarget(episode(season = 1, number = 0), series) })
    }
}
