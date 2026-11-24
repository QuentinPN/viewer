package io.github.quentinpn.viewer.tv.player

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaBundleTest {
    @Test
    fun everyCertificateComesBackIdenticalFromItsPemBlock() {
        val certs = listOf(ByteArray(200) { it.toByte() }, ByteArray(3) { (it + 7).toByte() })

        val pem = CaBundle.pem(certs)

        val blocks = Regex("-----BEGIN CERTIFICATE-----\n(.*?)\n-----END CERTIFICATE-----\n", RegexOption.DOT_MATCHES_ALL)
            .findAll(pem).map { it.groupValues[1] }.toList()
        assertEquals(certs.size, blocks.size)
        blocks.zip(certs).forEach { (body, der) ->
            assertTrue(body.lines().all { it.length <= 64 })
            assertArrayEquals(der, Base64.getMimeDecoder().decode(body))
        }
    }
}
