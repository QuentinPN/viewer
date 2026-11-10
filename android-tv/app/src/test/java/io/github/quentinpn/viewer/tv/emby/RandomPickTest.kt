package io.github.quentinpn.viewer.tv.emby

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RandomPickTest {
    private val grid = "https://emby.example/emby/Users/u1/Items".toHttpUrl().newBuilder()
        .addQueryParameter("ParentId", "lib1")
        .addQueryParameter("Recursive", "true")
        .addQueryParameter("IncludeItemTypes", "Movie")
        .addQueryParameter("SortBy", "ProductionYear,SortName")
        .addQueryParameter("SortOrder", "Descending")
        .addQueryParameter("Filters", "IsUnplayed,IsFavorite")
        .addQueryParameter("Genres", "Drame")
        .addQueryParameter("StartIndex", "60")
        .addQueryParameter("Limit", "60")

    @Test
    fun keepsTheFiltersAndDrawsOneTitle() {
        val url = randomPick(grid).build()
        for ((name, value) in listOf(
            "ParentId" to "lib1", "Recursive" to "true", "IncludeItemTypes" to "Movie",
            "Filters" to "IsUnplayed,IsFavorite", "Genres" to "Drame",
            "SortBy" to "Random", "Limit" to "1", "EnableImages" to "false", "EnableUserData" to "false",
        )) {
            assertEquals(name, value, url.queryParameter(name))
        }
        // One value each: the grid's sort and page are replaced, not doubled
        assertEquals(listOf("Random"), url.queryParameterValues("SortBy"))
        assertEquals(listOf("1"), url.queryParameterValues("Limit"))
        assertNull(url.queryParameter("SortOrder"))
        assertNull(url.queryParameter("StartIndex"))
    }

    @Test
    fun randomIsTheDocumentedSortKey() {
        assertEquals("Random", RANDOM_SORT)
    }
}
