package io.github.quentinpn.viewer.tv.request

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The Request API as pinned in contracts/request/openapi.json (ADR 0125).
 * Only the fields the app reads are declared; the shared fixtures check them against the contract.
 */

@Serializable
enum class MediaType(val wire: String) {
    @SerialName("movie") Movie("movie"),
    @SerialName("tv") Tv("tv"),
}

@Serializable
enum class MediaStatus {
    @SerialName("available") Available,
    @SerialName("partial") Partial,
    @SerialName("downloading") Downloading,
    @SerialName("requested") Requested,
    @SerialName("pending") Pending,
    @SerialName("declined") Declined,
}

@Serializable
enum class RequestStatus(val wire: String) {
    @SerialName("pending") Pending("pending"),
    @SerialName("approved") Approved("approved"),
    @SerialName("declined") Declined("declined"),
    @SerialName("cancelled") Cancelled("cancelled"),
    @SerialName("sent") Sent("sent"),
    @SerialName("failed") Failed("failed"),
    @SerialName("available") Available("available"),
}

@Serializable
enum class Role {
    @SerialName("member") Member,
    @SerialName("auto_approve") AutoApprove,
    @SerialName("manager") Manager,
    @SerialName("owner") Owner,
}

@Serializable
data class ApiVersion(val apiVersion: String, val features: List<String>)

@Serializable
data class Capabilities(
    val canRequestMovies: Boolean,
    val canRequestTv: Boolean,
    val canRequest: Boolean,
    val quotaExempt: Boolean,
    val canRequest4k: Boolean = false,
    val canManageRequests: Boolean = false,
    val isOwner: Boolean = false,
)

@Serializable
data class Viewer(
    val id: String,
    val name: String,
    val role: Role,
    val capabilities: Capabilities,
    val avatarUrl: String? = null,
)

@Serializable
data class ViewerResponse(val viewer: Viewer)

/** The session token the server issued: never shown by [toString]. */
@Serializable
data class EmbyTokenSession(val token: String, val expiresAt: String, val viewer: Viewer) {
    override fun toString() = "EmbyTokenSession(token=<redacted>, expiresAt=$expiresAt, viewer=$viewer)"
}

@Serializable
data class AuthSession(
    val id: String,
    val deviceName: String? = null,
    val userAgent: String? = null,
    val createdAt: String,
    val lastSeenAt: String,
    val expiresAt: String,
    val current: Boolean,
)

@Serializable
data class AuthSessions(val sessions: List<AuthSession>)

@Serializable
data class Blocked(val by: String, val keyword: String? = null)

@Serializable
data class MediaSummary(
    val tmdbId: Int,
    val type: MediaType,
    val title: String,
    val year: Int? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val overview: String = "",
    val blocked: Blocked? = null,
    val status: MediaStatus? = null,
    val progress: Double? = null,
    val embyItemId: String? = null,
)

/** Filters of `GET /api/discover/{type}` and of a row's « Tout voir ». */
@Serializable
data class BrowseFilters(
    val genres: List<Int>? = null,
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val rating: Int? = null,
    val runtimeMin: Int? = null,
    val runtimeMax: Int? = null,
    val language: String? = null,
    val provider: Int? = null,
    val sort: String? = null,
)

@Serializable
data class SeeAll(val type: MediaType, val filters: BrowseFilters)

@Serializable
data class DiscoverRow(val id: String, val title: String, val items: List<MediaSummary>, val seeAll: SeeAll? = null)

@Serializable
data class DiscoverResponse(val rows: List<DiscoverRow>, val layout: List<String> = emptyList())

@Serializable
data class PagedMedia(val page: Int, val totalPages: Int, val results: List<MediaSummary>)

@Serializable
data class SearchPerson(
    val id: Int,
    val name: String,
    val profilePath: String? = null,
    val knownFor: List<String> = emptyList(),
)

@Serializable
data class MediaSearchResults(
    val page: Int,
    val totalPages: Int,
    val results: List<MediaSummary>,
    val people: List<SearchPerson> = emptyList(),
)

@Serializable
data class Genre(val id: Int, val name: String)

@Serializable
data class Genres(val genres: List<Genre>)

@Serializable
data class EpisodeCounts(val present: Int, val expected: Int)

@Serializable
data class Season(
    val seasonNumber: Int,
    val episodeCount: Int,
    val airDate: String? = null,
    val episodes: EpisodeCounts? = null,
    val status: MediaStatus? = null,
    val progress: Double? = null,
)

@Serializable
data class CastMember(val id: Int, val name: String, val character: String, val profilePath: String? = null)

@Serializable
data class CrewMember(val id: Int, val name: String, val jobs: List<String>, val profilePath: String? = null)

@Serializable
data class WatchProvider(val id: Int, val name: String, val logoPath: String? = null)

@Serializable
data class WatchProviders(
    val flatrate: List<WatchProvider> = emptyList(),
    val rent: List<WatchProvider> = emptyList(),
    val buy: List<WatchProvider> = emptyList(),
)

@Serializable
data class MediaDetails(
    val tmdbId: Int,
    val type: MediaType,
    val title: String,
    val year: Int? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val overview: String = "",
    val blocked: Blocked? = null,
    val status: MediaStatus? = null,
    val progress: Double? = null,
    val embyItemId: String? = null,
    val genres: List<Genre> = emptyList(),
    val runtime: Int? = null,
    val seasons: List<Season> = emptyList(),
    val cast: List<CastMember> = emptyList(),
    val crew: List<CrewMember> = emptyList(),
    val trailerKey: String? = null,
    val watchProviders: WatchProviders? = null,
    val recommendations: List<MediaSummary> = emptyList(),
    val similar: List<MediaSummary> = emptyList(),
)

@Serializable
data class QuotaUsage(val limit: Int, val days: Int, val used: Int, val remaining: Int)

@Serializable
data class RequestQuota(val movies: QuotaUsage, val seasons: QuotaUsage, val exempt: Boolean)

@Serializable
data class RequestPerson(val id: String, val name: String, val avatarUrl: String? = null)

@Serializable
data class RequestEvent(
    val id: Int,
    val type: String,
    val actor: RequestPerson? = null,
    val detail: String? = null,
    val createdAt: String,
)

/** One request. [events] is only filled by the detail endpoint (see [RequestDetail]). */
@Serializable
data class RequestView(
    val id: Int,
    val type: MediaType,
    val tmdbId: Int,
    val status: RequestStatus,
    val seasons: List<Int> = emptyList(),
    val requester: RequestPerson,
    val declineReason: String? = null,
    val failureReason: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val media: MediaSummary? = null,
    val events: List<RequestEvent> = emptyList(),
)

typealias RequestDetail = RequestView

@Serializable
data class RequestTotals(
    val pending: Int,
    val approved: Int,
    val declined: Int,
    val cancelled: Int,
    val sent: Int,
    val failed: Int,
    val available: Int,
)

@Serializable
data class RequestPage(val page: Int, val totalPages: Int, val results: List<RequestView>, val totals: RequestTotals)

/** Which seasons of a series to request. */
sealed interface SeasonsChoice {
    data object All : SeasonsChoice

    data class Some(val numbers: List<Int>) : SeasonsChoice
}

/** `POST /api/requests`; [seasons] only applies to a series and defaults to all of them. */
data class NewRequest(val type: MediaType, val tmdbId: Int, val seasons: SeasonsChoice? = null)
