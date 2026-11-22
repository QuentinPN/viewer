package io.github.quentinpn.viewer.tv.ui.requests

import io.github.quentinpn.viewer.tv.request.MediaDetails
import io.github.quentinpn.viewer.tv.request.MediaStatus
import io.github.quentinpn.viewer.tv.request.MediaType
import io.github.quentinpn.viewer.tv.request.RequestQuota
import io.github.quentinpn.viewer.tv.request.Season
import io.github.quentinpn.viewer.tv.request.mediaStatusLabel
import java.time.LocalDate
import java.time.ZoneOffset

/*
 * What can be requested, as Request decides it (src/shared/requests.ts and status.ts of
 * Request at 54cd791); the same rule as the desktop app's seasons.ts.
 */

/** Today's date, UTC, as `AAAA-MM-JJ`: the day a season counts as aired. */
internal fun isoDay(): String = LocalDate.now(ZoneOffset.UTC).toString()

/** A status that leaves room for a request: none yet, partly there, or refused before. */
private fun MediaStatus?.allowsRequest() = this == null || this == MediaStatus.Partial || this == MediaStatus.Declined

/** Aired by [today]: it has episodes and an air date on or before that day. */
internal fun hasAired(season: Season, today: String): Boolean {
    val airDate = season.airDate ?: return false
    return season.episodeCount > 0 && airDate.take(DATE_LENGTH) <= today
}

/** A real season (not the specials, number 0), aired, and with no status or a partial or declined one. */
internal fun isSeasonRequestable(season: Season, today: String = isoDay()): Boolean =
    season.seasonNumber > 0 && hasAired(season, today) && season.status.allowsRequest()

/** The numbers of the seasons that can be requested now, in order. */
internal fun requestableSeasons(details: MediaDetails, today: String = isoDay()): List<Int> =
    details.seasons.filter { isSeasonRequestable(it, today) }.map { it.seasonNumber }

/**
 * Whether there is anything to ask for: a film with no status, partial or declined; a series that
 * is not wholly available and has such a status itself or at least one season to request.
 */
internal fun isRequestable(details: MediaDetails, today: String = isoDay()): Boolean = when (details.type) {
    MediaType.Movie -> details.status.allowsRequest()
    MediaType.Tv -> details.status != MediaStatus.Available && (details.status.allowsRequest() || requestableSeasons(details, today).isNotEmpty())
}

/**
 * Whether « Demander » has anything to offer now: [isRequestable], and for a series whose seasons are
 * listed, at least one of them to tick (its own status alone would open a dialog with nothing in it).
 */
internal fun canAskNow(details: MediaDetails, today: String = isoDay()): Boolean =
    isRequestable(details, today) &&
        (details.type == MediaType.Movie || details.seasons.none { it.seasonNumber > 0 } || requestableSeasons(details, today).isNotEmpty())

/** « Demander 1 saison », « Demander 3 saisons ». */
internal fun seasonsLabel(count: Int): String = if (count == 1) "Demander 1 saison" else "Demander $count saisons"

/** Why a season of the list cannot be ticked, in French; null when it can. */
internal fun seasonNote(season: Season, today: String = isoDay()): String? = when {
    isSeasonRequestable(season, today) -> null
    season.status != null -> mediaStatusLabel(season.status, season.progress).text
    !hasAired(season, today) -> "Pas encore diffusée"
    else -> null
}

/** « Quota : 8 saisons sur 10 restantes », « Quota : 1 film sur 10 restant »; null when the member has no quota. */
internal fun quotaLine(quota: RequestQuota, type: MediaType): String? {
    if (quota.exempt) return null
    val usage = if (type == MediaType.Tv) quota.seasons else quota.movies
    val noun = if (type == MediaType.Tv) "saison" else "film"
    val adjective = if (type == MediaType.Tv) "restante" else "restant"
    val s = if (usage.remaining > 1) "s" else ""
    return "Quota : ${usage.remaining} $noun$s sur ${usage.limit} $adjective$s"
}

/** True when asking for [count] more than the quota lets: nothing is sent, the server would refuse it. */
internal fun exceedsQuota(quota: RequestQuota?, type: MediaType, count: Int): Boolean {
    if (quota == null || quota.exempt) return false
    val remaining = if (type == MediaType.Tv) quota.seasons.remaining else quota.movies.remaining
    return count > remaining
}

/** « 1 h 52 » or « 48 min »; null without a length. */
internal fun runtimeLabel(minutes: Int?): String? = minutes?.takeIf { it > 0 }?.let {
    if (it < 60) "$it min" else "${it / 60} h ${(it % 60).toString().padStart(2, '0')}"
}

private const val DATE_LENGTH = 10
