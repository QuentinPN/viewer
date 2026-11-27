package io.github.quentinpn.viewer.tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class LoadOptionsTest {
    private fun redirects(external: Boolean, headers: Boolean) =
        loadOptions(external, quiet = true, headers = headers).toMap()["stream-lavf-o"]?.substringAfter("max_redirects=")

    @Test
    fun onlyAStremioStreamWithoutHeadersFollowsRedirects() {
        // DECISIONS.md: a header never follows a redirect to another host; a bare debrid link answers 302
        assertEquals("5", redirects(external = true, headers = false))
        assertEquals("0", redirects(external = true, headers = true))
        // Emby's token header: a redirect would carry it to another host
        assertEquals("0", redirects(external = false, headers = true))
        assertEquals("0", redirects(external = false, headers = false))
    }

    @Test
    fun everyLoadReconnects() {
        // As desktop's streamLavfOptions (mpvprops.go), Emby included
        for (external in listOf(true, false)) {
            assertEquals(
                "reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1,reconnect_delay_max=7,max_redirects=0",
                loadOptions(external, quiet = true, headers = true).toMap()["stream-lavf-o"],
            )
        }
    }

    @Test
    fun mpvLogsNothingWhereLibplayersCopyReachesLogcat() {
        assertEquals("all=no", loadOptions(external = true, quiet = false, headers = false).toMap()["msg-level"])
        // Quiet: libplayer's copy is dropped, the redacted lines stay for diagnostics
        assertEquals(null, loadOptions(external = true, quiet = true, headers = false).toMap()["msg-level"])
        // Emby's lines carry no secret once redacted: they stay
        assertEquals(null, loadOptions(external = false, quiet = false, headers = true).toMap()["msg-level"])
    }
}
