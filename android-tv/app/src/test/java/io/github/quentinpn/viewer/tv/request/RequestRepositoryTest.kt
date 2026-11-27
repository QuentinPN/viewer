package io.github.quentinpn.viewer.tv.request

import io.github.quentinpn.viewer.tv.emby.EmbyClient
import io.github.quentinpn.viewer.tv.session.KeyValueEditor
import io.github.quentinpn.viewer.tv.session.KeyValueStore
import io.github.quentinpn.viewer.tv.session.TokenCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The shared fixtures of contracts/request, read from the repository root (cwd of the unit tests is `android-tv/app`). */
private fun fixture(name: String) = File("../../contracts/request/fixtures/$name.json").readText()

private const val FIXTURE_TOKEN = "ur_sess_EXEMPLE_NON_SECRET_0000000000000000"

private class MapStore : KeyValueStore {
    private val map = HashMap<String, String>()

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

class RequestRepositoryTest {
    private val server = MockWebServer()
    private val exchanges = AtomicInteger()
    private val tokenReads = AtomicInteger()
    private val seen = CopyOnWriteArrayList<RecordedRequest>()

    /** Whether the server accepts this `Authorization` header on the API calls. */
    @Volatile private var accepts: (String?) -> Boolean = { it != null && it.startsWith("Bearer tok-") }

    @Volatile private var exchangeAnswer: () -> MockResponse = { exchangeOk(exchanges.get()) }

    @Volatile private var identity: EmbyAccount? = EmbyAccount("srv1", "userA")
    private val prefs = MapStore()
    private val store = RequestStore(prefs, FakeCipher)
    private lateinit var repo: RequestRepository
    private lateinit var address: String

    private fun exchangeOk(n: Int) = MockResponse.Builder().code(200)
        .body(fixture("emby-token").replace(FIXTURE_TOKEN, "tok-$n"))
        .headersDelay(60, TimeUnit.MILLISECONDS)
        .build()

    private fun json(code: Int, body: String) = MockResponse.Builder().code(code).body(body).build()

    private fun repository() = RequestRepository(
        store,
        EmbyClient.defaultHttp(),
        { identity },
        { tokenReads.incrementAndGet(); "emby-secret-token" },
        deviceLabel("Shield"),
        "Viewer-TV/test",
    )

