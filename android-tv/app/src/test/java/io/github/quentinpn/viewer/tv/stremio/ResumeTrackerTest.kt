package io.github.quentinpn.viewer.tv.stremio

import java.io.File
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The desktop's `resume_test.go` cases a TV player can meet: one player, one stream. */
class ResumeTrackerTest {
    @get:Rule val dir = TemporaryFolder()

    private val clock = HandClock()
    private val file by lazy { File(dir.root, RESUME_FILE) }
    private val store by lazy { ResumeStore(file, { clock.now }, {}) }

    /** Fixes sent (each a seek to 0 and a resume), the ones spawned and not run yet, and ends released. */
    private var fixes = 0
    private var fixWorks = true
    private val spawned = mutableListOf<() -> Unit>()
    private var released = 0

    private fun tracker(target: String = FILM, start: Double? = null) = ResumeTracker(
        store,
        target,
        start,
        fix = { fixes++; fixWorks },
        spawn = { spawned += it },
        release = { released++ },
    )

    /** Runs the fixes spawned so far, as the main thread would. */
    private fun runSpawned() {
        val run = spawned.toList()
        spawned.clear()
        run.forEach { it() }
    }

    /** A tracker whose file of [duration] seconds is loaded. */
    private fun playing(start: Double? = null, duration: Double = 3000.0) = tracker(start = start).apply {
        fileLoaded()
        duration(duration)
    }

    /** The file read back, as a fresh launch would. */
    private fun onDisk(target: String = FILM) = ResumeStore(file, { clock.now }, {}).get(target)

    @Test fun `nothing is written before 60 s, and an entry already there stays`() {
        playing().apply { position(59.0); end() }
        assertNull(onDisk())

        store.put(FILM, ResumePoint(1200.0, 3000.0))
        playing().apply { position(30.0); end() }
        assertEquals(ResumePoint(1200.0, 3000.0), onDisk())

        playing().apply { position(61.0); end() }
        assertEquals(ResumePoint(61.0, 3000.0), onDisk())
    }

    @Test fun `from 95 percent on the entry goes`() {
        store.put(FILM, ResumePoint(1200.0, 3000.0))
        playing().apply { position(2850.0); end() }
        assertNull(onDisk())
    }

    @Test fun `a real end of file deletes the entry`() {
        store.put(FILM, ResumePoint(1200.0, 3000.0))
        val t = playing()
        t.position(2999.0)
        assertTrue(t.endReached())
        assertNull(onDisk())
    }

    @Test fun `a stream cut short keeps its position, by an early end of file or a failure`() {
        playing().apply { position(1500.0); assertTrue(endReached()) }
        assertEquals(ResumePoint(1500.0, 3000.0), onDisk())

        playing().apply { position(1600.0); endFile() }
        assertEquals(ResumePoint(1600.0, 3000.0), onDisk())
    }

    @Test fun `without a duration nothing is written, and its end of file still deletes`() {
        store.put(FILM, ResumePoint(1500.0, 3000.0))
        tracker().apply { fileLoaded(); position(1600.0); end() }
        assertEquals(ResumePoint(1500.0, 3000.0), onDisk())

        tracker().apply { fileLoaded(); position(1600.0); assertTrue(endReached()) }
        assertNull(onDisk())
    }

    @Test fun `a playback writes at most every 15 s, and when it ends`() {
        val t = playing()
        fun step(pos: Double, by: Long) {
            clock.advance(Duration.ofSeconds(by))
            t.position(pos)
        }
        step(100.0, 14)
        assertNull("written before 15 s", onDisk())
        step(101.0, 1)
        assertEquals(ResumePoint(101.0, 3000.0), onDisk())
        step(110.0, 10)
        assertEquals(ResumePoint(101.0, 3000.0), onDisk())
        step(116.0, 5)
        assertEquals(ResumePoint(116.0, 3000.0), onDisk())
        step(120.0, 1)
        t.end()
        assertEquals(ResumePoint(120.0, 3000.0), onDisk())
        // Ended, nothing is followed any more
        step(500.0, 60)
        t.save()
        assertEquals(ResumePoint(120.0, 3000.0), onDisk())
    }

