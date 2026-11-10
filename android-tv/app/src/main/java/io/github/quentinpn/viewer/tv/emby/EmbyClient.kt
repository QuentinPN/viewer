package io.github.quentinpn.viewer.tv.emby

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

private const val CLIENT_NAME = "Viewer TV"
private val JSON_TYPE = "application/json".toMediaType()

/** A download larger than the caller's cap, stopped there (ADR 0069). */
class TooLargeException(limit: Int) : Exception("larger than $limit bytes")

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

    /**
     * The account's track preferences, from `Configuration` of `GET /Users/{Id}` (ADR 0065).
     * The documentation types it `UserConfiguration[]`: an object or the first object of
     * an array is read. Null when the user has none.
     */
    suspend fun userConfiguration(userId: String): UserConfigurationDto? {
        val user = call(get(server.api("/Users/${segment(userId)}")), UserWithConfiguration.serializer())
        val config = when (val element = user.configuration) {
            is JsonObject -> element
            is JsonArray -> element.firstOrNull() as? JsonObject
            else -> null
        } ?: return null
        return try {
            json.decodeFromJsonElement(UserConfigurationDto.serializer(), config)
        } catch (e: SerializationException) {
            throw EmbyException(EmbyError.InvalidResponse(e.message ?: "JSON invalide"), e)
        } catch (e: IllegalArgumentException) {
            throw EmbyException(EmbyError.InvalidResponse(e.message ?: "JSON invalide"), e)
        }
    }

    suspend fun logout() {
        requireToken()
        execute(post(server.api("/Sessions/Logout"), ByteArray(0).toRequestBody()), authenticated = true).close()
    }

    suspend fun views(userId: String): QueryResult<BaseItemDto> =
        call(get(server.api("/Users/${segment(userId)}/Views")), QueryResult.serializer(BaseItemDto.serializer()))

    /**
     * Films and episodes started but not finished, most recent first. With the Windows
     * hero `Fields` (ADR 0015): one request serves the "Reprendre" shelf and the hero (ADR 0059).
     */
    suspend fun resumeItems(userId: String, limit: Int): QueryResult<BaseItemDto> {
        val url = server.api("/Users/${segment(userId)}/Items/Resume").newBuilder()
            .addQueryParameter("Limit", limit.toString())
            .addQueryParameter("MediaTypes", "Video")
            .addQueryParameter("IncludeItemTypes", "Movie,Episode")
            .addQueryParameter("Fields", HERO_FIELDS)
            .addQueryParameter("EnableUserData", "true")
            .addQueryParameter("ImageTypeLimit", "1")
            .addQueryParameter("EnableImageTypes", "Primary,Backdrop,Thumb,Logo")
            .build()
        return call(get(url), QueryResult.serializer(BaseItemDto.serializer()))
    }

    /**
     * Home hero (ADR 0015, 0059): films and series not watched yet that have a backdrop,
     * most recently added first; the Windows `hero_latest` query, without the logos.
     */
    suspend fun heroLatest(userId: String, limit: Int): QueryResult<BaseItemDto> {
        val url = server.api("/Users/${segment(userId)}/Items").newBuilder()
            .addQueryParameter("Recursive", "true")
            .addQueryParameter("IncludeItemTypes", "Movie,Series")
            .addQueryParameter("IsPlayed", "false")
            .addQueryParameter("ImageTypes", "Backdrop")
            .addQueryParameter("SortBy", "DateCreated")
            .addQueryParameter("SortOrder", "Descending")
            .addQueryParameter("Limit", limit.toString())
            .addQueryParameter("Fields", HERO_FIELDS)
            .addQueryParameter("EnableUserData", "true")
            .addQueryParameter("ImageTypeLimit", "1")
            .addQueryParameter("EnableImageTypes", "Primary,Backdrop,Logo")
            .build()
        return call(get(url), QueryResult.serializer(BaseItemDto.serializer()))
    }

    /**
     * First episode of a series not watched yet, in the series' order; the Windows
     * `first_unplayed_episode` query (ADR 0015). Unknown series: 404.
     */
    suspend fun firstUnplayedEpisode(userId: String, seriesId: String): QueryResult<BaseItemDto> {
        val url = server.api("/Shows/${segment(seriesId)}/Episodes").newBuilder()
            .addQueryParameter("UserId", segment(userId))
            .addQueryParameter("IsPlayed", "false")
            .addQueryParameter("Limit", "1")
            .addQueryParameter("EnableUserData", "true")
            .addQueryParameter("EnableImageTypes", "Primary")
            .addQueryParameter("ImageTypeLimit", "1")
            .build()
        return call(get(url), QueryResult.serializer(BaseItemDto.serializer()))
    }

    /** Next episode to watch of each series in progress (`/Shows/NextUp`), the Windows query (ADR 0010). */
    suspend fun nextUp(userId: String, limit: Int): QueryResult<BaseItemDto> {
        val url = server.api("/Shows/NextUp").newBuilder()
            .addQueryParameter("UserId", segment(userId))
            .addQueryParameter("Limit", limit.toString())
            .addQueryParameter("Fields", "PrimaryImageAspectRatio")
            .addQueryParameter("EnableUserData", "true")
            .addQueryParameter("ImageTypeLimit", "1")
            .addQueryParameter("EnableImageTypes", "Primary,Backdrop,Thumb")
            .build()
        return call(get(url), QueryResult.serializer(BaseItemDto.serializer()))
    }

    /** Sections of the user's home screen (`/Users/{Id}/HomeSections`, a bare array on Emby 4.10.1.0). */
    suspend fun homeSections(userId: String): List<HomeSectionDto> =
        call(
            get(server.api("/Users/${segment(userId)}/HomeSections")),
            kotlinx.serialization.builtins.ListSerializer(HomeSectionDto.serializer()),
        )

    /**
     * Items of one home section (`/Users/{Id}/Sections/{Id}/Items`). On Emby 4.10.1.0 the
     * "resume" section holds the next episodes `/Shows/NextUp` omits (ADR 0010).
     */
    suspend fun sectionItems(userId: String, sectionId: String, limit: Int): QueryResult<BaseItemDto> {
        val url = server.api("/Users/${segment(userId)}/Sections/${segment(sectionId)}/Items").newBuilder()
            .addQueryParameter("Limit", limit.toString())
            .addQueryParameter("Fields", "PrimaryImageAspectRatio")
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

    /**
     * One page of a library's films or series, sorted and filtered as the Windows grid
     * asks; [itemTypes] is `Movie` or `Series`. The server ignores an unknown `ParentId`
     * and answers the whole server: pass library ids read from [views] only.
     */
    suspend fun libraryItems(
        userId: String,
        parentId: String,
        itemTypes: String,
        startIndex: Int,
        limit: Int,
        options: ItemsOptions = ItemsOptions(),
    ): QueryResult<BaseItemDto> {
        val builder = libraryQuery(userId, parentId, itemTypes, options)
            .addQueryParameter("StartIndex", startIndex.toString())
            .addQueryParameter("Limit", limit.toString())
            .addQueryParameter("Fields", "ProductionYear")
            .addQueryParameter("EnableUserData", "true")
            .addQueryParameter("ImageTypeLimit", "1")
            .addQueryParameter("EnableImageTypes", "Primary")
        return call(get(builder.build()), QueryResult.serializer(BaseItemDto.serializer()))
    }

    /**
     * Every title of the grid's query, in its order and filters, with `SortName` only: the
     * letter index of the jump (ADR 0070). No images, no user data, no paging.
     */
    suspend fun sortNames(userId: String, parentId: String, itemTypes: String, options: ItemsOptions): List<SortNameDto> {
        val url = libraryQuery(userId, parentId, itemTypes, options)
            .addQueryParameter("Fields", "SortName")
            .addQueryParameter("EnableImages", "false")
            .addQueryParameter("EnableUserData", "false")
            .build()
        return call(get(url), QueryResult.serializer(SortNameDto.serializer())).items
    }

    /** The library grid's items query: parent, types, sort and filters, shared by its pages and its letter index. */
    private fun libraryQuery(userId: String, parentId: String, itemTypes: String, options: ItemsOptions): HttpUrl.Builder {
        val builder = server.api("/Users/${segment(userId)}/Items").newBuilder()
            .addQueryParameter("ParentId", segment(parentId))
            .addQueryParameter("Recursive", "true")
            .addQueryParameter("IncludeItemTypes", itemTypes)
            .addQueryParameter("SortBy", options.sort.sortBy)
            .addQueryParameter("SortOrder", if (options.descending) "Descending" else "Ascending")
        options.filters()?.let { builder.addQueryParameter("Filters", it) }
        options.genre?.takeIf(::isUsableGenre)?.let { builder.addQueryParameter("Genres", it) }
        return builder
    }

    /**
     * Films, series or episodes whose name matches [term], across every library the user
     * can see (ADR 0011, 0020); the Windows query. [itemType] is `Movie`, `Series` or
     * `Episode`; episodes come with the 16:9 images of the home cards.
     */
    suspend fun search(userId: String, term: String, itemType: String, limit: Int): QueryResult<BaseItemDto> {
        val episode = itemType == "Episode"
        val url = server.api("/Users/${segment(userId)}/Items").newBuilder()
            .addQueryParameter("SearchTerm", term)
            .addQueryParameter("Recursive", "true")
            .addQueryParameter("IncludeItemTypes", itemType)
            .addQueryParameter("Limit", limit.toString())
            .addQueryParameter("Fields", if (episode) "PrimaryImageAspectRatio" else "PrimaryImageAspectRatio,ProductionYear")
            .addQueryParameter("EnableImageTypes", if (episode) "Primary,Backdrop,Thumb" else "Primary")
            .addQueryParameter("ImageTypeLimit", "1")
            .addQueryParameter("EnableUserData", "true")
            .build()
        return call(get(url), QueryResult.serializer(BaseItemDto.serializer()))
    }

    /** Genre names used by a library's films or series, by name. */
    suspend fun genres(userId: String, parentId: String, itemTypes: String): List<String> {
        val url = server.api("/Genres").newBuilder()
            .addQueryParameter("UserId", segment(userId))
            .addQueryParameter("ParentId", segment(parentId))
            .addQueryParameter("IncludeItemTypes", itemTypes)
            .addQueryParameter("Recursive", "true")
            .addQueryParameter("SortBy", "SortName")
            .addQueryParameter("SortOrder", "Ascending")
            .addQueryParameter("EnableImages", "false")
            .addQueryParameter("EnableUserData", "false")
            .build()
        return call(get(url), QueryResult.serializer(BaseItemDto.serializer())).items.map { it.name }.filter(::isUsableGenre).distinct()
    }

    /** One series or film, with its overview, length and user data. */
    suspend fun item(userId: String, itemId: String): BaseItemDto {
        val url = server.api("/Users/${segment(userId)}/Items/${segment(itemId)}").newBuilder()
            .addQueryParameter("Fields", "Overview")
            .build()
        return call(get(url), BaseItemDto.serializer())
    }

    /**
     * Films and series the server finds close to [itemId] (`/Items/{Id}/Similar`, ADR 0063),
     * with the posters and user data of the library grid.
     */
    suspend fun similar(userId: String, itemId: String, limit: Int): QueryResult<BaseItemDto> {
        val url = server.api("/Items/${segment(itemId)}/Similar").newBuilder()
            .addQueryParameter("UserId", segment(userId))
            .addQueryParameter("Limit", limit.toString())
            .addQueryParameter("IncludeItemTypes", "Movie,Series")
            .addQueryParameter("Fields", "PrimaryImageAspectRatio,ProductionYear")
            .addQueryParameter("EnableUserData", "true")
            .addQueryParameter("ImageTypeLimit", "1")
            .addQueryParameter("EnableImageTypes", "Primary")
            .build()
        return call(get(url), QueryResult.serializer(BaseItemDto.serializer()))
    }

    /**
     * The films and series of one person in the user's library (ADR 0068): the items query
     * filtered by `PersonIds`, built by [personItemsQuery]. A person is an item too: its
     * overview comes from [item] with the person's id.
     */
    suspend fun personItems(userId: String, personId: String, limit: Int = PERSON_TITLES_LIMIT): QueryResult<BaseItemDto> {
        val builder = server.api("/Users/${segment(userId)}/Items").newBuilder()
        personItemsQuery(segment(personId), limit).forEach { (name, value) -> builder.addQueryParameter(name, value) }
        return call(get(builder.build()), QueryResult.serializer(BaseItemDto.serializer()))
    }

    /**
     * The titles of one collection (BoxSet, ADR 0071): the items query with the BoxSet as
     * `ParentId`, built by [collectionItemsQuery].
     */
    suspend fun collectionItems(userId: String, collectionId: String, limit: Int = COLLECTION_TITLES_LIMIT): QueryResult<BaseItemDto> {
        val builder = server.api("/Users/${segment(userId)}/Items").newBuilder()
        collectionItemsQuery(segment(collectionId), limit).forEach { (name, value) -> builder.addQueryParameter(name, value) }
        return call(get(builder.build()), QueryResult.serializer(BaseItemDto.serializer()))
    }

    /**
     * One item without `Fields`, as the Windows client reads it for segments (ADR 0030):
     * Emby 4.10.2.0 then returns its `Chapters` and `ProviderIds`.
     */
    suspend fun fullItem(userId: String, itemId: String): BaseItemDto =
        call(get(server.api("/Users/${segment(userId)}/Items/${segment(itemId)}")), BaseItemDto.serializer())

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

    /**
     * Episodes around one of a series, across seasons (ADR 0016). Emby 4.10.1.0 answers
     * up to 5 episodes before it then the rest of the series; no images, but user data
     * for the resume point of the next one.
     */
    suspend fun adjacentEpisodes(userId: String, seriesId: String, episodeId: String): List<BaseItemDto> {
        val url = server.api("/Shows/${segment(seriesId)}/Episodes").newBuilder()
            .addQueryParameter("UserId", segment(userId))
            .addQueryParameter("AdjacentTo", segment(episodeId))
            .addQueryParameter("EnableImages", "false")
            .addQueryParameter("EnableUserData", "true")
            .build()
        return call(get(url), QueryResult.serializer(BaseItemDto.serializer())).items
    }

    /**
     * Playback negotiation (ADR 0007): the server answers with its sources for this profile,
     * only [mediaSourceId]'s when one version is asked for (ADR 0058).
     */
    suspend fun playbackInfo(
        userId: String,
        itemId: String,
        profile: DeviceProfile,
        mediaSourceId: String? = null,
    ): PlaybackInfoResponse {
        if (mediaSourceId != null && !isSourceId(mediaSourceId)) {
            throw EmbyException(EmbyError.InvalidResponse("version invalide"))
        }
        val id = segment(userId)
        val url = server.api("/Items/${segment(itemId)}/PlaybackInfo").newBuilder()
            .addQueryParameter("UserId", id)
            .build()
        val body = json.encodeToString(
            PlaybackInfoRequest.serializer(),
            PlaybackInfoRequest(
                userId = id,
                maxStreamingBitrate = profile.maxStreamingBitrate,
                deviceProfile = profile,
                mediaSourceId = mediaSourceId,
            ),
        )
        return call(post(url, body.toRequestBody(JSON_TYPE)), PlaybackInfoResponse.serializer())
    }

    /** Playback report (ADR 0008); the server answers 204 without a body. */
    suspend fun reportPlayback(report: Report) {
        requireToken()
        val body = json.encodeToString(PlaybackReport.serializer(), report.body)
        execute(post(server.api(report.kind.path), body.toRequestBody(JSON_TYPE)), authenticated = true).close()
    }

    /**
     * Seek thumbnails of an item (ADR 0069): `GET /Videos/{Id}/index.bif?Width=`, a Roku BIF
     * file, read whole into memory. Null on 404, the answer of a server that has extracted
     * none. A file announced or read beyond [maxBytes] throws [TooLargeException] without
     * reading the rest.
     */
    suspend fun thumbnailBif(itemId: String, width: Int, maxBytes: Int): ByteArray? {
        requireToken()
        val url = server.api("/Videos/${segment(itemId)}/index.bif").newBuilder()
            .addQueryParameter("Width", width.toString())
            .build()
        val res = try {
            execute(request(url).header("Accept", "application/octet-stream").get().build(), authenticated = true)
        } catch (e: EmbyException) {
            if (e.error == EmbyError.NotFound) return null
            throw e
        }
        return res.use { r ->
            if (r.body.contentLength() > maxBytes) throw TooLargeException(maxBytes)
            try {
                withContext(Dispatchers.IO) {
                    val source = r.body.source()
                    val buffer = okio.Buffer()
                    while (source.read(buffer, 64 * 1024L) != -1L) {
                        if (buffer.size > maxBytes) throw TooLargeException(maxBytes)
                    }
                    buffer.readByteArray()
                }
            } catch (e: IOException) {
                throw EmbyException(EmbyError.Unreachable, e)
            }
        }
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
