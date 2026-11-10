package io.github.quentinpn.viewer.tv.player

import io.github.quentinpn.viewer.tv.emby.Segment
import io.github.quentinpn.viewer.tv.emby.SegmentKind
import io.github.quentinpn.viewer.tv.emby.SegmentSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerTest {
    private val intro = Segment(SegmentKind.Intro, 50.0, 80.0, SegmentSource.IntroDb)
    private val credits = Segment(SegmentKind.Credits, 1345.0, 1435.1, SegmentSource.IntroDb)
    private val duration = 1440.0
    private val now = 1_700_000_000_000L

    @Test
    fun choicesOfferTheEndOnlyForAnEpisodeOrAFilm() {
        val minutes = listOf(15, 30, 45, 60, 90).map { SleepChoice.Minutes(it) }
        assertEquals(listOf(SleepChoice.Off) + minutes, sleepChoices(null))
        assertEquals(listOf(SleepChoice.Off) + minutes + SleepChoice.EndOfItem, sleepChoices(SleepItem.Episode))
        assertEquals(SleepItem.Episode, SleepItem.of("Episode"))
        assertEquals(SleepItem.Movie, SleepItem.of("Movie"))
        assertNull(SleepItem.of("Video"))
        assertNull(SleepItem.of(null))
    }

    @Test
    fun leftRightHoldAtTheEndsAndOkWraps() {
        val item = SleepItem.Movie
        assertEquals(SleepChoice.Minutes(15), changeSleep(SleepChoice.Off, SleepChange.Next, item))
        assertEquals(SleepChoice.Off, changeSleep(SleepChoice.Off, SleepChange.Previous, item))
        assertEquals(SleepChoice.EndOfItem, changeSleep(SleepChoice.Minutes(90), SleepChange.Next, item))
        assertEquals(SleepChoice.EndOfItem, changeSleep(SleepChoice.EndOfItem, SleepChange.Next, item))
        assertEquals(SleepChoice.Off, changeSleep(SleepChoice.EndOfItem, SleepChange.Cycle, item))
        // Without an episode or a film, 90 min is the last value
        assertEquals(SleepChoice.Off, changeSleep(SleepChoice.Minutes(90), SleepChange.Cycle, null))
        // Not offered any more: from « Désactivée »
        assertEquals(SleepChoice.Minutes(15), changeSleep(SleepChoice.EndOfItem, SleepChange.Cycle, null))
        val all = generateSequence<SleepChoice>(SleepChoice.Off) { changeSleep(it, SleepChange.Cycle, item).takeIf { next -> next != SleepChoice.Off } }.toList()
        assertEquals(sleepChoices(item), all)
    }

    @Test
    fun minutesRunOutOnTheWallClockPausedOrNot() {
        val timer = sleepTimer(SleepChoice.Minutes(15), now, 300.0)
        assertTrue(timer.on)
        assertFalse(timer.holdsNext)
        assertEquals(now + 15 * 60_000, timer.deadline)
        // The position does not count: only the time since it was set
        assertFalse(sleepDue(timer, now + 15 * 60_000 - 1, 9_999.0, duration, emptyList()))
        assertTrue(sleepDue(timer, now + 15 * 60_000, 0.0, duration, emptyList()))
        assertFalse("off never runs out", sleepDue(SleepTimer(), Long.MAX_VALUE, duration, duration, emptyList()))
        assertEquals(SleepTimer(), sleepTimer(SleepChoice.Off, now, 10.0))
    }

    @Test
    fun minutesLeftRoundUp() {
        val timer = sleepTimer(SleepChoice.Minutes(30), now, 0.0)
        assertEquals(30, sleepMinutesLeft(timer, now))
        assertEquals(30, sleepMinutesLeft(timer, now + 1))
        assertEquals(29, sleepMinutesLeft(timer, now + 60_000))
        assertEquals(1, sleepMinutesLeft(timer, now + 30 * 60_000 - 1))
        assertEquals(0, sleepMinutesLeft(timer, now + 31 * 60_000))
        assertNull(sleepMinutesLeft(SleepTimer(), now))
        assertNull(sleepMinutesLeft(sleepTimer(SleepChoice.EndOfItem, now, 0.0), now))
    }

    @Test
    fun endOfItemStopsAtTheCreditsElseAtTheEnd() {
        val timer = sleepTimer(SleepChoice.EndOfItem, now, 100.0)
        assertTrue(timer.holdsNext)
        assertEquals(1345.0, sleepStop(listOf(intro, credits), duration, 100.0)!!, 0.0)
        assertFalse(sleepDue(timer, now, 1344.0, duration, listOf(intro, credits)))
        assertTrue(sleepDue(timer, now, 1345.0, duration, listOf(intro, credits)))
        // Without credits: just before the end, positions come about once a second
        assertEquals(1439.0, sleepStop(listOf(intro), duration, 100.0)!!, 0.0)
        assertFalse(sleepDue(timer, now, 1438.0, duration, emptyList()))
        assertTrue(sleepDue(timer, now, 1439.0, duration, emptyList()))
        // Time alone never runs it out
        assertFalse(sleepDue(timer, Long.MAX_VALUE, 200.0, duration, emptyList()))
    }

    @Test
    fun endOfItemSetDuringTheCreditsWaitsForTheEnd() {
        val timer = sleepTimer(SleepChoice.EndOfItem, now, 1350.0)
        assertEquals(1439.0, sleepStop(listOf(credits), duration, 1350.0)!!, 0.0)
        assertFalse(sleepDue(timer, now, 1351.0, duration, listOf(credits)))
        // Duration not known yet and no credits: nothing to stop at
        assertNull(sleepStop(emptyList(), 0.0, 0.0))
        assertFalse(sleepDue(sleepTimer(SleepChoice.EndOfItem, now, 0.0), now, 5.0, 0.0, emptyList()))
    }

    @Test
    fun textsAreFrench() {
        assertEquals("Désactivée", sleepValue(SleepChoice.Off, SleepItem.Episode))
        assertEquals("45 min", sleepValue(SleepChoice.Minutes(45), null))
        assertEquals("Fin de l'épisode", sleepValue(SleepChoice.EndOfItem, SleepItem.Episode))
        assertEquals("Fin du film", sleepValue(SleepChoice.EndOfItem, SleepItem.Movie))
        assertNull(sleepBadge(SleepTimer(), now, SleepItem.Movie))
        assertEquals("Arrêt dans 60 min", sleepBadge(sleepTimer(SleepChoice.Minutes(60), now, 0.0), now, null))
        assertEquals("Arrêt dans 1 min", sleepBadge(sleepTimer(SleepChoice.Minutes(15), now, 0.0), now + 14 * 60_000 + 1, null))
        assertEquals("Arrêt : fin de l'épisode", sleepBadge(sleepTimer(SleepChoice.EndOfItem, now, 0.0), now, SleepItem.Episode))
        assertEquals("Arrêt : fin du film", sleepBadge(sleepTimer(SleepChoice.EndOfItem, now, 0.0), now, SleepItem.Movie))
        assertEquals("Minuterie écoulée : lecture en pause", SLEEP_NOTICE)
    }
}
