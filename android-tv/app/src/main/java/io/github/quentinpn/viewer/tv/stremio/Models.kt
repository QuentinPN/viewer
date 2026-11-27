package io.github.quentinpn.viewer.tv.stremio

import io.github.quentinpn.viewer.tv.request.isPrivateHost
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// The authKey, every addon transportUrl (its path often carries a debrid key) and every stream URL are
// secrets: no class holding one is a data class, and each one's toString masks it.

/** The `{type}` of a stream request. */
enum class ContentType(val wire: String) { Movie("movie"), Series("series") }

/** The session key of the Stremio account. */
class AuthKey(internal val value: String) {
    override fun toString() = "AuthKey(***)"
}

/**
 * One entry of a manifest's `resources`: the short form `"stream"` (types and idPrefixes null: the
 * manifest's apply) or the object form `{name, types, idPrefixes}`, each field overriding the manifest's
 * when present. An empty list is present: it overrides.
 */
internal class Resource(val name: String, val types: List<String>?, val idPrefixes: List<String>?)

/** An installed addon: its manifest and its base address, the transportUrl without `/manifest.json`. */
class Addon internal constructor(
    val name: String,
    internal val base: String,
    private val resources: List<Resource>,
    private val types: List<String>?,
    private val idPrefixes: List<String>?,
) {
    /**
     * Whether the addon is asked for streams of [id]: it declares the `stream` resource for [type] and, when
     * idPrefixes are declared, one of them starts [id] (none at all, or an empty list: any id).
     */
    fun serves(type: ContentType, id: String): Boolean = resources.any { r ->
        if (r.name != "stream") return@any false
        val served = r.types ?: this.types.orEmpty()
        val prefixes = r.idPrefixes ?: this.idPrefixes.orEmpty()
        type.wire in served && (prefixes.isEmpty() || prefixes.any { id.startsWith(it) })
    }

    override fun toString() = "Addon(***)"
}

private const val MANIFEST_SUFFIX = "/manifest.json"

/**
 * Reads one addon of the collection. An unreadable manifest, or one without a name, is
 * [StremioErrorKind.Server]; a transportUrl that is not `…/manifest.json` over https (http only on the
 * local network), or that holds credentials, a query or a fragment, is [StremioErrorKind.Invalid]. Neither
 * error holds the address.
 */
internal fun parseAddon(transportUrl: String?, manifest: JsonElement?): Addon {
    val m = manifest as? JsonObject ?: stremioError(StremioErrorKind.Server)
    val name = m["name"].string()?.takeIf { it.isNotBlank() } ?: stremioError(StremioErrorKind.Server)
    val address = transportUrl.orEmpty()
    val url = address.takeIf { it.endsWith(MANIFEST_SUFFIX) && '?' !in it && '#' !in it }?.toHttpUrlOrNull()
    if (url == null || url.username.isNotEmpty() || url.password.isNotEmpty() || (!url.isHttps && !isPrivateHost(url.host))) {
        stremioError(StremioErrorKind.Invalid, addon = name)
    }
    // Any other resource form cannot declare a stream resource: skipped
    val resources = (m["resources"] as? JsonArray).orEmpty().mapNotNull { raw ->
        raw.string()?.let { return@mapNotNull Resource(it, null, null) }
        val obj = raw as? JsonObject ?: return@mapNotNull null
        obj["name"].string()?.takeIf { it.isNotEmpty() }?.let { Resource(it, obj["types"].strings(), obj["idPrefixes"].strings()) }
    }
    return Addon(name, address.removeSuffix(MANIFEST_SUFFIX), resources, m["types"].strings(), m["idPrefixes"].strings())
}

private val IMDB_ID = Regex("^tt[0-9]{5,}$")

/**
 * The `{id}` of a stream request: `tt…` for a movie (season and episode 0), `tt…:S:E` for an episode
 * (season ≥ 0, 0 being the specials; episode ≥ 1). Anything else is [StremioErrorKind.InvalidId].
 */
fun streamId(imdb: String, season: Int, episode: Int): String = when {
    !IMDB_ID.matches(imdb) -> stremioError(StremioErrorKind.InvalidId)
    season == 0 && episode == 0 -> imdb
    season >= 0 && episode >= 1 -> "$imdb:$season:$episode"
    else -> stremioError(StremioErrorKind.InvalidId)
}

/**
 * One playable stream of an addon: what the UI may show ([name], [description], [filename], [size]) and,
 * kept inside, the URL and HTTP headers to open it with (a signed debrid link).
 */
class Stream internal constructor(
    val name: String,
    /** The entry's `description`, or its `title` without one; both keep their line breaks. */
    val description: String,
    val filename: String?,
    /** In bytes, null when unknown. */
    val size: Long?,
    internal val url: String,
    /** The headers the addon asks for (`behaviorHints.proxyHeaders.request`). */
    internal val headers: Map<String, String>,
) {
    override fun toString() = "Stream(***)"
}

/**
 * The answer of one addon: its playable streams, in its order, and how many entries cannot be played
 * (torrent without URL, YouTube, external link).
 */
class StreamsResult(val streams: List<Stream>, val unsupported: Int) {
    override fun toString() = "StreamsResult(streams=${streams.size}, unsupported=$unsupported)"
}

/** One playable entry of an addon's `streams`; null when it has no http(s) `url` (an `url` wins over an `infoHash`). */
internal fun parseStream(raw: JsonElement): Stream? {
    val e = raw as? JsonObject ?: return null
    val url = e["url"].string()?.takeIf { it.toHttpUrlOrNull() != null } ?: return null
    // Hints of an odd shape are read leniently: they never make a playable stream unplayable
    val hints = e["behaviorHints"] as? JsonObject
    // `description` replaces the deprecated `title`
    val description = e["description"].string()?.takeIf { it.isNotEmpty() } ?: e["title"].string().orEmpty()
    val request = ((hints?.get("proxyHeaders") as? JsonObject)?.get("request") as? JsonObject).orEmpty()
    val headers = request.mapNotNull { (k, v) -> v.string()?.takeIf { headerSafe(k) && headerSafe(it) }?.let { k to it } }.toMap()
    return Stream(
        name = e["name"].string().orEmpty(),
        description = description,
        filename = hints?.get("filename").string(),
        size = size(hints?.get("videoSize")) ?: size(e["sizeBytes"]),
        url = url,
        headers = headers,
    )
}

/** A byte count given as a JSON number (integer or not); null when absent, not positive or out of range. */
private fun size(raw: JsonElement?): Long? {
    val p = raw as? JsonPrimitive ?: return null
    if (p.isString) return null
    p.longOrNull?.let { return it.takeIf { n -> n > 0 } }
    return p.doubleOrNull?.takeIf { it > 0 && it < (1L shl 62) }?.toLong()
}

/** Refuses an empty header name or value, or one holding a control character (CR/LF would split the request). */
private fun headerSafe(s: String) = s.isNotEmpty() && s.none { it.code < 0x20 || it.code == 0x7f }

internal fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A list of strings, null when absent or not a list. */
private fun JsonElement?.strings(): List<String>? = (this as? JsonArray)?.mapNotNull { it.string() }
