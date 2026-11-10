package io.github.quentinpn.viewer.tv.settings

import io.github.quentinpn.viewer.tv.emby.EmbyError
import io.github.quentinpn.viewer.tv.emby.PublicSystemInfo
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutTest {
    private val hevc = "video/hevc"

    // Shield-like list: hardware HEVC with Main 10 HDR10, a software HEVC, hardware Dolby Vision 5 and 8
    private val shield = listOf(
        DecoderInfo("OMX.Nvidia.h265.decode", true, mapOf(hevc to listOf(0x1, 0x2, 0x1000))),
        DecoderInfo("c2.android.hevc.decoder", false, mapOf(hevc to listOf(0x1))),
        DecoderInfo("OMX.Nvidia.DOVI.decode", true, mapOf(DOLBY_VISION_MIME to listOf(0x20, 0x100))),
        DecoderInfo("c2.android.av1.decoder", false, mapOf("video/av01" to listOf(0x1, 0x2))),
        DecoderInfo("OMX.Nvidia.h264.decode", true, mapOf("video/avc" to listOf(0x1, 0x2, 0x8))),
    )

    @Test
    fun hardwareDecodersComeFirstWithTheirAdvertisedHdr() {
        val row = codecRow(VideoCodec.Hevc, shield)
        assertEquals("HEVC (H.265)", row.label)
        assertEquals("Matériel · 10 bits · HDR10", row.value)
        // The software decoder is not named when a hardware one exists
        assertEquals("OMX.Nvidia.h265.decode", row.detail)
    }

    @Test
    fun softwareOnlyAndMissingCodecsAreSaidSo() {
        val av1 = codecRow(VideoCodec.Av1, shield)
        assertEquals("Logiciel seulement · 10 bits", av1.value)
        assertEquals("c2.android.av1.decoder", av1.detail)
        val vp9 = codecRow(VideoCodec.Vp9, shield)
        assertEquals("Aucun décodeur", vp9.value)
        assertNull(vp9.detail)
        assertEquals("Matériel", codecRow(VideoCodec.H264, shield).value)
    }

    @Test
    fun hdr10PlusAndVp9HdrProfilesAreRead() {
        val decoders = listOf(
            DecoderInfo("a", true, mapOf(hevc to listOf(0x2000))),
            DecoderInfo("b", true, mapOf("video/x-vnd.on2.vp9" to listOf(0x1000, 0x4000))),
            DecoderInfo("c", true, mapOf("video/av01" to listOf(0x1000))),
        )
        assertEquals("Matériel · 10 bits · HDR10+", codecRow(VideoCodec.Hevc, decoders).value)
        assertEquals("Matériel · 10 bits · HDR10 · HDR10+", codecRow(VideoCodec.Vp9, decoders).value)
        assertEquals("Matériel · 10 bits · HDR10", codecRow(VideoCodec.Av1, decoders).value)
        // H.264 High 10 is the only 10-bit AVC profile
        assertEquals("Matériel · 10 bits", codecRow(VideoCodec.H264, listOf(DecoderInfo("d", true, mapOf("video/avc" to listOf(0x10))))).value)
    }

    @Test
    fun severalDecodersAreAllNamed() {
        val decoders = listOf(
            DecoderInfo("x.hevc", true, mapOf(hevc to listOf(0x1))),
            DecoderInfo("y.hevc", true, mapOf(hevc to listOf(0x2))),
        )
        val row = codecRow(VideoCodec.Hevc, decoders)
        assertEquals("Matériel · 10 bits", row.value)
        assertEquals("x.hevc, y.hevc", row.detail)
    }

    @Test
    fun dolbyVisionProfilesAreBitIndexes() {
        assertEquals(listOf(5, 8), dolbyVisionProfiles(listOf(0x20, 0x100)))
        assertEquals(listOf(0, 7, 10), dolbyVisionProfiles(listOf(0x1, 0x80, 0x400)))
        assertEquals(emptyList<Int>(), dolbyVisionProfiles(emptyList()))
        assertEquals("Matériel · profils 5, 8", dolbyVisionRow(shield).value)
        assertEquals("Matériel · profil 8", dolbyVisionRow(listOf(DecoderInfo("d", true, mapOf(DOLBY_VISION_MIME to listOf(0x100))))).value)
        assertEquals("Non annoncé", dolbyVisionRow(shield.filter { DOLBY_VISION_MIME !in it.profiles }).value)
    }

    @Test
    fun rowsKeepTheirOrder() {
        assertEquals(listOf("HEVC (H.265)", "AV1", "VP9", "H.264", "Dolby Vision"), decoderRows(shield).map { it.label })
        assertEquals(List(4) { "Aucun décodeur" } + "Non annoncé", decoderRows(emptyList()).map { it.value })
    }

    @Test
    fun softwareNamesBeforeApi29() {
        assertTrue(isSoftwareName("OMX.google.h264.decoder"))
        assertTrue(isSoftwareName("c2.android.hevc.decoder"))
        assertTrue(isSoftwareName("C2.Google.av1.decoder"))
        assertFalse(isSoftwareName("OMX.Nvidia.h265.decode"))
        assertFalse(isSoftwareName("c2.amlogic.hevc.decoder"))
    }

    @Test
    fun displayHdrTypesInAFixedOrder() {
        assertEquals("HDR10, HDR10+, HLG, Dolby Vision", displayHdrRow(intArrayOf(1, 3, 4, 2)).value)
        assertEquals("HDR10, HLG", displayHdrRow(intArrayOf(3, 2, 6)).value)
        assertEquals("Aucun format HDR", displayHdrRow(intArrayOf()).value)
        assertEquals("Inconnu", displayHdrRow(null).value)
    }

    @Test
    fun appAndDeviceTexts() {
        assertEquals("0.1.0 (1)", appVersion("0.1.0", 1))
        assertEquals("? (0)", appVersion(null, 0))
        assertEquals("Débogage", buildType(true))
        assertEquals("Publication", buildType(false))
        assertEquals("NVIDIA SHIELD Android TV", deviceName("NVIDIA", "SHIELD Android TV"))
        assertEquals("Google Chromecast", deviceName("Google", "Chromecast"))
        assertEquals("Google Pixel 8", deviceName("Google", "Google Pixel 8"))
        assertEquals("NVIDIA", deviceName("NVIDIA", " "))
        assertEquals("Inconnu", deviceName(null, null))
        assertEquals("Android 11 (API 30)", androidVersion("11", 30))
        assertEquals("Android ? (API 28)", androidVersion("", 28))
    }

    @Test
    fun serverAddressKeepsSchemeHostAndPortOnly() {
        assertEquals("http://192.168.1.14:8096", serverAddress("http://192.168.1.14:8096/emby".toHttpUrl()))
        assertEquals("https://media.example.org", serverAddress("https://media.example.org/emby?api_key=secret".toHttpUrl()))
        assertEquals("http://[fd00::1]:8096", serverAddress("http://[fd00::1]:8096/emby".toHttpUrl()))
    }

    @Test
    fun serverRowsForEachState() {
        val address = "http://192.168.1.14:8096"
        assertEquals(
            listOf(InfoRow("Serveur", "Chargement…"), InfoRow("Adresse", address)),
            serverRows(ServerAbout.Loading, address),
        )
        assertEquals(
            listOf(InfoRow("Serveur", "Salon"), InfoRow("Version d'Emby", "4.10.1.0"), InfoRow("Adresse", address)),
            serverRows(ServerAbout.Loaded(PublicSystemInfo("Salon", "4.10.1.0", "id")), address),
        )
        assertEquals(
            listOf(InfoRow("Serveur", "Serveur injoignable"), InfoRow("Adresse", address)),
            serverRows(ServerAbout.Failed(EmbyError.Unreachable), address),
        )
        assertEquals("Réponse inattendue du serveur", serverError(EmbyError.InvalidResponse("Unexpected JSON token at offset 3")))
        assertEquals("Erreur du serveur (HTTP 502)", serverError(EmbyError.Server(502)))
    }
}