    @Test fun `leaving the screen writes where it is and goes on following`() {
        val t = playing()
        t.position(700.0)
        t.save()
        assertEquals(ResumePoint(700.0, 3000.0), onDisk())
        t.position(710.0)
        t.end()
        assertEquals(ResumePoint(710.0, 3000.0), onDisk())
    }

    @Test fun `a start inside the file is left alone`() {
        val t = playing(start = 1200.0)
        t.position(1200.0)
        t.position(2999.0)
        assertTrue(t.endReached())
        assertEquals(0, fixes + spawned.size)
    }

    // Seen with libmpv 0.40 and keep-open: FileLoaded, a position at the end, Duration, then the end of file
    @Test fun `a start past the end of a shorter file goes back to 0, its end of file held back, the entry kept`() {
        store.put(FILM, ResumePoint(5000.0, 6000.0))
        val t = tracker(start = 5000.0)
        t.fileLoaded()
        t.position(3000.0)
        t.duration(3000.0)

        assertFalse("end of file shown to the player", t.endReached())
        runSpawned()
        assertEquals(1, fixes)
        assertEquals(0, released)
        clock.advance(Duration.ofMinutes(1))
        listOf(3000.0, 0.0, 30.0).forEach(t::position)
        t.end()
        assertEquals(ResumePoint(5000.0, 6000.0), onDisk())
    }

    @Test fun `a duration before FileLoaded still puts a start past the end back to 0`() {
        val t = tracker(start = 5000.0)
        t.duration(3000.0)
        t.fileLoaded()

        runSpawned()
        assertEquals(1, fixes)
        assertFalse(t.endReached())
        assertTrue(t.endReached())
    }

    @Test fun `the fix answered before the end of file, that end is still held back once, the next one shows`() {
        val t = playing(start = 5000.0)
        runSpawned()
        assertFalse(t.endReached())
        t.position(0.0)
        t.position(2999.0)
        assertTrue(t.endReached())
        assertNull("kept after the end", onDisk())
    }

    @Test fun `the fix won the race and no end of file came, the next real one shows`() {
        val t = playing(start = 5000.0)
        runSpawned()
        t.position(0.0)
        t.position(2999.0)
        assertTrue(t.endReached())
    }

    @Test fun `only one end of file is held back, even before any position from 0`() {
        val t = playing(start = 5000.0)
        runSpawned()
        assertFalse(t.endReached())
        assertTrue(t.endReached())
    }

    @Test fun `a fix that fails hands the held end of file on, and does not hold back a later one`() {
        fixWorks = false
        val t = playing(start = 5000.0)
        assertFalse("shown before the fix answered", t.endReached())
        runSpawned()
        assertEquals(1, released)

        released = 0
        val u = playing(start = 5000.0)
        runSpawned()
        assertTrue(u.endReached())
        assertEquals(0, released)
    }

    @Test fun `without a duration first, an end of file is held back only when mpv stopped short of the start`() {
        tracker(start = 5000.0).apply { fileLoaded(); assertTrue(endReached()) }
        tracker(start = 5000.0).apply {
            fileLoaded()
            position(5000.0)
            position(5003.0)
            assertTrue(endReached())
        }
        assertEquals(0, spawned.size)

        val t = tracker(start = 5000.0)
        t.fileLoaded()
        t.position(3000.0)
        assertFalse(t.endReached())
        runSpawned()
        assertEquals(1, fixes)
        t.position(0.0)
        t.position(2999.0)
        assertTrue(t.endReached())
    }

    @Test fun `a fix spawned after the player went is never sent`() {
        val t = playing(start = 5000.0)
        t.end()
        runSpawned()
        assertEquals(0, fixes)
    }

    private companion object {
        const val FILM = "tt0111161"
    }
}
