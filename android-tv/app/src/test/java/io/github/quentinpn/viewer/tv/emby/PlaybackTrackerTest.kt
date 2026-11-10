package io.github.quentinpn.viewer.tv.emby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTrackerTest {
    private val t0 = 1_000_000L

    private fun playback() = ActivePlayback(
        itemId = "30207",
        mediaSourceId = "mediasource_30207",
        playSessionId = "ps1",
        method = PlayMethod.DirectPlay,
        runTimeTicks = null,
        startTicks = 600_000_000,
    )

    private fun kinds(reports: List<Report>) = reports.map { it.kind to it.body.eventName }

    private fun loaded(): PlaybackTracker {
        val tracker = PlaybackTracker()
        assertTrue(tracker.begin(playback(), t0).isEmpty())
        tracker.fileLoaded(t0)
        return tracker
    }

    @Test
    fun startIsReportedOnLoadAtTheResumePosition() {
        val tracker = PlaybackTracker()
        tracker.begin(playback(), t0)
        assertTrue("not loaded", tracker.tick(t0 + PROGRESS_INTERVAL_MS).isEmpty())
        val start = tracker.fileLoaded(t0)
        assertEquals(listOf(ReportKind.Start to null), kinds(start))
        assertEquals(
            PlaybackReport(
                itemId = "30207",
                mediaSourceId = "mediasource_30207",
                playSessionId = "ps1",
                positionTicks = 600_000_000,
                isPaused = false,
                canSeek = true,
                playMethod = "DirectPlay",
            ),
            start.single().body,
        )
    }

    @Test
    fun progressFollowsPauseSeekAndTheInterval() {
        val t = loaded()
        assertTrue(t.position(61.0, t0).isEmpty())
        t.duration(6000.0)
        assertTrue("initial unpaused state", t.paused(false, t0).isEmpty())
        assertTrue(t.tick(t0 + 9_000).isEmpty())

        val progress = t.tick(t0 + PROGRESS_INTERVAL_MS)
        assertEquals(listOf(ReportKind.Progress to "TimeUpdate"), kinds(progress))
        assertEquals(610_000_000L, progress.single().body.positionTicks)
        assertEquals(60_000_000_000L, progress.single().body.runTimeTicks)

        val t1 = t0 + 12_000
        val pause = t.paused(true, t1)
        assertEquals(listOf(ReportKind.Progress to "Pause"), kinds(pause))
        assertEquals(true, pause.single().body.isPaused)
        // Still reported while paused, counted from the last report
        assertTrue(t.tick(t1 + 9_000).isEmpty())
        assertEquals(1, t.tick(t1 + PROGRESS_INTERVAL_MS).size)

        val t2 = t1 + 11_000
        assertEquals(listOf(ReportKind.Progress to "Unpause"), kinds(t.paused(false, t2)))
        t.seeked()
        val afterSeek = t.position(1200.5, t2)
        assertEquals(listOf(ReportKind.Progress to "TimeUpdate"), kinds(afterSeek))
        assertEquals(12_005_000_000L, afterSeek.single().body.positionTicks)
        assertTrue(t.position(1201.0, t2).isEmpty())
        assertTrue(t.position(Double.NaN, t2).isEmpty())
    }

    @Test
    fun stopCarriesTheLastPosition() {
        for (end in listOf<PlaybackTracker.(Long) -> List<Report>>({ ended(it) }, { closed(it) })) {
            val t = loaded()
            // 10 s before the end of the 6000 s file: a normal end
            t.duration(6000.0)
            t.position(5990.0, t0)
            val stop = t.end(t0)
            assertEquals(listOf(ReportKind.Stopped to null), kinds(stop))
            val body = stop.single().body
            assertEquals(59_900_000_000L, body.positionTicks)
            assertEquals(false, body.failed)
            assertNull(body.isPaused)
            assertEquals("DirectPlay", body.playMethod)
            // Nothing more once stopped
            assertTrue(t.closed(t0).isEmpty())
            assertTrue(t.tick(t0 + PROGRESS_INTERVAL_MS * 3).isEmpty())
        }
    }

    @Test
    fun anEarlyEndIsFailedButLeavingIsNot() {
        val t = loaded()
        t.duration(6000.0)
        t.position(1000.0, t0)
        val stop = t.ended(t0).single().body
        assertEquals(true, stop.failed)
        assertEquals(10_000_000_000L, stop.positionTicks)

        val left = loaded()
        left.duration(6000.0)
        left.position(1000.0, t0)
        assertEquals(false, left.closed(t0).single().body.failed)
    }

    @Test
    fun aFileThatNeverLoadsIsNeverReported() {
        val t = PlaybackTracker()
        t.begin(playback(), t0)
        assertTrue(t.ended(t0).isEmpty())
        assertTrue(t.fileLoaded(t0).isEmpty())

        val left = PlaybackTracker()
        left.begin(playback(), t0)
        assertTrue(left.closed(t0).isEmpty())
        assertTrue(left.fileLoaded(t0).isEmpty())
    }

    @Test
    fun aNewPlaybackStopsTheCurrentOne() {
        val t = loaded()
        val stop = t.begin(playback().copy(itemId = "80475"), t0)
        assertEquals(listOf(ReportKind.Stopped to null), kinds(stop))
        assertEquals("30207", stop.single().body.itemId)
        assertEquals("80475", t.fileLoaded(t0).single().body.itemId)
    }

    @Test
    fun aLateProgressCarriesThePositionOfTheLostStop() {
        val stop = Report(
            ReportKind.Stopped,
            PlaybackReport(
                itemId = "30207",
                mediaSourceId = "ms",
                playSessionId = "ps1",
                positionTicks = 7_583_000_000,
                runTimeTicks = 60_000_000_000,
                playMethod = "Transcode",
                failed = true,
            ),
        )
        val late = stop.lateProgress()
        assertEquals(ReportKind.Progress, late.kind)
        assertEquals(7_583_000_000, late.body.positionTicks)
        assertEquals("ps1", late.body.playSessionId)
        assertEquals("Transcode", late.body.playMethod)
        assertEquals(false, late.body.isPaused)
        assertEquals(true, late.body.canSeek)
        assertEquals("TimeUpdate", late.body.eventName)
        assertNull(late.body.failed)
    }

    @Test
    fun idleOnlyWithNothingAnnouncedOrPlaying() {
        val t = PlaybackTracker()
        assertTrue(t.idle)
        t.begin(ActivePlayback("i", "ms", null, PlayMethod.DirectPlay, null, 0), 0)
        assertFalse(t.idle)
    }

    @Test
    fun itemIdIsTheAnnouncedOrPlayingItem() {
        val t = PlaybackTracker()
        assertNull(t.itemId)
        t.begin(playback(), t0)
        assertEquals("announced", "30207", t.itemId)
        t.fileLoaded(t0)
        assertEquals("playing", "30207", t.itemId)
        t.closed(t0)
        assertNull(t.itemId)
    }

    private fun requalified() = playback().copy(
        mediaSourceId = "mediasource_30207",
        playSessionId = "ps2",
        method = PlayMethod.Transcode,
        startTicks = 1_200_000_000,
    )

    @Test
    fun qualityChangeIsAProgressOnceTheNewFileLoads() {
        val t = loaded()
        t.duration(6000.0)
        t.position(120.0, t0)
        t.switched(requalified())
        assertTrue("nothing before the new file plays", t.tick(t0 + 1).isEmpty())

        val change = t.fileLoaded(t0 + 2_000)
        assertEquals(listOf(ReportKind.Progress to QUALITY_CHANGE), kinds(change))
        val body = change.single().body
        assertEquals("ps2", body.playSessionId)
        assertEquals("Transcode", body.playMethod)
        assertEquals(1_200_000_000L, body.positionTicks)
        assertEquals("duration kept", 60_000_000_000L, body.runTimeTicks)
        assertNull("never a stop", body.failed)

        // Later reports and the final stop use the new stream
        t.position(5990.0, t0 + 3_000)
        val stop = t.closed(t0 + 4_000).single()
        assertEquals(ReportKind.Stopped, stop.kind)
        assertEquals("ps2", stop.body.playSessionId)
        assertEquals(false, stop.body.failed)
    }

    @Test
    fun switchedIsIgnoredWithoutThatItemPlaying() {
        val idle = PlaybackTracker()
        idle.switched(requalified())
        assertTrue(idle.fileLoaded(t0).isEmpty())

        val other = loaded()
        other.switched(requalified().copy(itemId = "99"))
        assertTrue("other item: no report", other.fileLoaded(t0).isEmpty())

        // A new playback or a stop forgets a pending switch
        val begun = loaded()
        begun.switched(requalified())
        begun.begin(playback().copy(itemId = "30208", playSessionId = "ps3"), t0)
        assertEquals(listOf(ReportKind.Start to null), kinds(begun.fileLoaded(t0)))

        val closed = loaded()
        closed.switched(requalified())
        closed.closed(t0)
        assertTrue(closed.fileLoaded(t0).isEmpty())
    }
}
