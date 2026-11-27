package io.github.quentinpn.viewer.tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** The desktop's rule (`PlayerView.svelte` `interruptedBy`, `EARLY_EOF_SECONDS = 30`) for a Stremio stream. */
class StreamEndTest {
    @Test
    fun aStreamThatNeverLoadedIsBroken() {
        // No duration yet: mpv's END_FILE is never a real end under keep-open, so the link is reported dead
        assertEquals(true, streamBroke(endFile = true, position = 0.0, duration = 0.0))
    }

    @Test
    fun anEndOfFileIsBrokenOnlyFurtherThan30SecondsFromTheDuration() {
        val cases = listOf(
            // position, duration, broken
            Triple(6_000.0, 6_000.0, false),
            Triple(5_970.0, 6_000.0, false),
            Triple(5_969.0, 6_000.0, true),
            Triple(1_200.0, 6_000.0, true),
            // Unknown duration: nothing tells it apart from a real end
            Triple(1_200.0, 0.0, false),
        )
        for ((position, duration, broken) in cases) {
            assertEquals("$position/$duration", broken, streamBroke(endFile = false, position, duration))
        }
    }
}
