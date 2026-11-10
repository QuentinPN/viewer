package io.github.quentinpn.viewer.tv.ui

import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import io.github.quentinpn.viewer.tv.emby.PersonDto
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CastRowTest {
    private fun person(name: String, type: String, id: String? = name.filter { it.isLetterOrDigit() }, role: String? = null, tag: String? = null) =
        PersonDto(name, type, role, id?.let(::JsonPrimitive), tag)

    private val member = CastMember("p1", "Zendaya", "Chani", "t1")

    @Test
    fun actorsOfAFilmInBillingOrder() {
        val film = BaseItemDto(
            id = "m",
            type = "Movie",
            people = listOf(
                person("Denis Villeneuve", "Director"),
                person("Timothée Chalamet", "Actor", "1", " Paul ", "tag1"),
                person("Zendaya", "Actor", "2", "", " "),
                person("Invité", "GuestStar", "3"),
                person("Sans id", "Actor", null),
                person(" ", "Actor", "4"),
                person("Mauvais id", "Actor", "../x"),
                person("Doublon", "Actor", "1"),
            ),
        )
        assertEquals(
            listOf(CastMember("1", "Timothée Chalamet", "Paul", "tag1"), CastMember("2", "Zendaya", null, null)),
            castMembers(film),
        )
    }

    @Test
    fun guestStarsOfAnEpisodeAndTheLimit() {
        val episode = BaseItemDto(id = "e", type = "Episode", people = listOf(person("Habitué", "Actor", "1"), person("Invitée", "GuestStar", "2", "Lilysa")))
        assertEquals(listOf(CastMember("2", "Invitée", "Lilysa", null)), castMembers(episode))
        val crowd = BaseItemDto(id = "s", type = "Series", people = (1..20).map { person("Acteur $it", "Actor", "a$it") })
        assertEquals(CAST_LIMIT, castMembers(crowd).size)
        assertEquals("a1", castMembers(crowd).first().id)
        assertEquals(emptyList<CastMember>(), castMembers(BaseItemDto(id = "x")))
    }

    @Test
    fun initialsOfFirstAndLastWords() {
        assertEquals("DV", initials("Denis Villeneuve"))
        assertEquals("Z", initials("Zendaya"))
        assertEquals("JL", initials("Jean-Pierre  Léaud"))
        assertEquals("ÉB", initials("élodie (voix) Bouchez"))
        assertEquals("?", initials("  "))
        assertEquals("?", initials("—"))
    }

    @Test
    fun aPersonPageRemembersTheCardBelow() {
        val pages = openOver(listOf(DetailPage.Movie("m1")), DetailPage.Person(member), member.id)
        assertEquals(listOf(DetailPage.Movie("m1", person = "p1"), DetailPage.Person(member)), pages)
        // Back: the film page, read anew, focuses the card again
        assertEquals(listOf(DetailPage.Movie("m1", person = "p1")), pages.dropLast(1))
    }

    @Test
    fun aTitleOfThePersonPageIsRememberedThenASimilarTitleForgetsTheCard() {
        val person = openOver(listOf(DetailPage.Series(SeriesTarget("s1"))), DetailPage.Person(member), member.id)
        val title = openOver(person, DetailPage.Movie("m2"), "m2")
        assertEquals(
            listOf(DetailPage.Series(SeriesTarget("s1"), person = "p1"), DetailPage.Person(member, opened = "m2"), DetailPage.Movie("m2")),
            title,
        )
        // Back to the film page that left for the person, then on to a similar title: no card to focus later
        val similar = openOver(listOf(DetailPage.Movie("m1", person = "p1")), DetailPage.Movie("m3"))
        assertEquals(listOf(DetailPage.Movie("m1"), DetailPage.Movie("m3")), similar)
        assertEquals(listOf(DetailPage.Movie("m3")), openOver(emptyList(), DetailPage.Movie("m3")))
    }

    @Test
    fun anEpisodeStacksOverItsSeriesAndBackNamesItsSeason() {
        val series = listOf(DetailPage.Movie("m1"), DetailPage.Series(SeriesTarget("s1")))
        val opened = SeriesTarget("s1", seasonId = "se2", episodeId = "e5")
        val withEpisode = openOver(series, DetailPage.Episode(opened))
        assertEquals(DetailPage.Episode(opened), withEpisode.last())
        // A person from the episode, then Back: the episode remembers the card
        val withPerson = openOver(withEpisode, DetailPage.Person(member), member.id)
        assertEquals(DetailPage.Episode(opened, person = "p1"), withPerson.dropLast(1).last())
        val back = SeriesTarget("s1", seasonId = "se2", seasonNumber = 2, episodeId = "e5")
        assertEquals(listOf(DetailPage.Movie("m1"), DetailPage.Series(back)), backFromEpisode(withEpisode, back))
        assertEquals(listOf(DetailPage.Series(back)), backFromEpisode(listOf(DetailPage.Episode(opened)), back))
    }

    @Test
    fun oneEpisodeCompositionAcrossPreviousAndNext() {
        val first = DetailPage.Episode(SeriesTarget("s1", episodeId = "e1"))
        val next = DetailPage.Episode(SeriesTarget("s1", episodeId = "e2"))
        assertEquals(pageKey(first), pageKey(next))
        assertEquals(DetailPage.Movie("m1"), pageKey(DetailPage.Movie("m1")))
        assertNotEquals(pageKey(DetailPage.Movie("m1")), pageKey(DetailPage.Movie("m2")))
    }
}
