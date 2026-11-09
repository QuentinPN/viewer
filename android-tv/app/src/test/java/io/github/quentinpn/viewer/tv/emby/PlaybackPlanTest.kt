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
        // A transcode only shows the server's subtitle pick when it comes as a file
        assertEquals(ServerTracks(audio = null, subtitle = 3), plan.serverTracks)
    }

    /** A film with the account's picks given as JSON values. */
    private fun directPlay(audio: String, subtitle: String) = plan(
        server,
        response(
            """{"MediaSources":[{"Id":"m1","SupportsDirectPlay":true,"DirectStreamUrl":"/videos/i1/stream.mkv",
            "DefaultAudioStreamIndex":$audio,"DefaultSubtitleStreamIndex":$subtitle,"MediaStreams":[
              {"Index":0,"Type":"Video"},
              {"Index":1,"Type":"Audio","Language":"fre"},
              {"Index":2,"Type":"Audio","Language":"eng"},
              {"Index":4,"Type":"Subtitle","Language":"fre"},
              {"Index":9,"Type":"Subtitle","Language":"spa","IsExternal":true}]}]}""",
        ),
    ).serverTracks

    @Test
    fun theServerPicksAreKeptWhenThisPlaybackCanShowThem() {
        assertEquals(ServerTracks(audio = 1, subtitle = 4), directPlay("1", "4"))
        // -1 (none), absent, or an index of no such stream: mpv decides, as on Windows
        assertEquals(ServerTracks(audio = 2, subtitle = null), directPlay("2", "-1"))
        assertEquals(ServerTracks(audio = null, subtitle = null), directPlay("null", "null"))
        assertEquals(ServerTracks(audio = null, subtitle = null), directPlay("4", "2"))
        // The server's external file is not loaded when playing the file
        assertEquals(ServerTracks(audio = 1, subtitle = null), directPlay("1", "9"))
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
