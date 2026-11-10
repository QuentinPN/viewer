package io.github.quentinpn.viewer.tv.ui

import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import io.github.quentinpn.viewer.tv.emby.NameDto
import io.github.quentinpn.viewer.tv.emby.PersonDto
import org.junit.Assert.assertEquals
import org.junit.Test

class FactsTest {
    private val people = listOf(
        PersonDto("Denis Villeneuve", "Director"),
        PersonDto("Jon Spaihts", "Writer"),
        PersonDto(" ", "Actor"),
    ) + (1..8).map { PersonDto("Acteur $it", "Actor") }

    @Test
    fun filmFacts() {
        val film = BaseItemDto(id = "f", type = "Movie", people = people, studios = listOf(NameDto("Legendary")))
        assertEquals(
            listOf(
                Fact("Réalisation", "Denis Villeneuve"),
                Fact("Scénario", "Jon Spaihts"),
                Fact("Studio", "Legendary"),
            ),
            facts(film),
        )
    }

    @Test
    fun seriesShowNoWriterNorCastAndABroadcaster() {
        val series = BaseItemDto(id = "s", type = "Series", people = people.drop(1).take(3), studios = listOf(NameDto("Crunchyroll")))
        // The cast is the « Distribution » row now (ADR 0068)
        assertEquals(listOf(Fact("Diffusion", "Crunchyroll")), facts(series))
        assertEquals(emptyList<Fact>(), facts(BaseItemDto(id = "e")))
    }

    @Test
    fun tagsAfterTheYear() {
        val item = BaseItemDto(
            id = "i",
            officialRating = "TV-14",
            genres = listOf("Action", "", "Fantastique", "Animation", "Aventure"),
            communityRating = 7.44,
            criticRating = 87.0,
        )
        // The age rating is the badge now (ADR 0074)
        assertEquals(listOf("Action, Fantastique, Animation", "★ 7,4", "87 % critiques"), tags(item))
        assertEquals(listOf("★ 7,4", "87 % critiques"), scores(item))
        assertEquals(emptyList<String>(), tags(BaseItemDto(id = "j", officialRating = "12", communityRating = 0.0)))
    }
}
