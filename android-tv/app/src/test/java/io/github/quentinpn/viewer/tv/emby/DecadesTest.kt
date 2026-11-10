package io.github.quentinpn.viewer.tv.emby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecadesTest {
    @Test
    fun aYearFallsInItsDecade() {
        assertEquals(1980, decadeOf(1987))
        assertEquals(1980, decadeOf(1980))
        assertEquals(2020, decadeOf(2029))
    }

    @Test
    fun aDecadeIsTenYears() {
        assertEquals("2010,2011,2012,2013,2014,2015,2016,2017,2018,2019", decadeYears(2010))
    }

    @Test
    fun onlyRoundPlausibleDecadesAreUsable() {
        assertTrue(isUsableDecade(1920))
        assertTrue(isUsableDecade(FIRST_DECADE))
        assertFalse(isUsableDecade(1995))
        assertFalse(isUsableDecade(-1))
        assertFalse(isUsableDecade(FIRST_DECADE - 10))
        assertFalse(isUsableDecade(LAST_DECADE + 10))
    }

    @Test
    fun libraryDecadesComeNewestFirstWithoutDuplicates() {
        assertEquals(listOf(2020, 1990, 1950), decadesOf(listOf(1994, null, 2021, 1955, 1999, 2024, 0, 9999)))
        assertEquals(emptyList<Int>(), decadesOf(listOf(null)))
    }

    @Test
    fun theYearsParameterFollowsTheDecade() {
        assertNull(ItemsOptions().years())
        assertNull(ItemsOptions(decade = 1995).years())
        assertEquals(decadeYears(1970), ItemsOptions(decade = 1970).years())
        assertTrue(ItemsOptions(decade = 1970).narrowed)
        assertFalse(ItemsOptions().narrowed)
    }
}
