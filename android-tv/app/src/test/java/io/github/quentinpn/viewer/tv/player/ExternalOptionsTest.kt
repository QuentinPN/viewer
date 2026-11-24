package io.github.quentinpn.viewer.tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalOptionsTest {
    @Test
    fun redirectsAreFollowedOnlyWithoutHeaders() {
        // DECISIONS.md: a header never follows a redirect to another host; a bare debrid link answers 302
        assertEquals("reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1,reconnect_delay_max=7,max_redirects=0", externalOptions(quiet = true, headers = true).toMap()["stream-lavf-o"])
        assertEquals("reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1,reconnect_delay_max=7,max_redirects=5", externalOptions(quiet = true, headers = false).toMap()["stream-lavf-o"])
    }

    @Test
    fun mpvLogsNothingWhereLibplayersCopyReachesLogcat() {
        assertEquals("all=no", externalOptions(quiet = false, headers = false).toMap()["msg-level"])
        // Quiet: libplayer's copy is dropped, the redacted lines stay for diagnostics
        assertEquals(null, externalOptions(quiet = true, headers = false).toMap()["msg-level"])
    }
}
