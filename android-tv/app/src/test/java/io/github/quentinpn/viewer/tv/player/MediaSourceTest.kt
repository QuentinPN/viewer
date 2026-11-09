package io.github.quentinpn.viewer.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaSourceTest {
    @Test
    fun acceptsAbsolutePathsAndHttpUrls() {
        assertEquals(MediaSource.LocalFile("/sdcard/Movies/a.mkv"), MediaSource.parse(" /sdcard/Movies/a.mkv "))
        assertEquals(MediaSource.Http("http://192.168.1.10:8096/v.mkv"), MediaSource.parse("http://192.168.1.10:8096/v.mkv"))
        assertEquals(MediaSource.Http("HTTPS://emby.example/v"), MediaSource.parse("HTTPS://emby.example/v"))
    }

    @Test
    fun refusesOtherProtocolsAndMalformedInput() {
        for (raw in listOf("", "a.mkv", "file:///sdcard/a.mkv", "av://lavfi:testsrc", "edl://x", "ftp://h/x", "http:///nohost", "/a\nb")) {
            assertNull(raw, MediaSource.parse(raw))
        }
    }

    @Test
    fun redactsTokens() {
        assertEquals("GET /v?api_key=*** ok", redactSecrets("GET /v?api_key=abc123 ok"))
        assertEquals("X-Emby-Token: ***", redactSecrets("X-Emby-Token: secret"))
        assertEquals("X-Emby-Authorization: ***", redactSecrets("X-Emby-Authorization: MediaBrowser Token=\"t\""))
    }
}
