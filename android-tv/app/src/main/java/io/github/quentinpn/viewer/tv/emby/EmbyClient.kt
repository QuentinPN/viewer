package io.github.quentinpn.viewer.tv.emby

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

private const val CLIENT_NAME = "Viewer TV"
private val JSON_TYPE = "application/json".toMediaType()

/** Sent with every request; [id] is a random id kept per installation. */
data class DeviceIdentity(val name: String, val id: String, val version: String)

/**
 * Typed Emby operations, ported from the Windows client (ADR 0004, 0007). The token
 * travels in the `X-Emby-Authorization` header, never in a URL.
 */
class EmbyClient(
    val server: ServerUrl,
    private val device: DeviceIdentity,
    private val http: OkHttpClient,
    private val token: String? = null,
) {
    // encodeDefaults: PlaybackInfoRequest flags such as IsPlayback are defaults
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    fun withToken(token: String) = EmbyClient(server, device, http, token)

    /** Quotes and commas are stripped from values so they cannot break the header syntax. */
    fun authorizationHeader(): String {
        fun clean(v: String) = v.replace("\"", "").replace(",", "")
        val base = "Emby Client=\"$CLIENT_NAME\", Device=\"${clean(device.name)}\", " +
            "DeviceId=\"${clean(device.id)}\", Version=\"${clean(device.version)}\""
        return if (token == null) base else "$base, Token=\"${clean(token)}\""
    }

    /** Headers the player sends with stream requests. */
    fun streamHeaders(): Map<String, String> = mapOf("X-Emby-Token" to requireToken())

    suspend fun publicInfo(): PublicSystemInfo =
        call(get(server.api("/System/Info/Public")), PublicSystemInfo.serializer(), authenticated = false)

    /** Signs in by user name. The password is only sent in the request body. */
    suspend fun authenticate(username: String, password: String): AuthenticationResult {
        val body = json.encodeToString(AuthenticateUserByName.serializer(), AuthenticateUserByName(username, password))
        val result = call(
            post(server.api("/Users/AuthenticateByName"), body.toRequestBody(JSON_TYPE)),
            AuthenticationResult.serializer(),
            authenticated = false,
        )
        if (result.accessToken.isEmpty()) throw EmbyException(EmbyError.InvalidResponse("jeton absent"))
        return result
    }

    /** Current user; also checks that the stored token is still accepted. */
    suspend fun user(userId: String): UserDto =
        call(get(server.api("/Users/${segment(userId)}")), UserDto.serializer())

    suspend fun logout() {
        requireToken()
        execute(post(server.api("/Sessions/Logout"), ByteArray(0).toRequestBody()), authenticated = true).close()
    }

    suspend fun views(userId: String): QueryResult<BaseItemDto> =
        call(get(server.api("/Users/${segment(userId)}/Views")), QueryResult.serializer(BaseItemDto.serializer()))

    /** Films and episodes started but not finished, most recent first. */
    suspend fun resumeItems(userId: String, limit: Int): QueryResult<BaseItemDto> {
        val url = server.api("/Users/${segment(userId)}/Items/Resume").newBuilder()
            .addQueryParameter("Limit", limit.toString())
            .addQueryParameter("MediaTypes", "Video")
            .addQueryParameter("IncludeItemTypes", "Movie,Episode")
            .addQueryParameter("EnableUserData", "true")
            .addQueryParameter("ImageTypeLimit", "1")
            .addQueryParameter("EnableImageTypes", "Primary,Backdrop,Thumb")
            .build()
        return call(get(url), QueryResult.serializer(BaseItemDto.serializer()))
    }

    /** Most recently added films or series of a library. */
    suspend fun latest(userId: String, parentId: String, limit: Int): List<BaseItemDto> {
        val url = server.api("/Users/${segment(userId)}/Items/Latest").newBuilder()
            .addQueryParameter("ParentId", segment(parentId))
            .addQueryParameter("Limit", limit.toString())
            .addQueryParameter("EnableUserData", "true")
            .addQueryParameter("ImageTypeLimit", "1")
            .addQueryParameter("EnableImageTypes", "Primary")
            .build()
        return call(get(url), kotlinx.serialization.builtins.ListSerializer(BaseItemDto.serializer()))
    }

    /** One series or film, with its overview, length and user data. */
    suspend fun item(userId: String, itemId: String): BaseItemDto {
        val url = server.api("/Users/${segment(userId)}/Items/${segment(itemId)}").newBuilder()
            .addQueryParameter("Fields", "Overview")
            .build()
        return call(get(url), BaseItemDto.serializer())
    }

    /** Seasons of a series, in order (`0` = specials). Unknown series: 404. */
    suspend fun seasons(userId: String, seriesId: String): QueryResult<BaseItemDto> {
        val url = server.api("/Shows/${segment(seriesId)}/Seasons").newBuilder()
            .addQueryParameter("UserId", segment(userId))
            .addQueryParameter("EnableUserData", "true")
            .addQueryParameter("EnableImageTypes", "Primary")
            .addQueryParameter("ImageTypeLimit", "1")
            .build()
        return call(get(url), QueryResult.serializer(BaseItemDto.serializer()))
    }

    /**
     * All episodes of a season, page by page. Emby 4.10.1.0 trusts `SeasonId` over
     * the series id, so episodes of another series are dropped.
     */
    suspend fun episodes(userId: String, seriesId: String, seasonId: String, pageSize: Int = 100): List<BaseItemDto> {
        val all = mutableListOf<BaseItemDto>()
        while (true) {
            val url = server.api("/Shows/${segment(seriesId)}/Episodes").newBuilder()
                .addQueryParameter("UserId", segment(userId))
                .addQueryParameter("SeasonId", segment(seasonId))
                .addQueryParameter("StartIndex", all.size.toString())
                .addQueryParameter("Limit", pageSize.toString())
                // Without it episodes come without their overview
                .addQueryParameter("Fields", "Overview")
                .addQueryParameter("EnableUserData", "true")
                .addQueryParameter("EnableImageTypes", "Primary")
                .addQueryParameter("ImageTypeLimit", "1")
                .build()
            val page = call(get(url), QueryResult.serializer(BaseItemDto.serializer()))
            all += page.items
            if (page.items.isEmpty() || all.size >= page.totalRecordCount) break
        }
        return all.filter { it.seriesId == null || it.seriesId == seriesId }
    }

    /** Playback negotiation (ADR 0007): the server answers with its sources for this profile. */
    suspend fun playbackInfo(userId: String, itemId: String, profile: DeviceProfile): PlaybackInfoResponse {
        val id = segment(userId)
        val url = server.api("/Items/${segment(itemId)}/PlaybackInfo").newBuilder()
            .addQueryParameter("UserId", id)
            .build()
        val body = json.encodeToString(
            PlaybackInfoRequest.serializer(),
            PlaybackInfoRequest(userId = id, maxStreamingBitrate = profile.maxStreamingBitrate, deviceProfile = profile),
        )
        return call(post(url, body.toRequestBody(JSON_TYPE)), PlaybackInfoResponse.serializer())
    }

    /** Playback report (ADR 0008); the server answers 204 without a body. */
    suspend fun reportPlayback(report: Report) {
        requireToken()
        val body = json.encodeToString(PlaybackReport.serializer(), report.body)
        execute(post(server.api(report.kind.path), body.toRequestBody(JSON_TYPE)), authenticated = true).close()
    }

    /** Image URL on this server; fetched with [authorizationHeader] by the image loader. */
    fun imageUrl(itemId: String, type: String, tag: String, maxWidth: Int): HttpUrl =
        server.api("/Items/${segment(itemId)}/Images/${segment(type)}").newBuilder()
            .addQueryParameter("tag", tag)
            .addQueryParameter("maxWidth", maxWidth.toString())
            .build()

    private fun get(url: HttpUrl) = request(url).get().build()

    private fun post(url: HttpUrl, body: RequestBody) = request(url).post(body).build()

    private fun request(url: HttpUrl) = Request.Builder().url(url)
        .header("X-Emby-Authorization", authorizationHeader())
        .header("Accept", "application/json")

    private suspend fun <T> call(request: Request, serializer: KSerializer<T>, authenticated: Boolean = true): T {
        if (authenticated) requireToken()
        return execute(request, authenticated).use { res ->
            val text = withContext(Dispatchers.IO) { res.body.string() }
            try {
                json.decodeFromString(serializer, text)
            } catch (e: SerializationException) {
                throw EmbyException(EmbyError.InvalidResponse(e.message ?: "JSON invalide"), e)
            } catch (e: IllegalArgumentException) {
                throw EmbyException(EmbyError.InvalidResponse(e.message ?: "JSON invalide"), e)
            }
        }
    }

    /**
     * Maps HTTP status to error categories. [authenticated] tells an expired token
     * (401 on a token call) from a wrong password (401 on sign-in).
     */
    private suspend fun execute(request: Request, authenticated: Boolean): okhttp3.Response {
        val res = try {
            withContext(Dispatchers.IO) { http.newCall(request).execute() }
        } catch (e: IOException) {
            throw EmbyException(EmbyError.Unreachable, e)
        }
        if (res.isSuccessful) return res
        res.close()
        throw EmbyException(
            when {
                res.code == 401 && authenticated -> EmbyError.SessionExpired
                res.code == 401 -> EmbyError.InvalidCredentials
                res.code == 403 -> EmbyError.Forbidden
                res.code == 404 -> EmbyError.NotFound
                res.isRedirect -> EmbyError.InvalidResponse("redirection HTTP ${res.code} non suivie")
                else -> EmbyError.Server(res.code)
            },
        )
    }

    private fun requireToken(): String = token ?: throw EmbyException(EmbyError.NotSignedIn)

    companion object {
        /** The client must not follow redirects: they could carry the auth header elsewhere. */
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .readTimeout(java.time.Duration.ofSeconds(30))
            .build()

        /** Ids come from the server, but are still checked before going into a path. */
        internal fun segment(id: String): String {
            if (id.isEmpty() || !id.all { it.isLetterOrDigit() && it.code < 128 || it == '-' }) {
                throw EmbyException(EmbyError.InvalidResponse("identifiant invalide"))
            }
            return id
        }
    }
}
