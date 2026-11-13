package io.github.quentinpn.viewer.tv.ui

import io.github.quentinpn.viewer.tv.emby.MediaSourceDto
import io.github.quentinpn.viewer.tv.emby.SourceStreamDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TechInfoTest {
    private val uhd = MediaSourceDto(
        id = "a",
        container = "mkv",
        size = 30_000_000_000,
        bitrate = 45_000_000,
        mediaStreams = listOf(
            SourceStreamDto(type = "Video", codec = "hevc", width = 3840, height = 1608, bitDepth = 10, videoRange = "HDR 10"),
            SourceStreamDto(type = "Audio", codec = "truehd", language = "fre", channels = 8, isDefault = true),
            SourceStreamDto(type = "Audio", codec = "ac3", language = "eng", channels = 6, title = "Commentaires"),
            SourceStreamDto(type = "Subtitle", codec = "subrip", language = "fre", title = "Forced", isForced = true, isExternal = true),
        ),
    )
    private val hd = MediaSourceDto(id = "b")

    @Test
    fun `rows describe video, file, audio and subtitles`() {
        assertEquals(
            listOf(
                TechRow("Vidéo", listOf("4K · HEVC 10 bits · HDR 10")),
                TechRow("Fichier", listOf("MKV · 27,9 Go · 45 Mb/s")),
                TechRow("Audio", listOf("Français · Dolby TrueHD 7.1 · par défaut", "Anglais · Dolby Digital 5.1 · Commentaires")),
                TechRow("Sous-titres", listOf("Français · SRT · forcés · externe")),
            ),
            techRows(uhd),
        )
    }

    @Test
    fun `an empty source says so`() {
        assertEquals(
            listOf(TechRow("Fichier", listOf("Inconnu")), TechRow("Audio", listOf("Aucune piste")), TechRow("Sous-titres", listOf("Aucun"))),
            techRows(hd),
        )
    }

    @Test
    fun `the chosen version is shown, else the first`() {
        assertEquals("b", shownSource(listOf(uhd, hd), "b")?.id)
        assertEquals("a", shownSource(listOf(uhd, hd), "zz")?.id)
        assertEquals("a", shownSource(listOf(uhd, hd), null)?.id)
        assertNull(shownSource(emptyList(), null))
    }
}
