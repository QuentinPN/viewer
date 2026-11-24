package io.github.quentinpn.viewer.tv.stremio

import io.github.quentinpn.viewer.tv.request.viewerJson
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

private const val FIXTURE_KEY = "REDACTED_AUTHKEY"

/** The error [block] throws; fails when it throws none. */
internal inline fun stremioFailure(block: () -> Unit): StremioError {
    try {
        block()
    } catch (e: StremioException) {
        return e.error
    }
    fail("no StremioException")
    error("unreachable")
}

private fun manifestAddon(transportUrl: String, manifest: String) =
    parseAddon(transportUrl, viewerJson.parseToJsonElement(manifest))

class StremioClientTest {
    private val fake = FakeStremio()
    private val logs = CopyOnWriteArrayList<String>()
    private val client = fake.client(log = { logs += it })

    @After fun tearDown() = fake.close()

    private fun addons() = runBlocking { client.addons(AuthKey(FIXTURE_KEY)) }

    private fun named(name: String) = addons().single { it.name == name }

    @Test fun `login sends the credentials and returns the session key`() = runBlocking {
        val key = client.login("owner@example.com", "hunter2")

        assertEquals(FIXTURE_KEY, key.value)
        val request = fake.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/api/login", request.url.encodedPath)
        val body = viewerJson.parseToJsonElement(request.body!!.utf8()).jsonObject
        val want = mapOf("type" to "Login", "email" to "owner@example.com", "password" to "hunter2")
        assertEquals(want.keys + "facebook", body.keys)
        want.forEach { (k, v) -> assertEquals(JsonPrimitive(v), body[k]) }
        assertEquals(JsonPrimitive(false), body["facebook"])
    }

    @Test fun `a wrong password answered with HTTP 200 is a refusal of the login`() {
        fake.login = { json(200, fixture("login_wrong_password")) }

        val error = stremioFailure { runBlocking { client.login("owner@example.com", "hunter2") } }

        assertEquals(StremioError(StremioErrorKind.Rejected, login = true), error)
    }

    @Test fun `an unknown session answered with HTTP 200 is a refusal of the session`() {
        fake.collection = { json(200, fixture("addon_collection_session_expired")) }

        assertEquals(StremioError(StremioErrorKind.Rejected), stremioFailure { addons() })
    }

    @Test fun `the collection request carries the key and keeps the collection order`() {
        val names = addons().map { it.name }

        assertEquals(
            listOf(
                "The Movie Database Addon", "TMDB Collections", "Local Files (without catalog support)", "Cinemeta",
                "Lumio · Profil", "AIOSources", "StreamFusionReborn", "Wastream | AD", "Comet | AD", "TorrentsDB AD",
            ),
            names,
        )
        val body = viewerJson.parseToJsonElement(fake.requests.single().body!!.utf8()).jsonObject
        assertEquals(setOf("type", "authKey", "update"), body.keys)
        assertEquals(JsonPrimitive("AddonCollectionGet"), body["type"])
        assertEquals(JsonPrimitive(FIXTURE_KEY), body["authKey"])
        assertEquals(JsonPrimitive(true), body["update"])
    }

    @Test fun `only the addons declaring streams for the type and an IMDb id are asked`() {
        val addons = addons()
        fun serving(type: ContentType, id: String) = addons.filter { it.serves(type, id) }.map { it.name }
        val movie = listOf(
            "TMDB Collections", "Local Files (without catalog support)", "Lumio · Profil", "AIOSources",
            "StreamFusionReborn", "Wastream | AD", "Comet | AD", "TorrentsDB AD",
        )

        assertEquals(movie, serving(ContentType.Movie, "tt0111161"))
        assertEquals(movie.drop(1), serving(ContentType.Series, "tt0944947:1:2"))
        assertEquals(listOf("Lumio · Profil", "Wastream | AD", "Comet | AD", "TorrentsDB AD"), serving(ContentType.Movie, "kitsu:1"))
    }

    @Test fun `the stream resource rules of a manifest`() {
        val cases = listOf(
            Triple("kitsu only", """{"resources":["stream"],"types":["movie","series"],"idPrefixes":["kitsu"]}""", false),
            Triple("no prefixes", """{"resources":["stream"],"types":["movie"]}""", true),
            Triple("type not listed", """{"resources":["stream"],"types":["series"],"idPrefixes":["tt"]}""", false),
            Triple("object types override", """{"resources":[{"name":"stream","types":["series"]}],"types":["movie"]}""", false),
            Triple("object prefixes override", """{"resources":[{"name":"stream","idPrefixes":["tt"]}],"types":["movie"],"idPrefixes":["kitsu"]}""", true),
            Triple("object falls back to manifest", """{"resources":[{"name":"stream"}],"types":["movie"],"idPrefixes":["tt"]}""", true),
            Triple("empty prefixes accept any id", """{"resources":[{"name":"stream","types":["movie"],"idPrefixes":[]}],"types":["movie"]}""", true),
            Triple("empty manifest prefixes accept any id", """{"resources":["stream"],"types":["movie"],"idPrefixes":[]}""", true),
            Triple("no stream resource", """{"resources":["catalog","meta"],"types":["movie"],"idPrefixes":["tt"]}""", false),
            Triple("unknown resource forms skipped", """{"resources":[1,null,{"types":["movie"]},"stream"],"types":["movie"]}""", true),
            Triple("no resources", """{"types":["movie"]}""", false),
        )
        for ((name, manifest, want) in cases) {
            val withName = JsonObject(viewerJson.parseToJsonElement(manifest).jsonObject + ("name" to JsonPrimitive("X"))).toString()
            val addon = manifestAddon("https://a.example/x/manifest.json", withName)
            assertEquals(name, want, addon.serves(ContentType.Movie, "tt0111161"))
        }
    }

