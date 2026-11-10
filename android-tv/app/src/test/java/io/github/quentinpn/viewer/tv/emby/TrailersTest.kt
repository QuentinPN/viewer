package io.github.quentinpn.viewer.tv.emby

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TrailersTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun item(local: Int? = null, vararg urls: String?) =
        BaseItemDto("m1", localTrailerCount = local, remoteTrailers = urls.map { MediaUrlDto(it, "Trailer") })

    @Test
    fun youtubeAddressesAreKept() {
        assertEquals("https://www.youtube.com/watch?v=abc123", trailerUrl("https://www.youtube.com/watch?v=abc123"))
        assertEquals("https://youtu.be/abc123", trailerUrl("https://youtu.be/abc123"))
        assertEquals("https://m.youtube.com/watch?v=abc", trailerUrl("https://m.youtube.com/watch?v=abc"))
        assertEquals("https://youtube.com/watch?v=abc", trailerUrl(" https://YouTube.com/watch?v=abc "))
    }

    @Test
    fun otherAddressesAreRefused() {
        listOf(
            null,
            "",
            "http://www.youtube.com/watch?v=abc",
            "https://evil.example/watch?v=abc",
            "https://www.youtube.com.evil.example/watch",
            "https://evilyoutube.com/watch",
            "https://music.youtube.com/watch?v=abc",
            "https://user:pw@www.youtube.com/watch?v=abc",
            "https://www.youtube.com@evil.example/",
            "https://www.youtube.com:8443/watch?v=abc",
            "https://www.youtube.com/watch?v=a b",
            "https://www.youtube.com/watch?v=a\nb",
            "javascript:alert(1)",
            "intent://www.youtube.com/#Intent;scheme=https;end",
            "file:///sdcard/x.mp4",
            "plugin://plugin.video.youtube/?action=play_video&videoid=abc",
        ).forEach { assertNull("refused: $it", trailerUrl(it)) }
    }

    @Test
    fun localComesBeforeRemote() {
        val choices = trailerChoices(item(2, "https://www.youtube.com/watch?v=abc"))
        assertEquals(listOf(TrailerChoice.Local, TrailerChoice.Remote("https://www.youtube.com/watch?v=abc")), choices)
    }

    @Test
    fun firstValidRemoteIsTaken() {
        val choices = trailerChoices(item(0, null, "https://evil.example/x", "https://youtu.be/ok"))
        assertEquals(listOf(TrailerChoice.Remote("https://youtu.be/ok")), choices)
    }

    @Test
    fun noTrailerNoButton() {
        assertTrue(trailerChoices(item()).isEmpty())
        assertTrue(trailerChoices(item(0, "https://evil.example/x")).isEmpty())
    }

    @Test
    fun localTrailerPlaysByItsOwnId() = runBlocking {
        val action = trailerAction(listOf(TrailerChoice.Local, TrailerChoice.Remote("https://youtu.be/x"))) {
            listOf(BaseItemDto("bad/../id"), BaseItemDto("t1"))
        }
        assertEquals(TrailerAction.Play("t1"), action)
    }

    @Test
    fun emptyOrFailedListFallsBackToRemote() = runBlocking {
        val choices = listOf(TrailerChoice.Local, TrailerChoice.Remote("https://youtu.be/x"))
        assertEquals(TrailerAction.Open("https://youtu.be/x"), trailerAction(choices) { emptyList() })
        assertEquals(TrailerAction.Open("https://youtu.be/x"), trailerAction(choices) { throw EmbyException(EmbyError.NotFound) })
        assertNull(trailerAction(listOf(TrailerChoice.Local)) { emptyList() })
    }

    @Test
    fun expiredSessionIsThrownAgain() {
        try {
            runBlocking { trailerAction(listOf(TrailerChoice.Local, TrailerChoice.Remote("https://youtu.be/x"))) { throw EmbyException(EmbyError.SessionExpired) } }
            fail("expected SessionExpired")
        } catch (e: EmbyException) {
            assertEquals(EmbyError.SessionExpired, e.error)
        }
    }

    @Test
    fun itemReadsBothTrailerFields() {
        val item = json.decodeFromString(
            BaseItemDto.serializer(),
            """{"Id":"m1","LocalTrailerCount":1,"RemoteTrailers":[{"Url":"https://www.youtube.com/watch?v=abc","Name":"Bande-annonce"},{"Name":"sans adresse"}]}""",
        )
        assertEquals(1, item.localTrailerCount)
        assertEquals(2, item.remoteTrailers.size)
        assertNull(item.remoteTrailers[1].url)
        val bare = json.decodeFromString(BaseItemDto.serializer(), """{"Id":"m2"}""")
        assertNull(bare.localTrailerCount)
        assertTrue(bare.remoteTrailers.isEmpty())
    }
}
