package io.github.quentinpn.viewer.tv.request

import kotlin.math.roundToInt

/** How a status is coloured (DESIGN.md): done, in progress, waiting, refused, or neutral. */
enum class Tone { Success, Accent, Warning, Danger, Muted }

/** A status as shown: its French [text] and its [tone]. */
data class StatusLabel(val text: String, val tone: Tone)

/** A fraction `0.0..1.0` as a whole percentage, or null when the server sent none. */
fun percent(progress: Double?): Int? = progress?.let { (it * 100).roundToInt().coerceIn(0, 100) }

/** The status of a title (ADR 0125); [progress] only shows on `downloading`. */
fun mediaStatusLabel(status: MediaStatus, progress: Double? = null): StatusLabel = when (status) {
    MediaStatus.Available -> StatusLabel("Disponible", Tone.Success)
    MediaStatus.Partial -> StatusLabel("Partiel", Tone.Warning)
    MediaStatus.Downloading -> StatusLabel(percent(progress)?.let { "Téléchargement $it %" } ?: "Téléchargement", Tone.Accent)
    MediaStatus.Requested -> StatusLabel("Demandé", Tone.Accent)
    MediaStatus.Pending -> StatusLabel("En attente", Tone.Warning)
    MediaStatus.Declined -> StatusLabel("Refusé", Tone.Danger)
}

/** The status of a request. */
fun requestStatusLabel(status: RequestStatus): StatusLabel = when (status) {
    RequestStatus.Pending -> StatusLabel("En attente", Tone.Warning)
    RequestStatus.Approved -> StatusLabel("Approuvée", Tone.Accent)
    RequestStatus.Sent -> StatusLabel("Envoyée", Tone.Accent)
    RequestStatus.Available -> StatusLabel("Disponible", Tone.Success)
    RequestStatus.Declined -> StatusLabel("Refusée", Tone.Danger)
    RequestStatus.Cancelled -> StatusLabel("Annulée", Tone.Muted)
    RequestStatus.Failed -> StatusLabel("Échec", Tone.Danger)
}

/** What a request in progress says: the title's download progress when there is one, else the request's own status. */
fun requestLine(request: RequestView): StatusLabel {
    val media = request.media
    return if (media?.status == MediaStatus.Downloading) mediaStatusLabel(MediaStatus.Downloading, media.progress) else requestStatusLabel(request.status)
}

/** Key of a title in lists and focus memory: movie and series ids may collide. */
fun MediaSummary.key(): String = "${type.wire}:$tmdbId"

/** [current] followed by the titles of [next] it does not hold yet, so a page read twice adds nothing. */
fun mergeMedia(current: List<MediaSummary>, next: List<MediaSummary>): List<MediaSummary> {
    val seen = current.mapTo(HashSet()) { it.key() }
    return current + next.filter { seen.add(it.key()) }
}
