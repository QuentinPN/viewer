package io.github.quentinpn.viewer.tv.stremio

import io.github.quentinpn.viewer.tv.session.KeyValueEditor
import io.github.quentinpn.viewer.tv.session.KeyValueStore
import io.github.quentinpn.viewer.tv.session.TokenCipher
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

private class MapStore : KeyValueStore {
    val map = HashMap<String, String>()

    @Synchronized override fun getString(key: String) = map[key]

    @Synchronized override fun edit(block: KeyValueEditor.() -> Unit) {
        object : KeyValueEditor {
            override fun putString(key: String, value: String) {
                map[key] = value
            }

            override fun remove(key: String) {
                map.remove(key)
            }
        }.block()
    }
}

private object FakeCipher : TokenCipher {
    override fun encrypt(plain: String) = "sealed:" + plain.reversed()

    override fun decrypt(sealed: String): String {
        require(sealed.startsWith("sealed:"))
        return sealed.removePrefix("sealed:").reversed()
    }
}

private val SHAWSHANK = StreamTarget.movie("tt0111161")

class StremioRepositoryTest {
    private val fake = FakeStremio()
    private val prefs = MapStore()
    private val store = StremioStore(prefs, FakeCipher)
    private val repo = StremioRepository(store, fake.client(), log = {})

    @After fun tearDown() = fake.close()

    private fun signIn() = runBlocking { repo.signIn(" owner@example.com ", "hunter2") }

    private fun calls(path: String) = fake.paths().count { it == path }

    /** Every list [target] goes through, until each addon has answered. */
    private fun list(repository: StremioRepository = repo) = runBlocking { repository.sources(SHAWSHANK).toList() }

    private fun firstStream(lists: List<SourceList>): StreamChoice =
        lists.last().addons.firstNotNullOf { (it.streams as? AddonStreams.Ready)?.streams?.firstOrNull() }

    @Test fun `signing in seals the key and lists the addons serving streams, fetched once per session`() {
        val status = signIn()

        val both = { name: String -> AddonInfo(name, movie = true, series = true) }
        val want = listOf(
            AddonInfo("TMDB Collections", movie = true, series = false), both("Local Files (without catalog support)"),
            both("Lumio · Profil"), both("AIOSources"), both("StreamFusionReborn"), both("Wastream | AD"), both("Comet | AD"),
            both("TorrentsDB AD"),
        )
        assertEquals(StremioStatus(StremioState.SignedIn, want), status)
        assertEquals("REDACTED_AUTHKEY", store.authKey()?.value)
        assertTrue("the key is sealed", prefs.map.values.none { "REDACTED_AUTHKEY" in it })
        runBlocking { repo.status() }
        assertEquals(1, calls("/api/addonCollectionGet"))
    }

    @Test fun `a refused session asks to sign in again, without asking again, until the next sign-in`() {
        signIn()
        val restarted = StremioRepository(store, fake.client(), log = {})
        fake.collection = { json(200, fixture("addon_collection_session_expired")) }

        assertEquals(StremioState.ReauthRequired, runBlocking { restarted.status() }.state)
        assertEquals(StremioState.ReauthRequired, runBlocking { restarted.status() }.state)
        assertEquals(2, calls("/api/addonCollectionGet"))
        val error = stremioFailure { list(restarted) }
        assertEquals(StremioError(StremioErrorKind.ReauthRequired, page = true), error)

        fake.collection = { json(200, fake.rewrittenCollection()) }
        assertEquals(StremioState.SignedIn, runBlocking { restarted.signIn("owner@example.com", "hunter2") }.state)
    }

    @Test fun `an unreachable collection is not remembered`() {
        store.save(AuthKey("REDACTED_AUTHKEY"))
        fake.collection = { json(500, "{}") }
        assertEquals(StremioState.Unreachable, runBlocking { repo.status() }.state)

        fake.collection = { json(200, fake.rewrittenCollection()) }
        assertEquals(StremioState.SignedIn, runBlocking { repo.status() }.state)
    }

    @Test fun `a wrong password keeps no key`() {
        fake.login = { json(200, fixture("login_wrong_password")) }

        assertEquals(StremioErrorKind.Rejected, stremioFailure { signIn() }.kind)

        assertNull(store.authKey())
        assertEquals(StremioState.SignedOut, runBlocking { repo.status() }.state)
    }

    @Test fun `signing out forgets the key on this device only`() {
        signIn()
        val before = fake.requests.size

        val status = runBlocking { repo.signOut() }

        assertEquals(StremioStatus(StremioState.SignedOut), status)
        assertNull(store.authKey())
        assertEquals("nothing sent to Stremio", before, fake.requests.size)
        assertEquals(StremioError(StremioErrorKind.SignedOut, page = true), stremioFailure { list() })
    }

    @Test fun `a list gives each addon's streams and resolves them to their URL and content`() {
        signIn()

        val lists = list()

        val loading = lists.first().addons
        assertEquals(8, loading.size)
        assertTrue(loading.all { it.streams == AddonStreams.Loading })
        val done = lists.last().addons.associate { it.name to it.streams }
        assertEquals(AddonStreams.Failed(StremioError(StremioErrorKind.Unreachable, "Local Files (without catalog support)", page = true)), done["Local Files (without catalog support)"])
        assertEquals(AddonStreams.Ready(emptyList(), 0), done["TMDB Collections"])
        val lumio = done.getValue("Lumio · Profil") as AddonStreams.Ready
        assertEquals(6, lumio.streams.size)
        assertEquals(31682067376L, lumio.streams.first().size)
        val resolved = repo.resolve(lumio.streams.first().id)!!
        assertEquals("https://debrid.example/REDACTED/file.mkv", resolved.url)
        assertEquals(emptyMap<String, String>(), resolved.headers)
        assertEquals("resumed under the content of its list", SHAWSHANK.id, resolved.target)
        assertNull(repo.resolve("stream_unknown"))
    }

    @Test fun `a slow addon does not hold back the others`() {
        signIn()
        fake.streams = { path ->
            if (path.startsWith("/addon-4/")) MockResponse.Builder().code(200).body("""{"streams":[]}""").headersDelay(1, TimeUnit.SECONDS).build() else null
        }

        val lists = list()

        assertTrue(
            lists.any { l ->
                l.addons.single { it.name == "Lumio · Profil" }.streams == AddonStreams.Loading &&
                    l.addons.count { it.streams is AddonStreams.Ready } >= 5
            },
        )
    }

    @Test fun `the streams of the last four lists stay resolvable`() {
        signIn()

        val firsts = (1..5).map { firstStream(list()).id }

        assertNull(repo.resolve(firsts[0]))
        firsts.drop(1).forEach { assertNotNull(repo.resolve(it)) }
    }

    @Test fun `signing in again or out forgets the lists`() {
        signIn()
        val beforeSignIn = firstStream(list()).id
        signIn()
        assertNull(repo.resolve(beforeSignIn))

        val beforeSignOut = firstStream(list()).id
        runBlocking { repo.signOut() }
        assertNull(repo.resolve(beforeSignOut))
    }
}
