package io.github.quentinpn.viewer.tv.emby

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PlaybackPlanTest {
    private val server = ServerUrl.parse("http://192.168.1.10:8096")
    private val json = Json { ignoreUnknownKeys = true }

    private fun response(text: String) = json.decodeFromString(PlaybackInfoResponse.serializer(), text)

    private fun expectError(expected: Class<out EmbyError>, block: () -> Unit) {
        try {
            block()
            fail("expected ${expected.simpleName}")
        } catch (e: EmbyException) {
            assertEquals(expected, e.error::class.java)
        }
    }

    @Test
    fun directPlayWinsAndTheTokenIsStripped() {
        val plan = plan(
            server,
            response(
                """{"PlaySessionId":"ps","MediaSources":[{"Id":"m1","SupportsDirectPlay":true,"SupportsDirectStream":true,
                "DirectStreamUrl":"/videos/i1/stream.mkv?Static=true&MediaSourceId=m1&api_key=secret","RunTimeTicks":100,
                "RequiredHttpHeaders":{"X-Custom":"ok","Authorization":"x","Cookie":"c","Bad":"a,b"}}]}""",
            ),
        )
        assertEquals(PlayMethod.DirectPlay, plan.method)
        assertEquals("http://192.168.1.10:8096/emby/videos/i1/stream.mkv?Static=true&MediaSourceId=m1", plan.url)
        assertEquals(mapOf("X-Custom" to "ok"), plan.headers)
        assertEquals("ps", plan.playSessionId)
        assertEquals(100L, plan.runTimeTicks)
    }

    @Test
    fun flagsDecideNotTheUrlPresent() {
        val plan = plan(
            server,
            response(
                """{"MediaSources":[{"Id":"m1","SupportsTranscoding":true,
                "DirectStreamUrl":"/videos/i1/master.m3u8?a=1","TranscodingUrl":"/videos/i1/master.m3u8?ApiKey=s&b=2",
                "DefaultSubtitleStreamIndex":3,"MediaStreams":[
                  {"Index":1,"Type":"Audio"},
                  {"Index":3,"Type":"Subtitle","Language":"fre","DeliveryMethod":"External","DeliveryUrl":"/videos/i1/m1/Subtitles/3/0/Stream.ass?api_key=s"},
                  {"Index":4,"Type":"Subtitle","DeliveryMethod":"Encode"}]}]}""",
            ),
        )
        assertEquals(PlayMethod.Transcode, plan.method)
        assertEquals("http://192.168.1.10:8096/emby/videos/i1/master.m3u8?b=2", plan.url)
        val sub = plan.subtitles.single()
        assertEquals("http://192.168.1.10:8096/emby/videos/i1/m1/Subtitles/3/0/Stream.ass", sub.url)
        assertTrue(sub.selected)
    }

    @Test
    fun serverRefusals() {
        expectError(EmbyError.PlaybackRefused::class.java) { plan(server, response("""{"ErrorCode":"NotAllowed"}""")) }
        expectError(EmbyError.PlaybackRefused::class.java) { plan(server, response("""{"ErrorCode":"RateLimitExceeded"}""")) }
        expectError(EmbyError.NoCompatibleStream::class.java) { plan(server, response("""{"ErrorCode":"NoCompatibleStream"}""")) }
        expectError(EmbyError.NoCompatibleStream::class.java) { plan(server, response("""{"MediaSources":[]}""")) }
        expectError(EmbyError.NoCompatibleStream::class.java) {
            plan(server, response("""{"MediaSources":[{"Id":"m1","DirectStreamUrl":"/videos/x"}]}"""))
        }
    }

    @Test
    fun streamUrlStaysUnderVideosOnTheServer() {
        for (raw in listOf(
            "http://evil.example/videos/x", "//evil.example/videos/x", "/System/Info", "/videos/../System/Info",
            "/videos/%2e%2e/System/Info", "videos/x", "/videos\\..\\x",
        )) {
            expectError(EmbyError.InvalidResponse::class.java) { streamUrl(server, raw) }
        }
        assertEquals("http://192.168.1.10:8096/emby/Videos/i1/stream", streamUrl(server, "/Videos/i1/stream?X-Emby-Token=t"))
    }

    @Test
    fun reverseProxyPrefixIsKept() {
        val proxied = ServerUrl.parse("https://media.example.com/jellyroll/web/index.html")
        assertEquals("https://media.example.com/jellyroll/emby/videos/i1/stream", streamUrl(proxied, "/videos/i1/stream"))
    }
}
