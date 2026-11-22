package io.github.quentinpn.viewer.tv.settings

import io.github.quentinpn.viewer.tv.emby.EmbyError
import io.github.quentinpn.viewer.tv.emby.PublicSystemInfo
import okhttp3.HttpUrl

/**
 * « À propos » of the settings: pure formatting of what the app, the device,
 * the server and the decoders say about themselves. Android reads them in `DeviceReport`.
 */
data class InfoRow(val label: String, val value: String, val detail: String? = null)

/** One decoder as `MediaCodecList` lists it: its MIME types with their advertised profiles. */
data class DecoderInfo(val name: String, val hardware: Boolean, val profiles: Map<String, List<Int>>)

// Values of MediaCodecInfo.CodecProfileLevel, kept here: the AV1 and VP9 HDR10+ ones are
// API 29 (minSdk is 28), and the tests run on the JVM.
private const val AVC_HIGH10 = 0x10
private const val HEVC_MAIN10 = 0x2
private const val HEVC_MAIN10_HDR10 = 0x1000
private const val HEVC_MAIN10_HDR10_PLUS = 0x2000
private const val VP9_PROFILE2 = 0x4
private const val VP9_PROFILE3 = 0x8
private const val VP9_PROFILE2_HDR = 0x1000
private const val VP9_PROFILE3_HDR = 0x2000
private const val VP9_PROFILE2_HDR10_PLUS = 0x4000
private const val VP9_PROFILE3_HDR10_PLUS = 0x8000
private const val AV1_MAIN10 = 0x2
private const val AV1_MAIN10_HDR10 = 0x1000
private const val AV1_MAIN10_HDR10_PLUS = 0x2000

/** Dolby Vision decoders are listed under their own MIME type. */
const val DOLBY_VISION_MIME = "video/dolby-vision"

/** The video codecs the « Lecture » rows describe, with the profiles that mean 10 bits, HDR10 and HDR10+. */
enum class VideoCodec(
    val label: String,
    val mime: String,
    val tenBit: Set<Int>,
    val hdr10: Set<Int> = emptySet(),
    val hdr10Plus: Set<Int> = emptySet(),
) {
    Hevc(
        "HEVC (H.265)",
        "video/hevc",
        setOf(HEVC_MAIN10, HEVC_MAIN10_HDR10, HEVC_MAIN10_HDR10_PLUS),
        setOf(HEVC_MAIN10_HDR10),
        setOf(HEVC_MAIN10_HDR10_PLUS),
    ),
    Av1("AV1", "video/av01", setOf(AV1_MAIN10, AV1_MAIN10_HDR10, AV1_MAIN10_HDR10_PLUS), setOf(AV1_MAIN10_HDR10), setOf(AV1_MAIN10_HDR10_PLUS)),
    Vp9(
        "VP9",
        "video/x-vnd.on2.vp9",
        setOf(VP9_PROFILE2, VP9_PROFILE3, VP9_PROFILE2_HDR, VP9_PROFILE3_HDR, VP9_PROFILE2_HDR10_PLUS, VP9_PROFILE3_HDR10_PLUS),
        setOf(VP9_PROFILE2_HDR, VP9_PROFILE3_HDR),
        setOf(VP9_PROFILE2_HDR10_PLUS, VP9_PROFILE3_HDR10_PLUS),
    ),
    H264("H.264", "video/avc", setOf(AVC_HIGH10)),
}

/** MIME types read from `MediaCodecList`: the codecs above and Dolby Vision. */
val describedMimes: Set<String> = VideoCodec.entries.map { it.mime }.toSet() + DOLBY_VISION_MIME

/**
 * Before API 29 a decoder does not say whether it is hardware: the Android software
 * decoders are told by their name.
 */
fun isSoftwareName(name: String): Boolean {
    val lower = name.lowercase()
    return lower.startsWith("omx.google.") || lower.startsWith("c2.android.") || lower.startsWith("c2.google.")
}

/**
 * One codec row: hardware decoders first, else the software ones, with what their
 * profiles advertise. Advertised, not proven: a decoder may play HDR10 from Main 10 alone.
 */
fun codecRow(codec: VideoCodec, decoders: List<DecoderInfo>): InfoRow {
    val all = decoders.filter { codec.mime in it.profiles }
    val hardware = all.filter { it.hardware }
    val used = hardware.ifEmpty { all }
    if (used.isEmpty()) return InfoRow(codec.label, "Aucun décodeur")
    val profiles = used.flatMap { it.profiles[codec.mime].orEmpty() }.toSet()
    val traits = buildList {
        add(if (hardware.isNotEmpty()) "Matériel" else "Logiciel seulement")
        if (profiles.any { it in codec.tenBit }) add("10 bits")
        if (profiles.any { it in codec.hdr10 }) add("HDR10")
        if (profiles.any { it in codec.hdr10Plus }) add("HDR10+")
    }
    return InfoRow(codec.label, traits.joinToString(" · "), used.joinToString(", ") { it.name })
}

