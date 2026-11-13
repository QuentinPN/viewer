package io.github.quentinpn.viewer.tv.player

import io.github.quentinpn.viewer.tv.emby.Segment
import io.github.quentinpn.viewer.tv.emby.SegmentKind
import io.github.quentinpn.viewer.tv.emby.SegmentSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipTest {
    // The episode of the 2026-11-07 evidence: intro 50–80 s, credits 1345–1435.1 s
    private val intro = Segment(SegmentKind.Intro, 50.0, 80.0, SegmentSource.IntroDb)
    private val credits = Segment(SegmentKind.Credits, 1345.0, 1435.1, SegmentSource.IntroDb)
    private val segments = listOf(intro, credits)
    private val duration = 1440.0

    @Test
    fun creditsNearOrWithoutAnEndRunToTheEndOfTheFile() {
        assertEquals(1435.1, segmentEnd(credits, duration)!!, 0.0)
        assertEquals(1436.0, segmentEnd(credits, 1436.0)!!, 0.0)
        val open = Segment(SegmentKind.Credits, 1345.0, null, SegmentSource.Emby)
        assertEquals(duration, segmentEnd(open, duration)!!, 0.0)
        assertNull("duration unknown", segmentEnd(open, 0.0))
    }

    @Test
    fun activeSegmentFollowsThePosition() {
        assertNull(activeSegment(segments, 49.0, duration))
        assertEquals(intro, activeSegment(segments, 50.0, duration))
        assertEquals(intro, activeSegment(segments, 79.0, duration))
        assertNull("gone in the last half second", activeSegment(segments, 79.6, duration))
        assertNull(activeSegment(segments, 600.0, duration))
        assertEquals(credits, activeSegment(segments, 1400.0, duration))
        assertNull(activeSegment(emptyList(), 60.0, duration))
    }

    @Test
    fun introSeeksToItsEnd() {
        val plan = SkipPlan(segments)
        assertEquals(SkipPrompt.Button("Passer l'intro", 80.0), plan.at(60.0, duration, hasNext = true))
        assertNull(plan.at(100.0, duration, hasNext = true))
    }

    @Test
    fun creditsOfAnEpisodeWithANextOneOfferTheCardOnce() {
        val plan = SkipPlan(segments)
        assertEquals(SkipPrompt.UpNext, plan.at(1345.0, duration, hasNext = true))
        // Dismissed: the credits keep a button instead
        assertEquals(SkipPrompt.Button("Passer le générique", 1435.1), plan.at(1346.0, duration, hasNext = true))
        assertEquals(SkipPrompt.Button("Passer le générique", 1435.1), plan.at(1350.0, duration, hasNext = true))
    }

    @Test
    fun creditsWithoutANextOneSkipToTheEndOfTheFile() {
        val open = Segment(SegmentKind.Credits, 5400.0, null, SegmentSource.Emby)
        val plan = SkipPlan(listOf(open))
        assertEquals(SkipPrompt.Button("Passer le générique", 5600.0), plan.at(5450.0, 5600.0, hasNext = false))
        // Credits before a last scene end where they end
        assertEquals(SkipPrompt.Button("Passer le générique", 1435.1), SkipPlan(segments).at(1400.0, 1500.0, hasNext = false))
    }

    @Test
    fun autoSkipsEachSegmentOnceThenShowsTheButton() {
        val plan = SkipPlan(segments, auto = true)
        assertEquals(SkipPrompt.Auto(80.0), plan.at(50.0, duration, hasNext = false))
        // Seeking back into the intro: the button, as on Windows
        assertEquals(SkipPrompt.Button("Passer l'intro", 80.0), plan.at(55.0, duration, hasNext = false))
        assertEquals(SkipPrompt.Auto(1435.1), plan.at(1345.0, 1500.0, hasNext = false))
        assertEquals(SkipPrompt.Button("Passer le générique", 1435.1), plan.at(1350.0, 1500.0, hasNext = false))
    }

    @Test
    fun autoSkipLeavesCreditsWithANextEpisodeToTheCard() {
        val plan = SkipPlan(segments, auto = true)
        assertEquals(SkipPrompt.UpNext, plan.at(1345.0, duration, hasNext = true))
        // The card dismissed, the credits play on with a button, never skipped behind the user
        assertEquals(SkipPrompt.Button("Passer le générique", 1435.1), plan.at(1346.0, duration, hasNext = true))
    }

    @Test
    fun withoutAutoNothingSkipsOnItsOwn() {
        val plan = SkipPlan(segments)
        assertEquals(SkipPrompt.Button("Passer l'intro", 80.0), plan.at(50.0, duration, hasNext = false))
        assertEquals(SkipPrompt.Button("Passer le générique", 1435.1), plan.at(1345.0, 1500.0, hasNext = false))
    }

    @Test
    fun noSegmentsNoPrompt() {
        assertNull(SkipPlan(emptyList()).at(1400.0, duration, hasNext = true))
    }

    // ADR 0089: the button hides 10 s after it appears, as on Windows
    private val introButton = SkipPrompt.Button("Passer l'intro", 80.0)
    private val creditsButton = SkipPrompt.Button("Passer le générique", 1435.1)

    @Test
    fun buttonShowsTenSecondsThenHides() {
        val shown = trackSkipShown(SkipShown(), introButton, 1_000)
        assertTrue(skipShown(shown, 1_000, controls = false))
        assertTrue(skipShown(shown, 1_000 + SKIP_SHOWN_MS - 1, controls = false))
        assertFalse(skipShown(shown, 1_000 + SKIP_SHOWN_MS, controls = false))
    }

    @Test
    fun hiddenButtonComesBackWithTheControls() {
        val shown = trackSkipShown(SkipShown(), introButton, 0)
        assertTrue(skipShown(shown, 30_000, controls = true))
        assertFalse(skipShown(shown, 30_001, controls = false))
    }

    @Test
    fun countdownKeptWithinTheSegment() {
        val first = trackSkipShown(SkipShown(), introButton, 0)
        val later = trackSkipShown(first, SkipPrompt.Button("Passer l'intro", 80.0), 20_000)
        assertSame(first, later)
        assertFalse(skipShown(later, 20_000, controls = false))
    }

    @Test
    fun countdownRestartsForTheNextSegmentOrAfterLeavingIt() {
        val intro = trackSkipShown(SkipShown(), introButton, 0)
        assertTrue(skipShown(trackSkipShown(intro, creditsButton, 60_000), 60_000, controls = false))
        // A seek before the intro's start, then playback back into it
        val out = trackSkipShown(intro, null, 20_000)
        assertFalse(skipShown(out, 20_000, controls = true))
        assertTrue(skipShown(trackSkipShown(out, introButton, 25_000), 25_000, controls = false))
    }

    @Test
    fun planPositionsDriveTheCountdown() {
        val plan = SkipPlan(segments)
        var shown = SkipShown()
        fun at(position: Double, now: Long): Boolean {
            shown = trackSkipShown(shown, plan.at(position, duration, hasNext = false) as? SkipPrompt.Button, now)
            return skipShown(shown, now, controls = false)
        }
        assertTrue(at(50.0, 0))
        assertTrue(at(59.0, 9_000))
        assertFalse(at(61.0, 11_000))
        assertFalse(at(45.0, 12_000))
        assertTrue("back into the intro", at(50.0, 17_000))
    }

    @Test
    fun okAfterASeekPlaysOrPausesInsteadOfSkipping() {
        // Shown during plain playback: armed, OK skips
        assertTrue(SkipArm.appeared(controls = false))
        assertEquals(SkipOk.Skip, SkipArm.onOk(visible = true, armed = true))
        // Entered by ◀ ▶, the controls up: OK plays or pauses
        assertFalse(SkipArm.appeared(controls = true))
        assertEquals(SkipOk.PlayPause, SkipArm.onOk(visible = true, armed = false))
        // Hidden after its 10 s: OK only brings it back (ADR 0089)
        assertEquals(SkipOk.Reveal, SkipArm.onOk(visible = false, armed = false))
        assertEquals(SkipOk.Reveal, SkipArm.onOk(visible = false, armed = true))
    }
}
