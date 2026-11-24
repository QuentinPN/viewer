package io.github.quentinpn.viewer.tv.stremio

import java.io.File
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A time the test moves by hand. */
internal class HandClock(var now: Instant = Instant.parse("2026-11-23T20:00:00Z")) {
    fun advance(by: Duration) {
        now += by
    }
}

class ResumeStoreTest {
    @get:Rule val dir = TemporaryFolder()

    private val clock = HandClock()
    private val logs = mutableListOf<String>()
    private val file by lazy { File(dir.root, RESUME_FILE) }

    private fun store() = ResumeStore(file, { clock.now }, { logs += it })

    /** The file read back, as a fresh launch would. */
    private fun onDisk(target: String) = store().get(target)

    @Test fun `the last 500 updates are kept, the oldest go first`() {
        val s = store()
        for (i in 0..500) {
            s.put("tt%07d".format(i), ResumePoint(600.0, 3000.0))
            clock.advance(Duration.ofSeconds(1))
        }
        // Touched again, the first one is no longer the oldest
        s.put("tt0000001", ResumePoint(700.0, 3000.0))
        s.put("tt9999999", ResumePoint(800.0, 3000.0))

        assertNull(onDisk("tt0000000"))
        assertNull(onDisk("tt0000002"))
        assertEquals(ResumePoint(700.0, 3000.0), onDisk("tt0000001"))
        assertEquals(ResumePoint(800.0, 3000.0), onDisk("tt9999999"))
        assertEquals(ResumePoint(600.0, 3000.0), onDisk("tt0000500"))
        assertFalse("temporary file left", File(dir.root, "$RESUME_FILE.tmp").exists())
    }

    @Test fun `a corrupt file is an empty store, logged without its content, replaced on the next write`() {
        file.writeText("""{"tt0111161": SECRETCONTENT""")

        val s = store()

        assertNull(s.get("tt0111161"))
        assertTrue(logs.isNotEmpty())
        assertTrue(logs.none { "SECRETCONTENT" in it })
        s.put("tt0111161", ResumePoint(600.0, 3000.0))
        assertEquals(ResumePoint(600.0, 3000.0), onDisk("tt0111161"))
    }

    @Test fun `entries that make no sense are dropped on read`() {
        file.writeText(
            """{"tt1":{"positionSeconds":-1,"durationSeconds":100,"updatedAt":"2026-11-23T20:00:00Z"},""" +
                """"tt2":{"positionSeconds":200,"durationSeconds":100,"updatedAt":"2026-11-23T20:00:00Z"},""" +
                """"tt3":{"positionSeconds":70,"durationSeconds":100,"updatedAt":"2026-11-23T20:00:00Z"}}""",
        )

        val s = store()

        assertNull(s.get("tt1"))
        assertNull(s.get("tt2"))
        assertEquals(ResumePoint(70.0, 100.0), s.get("tt3"))
    }

    @Test fun `a missing file is an empty store`() {
        assertNull(store().get("tt0111161"))
        assertTrue(logs.isEmpty())
    }
}
