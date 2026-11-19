package io.github.quentinpn.viewer.tv.request

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * The status segments of « Mes demandes »: « Toutes » asks for no filter, the others for the statuses
 * that `GET /api/requests?status=` takes comma-separated (never repeated).
 */
enum class StatusFilter(val label: String, val statuses: List<RequestStatus>) {
    All("Toutes", emptyList()),
    Pending("En attente", listOf(RequestStatus.Pending)),
    InProgress("En cours", listOf(RequestStatus.Approved, RequestStatus.Sent)),
    Available("Disponibles", listOf(RequestStatus.Available)),
    Refused("Refusées", listOf(RequestStatus.Declined, RequestStatus.Failed, RequestStatus.Cancelled)),
    ;

    /** How many requests of the member this segment holds, from `RequestPage.totals`. */
    fun count(totals: RequestTotals): Int = when (this) {
        All -> RequestStatus.entries.sumOf { it.countIn(totals) }
        else -> statuses.sumOf { it.countIn(totals) }
    }
}

private fun RequestStatus.countIn(totals: RequestTotals): Int = when (this) {
    RequestStatus.Pending -> totals.pending
    RequestStatus.Approved -> totals.approved
    RequestStatus.Declined -> totals.declined
    RequestStatus.Cancelled -> totals.cancelled
    RequestStatus.Sent -> totals.sent
    RequestStatus.Failed -> totals.failed
    RequestStatus.Available -> totals.available
}

/** Requests still on their way (« Mes demandes · N » and the strip): waiting, approved or sent. */
fun inFlightCount(totals: RequestTotals): Int = totals.pending + totals.approved + totals.sent

private val MONTHS = listOf("janv.", "févr.", "mars", "avr.", "mai", "juin", "juil.", "août", "sept.", "oct.", "nov.", "déc.")

/** « 15 nov. 2026 » or, with [time], « 15 nov. 2026 · 20:12 », in [zone]; the date part of [iso] if it cannot be read. */
fun formatWhen(iso: String, time: Boolean = false, zone: ZoneId = ZoneId.systemDefault()): String = try {
    val at = OffsetDateTime.parse(iso).atZoneSameInstant(zone)
    val day = "${at.dayOfMonth} ${MONTHS[at.monthValue - 1]} ${at.year}"
    if (time) "$day · ${at.hour.toString().padStart(2, '0')}:${at.minute.toString().padStart(2, '0')}" else day
} catch (_: DateTimeParseException) {
    iso.take(10)
}

/** « Saison 3 », « Saisons 1, 2 »; null for a film or a request for the whole series. */
fun seasonsText(seasons: List<Int>): String? = when (seasons.size) {
    0 -> null
    1 -> "Saison ${seasons.single()}"
    else -> "Saisons ${seasons.sorted().joinToString(", ")}"
}

/** The line under a request's title: film or series, its seasons, and the day it was made. */
fun requestMeta(request: RequestView, zone: ZoneId = ZoneId.systemDefault()): String =
    listOfNotNull(
        if (request.type == MediaType.Movie) "Film" else "Série",
        seasonsText(request.seasons),
        formatWhen(request.createdAt, zone = zone),
    ).joinToString(" · ")

/** The timeline's French label of an event; a type this version does not know shows as the server sent it. */
fun eventLabel(type: String): String = when (type) {
    "requested" -> "Demande envoyée"
    "approved" -> "Approuvée"
    "declined" -> "Refusée"
    "cancelled" -> "Annulée"
    "sent" -> "Envoyée"
    "failed" -> "Échec"
    "retried" -> "Nouvel essai"
    "available" -> "Disponible"
    else -> type
}

/** What a search offers to ask for: titles that are not in Emby yet (no `embyItemId`) and are not blocked. */
fun toRequest(results: List<MediaSummary>): List<MediaSummary> =
    results.filter { it.embyItemId == null && it.blocked == null }.distinctBy { it.key() }
