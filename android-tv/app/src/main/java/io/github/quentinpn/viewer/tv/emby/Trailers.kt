package io.github.quentinpn.viewer.tv.emby

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// Trailers of a film or series page (ADR 0083): a file on the server plays in the app,
// a web page opens outside it, only on an allow-list of YouTube hosts.

/** Hosts a remote trailer may open on; any other host is not offered. */
internal val TRAILER_HOSTS = setOf("youtube.com", "www.youtube.com", "youtu.be", "m.youtube.com")

/** What the « Bande-annonce » button tries, in order. */
sealed interface TrailerChoice {
    /** The item has trailer files on the server: listed on press, the first one plays. */
    data object Local : TrailerChoice

    /** A checked web address, opened outside the app. */
    data class Remote(val url: String) : TrailerChoice
}

/**
 * The trailers of [item], the server's files first, then the first remote trailer that
 * passes [trailerUrl]. Empty: no button.
 */
fun trailerChoices(item: BaseItemDto): List<TrailerChoice> {
    val local = if ((item.localTrailerCount ?: 0) > 0) listOf(TrailerChoice.Local) else emptyList()
    val remote = item.remoteTrailers.firstNotNullOfOrNull { trailerUrl(it.url) }?.let { TrailerChoice.Remote(it) }
    return local + listOfNotNull(remote)
}

/**
 * [raw] when it is an `https` address on [TRAILER_HOSTS] on the default port, without user
 * information, spaces or control characters; null otherwise. The address is the server's
 * as is: nothing is added to it, no token above all.
 */
fun trailerUrl(raw: String?): String? {
    val text = raw?.trim() ?: return null
    if (text.isEmpty() || text.length > 2048 || text.any { it.isWhitespace() || it.isISOControl() }) return null
    // Parsed by OkHttp, scheme and host are checked on what it read, not on the text
    if (!text.startsWith("https://", ignoreCase = true)) return null
    val url = text.toHttpUrlOrNull() ?: return null
    if (!url.isHttps || url.port != 443) return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    if (url.host.lowercase() !in TRAILER_HOSTS) return null
    return url.toString()
}

/** The first local trailer with an id that can go into a path, or null. */
fun localTrailerId(trailers: List<BaseItemDto>): String? = trailers.firstOrNull { isItemId(it.id) }?.id

/** Same rule as [EmbyClient.segment]: ASCII letters, digits and `-` only. */
internal fun isItemId(id: String) = id.isNotEmpty() && id.length <= 128 && id.all { it.isLetterOrDigit() && it.code < 128 || it == '-' }

/** What a press on « Bande-annonce » does. */
sealed interface TrailerAction {
    /** Plays this trailer item in the app's player, without any report (ADR 0083). */
    data class Play(val itemId: String) : TrailerAction

    /** Opens this checked address outside the app. */
    data class Open(val url: String) : TrailerAction
}

/**
 * The action of a press: the first local trailer, read with [listLocal], else the remote
 * one; null when none is left. A failed listing falls back to the remote trailer, except
 * an expired session, thrown again so the caller signs out.
 */
suspend fun trailerAction(choices: List<TrailerChoice>, listLocal: suspend () -> List<BaseItemDto>): TrailerAction? {
    for (choice in choices) {
        when (choice) {
            TrailerChoice.Local -> {
                val id = try {
                    localTrailerId(listLocal())
                } catch (e: EmbyException) {
                    if (e.error == EmbyError.SessionExpired) throw e
                    null
                }
                if (id != null) return TrailerAction.Play(id)
            }
            is TrailerChoice.Remote -> return TrailerAction.Open(choice.url)
        }
    }
    return null
}
