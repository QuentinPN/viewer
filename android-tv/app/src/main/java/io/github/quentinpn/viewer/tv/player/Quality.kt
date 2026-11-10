package io.github.quentinpn.viewer.tv.player

import io.github.quentinpn.viewer.tv.emby.PlayMethod
import io.github.quentinpn.viewer.tv.settings.BITRATE_CHOICES
import io.github.quentinpn.viewer.tv.settings.capLabel
import io.github.quentinpn.viewer.tv.settings.mbits
import io.github.quentinpn.viewer.tv.settings.offeredCap
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// « Qualité » in the player's menu (ADR 0081): the caps of Réglages (ADR 0051), for the
// playback in progress only. ◀ ▶ pick a cap without reloading anything; OK applies it.

/** The caps of Réglages, highest first: « Maximale » (null), then 40 down to 1,5 Mbit/s. */
val QUALITY_CHOICES: List<Long?> = listOf<Long?>(null) + BITRATE_CHOICES

enum class QualityChange { Lower, Higher }

/** The cap after [cap] in the direction of [change], held at both ends; a cap not offered reads as « Maximale ». */
fun stepQuality(cap: Long?, change: QualityChange): Long? {
    val index = QUALITY_CHOICES.indexOf(offeredCap(cap))
    val target = when (change) {
        QualityChange.Lower -> index + 1
        QualityChange.Higher -> index - 1
    }
    return QUALITY_CHOICES[target.coerceIn(0, QUALITY_CHOICES.lastIndex)]
}

enum class QualityPhase {
    /** Nothing under way. */
    Idle,

    /** Asking the server and reloading: no other change until it ends. */
    Applying,

    /** The last change did not go through: the playing stream is unchanged. */
    Failed,
}

/**
 * The row's state. [applied] is the cap of the stream playing, [chosen] the one shown,
 * picked with ◀ ▶ and not applied until OK; [method] how the server delivers the stream.
 */
data class Quality(
    val applied: Long? = null,
    val chosen: Long? = null,
    val phase: QualityPhase = QualityPhase.Idle,
    val method: PlayMethod? = null,
) {
    /** A pick that OK would apply. */
    val pending get() = chosen != applied && phase != QualityPhase.Applying

    companion object {
        /** The playback starts at the cap of Réglages for the server's kind of address. */
        fun of(cap: Long?, method: PlayMethod?) = offeredCap(cap).let { Quality(applied = it, chosen = it, method = method) }
    }
}

/** ◀ ▶ on the row: only the shown cap moves, never while a change is under way. */
fun Quality.step(change: QualityChange): Quality =
    if (phase == QualityPhase.Applying) this else copy(chosen = stepQuality(chosen, change), phase = QualityPhase.Idle)

/** OK on the row: the shown cap is being applied. */
fun Quality.applying(): Quality = copy(phase = QualityPhase.Applying)

/** The new stream plays at the shown cap. */
fun Quality.applied(method: PlayMethod): Quality = copy(applied = chosen, phase = QualityPhase.Idle, method = method)

/** The server or the player refused: the playing stream and its cap stay. */
fun Quality.failed(): Quality = copy(chosen = applied, phase = QualityPhase.Failed)

/** The row's value: the label of Réglages. */
fun qualityValue(quality: Quality): String = capLabel(quality.chosen)

/** The line under « Qualité ». */
fun qualityNote(quality: Quality): String = when {
    quality.phase == QualityPhase.Applying -> "Changement en cours…"
    quality.pending -> "OK applique ce débit à cette lecture"
    quality.phase == QualityPhase.Failed -> "Échec : la qualité n'a pas changé"
    else -> listOfNotNull(quality.method?.let { methodLabel(it, emptyList()) }, "◀ ▶ puis OK pour changer").joinToString("  ·  ")
}

/**
 * Whether a server-built stream URL carries `StartTimeTicks`: logged after a quality
 * change, to see on the device if the server offsets the stream itself (ADR 0081).
 * Only the parameter's name is read; the URL is never logged.
 */
fun hasStartParameter(url: String): Boolean {
    val parsed = url.toHttpUrlOrNull() ?: return false
    return (0 until parsed.querySize).any { parsed.queryParameterName(it).equals("StartTimeTicks", ignoreCase = true) }
}

/** « Débit maximal » of « Informations de lecture » (ADR 0061), and whether the player chose it (ADR 0081). */
fun capFact(cap: Long?, localAddress: Boolean, chosenInPlayer: Boolean): String =
    (cap?.let(::mbits) ?: "Maximale") +
        (if (localAddress) " · réseau local" else " · accès distant") +
        if (chosenInPlayer) " · choisi pour cette lecture" else ""
