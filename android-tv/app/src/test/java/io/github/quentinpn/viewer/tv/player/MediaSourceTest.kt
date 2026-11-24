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

    @Test
    fun streamLinesLoseEveryAddressAndHeaderValueButKeepTheRest() {
        val key = "Bearer d3br1d"
        val lines = listOf(
            "[cplayer] Playing: https://debrid.example/dl/KEY123/film.mkv",
            "[ffmpeg] http: Status 302: Redirecting to http://cdn.example/x?token=abc",
            "[stream] Failed to open HTTPS://debrid.example/dl/KEY123.",
            "[ffmpeg] header Authorization: $key unused",
        )
        val redacted = lines.map { redactStreamSecrets(it, listOf(key, "")) }
        for (line in redacted) {
            for (secret in listOf("KEY123", "cdn.example", "token=abc", "d3br1d")) assertEquals(line, false, secret in line)
        }
        // What is not a secret stays readable for diagnostics
        assertEquals("[ffmpeg] http: Status 302: Redirecting to ***", redacted[1])
        assertEquals("[ffmpeg] error 403 Forbidden", redactStreamSecrets("[ffmpeg] error 403 Forbidden", listOf(key)))
    }
}
