package io.github.quentinpn.viewer.tv.player

import java.net.URI

/**
 * A source the player may open: an absolute local path or an http(s) URL.
 * Mirrors `MediaSource::parse` on Windows: mpv protocols such as
 * `av://`, `edl://` or `file://` are refused so callers cannot reach them.
 */
sealed class MediaSource {
    abstract val uri: String

    data class LocalFile(override val uri: String) : MediaSource()

    data class Http(override val uri: String) : MediaSource()

    companion object {
        fun parse(raw: String): MediaSource? {
            val value = raw.trim()
            if (value.isEmpty() || value.any { it.isISOControl() }) return null
            if (value.startsWith("/")) return LocalFile(value)
            val parsed = runCatching { URI(value) }.getOrNull() ?: return null
            val scheme = parsed.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            if (parsed.host.isNullOrEmpty()) return null
            return Http(value)
        }
    }
}

private val SECRET_PATTERNS = listOf(
    Regex("(?i)(api_?key=)[^&\\s]+"),
    Regex("(?i)(X-Emby-Token:\\s*)\\S+"),
    Regex("(?i)(X-Emby-Authorization:\\s*).+"),
)

/** Masks tokens before a libmpv log line reaches logcat. */
fun redactSecrets(line: String): String =
    SECRET_PATTERNS.fold(line) { acc, pattern -> pattern.replace(acc) { "${it.groupValues[1]}***" } }

/** Any address with a scheme, up to the next blank: a link, a redirect target. */
private val ADDRESS = Regex("[A-Za-z][A-Za-z0-9+.-]*://\\S+")

/**
 * [redactSecrets], then every address and each of [values] masked: a Stremio stream's link, where it
 * redirects and the headers it asks for carry a debrid key.
 */
fun redactStreamSecrets(line: String, values: Collection<String>): String {
    val masked = values.filter { it.isNotEmpty() }.fold(redactSecrets(line)) { acc, value -> acc.replace(value, "***") }
    return ADDRESS.replace(masked, "***")
}
