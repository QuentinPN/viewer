package io.github.quentinpn.viewer.tv.player

import io.github.quentinpn.viewer.tv.emby.PlayMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackInfoTest {
    @Test
    fun methodLabelsAsOnWindows() {
        assertEquals("Lecture directe", methodLabel(PlayMethod.DirectPlay, emptyList()))
        assertEquals("Flux direct", methodLabel(PlayMethod.DirectStream, listOf("débit trop élevé")))
        assertEquals("Transcodage · raison non précisée", methodLabel(PlayMethod.Transcode, emptyList()))
        assertEquals(
            "Transcodage · débit trop élevé, codec audio non pris en charge",
            methodLabel(PlayMethod.Transcode, listOf("débit trop élevé", "codec audio non pris en charge")),
        )
    }

    @Test
    fun transcodeReasonsComeFromTheUrlParameterOnly() {
        val url = "http://192.168.1.14:8096/emby/videos/1/master.m3u8?DeviceId=d&TranscodeReasons=VideoCodecNotSupported,ContainerBitrateExceedsLimit,VideoBitrateNotSupported,Nouveau"
        assertEquals(listOf("codec vidéo non pris en charge", "débit trop élevé", "autre raison"), transcodeReasons(url))
        assertEquals(listOf("sous-titres non pris en charge"), transcodeReasons("http://h/emby/videos/1/a.m3u8?transcodereasons=SubtitleCodecNotSupported"))
        assertEquals(emptyList<String>(), transcodeReasons("http://h/emby/videos/1/a.m3u8?DeviceId=d"))
        assertEquals(emptyList<String>(), transcodeReasons("pas une adresse"))
    }

    @Test
    fun resolutionWithItsTier() {
        assertEquals("3840 × 2160 (4K)", resolution(3840, 2160))
        assertEquals("3840 × 1600 (4K)", resolution(3840, 1600))
        assertEquals("1920 × 800 (1080p)", resolution(1920, 800))
        assertEquals("1280 × 720 (720p)", resolution(1280, 720))
        assertEquals("720 × 576 (SD)", resolution(720, 576))
        assertNull(resolution(1920, null))
        assertNull(resolution(0, 1080))
    }

    @Test
    fun frameRateInFrenchWithoutTrailingZeros() {
        assertEquals("23,976 i/s", frameRate(23.976024))
        assertEquals("25 i/s", frameRate(25.0))
        assertEquals("59,94 i/s", frameRate(59.940059))
        assertEquals("24 i/s", frameRate(23.99996))
    }

    @Test
    fun bitrateInKbOrMb() {
        assertEquals("640 kb/s", bitrate(640_000.0))
        assertEquals("1 Mb/s", bitrate(1_000_000.0))
        assertEquals("12,3 Mb/s", bitrate(12_345_678.0))
        assertEquals("120 Mb/s", bitrate(119_600_000.0))
        assertEquals("12 Mb/s", bitrateOf(11_500_000.0, 500_000.0))
        assertEquals("640 kb/s", bitrateOf(null, 640_000.0))
        assertNull(bitrateOf(null, null))
    }

    @Test
    fun channelLayouts() {
        assertEquals("mono", channelLayout(1))
        assertEquals("stéréo", channelLayout(2))
        assertEquals("5.1", channelLayout(6))
        assertEquals("7.1", channelLayout(8))
        assertEquals("3 canaux", channelLayout(3))
    }

    @Test
    fun colourAndBitDepth() {
        assertEquals(10, bitDepth("yuv420p10le"))
        assertEquals(10, bitDepth("p010"))
        assertEquals(12, bitDepth("yuv444p12"))
        assertEquals(8, bitDepth("nv12"))
        assertEquals(8, bitDepth("yuv420p"))
        assertNull(bitDepth("mediacodec"))
        assertNull(bitDepth(null))
        assertEquals("HDR (PQ) · BT.2020 · 10 bits", colour("pq", "bt.2020", 10))
        assertEquals("HDR (HLG)", colour("hlg", null, null))
        assertEquals("SDR · BT.709 · 8 bits", colour("bt.1886", "bt.709", 8))
        assertNull(colour(null, "auto", null))
    }

    @Test
    fun otherValues() {
        assertEquals("Logiciel", decoding("no"))
        assertEquals("Matériel (mediacodec)", decoding("mediacodec"))
        assertEquals("Matroska (MKV)", containerName("matroska,webm"))
        assertEquals("MP4", containerName("mov,mp4,m4a,3gp,3g2,mj2"))
        assertEquals("HLS", containerName("hls"))
        assertEquals("FLV", containerName("flv"))
        assertEquals("H.265 (HEVC)", videoCodecName("hevc"))
        assertEquals("48 kHz", sampleRate(48_000))
        assertEquals("44,1 kHz", sampleRate(44_100))
        assertEquals("décodeur 0 · affichage 3", droppedFrames(0, 3))
        assertNull(droppedFrames(null, null))
        assertEquals("4,2 s d'avance", bufferAhead(4.19))
        assertEquals("38 s d'avance", bufferAhead(37.8))
    }

    @Test
    fun rowsHoldOnlyWhatMpvGives() {
        val props = mapOf(
            "file-format" to "matroska,webm",
            "video-format" to "hevc",
            "video-params/w" to "3840",
            "video-params/h" to "2160",
            "container-fps" to "23.976024",
            "video-params/gamma" to "pq",
            "video-params/primaries" to "bt.2020",
            "video-params/pixelformat" to "mediacodec",
            "hwdec-current" to "mediacodec",
            "audio-codec-name" to "eac3",
            "audio-params/channel-count" to "6",
            "audio-params/samplerate" to "48000",
            "current-ao" to "audiotrack",
            "audio-out-params/channel-count" to "2",
            "video-bitrate" to "18200000.000000",
            "audio-bitrate" to "640000.000000",
            "decoder-frame-drop-count" to "0",
            "frame-drop-count" to "2",
            "demuxer-cache-duration" to "12.4",
        )
        assertTrue(props.keys.all { it in INFO_PROPERTIES })
        val tracks = listOf(
            Track(1, TrackType.Audio, "Français · E-AC3 5.1", selected = true),
            Track(1, TrackType.Subtitle, "Français · SRT", selected = false),
        )
        val rows = playbackInfo(PlaybackFacts(PlayMethod.DirectPlay, cap = "Maximale · réseau local"), tracks) { props[it] }
        assertEquals(
            listOf(
                InfoRow("Lecture", "Lecture directe"),
                InfoRow("Débit maximal", "Maximale · réseau local"),
                InfoRow("Conteneur", "Matroska (MKV)"),
                InfoRow("Vidéo", "H.265 (HEVC)"),
                InfoRow("Résolution", "3840 × 2160 (4K)"),
                InfoRow("Images par seconde", "23,976 i/s"),
                InfoRow("Couleur", "HDR (PQ) · BT.2020"),
                InfoRow("Décodage", "Matériel (mediacodec)"),
                InfoRow("Piste audio", "Français · E-AC3 5.1"),
                InfoRow("Format audio", "E-AC3 · 5.1 · 48 kHz"),
                InfoRow("Sortie audio", "AudioTrack · stéréo"),
                InfoRow("Sous-titres", "Désactivés"),
                InfoRow("Débit mesuré", "18,8 Mb/s"),
                InfoRow("Images perdues", "décodeur 0 · affichage 2"),
                InfoRow("Mémoire tampon", "12 s d'avance"),
            ),
            rows,
        )
    }

    @Test
    fun beforeMpvKnowsAnythingOnlyThePlanShows() {
        val rows = playbackInfo(PlaybackFacts(PlayMethod.Transcode, listOf("débit trop élevé")), emptyList()) { null }
        assertEquals(listOf(InfoRow("Lecture", "Transcodage · débit trop élevé")), rows)
    }
}
