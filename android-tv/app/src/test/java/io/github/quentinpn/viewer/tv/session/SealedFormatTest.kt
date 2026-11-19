package io.github.quentinpn.viewer.tv.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The stored token format must stay byte-for-byte the one of earlier versions, or users sign in again. */
class SealedFormatTest {
    @Test
    fun keepsTheSessionKeyAlias() {
        assertEquals("viewer.session", SESSION_KEY_ALIAS)
    }

    @Test
    fun sealsAsBase64IvDotBase64Data() {
        // android.util.Base64.NO_WRAP of [0,1,2] and [3,4,5]
        assertEquals("AAEC.AwQF", SealedFormat.seal(byteArrayOf(0, 1, 2), byteArrayOf(3, 4, 5)))
        // Padding is kept
        assertEquals("AQ==.AQI=", SealedFormat.seal(byteArrayOf(1), byteArrayOf(1, 2)))
    }

    @Test
    fun opensWhatEarlierVersionsWrote() {
        val (iv, data) = SealedFormat.open("AAEC.AwQF")
        assertArrayEquals(byteArrayOf(0, 1, 2), iv)
        assertArrayEquals(byteArrayOf(3, 4, 5), data)
    }

    @Test
    fun refusesAnythingElse() {
        assertThrows(IllegalArgumentException::class.java) { SealedFormat.open("no-dot") }
        assertThrows(IllegalArgumentException::class.java) { SealedFormat.open("a.b.c") }
    }
}