/**
 * Dolby Vision profile numbers of a profile mask: `DolbyVisionProfileDvavPer` (1 shl 0) is
 * profile 0, `DvheSt` (1 shl 8) profile 8, `Dvav110` (1 shl 10) profile 10.
 */
fun dolbyVisionProfiles(profiles: Collection<Int>): List<Int> =
    (0..10).filter { n -> profiles.any { it and (1 shl n) != 0 } }

fun dolbyVisionRow(decoders: List<DecoderInfo>): InfoRow {
    val all = decoders.filter { DOLBY_VISION_MIME in it.profiles }
    val hardware = all.filter { it.hardware }
    val used = hardware.ifEmpty { all }
    if (used.isEmpty()) return InfoRow("Dolby Vision", "Non annoncé")
    val numbers = dolbyVisionProfiles(used.flatMap { it.profiles[DOLBY_VISION_MIME].orEmpty() })
    val kind = if (hardware.isNotEmpty()) "Matériel" else "Logiciel seulement"
    val value = when {
        numbers.isEmpty() -> kind
        numbers.size == 1 -> "$kind · profil ${numbers.single()}"
        else -> "$kind · profils ${numbers.joinToString(", ")}"
    }
    return InfoRow("Dolby Vision", value, used.joinToString(", ") { it.name })
}

/** The « Lecture » rows: HEVC, AV1, VP9, H.264, then Dolby Vision. */
fun decoderRows(decoders: List<DecoderInfo>): List<InfoRow> =
    VideoCodec.entries.map { codecRow(it, decoders) } + dolbyVisionRow(decoders)

// Display.HdrCapabilities.HDR_TYPE_*, in the order they are shown
private val HDR_TYPES = listOf(2 to "HDR10", 4 to "HDR10+", 3 to "HLG", 1 to "Dolby Vision")

/** HDR formats the display path advertises (the TV behind HDMI on a box); null when unread. */
fun displayHdrRow(types: IntArray?): InfoRow {
    val value = when {
        types == null -> "Inconnu"
        else -> HDR_TYPES.filter { (type, _) -> type in types }.joinToString(", ") { it.second }.ifEmpty { "Aucun format HDR" }
    }
    return InfoRow("Écran", value)
}

fun appVersion(name: String?, code: Long): String = "${name?.takeIf { it.isNotBlank() } ?: "?"} ($code)"

fun buildType(debuggable: Boolean): String = if (debuggable) "Débogage" else "Publication"

/** « NVIDIA SHIELD Android TV », without the maker twice when the model already starts with it. */
fun deviceName(manufacturer: String?, model: String?): String {
    val maker = manufacturer?.trim().orEmpty()
    val name = model?.trim().orEmpty()
    return when {
        name.isEmpty() -> maker.ifEmpty { "Inconnu" }
        maker.isEmpty() || name.startsWith(maker, ignoreCase = true) -> name
        else -> "$maker $name"
    }
}

fun androidVersion(release: String?, sdk: Int): String =
    "Android ${release?.takeIf { it.isNotBlank() } ?: "?"} (API $sdk)"

/** Scheme, host and a non-default port: never a path, a query or a token. */
fun serverAddress(url: HttpUrl): String {
    val host = if (':' in url.host) "[${url.host}]" else url.host
    val port = if (url.port == HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"
    return "${url.scheme}://$host$port"
}

/** What the server rows show while `/System/Info/Public` is read, once read, or after a failure. */
sealed interface ServerAbout {
    data object Loading : ServerAbout

    data class Loaded(val info: PublicSystemInfo) : ServerAbout

    data class Failed(val error: EmbyError) : ServerAbout
}

fun serverRows(state: ServerAbout, address: String): List<InfoRow> {
    val head = when (state) {
        ServerAbout.Loading -> listOf(InfoRow("Serveur", "Chargement…"))
        is ServerAbout.Loaded -> listOf(
            InfoRow("Serveur", state.info.serverName.ifBlank { "Sans nom" }),
            InfoRow("Version d'Emby", state.info.version.ifBlank { "Inconnue" }),
        )
        is ServerAbout.Failed -> listOf(InfoRow("Serveur", serverError(state.error)))
    }
    return head + InfoRow("Adresse", address)
}

/** Short French reason; a parse error's details stay out of the screen. */
fun serverError(error: EmbyError): String = when (error) {
    EmbyError.Unreachable -> "Serveur injoignable"
    is EmbyError.InvalidResponse -> "Réponse inattendue du serveur"
    else -> error.message.removeSuffix(".")
}
