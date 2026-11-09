package io.github.quentinpn.viewer.tv.emby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ServerUrlTest {
    private fun rejected(input: String, expected: Class<out EmbyError>) {
        try {
            ServerUrl.parse(input)
            fail("accepted $input")
        } catch (e: EmbyException) {
            assertEquals(input, expected, e.error::class.java)
        }
    }

    @Test
    fun normalizesWhatUsersType() {
        assertEquals("http://192.168.1.10:8096", ServerUrl.parse(" 192.168.1.10:8096 ").toString())
        assertEquals("https://media.example.com", ServerUrl.parse("HTTPS://Media.Example.com/").toString())
    }

    @Test
    fun stripsWebAndEmbySuffixes() {
        assertEquals("http://nas.lan:8096", ServerUrl.parse("http://nas.lan:8096/web/index.html").toString())
        assertEquals("http://nas.lan:8096", ServerUrl.parse("http://nas.lan:8096/emby/").toString())
        assertEquals("https://x.example/proxy", ServerUrl.parse("https://x.example/proxy/emby").toString())
        assertEquals("http://nas.lan:8096/emby/System/Info/Public", ServerUrl.parse("nas.lan:8096").api("/System/Info/Public").toString())
    }

    @Test
    fun refusesUnsafeAddresses() {
        rejected("ftp://192.168.1.10", EmbyError.InvalidUrl::class.java)
        rejected("http://user:pw@192.168.1.10", EmbyError.InvalidUrl::class.java)
        rejected("http://192.168.1.10/?api_key=x", EmbyError.InvalidUrl::class.java)
        rejected("", EmbyError.InvalidUrl::class.java)
        rejected("http://media.example.com", EmbyError.InsecureRemoteUrl::class.java)
        rejected("http://8.8.8.8:8096", EmbyError.InsecureRemoteUrl::class.java)
    }

    @Test
    fun localHostsWithoutDns() {
        for (h in listOf("localhost", "127.0.0.1", "10.0.0.2", "172.16.0.1", "192.168.1.10", "169.254.1.1", "[::1]", "[fd00::1]", "nas.local", "box.home.arpa")) {
            assertTrue(h, ServerUrl.isLocalHost(h))
        }
        for (h in listOf("8.8.8.8", "media.example.com", "[2001:db8::1]", "172.32.0.1")) {
            assertFalse(h, ServerUrl.isLocalHost(h))
        }
    }
}
