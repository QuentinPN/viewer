package io.github.quentinpn.viewer.tv.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class MapStore(val map: MutableMap<String, String> = mutableMapOf()) : KeyValueStore {
    override fun getString(key: String) = map[key]

    override fun edit(block: KeyValueEditor.() -> Unit) {
        val copy = map.toMutableMap()
        object : KeyValueEditor {
            override fun putString(key: String, value: String) {
                copy[key] = value
            }

            override fun remove(key: String) {
                copy.remove(key)
            }
        }.block()
        map.clear()
        map.putAll(copy)
    }
}

/** Seals by reversing behind a prefix; anything else cannot be opened, like a lost Keystore key. */
private object FakeCipher : TokenCipher {
    override fun encrypt(plain: String) = "sealed:" + plain.reversed()

    override fun decrypt(sealed: String): String {
        require(sealed.startsWith("sealed:"))
        return sealed.removePrefix("sealed:").reversed()
    }
}

class SavedServersTest {
    private var clock = 1_000L
    private val prefs = MapStore()
    private val repo = SavedServerRepository(prefs, FakeCipher) { clock }

    private fun session(server: String = "http://192.168.1.14:8096", user: String = "u1", token: String = "tok-1") =
        Session(server, user, "Quentin", token)

    @Test
    fun theOldSingleSessionBecomesOneSavedServerStillSignedIn() {
        prefs.map += mapOf(
            "deviceId" to "dev-1",
            "server" to "http://192.168.1.14:8096",
            "userId" to "u1",
            "userName" to "Quentin",
            "token" to FakeCipher.encrypt("live-token"),
        )
        val session = repo.load()
        assertEquals(Session("http://192.168.1.14:8096", "u1", "Quentin", "live-token"), session)
        val tile = repo.servers().single()
        assertEquals("u1", tile.userId)
        assertEquals("Quentin", tile.userName)
        assertTrue(tile.hasToken)
        assertEquals("192.168.1.14:8096", tile.displayName)
        // The old keys are gone, the device id and the address hint stay
        assertNull(prefs.map["userId"])
        assertNull(prefs.map["userName"])
        assertNull(prefs.map["token"])
        assertEquals("dev-1", prefs.map["deviceId"])
        assertEquals("http://192.168.1.14:8096", prefs.map["server"])
        // Read again (next start): same session, no second tile
        assertEquals(session, SavedServerRepository(prefs, FakeCipher) { clock }.load())
        assertEquals(1, repo.servers().size)
    }

    @Test
    fun migrationKeepsTheSealedTokenAsIs() {
        val sealed = FakeCipher.encrypt("live-token")
        prefs.map += mapOf("server" to "http://nas.local:8096", "userId" to "u1", "userName" to "Quentin", "token" to sealed)
        assertEquals(sealed, repo.servers().single().sealedToken)
    }

    @Test
    fun anOldSignOutLeavesNoTileButPrefillsTheAddress() {
        prefs.map += mapOf("server" to "http://192.168.1.14:8096", "deviceId" to "dev-1")
        assertNull(repo.load())
        assertTrue(repo.servers().isEmpty())
        assertEquals("http://192.168.1.14:8096", repo.lastServer)
    }

    @Test
    fun anOldTokenThatCannotBeOpenedKeepsTheTileWithoutIt() {
        prefs.map += mapOf("server" to "http://192.168.1.14:8096", "userId" to "u1", "userName" to "Quentin", "token" to "garbage")
        assertNull(repo.load())
        val tile = repo.servers().single()
        assertFalse(tile.hasToken)
        assertNull(repo.activeKey())
    }

    @Test
    fun migrationRunsOnce() {
        prefs.map += mapOf("server" to "http://192.168.1.14:8096", "userId" to "u1", "userName" to "Quentin", "token" to FakeCipher.encrypt("t"))
        val key = repo.servers().single().key
        repo.forget(key)
        assertTrue(repo.servers().isEmpty())
        assertTrue(SavedServerRepository(prefs, FakeCipher).servers().isEmpty())
    }

    @Test
    fun aFreshInstallHasNothing() {
        assertNull(repo.load())
        assertTrue(repo.servers().isEmpty())
        assertNull(repo.lastServer)
    }

    @Test
    fun saveOpensTheSessionAndNeverStoresTheTokenInClear() {
        repo.save(session(token = "secret-token"), "srv-1", "Salon")
        assertEquals(session(token = "secret-token"), repo.load())
        assertFalse(prefs.map.values.any { "secret-token" in it })
        val tile = repo.servers().single()
        assertEquals("Salon", tile.displayName)
        assertEquals("srv-1", tile.serverId)
        assertFalse("secret" in tile.toString())
    }

