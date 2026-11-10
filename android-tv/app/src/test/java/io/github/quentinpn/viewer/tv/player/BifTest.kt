package io.github.quentinpn.viewer.tv.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

private val MAGIC = intArrayOf(0x89, 0x42, 0x49, 0x46, 0x0d, 0x0a, 0x1a, 0x0a)

/**
 * A BIF as the Roku specification lays it out: header, N + 1 index entries ending with
 * 0xffffffff, then the images. [times] are in units of [multiplier] ms.
 */
internal fun bif(
    images: List<ByteArray>,
    times: List<Long> = images.indices.map { it * 10L },
    multiplier: Int = 1000,
    version: Int = 0,
    endMarker: Long = 0xFFFFFFFFL,
): ByteArray {
    val out = ByteArrayOutputStream()
    fun u32(v: Long) = repeat(4) { out.write(((v shr (8 * it)) and 0xFF).toInt()) }
    MAGIC.forEach(out::write)
    u32(version.toLong())
    u32(images.size.toLong())
    u32(multiplier.toLong())
    repeat(44) { out.write(0) }
    var offset = 64L + (images.size + 1) * 8L
    images.forEachIndexed { i, image ->
        u32(times[i])
        u32(offset)
        offset += image.size
    }
    u32(endMarker)
    u32(offset)
    images.forEach(out::write)
    return out.toByteArray()
}

private fun jpeg(tag: Int) = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), tag.toByte(), 0xFF.toByte(), 0xD9.toByte())

class BifTest {
    @Test
    fun readsHeaderIndexAndImageRanges() {
        val images = listOf(jpeg(1), jpeg(2), jpeg(3))
        val parsed = parseBif(bif(images))!!
        assertEquals(listOf(0L, 10_000L, 20_000L), parsed.frames.map { it.timeMs })
        assertEquals(10_000L, parsed.intervalMs)
        parsed.frames.forEachIndexed { i, frame ->
            assertArrayEquals(images[i], parsed.bytes.copyOfRange(frame.offset, frame.offset + frame.length))
        }
    }

    @Test
    fun zeroMultiplierMeansOneSecond() {
        val parsed = parseBif(bif(listOf(jpeg(1), jpeg(2)), times = listOf(0, 5), multiplier = 0))!!
        assertEquals(5_000L, parsed.frames[1].timeMs)
    }

    @Test
    fun multiplierScalesTimestamps() {
        val parsed = parseBif(bif(listOf(jpeg(1), jpeg(2)), times = listOf(0, 4), multiplier = 2500))!!
        assertEquals(10_000L, parsed.frames[1].timeMs)
    }

    @Test
    fun positionMapsToTheLastImageAtOrBeforeIt() {
        val parsed = parseBif(bif(List(5) { jpeg(it) }))!!
        assertEquals(0, parsed.frameAt(0.0))
        assertEquals(0, parsed.frameAt(-3.0))
        assertEquals(0, parsed.frameAt(9.99))
        assertEquals(1, parsed.frameAt(10.0))
        assertEquals(2, parsed.frameAt(25.0))
        assertEquals(4, parsed.frameAt(40.0))
        // Past the last image: the last one
        assertEquals(4, parsed.frameAt(10_000.0))
    }

    @Test
    fun firstImageLaterThanZeroStillShowsBeforeIt() {
        val parsed = parseBif(bif(listOf(jpeg(1), jpeg(2)), times = listOf(3, 13)))!!
        assertEquals(0, parsed.frameAt(1.0))
        assertEquals(1, parsed.frameAt(13.0))
    }

    @Test
    fun missingEndMarkerIsTolerated() {
        assertNotNull(parseBif(bif(listOf(jpeg(1)), endMarker = 0)))
    }

    @Test
    fun emptyImagesAreSkipped() {
        val parsed = parseBif(bif(listOf(jpeg(1), ByteArray(0), jpeg(3))))!!
        assertEquals(listOf(0L, 20_000L), parsed.frames.map { it.timeMs })
    }

    @Test
    fun refusesWhatIsNotABif() {
        assertNull(parseBif(ByteArray(0)))
        assertNull(parseBif(ByteArray(63)))
        assertNull(parseBif("<html>404</html>".padEnd(200).toByteArray()))
        val wrongMagic = bif(listOf(jpeg(1))).also { it[1] = 'X'.code.toByte() }
        assertNull(parseBif(wrongMagic))
        assertNull(parseBif(bif(listOf(jpeg(1)), version = 1)))
        // No images
        assertNull(parseBif(bif(emptyList())))
        // Only empty images
        assertNull(parseBif(bif(listOf(ByteArray(0)))))
    }

    @Test
    fun refusesTruncatedFiles() {
        val whole = bif(List(3) { jpeg(it) })
        // Cut inside the images: the last offset points past the end
        assertNull(parseBif(whole.copyOf(whole.size - 1)))
        // Cut inside the index
        assertNull(parseBif(whole.copyOf(64 + 8)))
        // Header only
        assertNull(parseBif(whole.copyOf(64)))
    }

    @Test
    fun refusesCorruptIndexes() {
        // A count far larger than the file
        val huge = bif(listOf(jpeg(1))).also { it[12] = 0xFF.toByte(); it[13] = 0xFF.toByte(); it[14] = 0xFF.toByte(); it[15] = 0xFF.toByte() }
        assertNull(parseBif(huge))
        // Timestamps going back
        assertNull(parseBif(bif(listOf(jpeg(1), jpeg(2)), times = listOf(10, 5))))
        // The end marker before the last image
        assertNull(parseBif(bif(listOf(jpeg(1), jpeg(2)), times = listOf(0, 0xFFFFFFFFL))))
        // An offset inside the header or index
        val intoIndex = bif(listOf(jpeg(1))).also { it[68] = 8; it[69] = 0; it[70] = 0; it[71] = 0 }
        assertNull(parseBif(intoIndex))
        // Offsets going back
        val backwards = bif(listOf(jpeg(1), jpeg(2))).also { it[76] = (it[68] - 1).toByte() }
        assertNull(parseBif(backwards))
    }

    @Test
    fun refusesFilesOverTheCap() {
        val big = bif(listOf(ByteArray(MAX_BIF_BYTES)))
        assertNull(parseBif(big))
    }
}
