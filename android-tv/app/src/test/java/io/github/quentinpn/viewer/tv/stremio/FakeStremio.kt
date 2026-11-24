package io.github.quentinpn.viewer.tv.stremio

import io.github.quentinpn.viewer.tv.emby.EmbyClient
import io.github.quentinpn.viewer.tv.request.viewerJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** The shared fixtures of contracts/stremio, read from the repository root (cwd of the unit tests is `android-tv/app`). */
internal fun fixture(name: String) = File("../../contracts/stremio/fixtures/$name.json").readText()

internal fun json(code: Int, body: String) = MockResponse.Builder().code(code).body(body).build()

private val STREAM_PATH = Regex("^/addon-([0-9]+)/stream/(movie|series)/(.+)\\.json$")

/**
 * The Stremio API and every addon of the fixture collection on one MockWebServer: each transportUrl is
 * rewritten to `{server}/addon-<n>/manifest.json`, and an addon answers its streams from the captured
 * `streams_*.json` (status 0, unreachable, closes the socket).
 */
internal class FakeStremio : AutoCloseable {
    val server = MockWebServer()
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    @Volatile var login: () -> MockResponse = { json(200, fixture("login_ok")) }

    @Volatile var collection: () -> MockResponse = { json(200, rewrittenCollection()) }

    /** Answers a stream request before the fixtures do, when not null. */
    @Volatile var streams: (path: String) -> MockResponse? = { null }

    private val captured = mapOf(
        "movie" to viewerJson.parseToJsonElement(fixture("streams_movie")).jsonObject,
        "series" to viewerJson.parseToJsonElement(fixture("streams_series")).jsonObject,
    )

    val base: String get() = "http://127.0.0.1:${server.port}"

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.url.encodedPath
                return when (path) {
                    "/api/login" -> login()
                    "/api/addonCollectionGet" -> collection()
                    else -> streams(path) ?: captured(path)
                }
            }
        }
        server.start()
    }

    fun paths(): List<String> = requests.map { it.url.encodedPath }

    fun client(log: (String) -> Unit = {}, streamTimeout: Duration = Duration.ofSeconds(10)) =
        StremioClient(EmbyClient.defaultHttp(), "test", apiBase = base.toHttpUrl(), streamTimeout = streamTimeout, log = log)

    /** The fixture collection pointing at this server; [overrides] replace whole entries by position. */
    fun rewrittenCollection(overrides: Map<Int, JsonElement> = emptyMap()): String {
        val root = viewerJson.parseToJsonElement(fixture("addon_collection")).jsonObject
        val result = root.getValue("result").jsonObject
        val addons = result.getValue("addons").jsonArray.mapIndexed { i, entry ->
            overrides[i] ?: JsonObject(entry.jsonObject + ("transportUrl" to JsonPrimitive("$base/addon-$i/manifest.json")))
        }
        return JsonObject(root + ("result" to JsonObject(result + ("addons" to JsonArray(addons))))).toString()
    }

    private fun captured(path: String): MockResponse {
        val (n, type) = STREAM_PATH.matchEntire(path)?.destructured ?: return json(404, "{}")
        val entry = captured.getValue(type)["addon-$n"]?.jsonObject ?: return json(404, "{}")
        return when (val status = entry.getValue("status").jsonPrimitive.int) {
            0 -> MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()
            200 -> json(200, entry.getValue("response").toString())
            else -> json(status, "{}")
        }
    }

    override fun close() = server.close()
}
