package io.github.quentinpn.viewer.tv.emby

import org.junit.Assert.assertEquals
import org.junit.Test

class PeopleTest {
    @Test
    fun queryOfAPersonsTitles() {
        val query = personItemsQuery("30209").toMap()
        assertEquals("30209", query["PersonIds"])
        assertEquals("true", query["Recursive"])
        assertEquals("Movie,Series", query["IncludeItemTypes"])
        assertEquals("ProductionYear,SortName", query["SortBy"])
        assertEquals("Descending", query["SortOrder"])
        assertEquals(PERSON_TITLES_LIMIT.toString(), query["Limit"])
        assertEquals("5", personItemsQuery("1", 5).toMap()["Limit"])
    }

    @Test
    fun titlesNewestFirstThenByNameWithoutYearLast() {
        val items = listOf(
            BaseItemDto(id = "a", name = "Ancien", type = "Movie", productionYear = 1999),
            BaseItemDto(id = "n", name = "Sans année", type = "Series"),
            BaseItemDto(id = "z", name = "Zodiac", type = "Movie", productionYear = 2021),
            BaseItemDto(id = "b", name = "beau", type = "Series", productionYear = 2021),
            BaseItemDto(id = "e", name = "Épisode", type = "Episode", productionYear = 2024),
            BaseItemDto(id = "z", name = "Zodiac", type = "Movie", productionYear = 2021),
        )
        assertEquals(listOf("b", "z", "a", "n"), personTitles(items).map { it.id })
        assertEquals(emptyList<BaseItemDto>(), personTitles(emptyList()))
    }
}
