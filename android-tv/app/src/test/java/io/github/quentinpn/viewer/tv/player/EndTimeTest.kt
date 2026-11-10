package io.github.quentinpn.viewer.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class EndTimeTest {
    private val paris = ZoneId.of("Europe/Paris")

    // 2026-11-10 21:05:30 in Paris (UTC+1 in winter)
    private val now = ZonedDateTime.of(2026, 11, 10, 21, 5, 30, 0, paris).toInstant().toEpochMilli()

    @Test
    fun clockIs24HoursInTheGivenZone() {
        assertEquals("21:05", formatClock(now, paris))
        assertEquals("20:05", formatClock(now, ZoneId.of("UTC")))
        // Next day, leading zero
        assertEquals("04:05", formatClock(now, ZoneId.of("Asia/Shanghai")))
    }

    @Test
    fun endIsNowPlusWhatIsLeft() {
        // 40 min left of a 45 min episode
        assertEquals(now + 2_400_000, endsAt(now, 300.0, 2_700.0, 1.0))
        assertEquals("Fin à 21:45", endLabel(now, 300.0, 2_700.0, 1.0, paris))
        // Past midnight
        assertEquals("Fin à 00:05", endLabel(now, 0.0, 3 * 3_600.0, 1.0, paris))
    }

    @Test
    fun speedShortensOrLengthensWhatIsLeft() {
        assertEquals(now + 1_200_000, endsAt(now, 300.0, 2_700.0, 2.0))
        assertEquals("Fin à 21:25", endLabel(now, 300.0, 2_700.0, 2.0, paris))
        assertEquals(now + 3_200_000, endsAt(now, 300.0, 2_700.0, 0.75))
        // An unreadable speed counts as « Normale »
        assertEquals(now + 2_400_000, endsAt(now, 300.0, 2_700.0, Double.NaN))
    }

    @Test
    fun noEndWithoutADuration() {
        assertNull("live", endLabel(now, 120.0, 0.0, 1.0, paris))
        assertNull(endsAt(now, 0.0, -1.0, 1.0))
        assertNull(endsAt(now, 0.0, Double.NaN, 1.0))
        assertNull(endsAt(now, 0.0, Double.POSITIVE_INFINITY, 1.0))
        // At or past the end (a seek shown before mpv's position): now
        assertEquals(now, endsAt(now, 2_800.0, 2_700.0, 1.0))
    }

    @Test
    fun nextMinuteIsNeverNow() {
        assertEquals(30_000, untilNextMinute(now))
        val boundary = now - 30_000
        assertEquals(60_000, untilNextMinute(boundary))
        assertEquals(1, untilNextMinute(boundary + 59_999))
    }
}
