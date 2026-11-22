package io.github.quentinpn.viewer.tv.emby

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

// Lost stop reports kept on disk and resent. Pure: time (wall clock, ms) is passed in.

/** Stop reports kept per account, one per item, the oldest forgotten beyond. */
const val MAX_PENDING_STOPS = 5

/**
 * Past this age a kept stop is given up: the older it is, the likelier another device played
 * the item since, and the server would take this older position over the newer one.
 */
const val PENDING_STOP_MAX_AGE_MS = 6 * 3_600_000L

/** Waits between tries while the server stays out of reach: doubled up to the cap. */
const val STOP_RESEND_FIRST_MS = 5_000L
const val STOP_RESEND_CAP_MS = 60_000L

/** A clock set back by more than this leaves the age of a kept stop unknown: it is given up. */
private const val CLOCK_SLACK_MS = 60_000L

/** A stop report the server did not get. [savedAt] is wall-clock time, so it survives a restart. */
@Serializable
data class PendingStop(
    @SerialName("Report") val report: PlaybackReport,
    @SerialName("SavedAt") val savedAt: Long,
)

/** Kept stops belong to one user on one server: never sent under another account. */
fun accountKey(server: ServerUrl, userId: String) = "$server|$userId"

/** Wait after the [failures]-th failed try in a row (1 or more). */
fun resendDelay(failures: Int): Long {
    val doublings = (failures - 1).coerceIn(0, 16)
    return (STOP_RESEND_FIRST_MS shl doublings).coerceAtMost(STOP_RESEND_CAP_MS)
}

fun isStale(stop: PendingStop, now: Long): Boolean {
    val age = now - stop.savedAt
    return age >= PENDING_STOP_MAX_AGE_MS || age < -CLOCK_SLACK_MS
}

fun pruneStops(queue: List<PendingStop>, now: Long) = queue.filterNot { isStale(it, now) }

/** Oldest first; a newer stop of the same item replaces the older one, its position is the one that counts. */
fun enqueueStop(queue: List<PendingStop>, stop: PendingStop, now: Long): List<PendingStop> =
    (pruneStops(queue, now).filter { it.report.itemId != stop.report.itemId } + stop).takeLast(MAX_PENDING_STOPS)

fun withoutItem(queue: List<PendingStop>, itemId: String) = queue.filter { it.report.itemId != itemId }

private val stopsJson = Json { ignoreUnknownKeys = true; explicitNulls = false }
private val stopsSerializer = ListSerializer(PendingStop.serializer())

fun encodeStops(queue: List<PendingStop>): String = stopsJson.encodeToString(stopsSerializer, queue)

/** Unreadable text (older format, damaged file) reads as nothing kept. */
fun decodeStops(text: String?): List<PendingStop> {
    if (text.isNullOrEmpty()) return emptyList()
    return try {
        stopsJson.decodeFromString(stopsSerializer, text)
    } catch (_: SerializationException) {
        emptyList()
    } catch (_: IllegalArgumentException) {
        emptyList()
    }
}

/** Where kept stops live, by [accountKey]; saving an empty list removes the account's entry. */
interface PendingStopStorage {
    fun keys(): Set<String>

    fun load(key: String): List<PendingStop>

    fun save(key: String, stops: List<PendingStop>)
}

/** Network or 5xx: may pass. Any other 4xx, and an expired session, will not. */
fun EmbyError?.isTransient() = this == EmbyError.Unreachable || (this is EmbyError.Server && status >= 500)
