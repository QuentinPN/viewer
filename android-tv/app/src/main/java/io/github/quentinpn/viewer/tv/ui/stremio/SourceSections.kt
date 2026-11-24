package io.github.quentinpn.viewer.tv.ui.stremio

import io.github.quentinpn.viewer.tv.stremio.AddonStreams
import io.github.quentinpn.viewer.tv.stremio.RESUME_DONE_RATIO
import io.github.quentinpn.viewer.tv.stremio.ResumePoint
import io.github.quentinpn.viewer.tv.stremio.SourceList
import io.github.quentinpn.viewer.tv.stremio.StreamChoice
import io.github.quentinpn.viewer.tv.stremio.StremioError
import io.github.quentinpn.viewer.tv.stremio.StremioErrorKind
import io.github.quentinpn.viewer.tv.ui.formatSize
import io.github.quentinpn.viewer.tv.ui.seconds

/** Shown over the list when a Stremio stream failed to open or broke off (the desktop's `SOURCE_FAILED`). */
internal const val SOURCE_FAILED = "Ce lien ne répond plus, choisis une autre source."

/** The second choice over the list, when there is something to resume. */
internal const val RESTART_LABEL = "Depuis le début"

/** « Reprendre à 42:10 » over the list, or null when there is nothing to resume: none saved, or past [RESUME_DONE_RATIO]. */
internal fun resumeLabel(point: ResumePoint?): String? {
    if (point == null) return null
    val (position, duration) = point
    if (!position.isFinite() || !duration.isFinite() || position <= 0 || position >= duration * RESUME_DONE_RATIO) return null
    return "Reprendre à ${seconds(position)}"
}

/** One stream as a row: the name on one line, the description lines under it, the size aside. Never its link. */
internal data class StreamRow(val id: String, val title: String, val details: List<String>, val size: String?)

/** One addon of the list: its rows once it answered, and the lines that say where it stands. */
internal data class SourceSection(val index: Int, val name: String, val rows: List<StreamRow>, val notes: List<String>)

/** The addons in collection order, each as far as it got. */
internal fun sourceSections(list: SourceList): List<SourceSection> = list.addons.map { addon ->
    val (rows, notes) = when (val s = addon.streams) {
        AddonStreams.Loading -> emptyList<StreamRow>() to listOf("Recherche…")
        is AddonStreams.Failed -> emptyList<StreamRow>() to listOf(s.error.message)
        is AddonStreams.Ready -> {
            val unsupported = unsupportedNote(s.unsupported)
            val empty = if (s.streams.isEmpty() && unsupported == null) "Aucune source trouvée." else null
            s.streams.map(::streamRow) to listOfNotNull(empty, unsupported)
        }
    }
    SourceSection(addon.index, addon.name, rows, notes)
}

/** The list is no longer kept: said once for the whole list, as on the desktop, not per addon. */
internal fun expiredList(list: SourceList): StremioError? = list.addons.firstNotNullOfOrNull { addon ->
    (addon.streams as? AddonStreams.Failed)?.error?.takeIf { it.kind == StremioErrorKind.Expired }
}

internal fun streamRow(s: StreamChoice) = StreamRow(s.id, lines(s.name).joinToString(" · "), lines(s.description), formatSize(s.size))

private fun unsupportedNote(count: Int): String? = if (count > 0) "$count flux non pris en charge (torrent sans lien)" else null

private fun lines(text: String): List<String> = text.split(Regex("\\r?\\n")).map { it.trim() }.filter { it.isNotEmpty() }
