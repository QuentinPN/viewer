package io.github.quentinpn.viewer.tv.ui

import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import io.github.quentinpn.viewer.tv.emby.MediaSourceDto
import io.github.quentinpn.viewer.tv.emby.SourceStreamDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Same cases as src/lib/detail/versions.test.ts on Windows.
class VersionsTest {
    private val uhd = MediaSourceDto(
        id = "mediasource_1",
        name = "2160p",
        size = 30_000_000_000,
        bitrate = 60_000_000,
        mediaStreams = listOf(
            SourceStreamDto(type = "Audio", codec = "truehd"),
            SourceStreamDto(type = "Video", codec = "hevc", width = 3840, height = 1608, bitDepth = 10, videoRange = "HDR 10"),
        ),
    )
    private val hd = MediaSourceDto(
        id = "mediasource_2",
        bitrate = 8_400_000,
        mediaStreams = listOf(SourceStreamDto(type = "Video", codec = "h264", width = 1920, height = 800, bitDepth = 8, videoRange = "SDR")),
    )

    @Test
    fun labelsGiveNameResolutionCodecRangeAndSize() {
        assertEquals("2160p — 4K · HEVC 10 bits · HDR 10 · 27,9 Go", versionLabel(uhd, 0))
        // No name: its place; no size: the bitrate; SDR is not mentioned
        assertEquals("Version 2 — 1080p · H.264 · 8,4 Mb/s", versionLabel(hd, 1))
        assertEquals("Version 3", versionLabel(MediaSourceDto(id = "x", name = "  "), 2))
    }

    @Test
    fun dolbyVisionAndSmallValues() {
        val dv = MediaSourceDto(
            bitrate = 900_000,
            mediaStreams = listOf(
                SourceStreamDto(
                    type = "Video",
                    codec = "dvhe",
                    width = 1280,
                    videoRange = "DolbyVision",
                    extendedVideoSubTypeDescription = "Profile 8.1 (HDR10 compatible)",
                ),
            ),
        )
        assertEquals("720p · DVHE · Dolby Vision Profile 8.1 (HDR10 compatible) · 900 kb/s", versionDetails(dv))
        val plain = SourceStreamDto(type = "Video", height = 576, videoRange = "DolbyVision", extendedVideoSubTypeDescription = "None")
        assertEquals("576p · Dolby Vision", versionDetails(MediaSourceDto(mediaStreams = listOf(plain))))
        assertEquals("3 Go", formatSize(3L * 1024 * 1024 * 1024))
        assertNull(formatSize(0))
        assertNull(formatBitrate(null))
    }

    @Test
    fun aChoiceOnlyFromTwoVersionsWithAUsableId() {
        assertEquals(emptyList<VersionChoice>(), versionChoices(emptyList()))
        assertEquals(emptyList<VersionChoice>(), versionChoices(listOf(uhd)))
        // A version without an id, or with one this client would not send, cannot be chosen
        assertEquals(emptyList<VersionChoice>(), versionChoices(listOf(uhd, hd.copy(id = null), hd.copy(id = "a/b"))))
        val choices = versionChoices(listOf(uhd, MediaSourceDto(id = null), hd))
        assertEquals(listOf("mediasource_1", "mediasource_2"), choices.map { it.id })
        // Numbered by place in the server's list
        assertEquals("Version 3 — 1080p · H.264 · 8,4 Mb/s", choices[1].label)
    }

    @Test
    fun theRememberedVersionElseTheServersFirst() {
        val choices = versionChoices(listOf(uhd, hd))
        assertEquals("mediasource_1", rememberedVersion(null, choices))
        assertEquals("mediasource_2", rememberedVersion("mediasource_2", choices))
        // A version the server no longer offers
        assertEquals("mediasource_1", rememberedVersion("mediasource_9", choices))
        assertNull(rememberedVersion("mediasource_2", emptyList()))
    }

    @Test
    fun okGoesToTheNextVersionThenBackToTheFirst() {
        val choices = versionChoices(listOf(uhd, hd))
        assertEquals("mediasource_2", nextVersion("mediasource_1", choices))
        assertEquals("mediasource_1", nextVersion("mediasource_2", choices))
        assertNull(nextVersion(null, emptyList()))
    }

    @Test
    fun versionsAreReadFromTheItem() {
        val json = Json { ignoreUnknownKeys = true }
        val item = json.decodeFromString(
            BaseItemDto.serializer(),
            """{"Id":"30500","Type":"Movie","MediaSources":[
            {"Id":"mediasource_30500","Name":"1080p","Path":"x","Size":2000,"MediaStreams":[{"Type":"Video","Codec":"h264","Width":1920}]},
            {"Id":"30501","MediaStreams":[{"Index":0,"Type":"Video","Codec":"hevc","Width":3840,"BitDepth":10,"VideoRange":"SDR"}]}]}""",
        )
        assertEquals(
            listOf(VersionChoice("mediasource_30500", "1080p — 1080p · H.264 · 2 Ko"), VersionChoice("30501", "Version 2 — 4K · HEVC 10 bits")),
            versionChoices(item.mediaSources),
        )
    }
}
