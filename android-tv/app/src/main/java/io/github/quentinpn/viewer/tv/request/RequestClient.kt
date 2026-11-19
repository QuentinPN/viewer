package io.github.quentinpn.viewer.tv.request

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

internal val viewerJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

/** API major and features this client needs (ADR 0125). */
const val SUPPORTED_MAJOR = "1"
val REQUIRED_FEATURES = listOf("auth.emby-token", "auth.bearer", "auth.sessions", "media.item-id")

/** The Request session token: [toString] never shows it. */
class SessionToken(val value: String) {
    override fun toString() = "SessionToken(***)"

    override fun equals(other: Any?) = other is SessionToken && other.value == value

    override fun hashCode() = value.hashCode()
}

/** The device name sent with the token exchange, shown under « Appareils connectés » (64 characters at most). */
fun deviceLabel(model: String) = "$model (Android TV)".take(MAX_DEVICE_NAME)

private const val MAX_DEVICE_NAME = 64
private const val DEFAULT_RETRY_AFTER_SECONDS = 60L

/**
 * Normalizes what the user typed (ADR 0125): trimmed, no trailing slash, `https://` when no
 * scheme is given. Plain `http://` is only accepted on the local network, so the Emby token of
 * the exchange never travels in clear over the Internet.
 */
fun normalizeAddress(input: String): HttpUrl {
    val text = input.trim()
    if (text.isEmpty()) invalid("adresse vide")
    val withScheme = if ("://" in text) text else "https://$text"
    val scheme = withScheme.substringBefore("://").lowercase()
    if (scheme != "http" && scheme != "https") invalid("schéma « $scheme » non pris en charge")
    if ('?' in withScheme || '#' in withScheme) invalid("l'adresse ne doit contenir ni « ? » ni « # »")
    val url = withScheme.toHttpUrlOrNull() ?: invalid("adresse invalide")
    if (url.username.isNotEmpty() || url.password.isNotEmpty()) invalid("l'adresse ne doit pas contenir d'identifiants")
    if (!url.isHttps && !isPrivateHost(url.host)) {
        invalid("HTTP non chiffré n'est accepté que sur le réseau local, utilise https://")
    }
    return url.newBuilder().encodedPath(url.encodedPath.trimEnd('/').ifEmpty { "/" }).build()
}

private fun invalid(reason: String): Nothing = throw RequestException(RequestError.InvalidAddress(reason))

/** An address as persisted and shown: no trailing slash. */
fun HttpUrl.display(): String = toString().trimEnd('/')

fun ensureCompatible(version: ApiVersion) {
    val missing = REQUIRED_FEATURES.filterNot { it in version.features }
    if (version.apiVersion.substringBefore('.') != SUPPORTED_MAJOR || missing.isNotEmpty()) {
        throw RequestException(RequestError.TooOld(version.apiVersion, missing))
    }
}

/**
 * Hosts where plain `http://` is accepted (ADR 0125): loopback (`localhost`, `127/8`, `::1`),
 * `10/8`, `172.16/12`, `192.168/16` and `*.local`. Nothing else: not link-local, not `.lan`.
 * Never resolves a name.
 */
internal fun isPrivateHost(host: String): Boolean {
    val h = host.lowercase().removePrefix("[").removeSuffix("]")
    if (h == "localhost" || h == "::1" || h.endsWith(".local")) return true
    val parts = h.split('.')
    if (parts.size != 4) return false
    val octets = parts.map { it.toIntOrNull() ?: return false }
    if (octets.any { it !in 0..255 }) return false
    val (a, b) = octets
    return a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
}

/** An id from the server, checked before it goes into a path. */
internal fun pathSegment(id: String): String {
    if (id.isEmpty() || !id.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }) {
        throw RequestException(RequestError.InvalidResponse)
    }
    return id
}

/**
 * Typed calls to one Request server. The token exchange goes without an `Authorization`
 * header; every other call carries the session token as a Bearer, in the header only.
 */
class RequestClient(val base: HttpUrl, private val http: OkHttpClient, private val userAgent: String) {
    suspend fun version(): ApiVersion = call(build("GET", "/api/version"), ApiVersion.serializer())

    /** Trades the Emby identity for a session token. Deliberately sent without `Authorization`. */
    suspend fun exchange(userId: String, embyToken: String, deviceName: String): EmbyTokenSession {
        val body = buildJsonObject {
            put("userId", userId)
            put("accessToken", embyToken)
            put("deviceName", deviceName.take(MAX_DEVICE_NAME))
        }
        return call(build("POST", "/api/auth/emby-token", body = body), EmbyTokenSession.serializer())
    }

    suspend fun <T> get(
        path: String,
        token: SessionToken,
        serializer: KSerializer<T>,
        query: List<Pair<String, String>> = emptyList(),
    ): T = call(build("GET", path, query, token = token), serializer)

    suspend fun <T> send(
        method: String,
        path: String,
        token: SessionToken,
        serializer: KSerializer<T>,
        body: JsonElement? = null,
    ): T = call(build(method, path, token = token, body = body ?: JsonObject(emptyMap())), serializer)

    /** For the calls that answer 204 No Content. */
    suspend fun sendEmpty(method: String, path: String, token: SessionToken) {
        execute(build(method, path, token = token, body = if (method == "POST") JsonObject(emptyMap()) else null)).close()
    }

    private fun build(
        method: String,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        token: SessionToken? = null,
        body: JsonElement? = null,
    ): Request {
        val url = base.newBuilder().apply {
            addPathSegments(path.trimStart('/'))
            query.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
        return Request.Builder().url(url)
            .method(method, body?.let { viewerJson.encodeToString(JsonElement.serializer(), it).toRequestBody(JSON) })
            .header("Accept", "application/json")
            .header("User-Agent", userAgent)
            .apply { token?.let { header("Authorization", "Bearer ${it.value}") } }
            .build()
    }

    private suspend fun <T> call(request: Request, serializer: KSerializer<T>): T = execute(request).use { res ->
        val text = withContext(Dispatchers.IO) { res.body.string() }
        try {
            viewerJson.decodeFromString(serializer, text)
        } catch (e: SerializationException) {
            throw RequestException(RequestError.InvalidResponse, e)
        } catch (e: IllegalArgumentException) {
            throw RequestException(RequestError.InvalidResponse, e)
        }
    }

    /** Maps the status to an error category; the server's `{ "error": "…" }` is shown as is. */
    private suspend fun execute(request: Request): okhttp3.Response {
        val res = try {
            withContext(Dispatchers.IO) { http.newCall(request).execute() }
        } catch (e: IOException) {
            throw RequestException(RequestError.Unreachable, e)
        }
        if (res.isSuccessful) return res
        val message = withContext(Dispatchers.IO) { errorMessage(res.body.string()) }
        val retryAfter = res.header("Retry-After")?.trim()?.toLongOrNull() ?: DEFAULT_RETRY_AFTER_SECONDS
        res.close()
        throw RequestException(
            when {
                res.code == 401 -> RequestError.Rejected(message)
                res.code == 403 -> RequestError.Forbidden(message ?: "Accès refusé par Request.")
                res.code == 404 -> RequestError.NotFound(message ?: "Élément introuvable sur Request.")
                res.code == 429 -> RequestError.RateLimited(retryAfter)
                res.isRedirect -> RequestError.InvalidResponse
                else -> RequestError.Server(res.code, message ?: "Erreur d'Request (HTTP ${res.code}).")
            },
        )
    }

    private fun errorMessage(text: String): String? = try {
        (viewerJson.parseToJsonElement(text) as? JsonObject)?.get("error")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
