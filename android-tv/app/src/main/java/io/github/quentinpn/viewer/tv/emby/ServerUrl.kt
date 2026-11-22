package io.github.quentinpn.viewer.tv.emby

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress

/**
 * Normalized Emby server base URL, without the `/emby` API prefix. Same rules as
 * the Windows client: http(s) only, no credentials, query or fragment,
 * and plain HTTP only towards local network addresses.
 */
class ServerUrl private constructor(val url: HttpUrl) {
    val isHttps: Boolean get() = url.isHttps

    /** Reached through a local-network address: picks the "Réseau local" streaming cap. */
    val isLocal: Boolean get() = isLocalHost(url.host)

    /** Base URL without trailing slash, as shown to the user and persisted. */
    override fun toString(): String = url.toString().trimEnd('/')

    /** Absolute URL of an API path, e.g. `api("/Users/AuthenticateByName")`. */
    fun api(path: String): HttpUrl = "$this/emby$path".toHttpUrlOrNull()
        ?: throw EmbyException(EmbyError.InvalidUrl("chemin invalide"))

    override fun equals(other: Any?) = other is ServerUrl && other.url == url

    override fun hashCode() = url.hashCode()

    companion object {
        fun parse(input: String): ServerUrl {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) invalid("adresse vide")
            // Users often type "192.0.2.10:8096" without a scheme
            val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
            val scheme = withScheme.substringBefore("://").lowercase()
            if (scheme != "http" && scheme != "https") invalid("schéma « $scheme » non pris en charge")
            // HttpUrl drops an empty "?" or "#": check the raw text
            if ('?' in withScheme || '#' in withScheme) invalid("l'adresse ne doit contenir ni « ? » ni « # »")
            val url = withScheme.toHttpUrlOrNull() ?: invalid("adresse invalide")
            if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
                invalid("l'adresse ne doit pas contenir d'identifiants")
            }
            // Keep a reverse-proxy prefix, drop a trailing slash and a typed "/emby"
            // or "/web/..." suffix copied from the browser address bar
            var path = url.encodedPath.trimEnd('/')
            val web = Regex("/web(/|$)").find(path)
            if (web != null) path = path.substring(0, web.range.first)
            path = path.removeSuffix("/emby")
            val server = ServerUrl(url.newBuilder().encodedPath(path.ifEmpty { "/" }).build())
            if (!server.isHttps && !isLocalHost(url.host)) {
                throw EmbyException(EmbyError.InsecureRemoteUrl)
            }
            return server
        }

        private fun invalid(reason: String): Nothing = throw EmbyException(EmbyError.InvalidUrl(reason))

        /** Loopback, private, link-local and well-known local DNS suffixes, never resolved. */
        internal fun isLocalHost(host: String): Boolean {
            val h = host.lowercase()
            if (h == "localhost" || listOf(".localhost", ".local", ".lan", ".home.arpa").any { h.endsWith(it) }) {
                return true
            }
            val literal = h.removePrefix("[").removeSuffix("]")
            val isIpLiteral = literal.contains(':') || Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(literal)
            if (!isIpLiteral) return false
            // A literal address: getByName does not touch the network
            val ip = runCatching { InetAddress.getByName(literal) }.getOrNull() ?: return false
            val b = ip.address
            if (ip.isLoopbackAddress || ip.isSiteLocalAddress || ip.isLinkLocalAddress) return true
            // Unique local IPv6 fc00::/7, which isSiteLocalAddress does not cover
            return b.size == 16 && (b[0].toInt() and 0xfe) == 0xfc
        }
    }
}