    @Test fun `an addon address must be https, or http on the local network, without credentials or query`() {
        val manifest = """{"name":"X","resources":["stream"],"types":["movie"]}"""
        val accepted = listOf(
            "https://a.example/key/manifest.json",
            "http://127.0.0.1:11470/manifest.json",
            "http://192.168.1.20/x/manifest.json",
        )
        val refused = listOf(
            "http://a.example/key/manifest.json",
            "https://user:pass@a.example/manifest.json",
            "https://a.example/manifest.json?key=1",
            "https://a.example/manifest.json#x",
            "https://a.example/key/stream.json",
            "ftp://a.example/manifest.json",
            "",
        )
        accepted.forEach { manifestAddon(it, manifest) }
        refused.forEach { url ->
            assertEquals(url, StremioError(StremioErrorKind.Invalid, addon = "X"), stremioFailure { manifestAddon(url, manifest) })
        }
    }

    @Test fun `a refused addon is skipped and logged by name, never by address`() {
        val public = viewerJson.parseToJsonElement(
            """{"transportUrl":"http://example.com/secret-path/manifest.json","manifest":{"id":"p","version":"1","name":"Public HTTP"}}""",
        )
        val broken = viewerJson.parseToJsonElement("""{"transportUrl":"${fake.base}/broken/manifest.json","manifest":"not a manifest"}""")
        fake.collection = { json(200, fake.rewrittenCollection(mapOf(1 to public, 5 to broken))) }

        val names = addons().map { it.name }

        assertFalse("Public HTTP" in names || "AIOSources" in names)
        assertEquals(8, names.size)
        val log = logs.joinToString("\n")
        assertTrue(log, "Public HTTP" in log)
        listOf("example.com", "secret-path", "broken", fake.base).forEach { assertFalse(log, it in log) }
    }

    /** Playable and unsupported counts of every addon serving [id]; -1, -1 for one that failed. */
    private fun counts(type: ContentType, id: String): Map<String, Pair<Int, Int>> = addons().filter { it.serves(type, id) }.associate { a ->
        a.name to try {
            runBlocking { client.streams(a, type, id) }.let { it.streams.size to it.unsupported }
        } catch (e: StremioException) {
            assertEquals(StremioError(StremioErrorKind.Unreachable, addon = a.name), e.error)
            -1 to -1
        }
    }

    @Test fun `streams of a movie, per addon of the fixture`() {
        val want = mapOf(
            "TMDB Collections" to (0 to 0), "Local Files (without catalog support)" to (-1 to -1),
            "Lumio · Profil" to (6 to 0), "AIOSources" to (6 to 0), "StreamFusionReborn" to (6 to 0),
            "Wastream | AD" to (6 to 0), "Comet | AD" to (6 to 0), "TorrentsDB AD" to (6 to 0),
        )

        assertEquals(want, counts(ContentType.Movie, "tt0111161"))
        assertTrue("/addon-4/stream/movie/tt0111161.json" in fake.paths())
        val lumio = runBlocking { client.streams(named("Lumio · Profil"), ContentType.Movie, "tt0111161") }.streams.first()
        assertEquals("[AD⚡️] Lumio", lumio.name)
        assertEquals("The Shawshank Redemption (Les Evadés) - (1994).mkv", lumio.filename)
        assertEquals(31682067376L, lumio.size)
        assertEquals("https://debrid.example/REDACTED/file.mkv", lumio.url)
        assertEquals(emptyMap<String, String>(), lumio.headers)
        assertTrue(lumio.description, "1080p • BluRay REMUX" in lumio.description)
        // TorrentsDB gives `title` instead of `description`
        val torrents = runBlocking { client.streams(named("TorrentsDB AD"), ContentType.Movie, "tt0111161") }.streams.first()
        assertTrue(torrents.description, "YIFY" in torrents.description)
    }

