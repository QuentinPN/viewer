package io.github.quentinpn.viewer.tv.emby

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RatingsTest {
    @Test
    fun communityScoreHasOneDecimalWithAComma() {
        assertEquals("★ 7,4", communityRatingLabel(7.44))
        assertEquals("★ 6,3", communityRatingLabel(6.25))
        assertEquals("★ 10,0", communityRatingLabel(10.0))
        // 0 means not rated, as on Windows
        assertNull(communityRatingLabel(0.0))
        assertNull(communityRatingLabel(null))
        assertNull(communityRatingLabel(-1.0))
        assertNull(communityRatingLabel(Double.NaN))
    }

    @Test
    fun criticScoreIsAPercentage() {
        assertEquals("87 % critiques", criticRatingLabel(87.0))
        assertEquals("17 % critiques", criticRatingLabel(16.6))
        // A real score, unlike the public's 0
        assertEquals("0 % critiques", criticRatingLabel(0.0))
        assertNull(criticRatingLabel(null))
        assertNull(criticRatingLabel(101.0))
        assertNull(criticRatingLabel(Double.NaN))
    }

    @Test
    fun ageRatingIsTrimmed() {
        assertEquals("TV-MA", ageRatingLabel(" TV-MA "))
        assertEquals("12", ageRatingLabel("12"))
        assertNull(ageRatingLabel("  "))
        assertNull(ageRatingLabel(null))
    }

    @Test
    fun detailFieldsAsTheServerSendsThem() {
        // Shapes of the Windows client's `/Users/{Id}/Items/30207` fixture (Emby 4.10): CriticRating is an integer
        val item = Json { ignoreUnknownKeys = true }.decodeFromString(
            BaseItemDto.serializer(),
            """{"Id":"30207","Type":"Movie","CriticRating":17,"OfficialRating":"PG-13","CommunityRating":6.234}""",
        )
        assertEquals("17 % critiques", criticRatingLabel(item.criticRating))
        assertEquals("★ 6,2", communityRatingLabel(item.communityRating))
        assertEquals("PG-13", ageRatingLabel(item.officialRating))
    }
}
