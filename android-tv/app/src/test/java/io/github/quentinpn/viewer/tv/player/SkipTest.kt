package io.github.quentinpn.viewer.tv.player

import io.github.quentinpn.viewer.tv.emby.Segment
import io.github.quentinpn.viewer.tv.emby.SegmentKind
import io.github.quentinpn.viewer.tv.emby.SegmentSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun noSegmentsNoPrompt() {
        assertNull(SkipPlan(emptyList()).at(1400.0, duration, hasNext = true))
    }
}