    private fun calls(path: String) = seen.filter { it.url.encodedPath == path }

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                val path = request.url.encodedPath
                return when {
                    path == "/api/version" -> json(200, fixture("version-ok"))
                    path == "/api/auth/emby-token" -> {
                        exchanges.incrementAndGet()
                        exchangeAnswer()
                    }
                    !accepts(request.headers["Authorization"]) -> json(401, """{"error":"Session invalide"}""")
                    path == "/api/auth/logout" -> MockResponse.Builder().code(204).build()
                    path == "/api/auth/me" -> json(200, """{"viewer":${viewerJson()}}""")
                    path == "/api/discover" -> json(200, fixture("discover"))
                    path == "/api/requests/quota" -> json(200, fixture("quota"))
                    else -> json(404, fixture("error"))
                }
            }
        }
        server.start()
        address = "http://127.0.0.1:${server.port}"
        store.address = address
        repo = repository()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun viewerJson(): String {
        val session = viewerJson.parseToJsonElement(fixture("emby-token")) as JsonObject
        return session.getValue("viewer").toString()
    }

    private fun expectError(block: suspend () -> Unit): RequestError {
        try {
            runBlocking { block() }
        } catch (e: RequestException) {
            return e.error
        }
        fail("expected a RequestException")
        throw AssertionError()
    }

    @Test
    fun parsesEveryFixture() {
        fun <T> parse(name: String, serializer: kotlinx.serialization.KSerializer<T>) =
            viewerJson.decodeFromString(serializer, fixture(name))

        assertEquals("1.0.0", parse("version-ok", ApiVersion.serializer()).apiVersion)
        assertEquals(listOf("api.version"), parse("version-old", ApiVersion.serializer()).features)
        assertEquals("Camille", parse("emby-token", EmbyTokenSession.serializer()).viewer.name)
        assertEquals(3, parse("sessions", AuthSessions.serializer()).sessions.size)
        assertTrue(parse("discover", DiscoverResponse.serializer()).rows.isNotEmpty())
        assertEquals(1001, parse("paged-media", PagedMedia.serializer()).totalPages)
        assertEquals(3, parse("search", MediaSearchResults.serializer()).people.size)
        val movie = parse("media-movie", MediaDetails.serializer())
        assertNull(movie.embyItemId)
        assertNull(movie.status)
        assertEquals("tt33764258", movie.externalIds.imdb)
        val tv = parse("media-tv", MediaDetails.serializer())
        assertEquals("tt0203259", tv.externalIds.imdb)
        assertEquals(MediaStatus.Partial, tv.status)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), tv.seasons.map { it.seasonNumber })
        assertEquals(10, parse("quota", RequestQuota.serializer()).movies.remaining)
        assertEquals(RequestStatus.Approved, parse("request-view", RequestView.serializer()).status)
        assertEquals(4, parse("request-page", RequestPage.serializer()).totals.available)
        assertEquals(3, parse("request-detail", RequestDetail.serializer()).events.size)
    }

    @Test
    fun rejectsOldServer() {
        val old = viewerJson.decodeFromString(ApiVersion.serializer(), fixture("version-old"))
        val error = (runCatching { ensureCompatible(old) }.exceptionOrNull() as RequestException).error
        assertEquals(REQUIRED_FEATURES, (error as RequestError.TooOld).missing)

        val next = ApiVersion("2.0.0", REQUIRED_FEATURES)
        val major = (runCatching { ensureCompatible(next) }.exceptionOrNull() as RequestException).error
        assertTrue(major is RequestError.TooOld)
        ensureCompatible(viewerJson.decodeFromString(ApiVersion.serializer(), fixture("version-ok")))
    }

    @Test
    fun anOldServerShowsInStatusAndGetsNoExchange() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                return json(200, fixture("version-old"))
            }
        }
        val status = repo.status()
        assertEquals(RequestState.TooOld, status.state)
        assertEquals(REQUIRED_FEATURES, status.missing)
        assertTrue(calls("/api/auth/emby-token").isEmpty())
    }

    @Test
    fun exchangeSendsNoAuthorization() = runBlocking {
        repo.discover()
        val exchange = calls("/api/auth/emby-token").single()
        assertNull(exchange.headers["Authorization"])
        val body = viewerJson.parseToJsonElement(exchange.body!!.utf8()) as JsonObject
        assertEquals(setOf("userId", "accessToken", "deviceName"), body.keys)
        assertEquals("userA", body.getValue("userId").jsonPrimitive.content)
        assertEquals("emby-secret-token", body.getValue("accessToken").jsonPrimitive.content)
        assertEquals("Shield (Android TV)", body.getValue("deviceName").jsonPrimitive.content)
        assertNull(calls("/api/version").single().headers["Authorization"])
        assertEquals("Bearer tok-1", calls("/api/discover").single().headers["Authorization"])
        assertFalse("no token in any URL", seen.any { "tok-" in it.url.toString() || "emby-secret" in it.url.toString() })
    }

    @Test
    fun deviceNameIsTruncatedTo64() {
        assertEquals(64, deviceLabel("M".repeat(100)).length)
        assertEquals("Shield (Android TV)", deviceLabel("Shield"))
    }

    @Test
    fun retriesOnceAfter401() = runBlocking {
        store.saveToken("srv1/userA", address, SessionToken("old"))
        val rows = repo.discover().rows
        assertTrue(rows.isNotEmpty())
        assertEquals(1, exchanges.get())
        assertEquals(listOf("Bearer old", "Bearer tok-1"), calls("/api/discover").map { it.headers["Authorization"] })
    }

    @Test
    fun rejectedAfterSingleRetry() {
        accepts = { false }
        val error = expectError { repo.discover() }
        assertTrue(error is RequestError.Rejected)
        // No token yet: one exchange to get one, one to renew it, then the refusal is final
        assertEquals(2, exchanges.get())
        assertEquals(2, calls("/api/discover").size)
    }

    @Test
    fun rejectedWhenTheEmbyTokenIsRefused() {
        exchangeAnswer = { json(401, """{"error":"Jeton Emby refusé"}""") }
        val error = expectError { repo.discover() }
        assertEquals("Jeton Emby refusé", (error as RequestError.Rejected).message)
        assertEquals(1, exchanges.get())
    }

    @Test
    fun concurrentCallsExchangeOnce() = runBlocking {
        (1..8).map { async(Dispatchers.Default) { repo.discover() } }.awaitAll()
        assertEquals(1, exchanges.get())
        assertEquals(8, calls("/api/discover").size)
    }

    @Test
    fun concurrent401sExchangeOnce() = runBlocking {
        store.saveToken("srv1/userA", address, SessionToken("old"))
        (1..8).map { async(Dispatchers.Default) { repo.discover() } }.awaitAll()
        assertEquals(1, exchanges.get())
    }

    @Test
    fun rateLimitedExposesRetryAfter() {
        exchangeAnswer = { MockResponse.Builder().code(429).addHeader("Retry-After", "120").body(fixture("error")).build() }
        val error = expectError { repo.discover() }
        assertEquals(120L, (error as RequestError.RateLimited).retryAfterSeconds)
        assertTrue("2 minutes" in error.message)
        assertEquals("no automatic retry", 1, exchanges.get())
    }

    @Test
    fun mapsErrorBody() {
        accepts = { true }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/api/version" -> json(200, fixture("version-ok"))
                "/api/auth/emby-token" -> exchangeOk(1)
                "/api/discover" -> json(403, fixture("error"))
                "/api/requests/quota" -> json(404, """{"error":"Introuvable ici"}""")
                else -> json(500, """{"error":"Panne"}""")
            }
        }
        val forbidden = expectError { repo.discover() }
        assertEquals("Quota de films atteint", (forbidden as RequestError.Forbidden).message)
        assertEquals("Introuvable ici", (expectError { repo.quota() } as RequestError.NotFound).message)
        val server = expectError { repo.sessions() } as RequestError.Server
        assertEquals(500, server.status)
        assertEquals("Panne", server.message)
    }

    @Test
    fun tokenNeverInToString() {
        assertFalse("secret" in SessionToken("secret-value").toString())
        val session = viewerJson.decodeFromString(EmbyTokenSession.serializer(), fixture("emby-token"))
        assertFalse(FIXTURE_TOKEN in session.toString())
        assertFalse("emby-secret-token" in EmbyAccount("s", "u").toString())
    }

    @Test
    fun signOutDoesNotAutoReconnect() = runBlocking {
        repo.discover()
        assertEquals(1, exchanges.get())

        repo.signOut()
        assertEquals("Bearer tok-1", calls("/api/auth/logout").single().headers["Authorization"])
        assertNull(store.token("srv1/userA", address))

        assertEquals(RequestError.SignedOut, expectError { repo.discover() })
        assertEquals(RequestState.SignedOut, repo.status().state)
        // The flag outlives the repository: a restart of the app does not reconnect either
        assertEquals(RequestError.SignedOut, expectError { repository().discover() })
        assertEquals(1, exchanges.get())

        assertEquals(RequestState.Ready, repo.signIn().state)
        assertEquals(2, exchanges.get())
    }

    @Test
    fun signOutDeletesTheTokenEvenWhenTheServerIsGone() = runBlocking {
        repo.discover()
        server.close()
        repo.signOut()
        assertNull(store.token("srv1/userA", address))
    }

    @Test
    fun normalizesAddress() {
        assertEquals("https://request.exemple.fr", normalizeAddress(" request.exemple.fr/ ").display())
        assertEquals("https://request.exemple.fr:8445", normalizeAddress("https://request.exemple.fr:8445/").display())
        assertEquals("http://192.168.1.14:3000", normalizeAddress("http://192.168.1.14:3000/").display())
        for (local in listOf("http://127.0.0.1:8000", "http://10.0.0.2", "http://172.20.1.1", "http://box.local", "http://localhost:3000", "http://172.31.255.1")) {
            assertEquals(local.removePrefix("http://"), normalizeAddress(local).display().removePrefix("http://"))
        }
        for (bad in listOf("", "  ", "ftp://x", "http://request.exemple.fr", "http://8.8.8.8", "http://169.254.1.1", "http://box.lan", "http://nas.home.arpa", "http://[fd00::1]", "http://172.15.0.1", "http://192.169.0.1", "http://172.32.0.1", "https://u:p@host", "https://host/?a=1")) {
            val error = (runCatching { normalizeAddress(bad) }.exceptionOrNull() as? RequestException)?.error
            assertTrue("$bad should be refused", error is RequestError.InvalidAddress)
        }
    }

    @Test
    fun setAddressSavesTheNormalizedAddressAndReadsTheStatus() = runBlocking {
        store.address = null
        assertEquals(RequestState.NotConfigured, repo.status().state)
        val status = repo.setAddress("  $address/ ")
        assertEquals(address, store.address)
        assertEquals(RequestState.Ready, status.state)
        assertEquals("Camille", status.viewer?.name)
        assertTrue(status.insecure)
        assertEquals("1.0.0", status.apiVersion)
    }

    @Test
    fun tokenIsScopedToEmbyAccount() = runBlocking {
        repo.discover()
        identity = EmbyAccount("srv1", "userB")
        repo.discover()
        assertEquals(2, exchanges.get())
        assertEquals(SessionToken("tok-1"), store.token("srv1/userA", address))
        assertEquals(SessionToken("tok-2"), store.token("srv1/userB", address))

        identity = EmbyAccount("srv1", "userA")
        repo.discover()
        assertEquals("A's own token again, no new exchange", 2, exchanges.get())
        assertEquals("Bearer tok-1", calls("/api/discover").last().headers["Authorization"])
        // The same user id on another Emby server is another account
        assertNull(store.token("srv2/userA", address))
    }

    @Test
    fun tokenIsOnlyReturnedForItsAddress() {
        store.saveToken("srv1/userA", address, SessionToken("tok-1"))
        assertNotNull(store.token("srv1/userA", address))
        assertNull(store.token("srv1/userA", "https://elsewhere.example"))
    }

    @Test
    fun forgettingAnAccountDropsItsTokenAndFlag() {
        store.saveToken("srv1/userA", address, SessionToken("tok-1"))
        store.setSignedOut("srv1/userA", true)
        repo.forgetAccount("srv1", "userA")
        assertNull(store.token("srv1/userA", address))
        assertFalse(store.isSignedOut("srv1/userA"))
    }

    @Test
    fun requestDetailKeepsTheTimeline() {
        val detail = viewerJson.decodeFromString(RequestDetail.serializer(), fixture("request-detail"))
        assertEquals(102, detail.request.id)
        assertEquals(RequestStatus.Approved, detail.request.status)
        assertEquals(listOf("requested", "approved", "sent"), detail.events.map { it.type })
        assertNull(detail.events[0].detail)
        assertEquals("Camille", detail.events[0].actor?.name)
        assertNull(detail.events[2].actor)
        assertEquals("Envoyé à Sonarr", detail.events[2].detail)
    }

    @Test
    fun requestDetailIsReadFromTheServer() = runBlocking {
        accepts = { true }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/api/version" -> json(200, fixture("version-ok"))
                "/api/auth/emby-token" -> exchangeOk(1)
                "/api/requests/102" -> json(200, fixture("request-detail"))
                else -> json(404, fixture("error"))
            }
        }
        assertEquals(3, repo.requestDetail(102).events.size)
    }

    /** The contract answers both writes with a RequestDetail: the timeline must not be dropped. */
    @Test
    fun writesAnswerWithTheTimeline() = runBlocking {
        accepts = { true }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                return when (request.url.encodedPath) {
                    "/api/version" -> json(200, fixture("version-ok"))
                    "/api/auth/emby-token" -> exchangeOk(1)
                    "/api/requests", "/api/requests/102/cancel" -> json(201, fixture("request-detail"))
                    else -> json(404, fixture("error"))
                }
            }
        }
        assertEquals(3, repo.request(NewRequest(MediaType.Movie, 603)).events.size)
        assertEquals(3, repo.cancel(102).events.size)
    }

    /** `POST /api/requests` refuses tmdbId 403 (403) and 409 (409) with the server's message, else answers a RequestDetail. */
    private fun serveWrites() {
        accepts = { true }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                val body = request.body?.utf8().orEmpty()
                return when (request.url.encodedPath) {
                    "/api/version" -> json(200, fixture("version-ok"))
                    "/api/auth/emby-token" -> exchangeOk(1)
                    "/api/requests" -> when {
                        "\"tmdbId\":403" in body -> json(403, fixture("error"))
                        "\"tmdbId\":409" in body -> json(409, """{"error":"Quota de séries atteint"}""")
                        else -> json(201, fixture("request-detail"))
                    }
                    "/api/requests/102/cancel" -> json(200, fixture("request-detail"))
                    else -> json(404, fixture("error"))
                }
            }
        }
    }

    @Test
    fun requestBodiesOnTheWire() = runBlocking {
        serveWrites()
        repo.request(NewRequest(MediaType.Movie, 603))
        repo.request(NewRequest(MediaType.Tv, 1399, SeasonsChoice.Some(listOf(1, 2))))
        repo.request(NewRequest(MediaType.Tv, 1399, SeasonsChoice.All))
        val posts = calls("/api/requests").onEach { assertEquals("POST", it.method) }
        assertEquals(
            listOf(
                """{"type":"movie","tmdbId":603}""",
                """{"type":"tv","tmdbId":1399,"seasons":[1,2]}""",
                """{"type":"tv","tmdbId":1399,"seasons":"all"}""",
            ),
            posts.map { it.body!!.utf8() },
        )
    }

    @Test
    fun quotaRefusalsShowTheServerMessage() {
        serveWrites()
        val forbidden = expectError { repo.request(NewRequest(MediaType.Movie, 403)) }
        assertEquals("Quota de films atteint", (forbidden as RequestError.Forbidden).message)
        val conflict = expectError { repo.request(NewRequest(MediaType.Tv, 409)) } as RequestError.Server
        assertEquals(409, conflict.status)
        assertEquals("Quota de séries atteint", conflict.message)
    }

    @Test
    fun cancelPostsToTheRequest() = runBlocking {
        serveWrites()
        repo.cancel(102)
        val cancel = calls("/api/requests/102/cancel").single()
        assertEquals("POST", cancel.method)
        assertEquals("Bearer tok-1", cancel.headers["Authorization"])
    }

    /** The Request code never logs, and nothing it prints or throws carries a token. */
    @Test
    fun tokenNeverInLoggedText() = runBlocking {
        val sources = File("src/main/java/io/github/quentinpn/viewer/tv/request").walk().filter { it.isFile }.toList()
        assertTrue(sources.isNotEmpty())
        sources.forEach { assertFalse("${it.name} logs", Regex("""\bLog\.|println\(|printStackTrace""").containsMatchIn(it.readText())) }

        serveWrites()
        val refused = expectError { repo.request(NewRequest(MediaType.Movie, 403)) }
        accepts = { false }
        val rejected = expectError { repo.quota() }
        val texts = listOf(refused.toString(), refused.message, rejected.toString(), rejected.message, repo.status().toString())
        for (text in texts) {
            for (secret in listOf("tok-1", "emby-secret-token", FIXTURE_TOKEN)) assertFalse("$secret in $text", secret in text)
        }
    }

    @Test
    fun statusTellsSignedOutFromNotConfigured() = runBlocking {
        store.address = null
        assertEquals(RequestState.NotConfigured, repo.status().state)
        store.address = address
        repo.discover()
        repo.signOut()
        val status = repo.status()
        assertEquals(RequestState.SignedOut, status.state)
        assertEquals(address, status.address)
    }

    @Test
    fun aValidTokenNeverOpensTheEmbyToken() = runBlocking {
        repo.discover()
        assertEquals(1, tokenReads.get())
        repeat(10) { repo.discover() }
        assertEquals("the cached session token serves every call", 1, tokenReads.get())

        // Even a stored token from an earlier run needs no Emby token
        tokenReads.set(0)
        store.saveToken("srv1/userA", address, SessionToken("tok-1"))
        repository().apply { repeat(5) { discover() } }
        assertEquals(0, tokenReads.get())
    }

    @Test
    fun theCacheFollowsSignOutAndA401() = runBlocking {
        repo.discover()
        accepts = { it == "Bearer tok-2" }
        repo.discover() // tok-1 is refused: renewed once, the new token cached
        assertEquals(2, exchanges.get())
        repeat(3) { repo.discover() }
        assertEquals(2, exchanges.get())
        repo.signOut()
        assertEquals(RequestError.SignedOut, expectError { repo.discover() })
    }

    @Test
    fun noEmbySessionMeansNoCallAndNoToken() {
        identity = null
        assertEquals(RequestError.EmbySignedOut, expectError { repo.discover() })
        assertEquals(0, seen.size)
    }

    @Test
    fun browseQueryJoinsGenresWithCommas() {
        val query = browseQuery(BrowseFilters(genres = listOf(28, 12), yearFrom = 2000, sort = "rating"), 3)
        assertEquals(
            listOf("page" to "3", "genres" to "28,12", "yearFrom" to "2000", "sort" to "rating"),
            query,
        )
    }

    @Test
    fun myRequestsStatusIsOneCommaList() {
        val query = myRequestsQuery(listOf(RequestStatus.Approved, RequestStatus.Sent), 2)
        assertEquals(listOf("mine" to "true", "status" to "approved,sent", "page" to "2"), query)
        assertEquals(listOf("mine" to "true", "page" to "1"), myRequestsQuery(emptyList(), 1))
    }

    @Test
    fun newRequestBody() {
        assertEquals("""{"type":"movie","tmdbId":5}""", NewRequest(MediaType.Movie, 5, SeasonsChoice.All).toJson().toString())
        assertEquals("""{"type":"tv","tmdbId":5,"seasons":"all"}""", NewRequest(MediaType.Tv, 5).toJson().toString())
        assertEquals(
            """{"type":"tv","tmdbId":5,"seasons":[1,3]}""",
            NewRequest(MediaType.Tv, 5, SeasonsChoice.Some(listOf(1, 3))).toJson().toString(),
        )
    }

    @Test
    fun imageUrlOnlyAcceptsRasterFilesOfKnownSizes() {
        val origin = normalizeAddress("https://request.exemple.fr")
        assertEquals("https://request.exemple.fr/api/images/w342/abc_1-2.jpg", imageUrl(origin, "w342", "/abc_1-2.jpg").toString())
        assertNotNull(imageUrl(origin, "original", "x.webp"))
        assertNull(imageUrl(origin, "w342", "/logo.svg"))
        assertNull(imageUrl(origin, "w342", "/../x.jpg"))
        assertNull(imageUrl(origin, "w342", "/a/b.jpg"))
        assertNull(imageUrl(origin, "w9999", "/a.jpg"))
        assertNull(imageUrl(origin, "w342", null))
    }
}
