package io.github.quentinpn.viewer.tv.player

import io.github.quentinpn.viewer.tv.emby.EmbyClient
import io.github.quentinpn.viewer.tv.emby.EmbyException
import io.github.quentinpn.viewer.tv.emby.TooLargeException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

/** Width asked of `index.bif`, the usual Emby thumbnail width; not confirmed by the documentation. */
const val THUMBNAIL_WIDTH = 320

/** Why a file has no seek thumbnails, for the log line. */
enum class NoThumbnails(val reason: String) {
    /** 404: the server has not extracted any for this item, or does not offer them. */
    Missing("not on the server"),

    /** A BIF with no image: the server answers one for an item it has extracted none for. */
    Empty("none extracted on the server"),

    /** Larger than [MAX_BIF_BYTES]: refused without reading the rest. */
    TooLarge("too large"),

    /** Not a BIF file, or one whose index does not hold. */
    Unreadable("unreadable"),

    /** Network or other server error: the rest of this file goes without. */
    Failed("request failed"),
}

/** What [SeekThumbnails] reports once, after its download. */
sealed interface ThumbnailsOutcome {
    data class Loaded(val count: Int, val intervalMs: Long, val bytes: Int) : ThumbnailsOutcome

    /** [detail]: size and first bytes of an unreadable answer, to tell what the server sent. */
    data class None(val why: NoThumbnails, val detail: String? = null) : ThumbnailsOutcome
}

/** The JPEG of one BIF image, [length] bytes of [bytes] from [offset]; [index] tells two targets on the same image apart. */
class ThumbnailFrame(val index: Int, val bytes: ByteArray, val offset: Int, val length: Int)

/**
 * The seek thumbnails of one file. Nothing is fetched until the first [frameAt];
 * [fetch] then runs once in [scope], so a seek that cancels its own lookup never cancels
 * the download. [fetch] answers the file's bytes, or the reason there are none; every
 * later call answers from memory. [close] drops the bytes; a new file gets a new instance.
 */
class SeekThumbnails(
    private val scope: CoroutineScope,
    private val fetch: suspend () -> Result,
    private val report: (ThumbnailsOutcome) -> Unit,
) {
    /** What [fetch] found: the file's bytes, or why there are none. */
    sealed interface Result {
        class Bytes(val bytes: ByteArray) : Result

        data class None(val why: NoThumbnails) : Result
    }

    private var load: Deferred<Bif?>? = null

    @Volatile private var closed = false

    /** The image at [seconds], or null while there is none for this file. */
    suspend fun frameAt(seconds: Double): ThumbnailFrame? {
        if (closed) return null
        val bif = start().await() ?: return null
        val index = bif.frameAt(seconds)
        val frame = bif.frames[index]
        return ThumbnailFrame(index, bif.bytes, frame.offset, frame.length)
    }

    // Parsed off the main thread: the file can hold thousands of index entries
    @Synchronized
    private fun start(): Deferred<Bif?> = load ?: scope.async(Dispatchers.Default) {
        val outcome = fetch()
        val bif = (outcome as? Result.Bytes)?.bytes?.let(::parseBif)
        report(
            when {
                bif != null -> ThumbnailsOutcome.Loaded(bif.frames.size, bif.intervalMs, bif.bytes.size)
                outcome is Result.None -> ThumbnailsOutcome.None(outcome.why)
                outcome is Result.Bytes && isEmptyBif(outcome.bytes) -> ThumbnailsOutcome.None(NoThumbnails.Empty)
                else -> ThumbnailsOutcome.None(NoThumbnails.Unreadable, (outcome as? Result.Bytes)?.bytes?.let(::firstBytes))
            },
        )
        bif.takeUnless { closed }
    }.also { load = it }

    /** At teardown or before the next file: the download stops and the bytes go. */
    @Synchronized
    fun close() {
        closed = true
        load?.cancel()
        load = null
    }
}

/**
 * [itemId]'s seek thumbnails from [client]: the BIF belongs to the item, whatever the stream
 * (Direct Play or transcode). 404 means none; a file over [MAX_BIF_BYTES] or a failed request
 * means none for this file, never an error shown or a retry.
 */
suspend fun downloadThumbnails(client: EmbyClient, itemId: String): SeekThumbnails.Result = try {
    client.thumbnailBif(itemId, THUMBNAIL_WIDTH, MAX_BIF_BYTES)?.let { SeekThumbnails.Result.Bytes(it) }
        ?: SeekThumbnails.Result.None(NoThumbnails.Missing)
} catch (_: TooLargeException) {
    SeekThumbnails.Result.None(NoThumbnails.TooLarge)
} catch (_: EmbyException) {
    SeekThumbnails.Result.None(NoThumbnails.Failed)
}

/** The one log line of a file's thumbnails: counts and reasons only, never the URL. */
fun thumbnailsLog(outcome: ThumbnailsOutcome): String = when (outcome) {
    is ThumbnailsOutcome.Loaded -> "seek thumbnails: ${outcome.count} images every ${outcome.intervalMs} ms (${outcome.bytes / 1024} KB)"
    is ThumbnailsOutcome.None -> "seek thumbnails: none (${outcome.why.reason})" + (outcome.detail?.let { ", $it" } ?: "")
}

/** "1234 bytes starting 89 42 49 46 ...": the size and the first 16 bytes in hex, never decoded as text. */
internal fun firstBytes(bytes: ByteArray): String =
    "${bytes.size} bytes starting " + bytes.take(16).joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
