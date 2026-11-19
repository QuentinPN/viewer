package io.github.quentinpn.viewer.tv.request

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/** The signed-in Emby account whose token is traded for a Request session. Never shows the token. */
class EmbyAccount(val serverId: String, val userId: String) {
    val account: String get() = accountKey(serverId, userId)

    override fun toString() = "EmbyAccount(serverId=$serverId, userId=$userId)"
}

enum class RequestState { NotConfigured, SignedOut, TooOld, Unreachable, Rejected, Ready }

/** What the Demander tab and the settings show; [message] is the French text of the failure, if any. */
data class RequestLinkStatus(
    val state: RequestState,
    val address: String? = null,
    /** True for a plain `http://` address on the local network: shown with a « non chiffré » warning. */
    val insecure: Boolean = false,
    val viewer: Viewer? = null,
    val apiVersion: String? = null,
    val missing: List<String> = emptyList(),
    val message: String? = null,
)

/**
 * Everything the app does with Request (ADR 0125). Calls carry the session token of the
 * signed-in Emby account, got by trading the Emby token ([embyToken]) once: the trade is
 * single-flight, so a burst of parallel calls costs one exchange, not one each. A 401 drops the
 * token, trades again once and replays the call; a second 401 is [RequestError.Rejected].
 */
class RequestRepository(
    private val store: RequestStore,
    private val http: OkHttpClient,
    private val account: () -> EmbyAccount?,
    private val embyToken: () -> String?,
    private val deviceName: String,
    private val userAgent: String,
) {
    private val exchangeLock = Mutex()

    private class Cached(val account: String, val address: String, val token: SessionToken)

    /** The decrypted session token in memory (one slot), so a call does not open the Keystore. */
    @Volatile private var cache: Cached? = null

    private fun stored(account: String, address: String): SessionToken? {
        cache?.takeIf { it.account == account && it.address == address }?.let { return it.token }
        return store.token(account, address)?.also { cache = Cached(account, address, it) }
    }

    private fun keep(account: String, address: String, token: SessionToken) {
        store.saveToken(account, address, token)
        cache = Cached(account, address, token)
    }

    private fun drop(account: String) {
        if (cache?.account == account) cache = null
        store.clearToken(account)
    }

    @Volatile private var checkedAddress: String? = null

    @Volatile private var apiVersion: String? = null

    private class Context(val address: String, val client: RequestClient, val identity: EmbyAccount) {
        val account: String get() = identity.account
    }

    suspend fun status(): RequestLinkStatus {
        val address = store.address ?: return RequestLinkStatus(RequestState.NotConfigured)
        val insecure = address.startsWith("http://")
        val base = RequestLinkStatus(RequestState.NotConfigured, address, insecure)
        return try {
            val viewer = authorized { client, token ->
                client.get("/api/auth/me", token, ViewerResponse.serializer()).viewer
            }
            base.copy(state = RequestState.Ready, viewer = viewer, apiVersion = apiVersion)
        } catch (e: RequestException) {
            when (val error = e.error) {
                RequestError.NotConfigured -> base
                RequestError.SignedOut -> base.copy(RequestState.SignedOut)
                is RequestError.TooOld ->
                    base.copy(RequestState.TooOld, apiVersion = error.apiVersion, missing = error.missing, message = error.message)
                RequestError.Unreachable -> base.copy(RequestState.Unreachable, message = error.message)
                is RequestError.Rejected, RequestError.EmbySignedOut ->
                    base.copy(RequestState.Rejected, message = error.message)
                else -> base.copy(RequestState.Unreachable, message = error.message)
            }
        }
    }

    /** Saves the address (an explicit « connect »: it clears the signed-out flag) and reads the status. */
    suspend fun setAddress(input: String): RequestLinkStatus {
        val address = normalizeAddress(input).display()
        if (address != store.address) {
            store.address = address
            checkedAddress = null
            apiVersion = null
            cache = null
        }
        account()?.let { store.setSignedOut(it.account, false) }
        return status()
    }

    /** Connects again after « Se déconnecter ». */
    suspend fun signIn(): RequestLinkStatus {
        account()?.let { store.setSignedOut(it.account, false) }
        return status()
    }

    /**
     * Ends this account's session: revoked on the server as far as it can be, the token deleted
     * in any case, and nothing reconnects until [signIn] or [setAddress].
     */
    suspend fun signOut() {
        val id = account() ?: return
        val address = store.address
        val token = address?.let { stored(id.account, it) }
        store.setSignedOut(id.account, true)
        drop(id.account)
        if (address != null && token != null) {
            try {
                client(address).sendEmpty("POST", "/api/auth/logout", token)
            } catch (_: RequestException) {
                // Best effort: the token is gone from the device either way
            }
        }
    }

    /** Emby sign-out or « Oublier ce serveur »: forgets this account's token and flag. */
    fun forgetAccount(embyServerId: String, embyUserId: String) {
        val key = accountKey(embyServerId, embyUserId)
        drop(key)
        store.forget(key)
    }

    suspend fun discover(): DiscoverResponse =
        authorized { c, t -> c.get("/api/discover", t, DiscoverResponse.serializer()) }

    suspend fun browse(type: MediaType, filters: BrowseFilters = BrowseFilters(), page: Int = 1): PagedMedia =
        authorized { c, t -> c.get("/api/discover/${type.wire}", t, PagedMedia.serializer(), browseQuery(filters, page)) }

    suspend fun genres(type: MediaType): List<Genre> =
        authorized { c, t -> c.get("/api/genres/${type.wire}", t, Genres.serializer()).genres }

    suspend fun search(query: String, page: Int = 1): MediaSearchResults = authorized { c, t ->
        c.get("/api/search", t, MediaSearchResults.serializer(), listOf("q" to query, "page" to page.toString()))
    }

    suspend fun media(type: MediaType, tmdbId: Int): MediaDetails =
        authorized { c, t -> c.get("/api/media/${type.wire}/$tmdbId", t, MediaDetails.serializer()) }

    suspend fun quota(): RequestQuota = authorized { c, t -> c.get("/api/requests/quota", t, RequestQuota.serializer()) }

    suspend fun request(request: NewRequest): RequestView =
        authorized { c, t -> c.send("POST", "/api/requests", t, RequestView.serializer(), request.toJson()) }

    /** The member's own requests; [statuses] go in one comma-separated `status` parameter, none means all. */
    suspend fun myRequests(statuses: List<RequestStatus> = emptyList(), page: Int = 1): RequestPage =
        authorized { c, t -> c.get("/api/requests", t, RequestPage.serializer(), myRequestsQuery(statuses, page)) }

    suspend fun requestDetail(id: Int): RequestDetail =
        authorized { c, t -> c.get("/api/requests/$id", t, RequestDetail.serializer()) }

    suspend fun cancel(id: Int): RequestView =
        authorized { c, t -> c.send("POST", "/api/requests/$id/cancel", t, RequestView.serializer()) }

    suspend fun sessions(): List<AuthSession> =
        authorized { c, t -> c.get("/api/auth/sessions", t, AuthSessions.serializer()).sessions }

    suspend fun revokeSession(id: String) {
        authorized { c, t -> c.sendEmpty("DELETE", "/api/auth/sessions/${pathSegment(id)}", t) }
    }

    /** The configured address, for the image loader to add the token to that origin only. */
    fun origin(): HttpUrl? = store.address?.let { runCatching { normalizeAddress(it) }.getOrNull() }

    /**
     * The session token for an image request: the stored one, got first when there is none, or
     * traded again when [failed] (the one that just got a 401) is still the current one.
     */
    suspend fun accessToken(failed: SessionToken? = null): SessionToken {
        val ctx = context()
        ensureVersion(ctx)
        return stored(ctx.account, ctx.address).takeIf { it != null && it != failed } ?: exchange(ctx, failed)
    }

    private fun client(address: String) = RequestClient(normalizeAddress(address), http, userAgent)

    private fun context(): Context {
        val address = store.address ?: throw RequestException(RequestError.NotConfigured)
        val id = account() ?: throw RequestException(RequestError.EmbySignedOut)
        if (store.isSignedOut(id.account)) throw RequestException(RequestError.SignedOut)
        return Context(address, client(address), id)
    }

    private suspend fun ensureVersion(ctx: Context) {
        if (checkedAddress == ctx.address) return
        val version = ctx.client.version()
        ensureCompatible(version)
        apiVersion = version.apiVersion
        checkedAddress = ctx.address
    }

    private suspend fun <T> authorized(block: suspend (RequestClient, SessionToken) -> T): T {
        val ctx = context()
        ensureVersion(ctx)
        val first = stored(ctx.account, ctx.address) ?: exchange(ctx, null)
        return try {
            block(ctx.client, first)
        } catch (e: RequestException) {
            if (e.error !is RequestError.Rejected) throw e
            // Expired or revoked: one new exchange and one replay; a second refusal is final
            block(ctx.client, exchange(ctx, first))
        }
    }

    /**
     * The single-flight exchange. Once the lock is ours, the stored token is read again: if it
     * is not the one that failed, another call already renewed it and we just use it.
     */
    private suspend fun exchange(ctx: Context, failed: SessionToken?): SessionToken = exchangeLock.withLock {
        stored(ctx.account, ctx.address)?.takeIf { it != failed }?.let { return it }
        drop(ctx.account)
        val session = ctx.client.exchange(ctx.identity.userId, embyToken() ?: throw RequestException(RequestError.EmbySignedOut), deviceName)
        SessionToken(session.token).also { keep(ctx.account, ctx.address, it) }
    }
}

