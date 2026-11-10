package io.github.quentinpn.viewer.tv.player

// Seek thumbnails (ADR 0069): Emby's `GET /Videos/{Id}/index.bif` answers a Roku BIF
// file. Layout, little-endian (Roku, "BIF file specification"): bytes 0-7 the magic
// number, 8-11 the version (0), 12-15 the image count N, 16-19 the timestamp multiplier
// in ms (0 means 1000), 20-63 reserved, then from byte 64 N + 1 entries of two u32,
// timestamp and offset of the image; the last entry's timestamp is 0xffffffff and its
// offset the end of the last image. Each image is a JPEG.

/** Above this, the file is refused unread: 30 MB is hours of 320 px images, one every 10 s. */
const val MAX_BIF_BYTES = 30 * 1024 * 1024

private val MAGIC = byteArrayOf(0x89.toByte(), 0x42, 0x49, 0x46, 0x0d, 0x0a, 0x1a, 0x0a)
private const val HEADER_BYTES = 64
private const val ENTRY_BYTES = 8
private const val DEFAULT_MULTIPLIER_MS = 1000L
private const val END_MARKER = 0xFFFFFFFFL

/** One image: shown from [timeMs] of the file, [length] bytes of JPEG from [offset]. */
data class BifFrame(val timeMs: Long, val offset: Int, val length: Int)

/** A parsed BIF: [bytes] is the whole file, [frames] index into it, in time order. */
class Bif(val bytes: ByteArray, val frames: List<BifFrame>) {
    /** The image to show at [seconds]: the last one starting at or before it, the first before them all. */
    fun frameAt(seconds: Double): Int {
        val target = (seconds.coerceAtLeast(0.0) * 1000).toLong()
        var low = 0
        var high = frames.size - 1
        var found = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (frames[mid].timeMs <= target) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    /** Average spacing of the images, for the log. */
    val intervalMs get() = if (frames.size < 2) 0L else (frames.last().timeMs - frames.first().timeMs) / (frames.size - 1)
}

/**
 * Reads [bytes] as a BIF; null when it is not one, a version other than 0, empty, larger
 * than [MAX_BIF_BYTES], or when its index points outside the file or goes back in time.
 * The last entry may lack the 0xffffffff marker; its offset still ends the last image.
 * Images of zero length are skipped.
 */
fun parseBif(bytes: ByteArray): Bif? {
    if (bytes.size < HEADER_BYTES || bytes.size > MAX_BIF_BYTES) return null
    if (!MAGIC.indices.all { bytes[it] == MAGIC[it] }) return null
    if (u32(bytes, 8) != 0L) return null
    val count = u32(bytes, 12)
    if (count == 0L) return null
    val indexEnd = HEADER_BYTES + (count + 1) * ENTRY_BYTES
    if (indexEnd > bytes.size) return null
    val multiplier = u32(bytes, 16).takeIf { it != 0L } ?: DEFAULT_MULTIPLIER_MS
    val frames = ArrayList<BifFrame>(count.toInt())
    var previousTime = -1L
    for (i in 0 until count.toInt()) {
        val at = HEADER_BYTES + i * ENTRY_BYTES
        val time = u32(bytes, at)
        val start = u32(bytes, at + 4)
        val end = u32(bytes, at + ENTRY_BYTES + 4)
        if (time == END_MARKER || time < previousTime) return null
        if (start < indexEnd || end < start || end > bytes.size) return null
        previousTime = time
        if (end > start) frames += BifFrame(time * multiplier, start.toInt(), (end - start).toInt())
    }
    return if (frames.isEmpty()) null else Bif(bytes, frames)
}

/**
 * A well-formed BIF of version 0 holding no image: what Emby 4.10 answers, 72 bytes, for an
 * item whose thumbnails were never extracted (seen on the Shield, ADR 0069).
 */
fun isEmptyBif(bytes: ByteArray): Boolean =
    bytes.size >= HEADER_BYTES && MAGIC.indices.all { bytes[it] == MAGIC[it] } && u32(bytes, 8) == 0L && u32(bytes, 12) == 0L

private fun u32(bytes: ByteArray, at: Int): Long =
    (bytes[at].toLong() and 0xFF) or
        ((bytes[at + 1].toLong() and 0xFF) shl 8) or
        ((bytes[at + 2].toLong() and 0xFF) shl 16) or
        ((bytes[at + 3].toLong() and 0xFF) shl 24)
