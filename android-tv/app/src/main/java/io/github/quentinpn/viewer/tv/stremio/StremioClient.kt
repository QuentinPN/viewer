package io.github.quentinpn.viewer.tv.stremio

import android.util.Log
import io.github.quentinpn.viewer.tv.request.viewerJson
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.Duration
import kotlin.coroutines.resumeWithException

private const val TAG = "Stremio"

/** Larger answers are refused (a real collection is under 100 KiB). */
private const val MAX_BODY_BYTES = 10L * 1024 * 1024

private val STREAM_ID = Regex("^tt[0-9]{5,}(:[0-9]+:[0-9]+)?$")

/**
 * Typed Stremio HTTP client: the account API (login, addon collection) and the addons' stream resource.
 *
 * TLS validated, no redirect followed (one could carry the authKey or an addon's key to another host), no
 * retry; connection 5 s, then a deadline per call: [apiTimeout] for the API, [streamTimeout] per addon. The
 * API answers a refusal (wrong password, unknown session) with HTTP 200 and an `error` object: that is
 * [StremioErrorKind.Rejected]. No answer is [StremioErrorKind.Unreachable]; any other unusable answer
 * [StremioErrorKind.Server]. No error holds an address, a key or a URL; [log] gets addon names only.
 */
class StremioClient(
    http: OkHttpClient,
    private val userAgent: String,
    private val apiBase: HttpUrl = API_BASE.toHttpUrl(),
    apiTimeout: Duration = Duration.ofSeconds(15),
    streamTimeout: Duration = Duration.ofSeconds(10),
    private val log: (String) -> Unit = { Log.w(TAG, it) },
) {
    private val base = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private val api = base.newBuilder().callTimeout(apiTimeout).build()
    private val addonHttp = base.newBuilder().callTimeout(streamTimeout).build()

    /** `POST /api/login`. The password is sent once and kept nowhere. */
    suspend fun login(email: String, password: String): AuthKey {
        val body = buildJsonObject {
            put("type", "Login")
            put("email", email)
            put("password", password)
            put("facebook", false)
        }
        val result = call("login", body, login = true) as? JsonObject
        val key = result?.get("authKey").string()?.takeIf { it.isNotEmpty() } ?: stremioError(StremioErrorKind.Server)
        return AuthKey(key)
    }

    /**
     * `POST /api/addonCollectionGet`: the account's addons, in collection order. An addon whose manifest or
     * address is refused is skipped and logged by name (by position when it has none).
     */
    suspend fun addons(key: AuthKey): List<Addon> {
        val body = buildJsonObject {
            put("type", "AddonCollectionGet")
            put("authKey", key.value)
            put("update", true)
        }
        val result = call("addonCollectionGet", body, login = false) as? JsonObject ?: stremioError(StremioErrorKind.Server)
        return (result["addons"] as? JsonArray).orEmpty().mapIndexedNotNull { i, entry ->
            val e = entry as? JsonObject
            try {
                parseAddon(e?.get("transportUrl").string(), e?.get("manifest"))
            } catch (x: StremioException) {
                log("addon skipped: ${x.error.addon ?: "#$i"} (${x.error.kind})")
                null
            }
        }
    }

    /**
     * `GET {base}/stream/{type}/{id}.json` on [addon]. Only entries with an http(s) `url` are playable; the
     * others are counted in [StreamsResult.unsupported]. The `:` of an episode id stays unescaped, the form
     * every captured addon answers.
     */
    suspend fun streams(addon: Addon, type: ContentType, id: String): StreamsResult {
        if (!STREAM_ID.matches(id)) stremioError(StremioErrorKind.InvalidId)
        val url = "${addon.base}/stream/${type.wire}/$id.json".toHttpUrlOrNull()
            ?: stremioError(StremioErrorKind.Server, addon = addon.name)
        val text = fetch(addonHttp, request(url).get().build(), addon.name)
        val body = parse(text) as? JsonObject ?: stremioError(StremioErrorKind.Server, addon = addon.name)
        val entries = (body["streams"] as? JsonArray).orEmpty()
        val streams = entries.mapNotNull(::parseStream)
        return StreamsResult(streams, entries.size - streams.size)
    }

    override fun toString() = "StremioClient"

    /** Posts [body] to the API [method] and returns its `result`; any non-null `error` is a refusal. */
    private suspend fun call(method: String, body: JsonObject, login: Boolean): JsonElement {
        val url = apiBase.newBuilder().addPathSegments("api/$method").build()
        val payload = viewerJson.encodeToString(JsonElement.serializer(), body).toRequestBody(JSON)
        val env = parse(fetch(api, request(url).post(payload).build(), null)) as? JsonObject
            ?: stremioError(StremioErrorKind.Server)
        env["error"]?.takeIf { it !is JsonNull }?.let { stremioError(StremioErrorKind.Rejected, login = login) }
        return env["result"]?.takeIf { it !is JsonNull } ?: stremioError(StremioErrorKind.Server)
    }

    private fun request(url: HttpUrl) = Request.Builder().url(url)
        .header("Accept", "application/json")
        .header("User-Agent", userAgent)

    /**
     * Sends [request] and reads its body, at most [MAX_BODY_BYTES]. Cancelling the coroutine cancels the
     * call at once, whatever it is waiting for.
     */
    private suspend fun fetch(client: OkHttpClient, request: Request, addon: String?): String =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    // The IOException is dropped: its message holds the URL
                    override fun onFailure(call: Call, e: IOException) =
                        cont.resumeWithException(StremioException(StremioError(StremioErrorKind.Unreachable, addon)))

                    override fun onResponse(call: Call, response: Response) = cont.resumeWith(
                        try {
                            Result.success(response.use { read(it, addon) })
                        } catch (e: StremioException) {
                            Result.failure(e)
                        } catch (_: IOException) {
                            Result.failure(StremioException(StremioError(StremioErrorKind.Unreachable, addon)))
                        },
                    )
                },
            )
        }

    private fun read(response: Response, addon: String?): String {
        if (!response.isSuccessful) stremioError(StremioErrorKind.Server, addon = addon)
        val source = response.body.source()
        if (source.request(MAX_BODY_BYTES + 1)) stremioError(StremioErrorKind.Server, addon = addon)
        return source.buffer.readUtf8()
    }

    /** The JSON of [text]; null when it is not JSON (the parser's message, which quotes the input, is dropped). */
    private fun parse(text: String): JsonElement? = try {
        viewerJson.parseToJsonElement(text)
    } catch (_: IllegalArgumentException) {
        null
    }

    companion object {
        /** The Stremio account API. */
        const val API_BASE = "https://api.strem.io"
        private val JSON = "application/json".toMediaType()
    }
}