/** The `GET /api/discover/{type}` query; several genres are comma-separated, all required at once. */
internal fun browseQuery(filters: BrowseFilters, page: Int): List<Pair<String, String>> = buildList {
    add("page" to page.toString())
    filters.genres?.takeIf { it.isNotEmpty() }?.let { add("genres" to it.joinToString(",")) }
    filters.yearFrom?.let { add("yearFrom" to it.toString()) }
    filters.yearTo?.let { add("yearTo" to it.toString()) }
    filters.rating?.let { add("rating" to it.toString()) }
    filters.runtimeMin?.let { add("runtimeMin" to it.toString()) }
    filters.runtimeMax?.let { add("runtimeMax" to it.toString()) }
    filters.language?.let { add("language" to it) }
    filters.provider?.let { add("provider" to it.toString()) }
    filters.sort?.let { add("sort" to it) }
}

/** `GET /api/requests`: mine only, statuses in one comma-separated parameter, never repeated. */
internal fun myRequestsQuery(statuses: List<RequestStatus>, page: Int): List<Pair<String, String>> = buildList {
    add("mine" to "true")
    if (statuses.isNotEmpty()) add("status" to statuses.joinToString(",") { it.wire })
    add("page" to page.toString())
}

internal fun NewRequest.toJson(): JsonElement = buildJsonObject {
    put("type", type.wire)
    put("tmdbId", tmdbId)
    if (type == MediaType.Tv) {
        when (val choice = seasons ?: SeasonsChoice.All) {
            SeasonsChoice.All -> put("seasons", "all")
            is SeasonsChoice.Some -> put("seasons", JsonArray(choice.numbers.map(::JsonPrimitive)))
        }
    }
}

private val IMAGE_SIZES = setOf("w185", "w342", "w500", "w780", "w1280", "original")
private val IMAGE_FILE = Regex("""[A-Za-z0-9_-]+\.(jpg|jpeg|png|webp)""")

/**
 * URL of a TMDB image served by Request, or null for anything else (path traversal, SVG,
 * unknown size). TMDB paths start with `/`, which the route does not want. No token in the URL.
 */
fun imageUrl(origin: HttpUrl, size: String, path: String?): HttpUrl? {
    val file = path?.removePrefix("/") ?: return null
    if (size !in IMAGE_SIZES || !IMAGE_FILE.matches(file)) return null
    return origin.newBuilder().addPathSegments("api/images/$size/$file").build()
}
