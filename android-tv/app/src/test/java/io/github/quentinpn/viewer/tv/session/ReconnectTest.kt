package io.github.quentinpn.viewer.tv.session

import io.github.quentinpn.viewer.tv.emby.DeviceIdentity
import io.github.quentinpn.viewer.tv.emby.EmbyClient
import io.github.quentinpn.viewer.tv.emby.EmbyError
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class ReconnectTest {
    private val server = MockWebServer()
    private val device = DeviceIdentity("Shield", "dev-1", "0.1.0")
    private val http = EmbyClient.defaultHttp()
    private val publicInfo = """{"ServerName":"Salon","Version":"4.10.1.0","Id":"srv-1"}"""

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun entry(serverId: String? = "srv-1") =
        SavedServer(server = "http://127.0.0.1:${server.port}", serverId = serverId, userId = "u1", userName = "Quentin", sealedToken = "x")

    private fun respond(code: Int, body: String = "") = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test
    fun anAcceptedTokenOpensWithTheServerName() = runBlocking {
        respond(200, publicInfo)
        respond(200, """{"Id":"u1","Name":"Quentin"}""")
        assertEquals(Reconnect.Opened("srv-1", "Salon"), reconnect(entry(serverId = null), "secret-token", device, http))
        val probe = server.takeRequest()
        assertEquals("/emby/System/Info/Public", probe.url.encodedPath)
        assertFalse("the probe goes without the token", "Token=" in probe.headers["X-Emby-Authorization"]!!)
        val user = server.takeRequest()
        assertEquals("/emby/Users/u1", user.url.encodedPath)
        assertEquals(null, user.url.query)
        assertTrue("Token=\"secret-token\"" in user.headers["X-Emby-Authorization"]!!)
    }

    @Test
    fun a401AsksThePasswordAgain() = runBlocking {
        respond(200, publicInfo)
        respond(401)
        assertEquals(Reconnect.Refused, reconnect(entry(), "secret-token", device, http))
    }

    @Test
    fun anotherServerAtTheAddressNeverReceivesTheToken() = runBlocking {
        respond(200, """{"ServerName":"Autre","Version":"4.10.1.0","Id":"srv-2"}""")
        assertEquals(Reconnect.Failed(EmbyError.ServerMismatch), reconnect(entry(), "secret-token", device, http))
        server.takeRequest()
        assertEquals(null, server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun aServerErrorKeepsTheTokenAndOffersARetry() = runBlocking {
        respond(200, publicInfo)
        respond(500)
        val result = reconnect(entry(), "secret-token", device, http)
        assertTrue(result is Reconnect.Failed && result.error is EmbyError.Server)
    }

    @Test
    fun anUnreachableServerIsReportedInFrench() = runBlocking {
        val port = server.port
        server.close()
        val gone = SavedServer(server = "http://127.0.0.1:$port", userId = "u1", userName = "Quentin", sealedToken = "x")
        val result = reconnect(gone, "secret-token", device, http)
        assertEquals(Reconnect.Failed(EmbyError.Unreachable), result)
        assertEquals("Serveur injoignable. Vérifiez l'adresse et le réseau.", (result as Reconnect.Failed).error.message)
    }
}
