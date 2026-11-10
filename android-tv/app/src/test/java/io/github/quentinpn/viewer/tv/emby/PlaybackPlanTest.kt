package io.github.quentinpn.viewer.tv.emby

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun theChosenVersionIsPlayedAndNeverReplaced() {
        // Two versions in one answer, as a server that ignores `MediaSourceId` would send (ADR 0036)
        val answer = response(
            """{"MediaSources":[
            {"Id":"m1","SupportsDirectPlay":true,"DirectStreamUrl":"/videos/i1/original.mkv?MediaSourceId=m1"},
            {"Id":"m2","SupportsDirectPlay":true,"DirectStreamUrl":"/videos/i1/original.mkv?MediaSourceId=m2"}]}""",
        )
        assertEquals("m1", plan(server, answer).mediaSourceId)
        val second = plan(server, answer, "m2")
        assertEquals("m2", second.mediaSourceId)
        assertTrue(second.url.endsWith("MediaSourceId=m2"))
        expectError(EmbyError.NoCompatibleStream::class.java) { plan(server, answer, "m3") }
    }

    @Test
    fun sourceIds() {
        assertTrue(isSourceId("mediasource_30207"))
        assertTrue(isSourceId("0123abcd-ef"))
        for (id in listOf("", "a/b", "a b", "é", "a".repeat(129))) assertFalse(id, isSourceId(id))
    }

    @Test
    fun transcodeSubtitlesAreNamedFromTheServerStreams() {
        // Overgeared S1E6 at 1,5 Mbit/s (ADR 0084): three French ASS files
        val plan = plan(
            server,
            response(
                """{"MediaSources":[{"Id":"m1","SupportsTranscoding":true,"TranscodingUrl":"/videos/i1/master.m3u8",
                "DefaultAudioStreamIndex":2,"MediaStreams":[
                  {"Index":1,"Type":"Audio","Language":"fre"},
                  {"Index":2,"Type":"Audio","Language":"jpn","Title":"VO"},
                  {"Index":3,"Type":"Subtitle","Language":"fre","Title":"Forced","IsForced":true,"DeliveryMethod":"External","DeliveryUrl":"/videos/i1/m1/Subtitles/3/0/Stream.ass"},
                  {"Index":4,"Type":"Subtitle","Language":"fre","DeliveryMethod":"External","DeliveryUrl":"/videos/i1/m1/Subtitles/4/0/Stream.ass"},
                  {"Index":5,"Type":"Subtitle","Language":"fre","Title":"CC","IsHearingImpaired":true,"DeliveryMethod":"External","DeliveryUrl":"/videos/i1/m1/Subtitles/5/0/Stream.ass"},
                  {"Index":9,"Type":"Audio","Language":"eng","IsExternal":true}]}]}""",
            ),
        )
        assertEquals(listOf("Forced" to "fre", null to "fre", "CC" to "fre"), plan.subtitles.map { it.title to it.language })
        // The source's own audio streams, an external file left out
        assertEquals(listOf(SourceAudio(1, "fre", null), SourceAudio(2, "jpn", "VO")), plan.audio)
        assertEquals(2, plan.serverTracks.audio)
    }

    @Test
    fun subtitleTitleSaysWhatTheFlagsSay() {
        fun title(title: String?, forced: Boolean = false, hearing: Boolean = false) =
            subtitleTitle(MediaStreamDto(3, "Subtitle", title = title, isForced = forced, isHearingImpaired = hearing))
        assertEquals("Forced", title("Forced", forced = true))
        assertEquals("FR forcés", title("FR forcés", forced = true))
        assertEquals("Forcés", title(null, forced = true))
        assertEquals("Signs, Forcés", title("Signs", forced = true))
        assertEquals("CC", title("CC", hearing = true))
        assertEquals("SDH", title(" ", hearing = true))
        assertEquals("Forcés, SDH", title(null, forced = true, hearing = true))
        assertEquals("Complets", title("Complets"))
        assertEquals(null, title(null))
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
