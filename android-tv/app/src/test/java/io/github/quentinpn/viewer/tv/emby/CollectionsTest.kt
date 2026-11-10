package io.github.quentinpn.viewer.tv.emby

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionsTest {
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    @Test
    fun refsReadANumberOrStringIdAndSkipWhatTheyCannotUse() {
        val raw = json(
            """[{"Name":"Dune - Saga","Id":120034},{"Name":"Favoris","Id":"bs-2"},{"Name":"Sans id"},""" +
                """{"Name":"Chemin","Id":"../Users"},{"Name":"  ","Id":5},{"Name":"Dune - Saga","Id":"120034"},"texte",null]""",
        )
        assertEquals(
            listOf(CollectionRef("120034", "Dune - Saga"), CollectionRef("bs-2", "Favoris")),
            collectionRefs(raw),
        )
    }

    @Test
    fun refsOfAnAbsentOrUnexpectedFieldAreEmptyAndLimited() {
        assertEquals(emptyList<CollectionRef>(), collectionRefs(null))
        assertEquals(emptyList<CollectionRef>(), collectionRefs(json("""{"Name":"x","Id":1}""")))
        assertEquals(emptyList<CollectionRef>(), collectionRefs(json("\"x\"")))
        val many = json((1..5).joinToString(",", "[", "]") { """{"Name":"C$it","Id":$it}""" })
        assertEquals(listOf("1", "2", "3"), collectionRefs(many).map { it.id })
    }

    @Test
    fun queryOfACollectionsTitles() {
        val query = collectionItemsQuery("120034").toMap()
        assertEquals("120034", query["ParentId"])
        assertEquals("PremiereDate,ProductionYear,SortName", query["SortBy"])
        assertEquals("Ascending", query["SortOrder"])
        assertEquals(COLLECTION_TITLES_LIMIT.toString(), query["Limit"])
        assertEquals("5", collectionItemsQuery("1", 5).toMap()["Limit"])
    }

    @Test
    fun titlesInReleaseOrderThenByNameWithoutDateLast() {
        val items = listOf(
            BaseItemDto(id = "3", name = "Dune : Troisième partie", type = "Movie"),
            BaseItemDto(id = "2", name = "Dune : Deuxième partie", type = "Movie", premiereDate = "2024-02-28T00:00:00.0000000Z", productionYear = 2024),
            BaseItemDto(id = "1", name = "Dune", type = "Movie", premiereDate = "2021-09-15T00:00:00.0000000Z", productionYear = 2021),
            BaseItemDto(id = "p", name = "Prophétie", type = "Series", productionYear = 2024),
            BaseItemDto(id = "b", name = "bonus", type = "Movie", premiereDate = "2021-09-15T00:00:00.0000000Z"),
            BaseItemDto(id = "e", name = "Épisode", type = "Episode", productionYear = 2022),
            BaseItemDto(id = "1", name = "Dune", type = "Movie", premiereDate = "2021-09-15T00:00:00.0000000Z", productionYear = 2021),
        )
        assertEquals(listOf("b", "1", "p", "2", "3"), collectionTitles(items).map { it.id })
        assertEquals(emptyList<BaseItemDto>(), collectionTitles(emptyList()))
    }

    @Test
    fun collectionsOfAFilmAreReadFromItsDetail() {
        val parser = Json { ignoreUnknownKeys = true }
        val film = parser.decodeFromString(
            BaseItemDto.serializer(),
            """{"Id":"m1","Type":"Movie","Collections":[{"Name":"Dune - Saga","Id":120034}]}""",
        )
        assertEquals(listOf(CollectionRef("120034", "Dune - Saga")), collectionRefs(film.rawCollections))
        // Any other shape leaves the film readable, without a row
        val odd = parser.decodeFromString(BaseItemDto.serializer(), """{"Id":"m1","Type":"Movie","Collections":"Dune"}""")
        assertEquals(emptyList<CollectionRef>(), collectionRefs(odd.rawCollections))
    }
}
