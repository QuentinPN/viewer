package io.github.quentinpn.viewer.tv.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The vectors shared with the desktop (ADR 0124): both sides read the same file. */
class SyncVectorsTest {
    private val vectors: JsonObject = Json.parseToJsonElement(vectorsFile().readText()).jsonObject

    private fun section(name: String): List<JsonObject> = vectors.getValue(name).jsonArray.map { it.jsonObject }

    private val JsonObject.name get() = getValue("name").jsonPrimitive.content

    @Test
    fun maxBytesMatches() {
        assertEquals(vectors.getValue("maxBytes").jsonPrimitive.int, MAX_ENVELOPE_BYTES)
    }

    @Test
    fun validEnvelopesParseWithTheirFields() {
        val valid = section("valid")
        assertTrue(valid.isNotEmpty())
        for (case in valid) {
            val parsed = parseEnvelope(case.getValue("raw").jsonPrimitive.content)
            assertTrue(case.name, parsed is Parsed.Ok)
            val envelope = (parsed as Parsed.Ok).envelope
            assertEquals(case.name, case.getValue("from").jsonPrimitive.content, envelope.from)
            assertEquals(case.name, case.getValue("link").jsonPrimitive.content, envelope.link)
            assertEquals(case.name, case.getValue("seq").jsonPrimitive.long, envelope.seq)
            assertEquals(case.name, case.getValue("kind").jsonPrimitive.content, envelope.body.kind.wire)
            assertEquals(case.name, case.getValue("fields").jsonObject, bodyFields(envelope))
            // What the TV writes reads back the same
            assertEquals(case.name, Parsed.Ok(envelope), parseEnvelope(encodeEnvelope(envelope)))
        }
    }

    @Test
    fun invalidEnvelopesAreRejectedWithTheirReason() {
        val invalid = section("invalid")
        assertTrue(invalid.isNotEmpty())
        for (case in invalid) {
            val raw = case.getValue("raw").jsonPrimitive.content
            assertEquals(case.name, Parsed.Rejected(Reject.entries.first { it.code == case.getValue("reason").jsonPrimitive.content }), parseEnvelope(raw))
        }
    }

    @Test
    fun acceptance() {
        for (case in section("accept")) {
            val state = case["state"]?.takeIf { it !is JsonNull }?.jsonObject
            val cursor = state?.let {
                LinkCursor(it.getValue("peer").jsonPrimitive.content, it.getValue("link").jsonPrimitive.content, it["lastSeq"]?.jsonPrimitive?.longOrNull)
            }
            val msg = case.getValue("msg").jsonObject
            val kind = Kind.entries.first { it.wire == msg.getValue("kind").jsonPrimitive.content }
            val expected = case.getValue("accept").jsonPrimitive.let { p ->
                when (p.booleanOrNull) {
                    true -> Acceptance.Accept
                    false -> Acceptance.Ignore
                    null -> Acceptance.Busy.also { assertEquals("busy", p.content) }
                }
            }
            val got = accept(cursor, msg.getValue("from").jsonPrimitive.content, msg.getValue("link").jsonPrimitive.content, msg.getValue("seq").jsonPrimitive.long, kind)
            assertEquals(case.name, expected, got)
        }
    }

    @Test
    fun rtt() {
        for (case in section("rtt")) {
            val samples = case.getValue("samples").jsonArray.map { it.jsonPrimitive.long }
            assertEquals(samples.toString(), case.getValue("rttMs").jsonPrimitive.long, rttMs(samples))
        }
    }

    @Test
    fun projection() {
        for (case in section("projection")) {
            val s = case.getValue("state").jsonObject
            val got = targetMs(
                s.getValue("positionMs").jsonPrimitive.long,
                s.getValue("paused").jsonPrimitive.boolean,
                s.getValue("buffering").jsonPrimitive.boolean,
                case.long("recvAt"),
                case.long("now"),
                case.long("rttMs"),
                case.long("offsetMs"),
            )
            assertEquals(case.name, case.long("targetMs"), got)
        }
    }

    @Test
    fun decision() {
        for (case in section("decide")) {
            val input = case.getValue("input").jsonObject
            val target = input.long("targetMs")
            val got = decide(
                input.long("tvPositionMs") - target,
                target,
                input.getValue("leaderPaused").jsonPrimitive.boolean,
                input.getValue("tvPaused").jsonPrimitive.boolean,
                input.getValue("correcting").jsonPrimitive.boolean,
                input.long("sinceSeekMs"),
            )
            val out = case.getValue("out").jsonObject
            val expected = Correction(
                pause = out["pause"]?.jsonPrimitive?.boolean ?: false,
                resume = out["resume"]?.jsonPrimitive?.boolean ?: false,
                speed = out["speed"]?.jsonPrimitive?.double,
                seekTo = out["seekTo"]?.jsonPrimitive?.long,
            )
            assertEquals(case.name, expected, got)
        }
    }

    private fun JsonObject.long(key: String) = getValue(key).jsonPrimitive.long

    /** The body's own fields, as the envelope carries them. */
    private fun bodyFields(envelope: Envelope): JsonObject {
        val all = Json.parseToJsonElement(encodeEnvelope(envelope)).jsonObject
        return JsonObject(all.filterKeys { it !in HEADER })
    }

    private companion object {
        val HEADER = setOf("tv", "v", "from", "link", "seq", "kind")

        /** `docs/sync/vectors.json`, found from the test's working directory up to the repository root. */
        fun vectorsFile(): File {
            var dir: File? = File("").absoluteFile
            while (dir != null) {
                val file = File(dir, "docs/sync/vectors.json")
                if (file.isFile) return file
                dir = dir.parentFile
            }
            error("docs/sync/vectors.json not found above ${File("").absolutePath}")
        }
    }
}