    @Test fun `streams of an episode keep the colons of its id in the path`() {
        val id = StreamTarget.episode("tt0944947", 1, 2).id
        val want = mapOf(
            "Local Files (without catalog support)" to (-1 to -1),
            "Lumio · Profil" to (6 to 0), "AIOSources" to (6 to 0), "StreamFusionReborn" to (6 to 0),
            "Wastream | AD" to (6 to 0), "Comet | AD" to (6 to 0), "TorrentsDB AD" to (6 to 0),
        )

        assertEquals(want, counts(ContentType.Series, id))
        assertTrue(fake.paths().toString(), "/addon-4/stream/series/tt0944947:1:2.json" in fake.paths())
    }

    @Test fun `entries without an http url are counted as unsupported and odd hints never drop a stream`() = runBlocking {
        fake.streams = {
            json(
                200,
                """{"streams":[
                {"name":"torrent","infoHash":"0123456789abcdef0123456789abcdef01234567","fileIdx":0},
                {"name":"yt","ytId":"abc"},
                {"name":"ext","externalUrl":"https://example.com/x"},
                {"name":"magnet","url":"magnet:?xt=urn:btih:0123"},
                {"name":"bad","url":5},
                {"name":"both","url":"https://debrid.example/REDACTED/a.mkv","infoHash":"0123456789abcdef0123456789abcdef01234567",
                 "description":"desc","behaviorHints":{"filename":"a.mkv","proxyHeaders":{"request":{"User-Agent":"UA","X-Bad":"a\r\nb","":"x"}}},"sizeBytes":1234},
                {"name":"float","title":"t","url":"http://192.168.1.20/b.mkv","behaviorHints":{"videoSize":1.5e9,"proxyHeaders":"weird"}}
                ]}""",
            )
        }
        val addon = manifestAddon("${fake.base}/addon-8/manifest.json", """{"name":"Comet | AD","resources":["stream"],"types":["movie"]}""")

        val result = client.streams(addon, ContentType.Movie, "tt0111161")

        assertEquals(5, result.unsupported)
        val (both, float) = result.streams
        assertEquals(listOf("both", "desc", "a.mkv"), listOf(both.name, both.description, both.filename))
        assertEquals(1234L, both.size)
        assertEquals(mapOf("User-Agent" to "UA"), both.headers)
        assertEquals(listOf("t", "http://192.168.1.20/b.mkv"), listOf(float.description, float.url))
        assertEquals(1_500_000_000L, float.size)
        assertEquals(emptyMap<String, String>(), float.headers)
    }

    @Test fun `a stalled addon fails within its own deadline`() {
        val slow = fake.client(streamTimeout = Duration.ofMillis(300))
        fake.streams = { MockResponse.Builder().code(200).body("""{"streams":[]}""").headersDelay(5, TimeUnit.SECONDS).build() }
        val started = System.nanoTime()

        val error = stremioFailure { runBlocking { slow.streams(named("Lumio · Profil"), ContentType.Movie, "tt0111161") } }

        assertEquals(StremioError(StremioErrorKind.Unreachable, addon = "Lumio · Profil"), error)
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(3))
    }

    @Test fun `cancelling a request stops waiting for its addon`() = runBlocking {
        val addon = named("Lumio · Profil")
        fake.streams = { MockResponse.Builder().code(200).body("""{"streams":[]}""").headersDelay(5, TimeUnit.SECONDS).build() }
        val request = launch { client.streams(addon, ContentType.Movie, "tt0111161") }
        delay(200)
        val started = System.nanoTime()

        request.cancelAndJoin()

        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(1))
    }

    @Test fun `no secret in a printed object, an error or its stack trace`() {
        val secrets = listOf(FIXTURE_KEY, "addon-4", "debrid.example", "SECRET-HEADER", "127.0.0.1")
        fake.streams = {
            json(200, """{"streams":[{"url":"https://debrid.example/x.mkv","behaviorHints":{"proxyHeaders":{"request":{"X-Key":"SECRET-HEADER"}}}}]}""")
        }
        val addon = named("Lumio · Profil")
        val result = runBlocking { client.streams(addon, ContentType.Movie, "tt0111161") }
        val printed = listOf(AuthKey(FIXTURE_KEY), addon, result, result.streams.single(), ResolvedStream("https://debrid.example/x.mkv", mapOf("X-Key" to "SECRET-HEADER"), "tt0111161"))
        // An addon that drops the connection, and an answer cut in the middle of the key
        fake.streams = { MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build() }
        val unreachable = runCatching { runBlocking { client.streams(addon, ContentType.Movie, "tt0111161") } }.exceptionOrNull()!!
        fake.collection = { json(200, """{"result":{"addons":[{"transportUrl":"$FIXTURE_KEY""") }
        val truncated = runCatching { addons() }.exceptionOrNull()!!

        val text = printed.joinToString("\n") + unreachable.stackTraceToString() + truncated.stackTraceToString()

        secrets.forEach { assertFalse("$it in:\n$text", it in text) }
    }
}