    @Test
    fun mostRecentFirstAndTheSameUserReplacesItsTile() {
        repo.save(session(server = "http://a.local"), "srv-a", "A")
        clock = 2_000
        repo.save(session(server = "http://b.local"), "srv-b", "B")
        assertEquals(listOf("B", "A"), repo.servers().map { it.displayName })
        clock = 3_000
        repo.save(session(server = "http://a.local", token = "tok-2"))
        assertEquals(listOf("A", "B"), repo.servers().map { it.displayName })
        assertEquals("tok-2", repo.load()!!.token)
        // The server id and name stay when the sign-in does not bring them
        assertEquals("srv-a", repo.servers().first().serverId)
    }

    @Test
    fun theSameServerAtAnotherAddressReplacesItsTile() {
        repo.save(session(server = "http://192.168.1.14:8096"), "srv-1", "Salon")
        repo.save(session(server = "https://emby.example.fr"), "srv-1", "Salon")
        val tile = repo.servers().single()
        assertEquals("https://emby.example.fr", tile.server)
    }

    @Test
    fun anotherUserOnTheSameServerGetsItsOwnTile() {
        repo.save(session(user = "u1"), "srv-1", "Salon")
        repo.save(session(user = "u2"), "srv-1", "Salon")
        assertEquals(2, repo.servers().size)
    }

    @Test
    fun atMostEightTilesTheOldestGoes() {
        repeat(9) {
            clock = 1_000L + it
            repo.save(session(server = "http://s$it.local"))
        }
        val servers = repo.servers()
        assertEquals(SavedServerRepository.MAX_SERVERS, servers.size)
        assertFalse(servers.any { it.server == "http://s0.local" })
    }

    @Test
    fun signOutKeepsTheTileWithoutItsToken() {
        repo.save(session(), "srv-1", "Salon")
        repo.signOut()
        assertNull(repo.load())
        val tile = repo.servers().single()
        assertFalse(tile.hasToken)
        assertNull(repo.token(tile.key))
    }

    @Test
    fun switchingServerKeepsTheTokenForTheTile() {
        repo.save(session(), "srv-1", "Salon")
        val key = repo.activeKey()!!
        repo.deactivate()
        assertNull(repo.load())
        assertEquals("tok-1", repo.token(key))
        clock = 5_000
        repo.activate(key, "srv-1", "Salon renommé")
        assertEquals(session(), repo.load())
        assertEquals("Salon renommé", repo.servers().single().displayName)
        assertEquals(5_000, repo.servers().single().lastUsed)
    }

    @Test
    fun forgetRemovesEverythingAndGivesTheTokenForTheServerSignOut() {
        repo.save(session(server = "http://a.local"), "srv-a", "A")
        repo.save(session(server = "http://b.local"), "srv-b", "B")
        val b = repo.activeKey()!!
        assertEquals("tok-1", repo.forget(b))
        assertNull(repo.load())
        assertEquals(listOf("A"), repo.servers().map { it.displayName })
        assertNull(repo.forget(b))
    }

    @Test
    fun droppingAnotherTileTokenKeepsTheOpenSession() {
        repo.save(session(server = "http://a.local"), "srv-a", "A")
        val a = repo.activeKey()!!
        repo.save(session(server = "http://b.local", token = "tok-b"), "srv-b", "B")
        repo.dropToken(a)
        assertEquals("tok-b", repo.load()!!.token)
        assertFalse(repo.server(a)!!.hasToken)
    }

    @Test
    fun aTokenThatCannotBeOpenedIsDropped() {
        repo.save(session(), "srv-1", "Salon")
        val key = repo.activeKey()!!
        prefs.map["servers"] = prefs.map["servers"]!!.replace(FakeCipher.encrypt("tok-1"), "broken")
        assertNull(repo.load())
        assertFalse(repo.server(key)!!.hasToken)
    }

    @Test
    fun describeNamesAMigratedTile() {
        prefs.map += mapOf("server" to "http://192.168.1.14:8096", "userId" to "u1", "userName" to "Quentin", "token" to FakeCipher.encrypt("t"))
        val key = repo.activeKey()!!
        repo.describe(key, "srv-1", "Salon")
        assertEquals("Salon", repo.servers().single().displayName)
        assertEquals("t", repo.load()!!.token)
    }

    @Test
    fun damagedListReadsAsEmpty() {
        prefs.map["servers"] = "{not json"
        assertTrue(repo.servers().isEmpty())
        assertNull(repo.load())
    }

    @Test
    fun hostLabelDropsTheDefaultPort() {
        assertEquals("192.168.1.14:8096", hostLabel("http://192.168.1.14:8096"))
        assertEquals("emby.example.fr", hostLabel("https://emby.example.fr"))
        assertEquals("emby.example.fr", hostLabel("https://emby.example.fr/emby-proxy"))
        assertEquals("nonsense", hostLabel("nonsense"))
    }
}
