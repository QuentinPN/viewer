package io.github.quentinpn.viewer.tv.brand

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class LogoTest {
    private val paris = ZoneId.of("Europe/Paris")
    private val hour = 3_600_000L

    private fun at(date: String, time: String) = ZonedDateTime.of(java.time.LocalDate.parse(date), java.time.LocalTime.parse(time), paris)

    @Test
    fun anOrdinaryDayEndsAtMidnight() {
        assertEquals(24 * hour, millisUntilNextMidnight(at("2026-06-10", "00:00")))
        assertEquals(hour + 1_000, millisUntilNextMidnight(at("2026-06-10", "22:59:59")))
    }

    @Test
    fun midnightItselfWaitsForTheNextOne() {
        assertEquals(24 * hour, millisUntilNextMidnight(at("2026-12-31", "00:00")))
    }

    @Test
    fun theSpringForwardDayHasTwentyThreeHours() {
        assertEquals(23 * hour, millisUntilNextMidnight(at("2026-03-29", "00:00")))
        // 03:30 CEST is 2 h 30 min after 00:00 CET: 20 h 30 min remain.
        assertEquals(20 * hour + hour / 2, millisUntilNextMidnight(at("2026-03-29", "03:30")))
    }

    @Test
    fun theFallBackDayHasTwentyFiveHours() {
        assertEquals(25 * hour, millisUntilNextMidnight(at("2026-10-25", "00:00")))
        val secondTwoThirty = at("2026-10-25", "02:30").withLaterOffsetAtOverlap()
        assertEquals(21 * hour + hour / 2, millisUntilNextMidnight(secondTwoThirty))
    }
}
