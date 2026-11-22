package io.github.quentinpn.viewer.tv.brand

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The vectors shared with the desktop (contracts/seasons): both sides read the same file. */
class SeasonTest {
    private val vectors =
        Json.parseToJsonElement(File("../../contracts/seasons/vectors.json").readText()).jsonArray.map {
            val o = it.jsonObject
            o.getValue("date").jsonPrimitive.content to o.getValue("season").jsonPrimitive.content
        }

    @Test
    fun everyVectorMatches() {
        assertTrue(vectors.isNotEmpty())
        for ((date, season) in vectors) {
            val (y, m, d) = date.split("-").map { it.toInt() }
            assertEquals(date, season, seasonFor(y, m, d).id)
        }
    }

    @Test
    fun vectorsCoverEverySeason() {
        assertEquals(Season.entries.map { it.id }.toSet(), vectors.map { it.second }.toSet())
    }

    @Test
    fun resIsTheIdWithUnderscores() {
        assertEquals("april_fools", Season.APRIL_FOOLS.res)
        assertEquals("new_year", Season.NEW_YEAR.res)
        assertEquals("default", Season.DEFAULT.res)
    }
}
