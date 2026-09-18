package dev.peashoot.proxy

import dev.peashoot.core.RULES_FILE
import dev.peashoot.core.Rules
import dev.peashoot.core.text
import io.ktor.client.request.get
import io.ktor.http.HttpMethod
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The control API's writing half (#15): the rule set and what a candidate one would do to the
 * recordings, cassettes, the config and what of it needs a restart, and shutdown.
 */
class ControlAdminTest {
    @Test
    fun `a rule set that cannot be used is a 400 naming it, and nothing is saved`() = withProxy {
        val before = home.resolve(RULES_FILE).readText()
        listOf(
                """{"ignorePointers":["metadata"]}""" to "ignorePointers[0]",
                """{"replace":[{"pointer":"/a","pattern":"[","replacement":""}]}""" to
                    "replace[0].pattern",
                """{"replace":[{"pointer":"/a","pattern":"x","replacement":"$1"}]}""" to
                    "replace[0].replacement",
                """{"replacements":[]}""" to "replacements",
                "not json" to RULES_FILE,
                """["keepHeaders"]""" to RULES_FILE,
            )
            .forEach { (body, named) ->
                assertContains(assertProblem(call(HttpMethod.Put, "/rules", body), 400), named)
            }
        assertEquals(before, home.resolve(RULES_FILE).readText(), "the file is untouched")
        assertEquals(Rules.DEFAULT.toJson(), json("/rules"), "and so is the live rule set")
    }

    @Test
    fun `getting the rules round-trips what putting saved, and the next request uses them`() =
        withProxy {
            val saved =
                okJson(
                    call(
                        HttpMethod.Put,
                        "/rules",
                        """{"keepHeaders":["Content-Type"],"ignorePointers":["/trace"]}""",
                    )
                )
            assertEquals(
                listOf("content-type"),
                saved.getValue("keepHeaders").jsonArray.map { it.text() },
            )
            assertEquals(saved, json("/rules"))
            assertEquals(
                saved,
                Json.parseToJsonElement(home.resolve(RULES_FILE).readText()).jsonObject,
                "what a restart would read back",
            )

            relay("""{"model":"m","messages":[],"trace":"1"}""")
            relay("""{"model":"m","messages":[],"trace":"2"}""")
            awaitRecordings(2)
            assertEquals(
                1,
                store.list().map { it.exchange.fingerprint }.toSet().size,
                "the new rules ignore /trace, with no restart",
            )
        }

    @Test
    fun `the rules test reports what a candidate set would collide and split`() = withProxy {
        listOf(
                """{"model":"m","messages":[],"metadata":{"user":"a"}}""",
                """{"model":"m","messages":[],"metadata":{"user":"b"}}""",
                """{"model":"m","messages":[],"trace":"1"}""",
                """{"model":"m","messages":[],"trace":"2"}""",
            )
            .forEach { relay(it) }
        awaitRecordings(4)
        val recorded = store.list().asReversed()
        val ids = recorded.map { it.exchange.id }
        val shared = recorded.first().exchange.fingerprint
        val before = home.resolve(RULES_FILE).readText()

        val result = okJson(call(HttpMethod.Post, "/rules/test", """{"rules":$CANDIDATE}"""))
        assertEquals(4, result.getValue("tested").jsonPrimitive.int)
        // `/trace` ignored: two requests that differ only there become one fingerprint.
        val collision = result.getValue("collisions").jsonArray.single().jsonObject
        assertEquals(
            setOf(ids[2], ids[3]),
            collision.getValue("exchangeIds").jsonArray.map { it.text() }.toSet(),
        )
        // `/metadata` no longer ignored: two requests that share one now stop sharing it.
        val split = result.getValue("splits").jsonArray.single().jsonObject
        assertEquals(shared, split["fingerprint"]?.text())
        assertEquals(
            setOf(setOf(ids[0]), setOf(ids[1])),
            split
                .getValue("groups")
                .jsonArray
                .map { group -> group.jsonArray.map { it.text() }.toSet() }
                .toSet(),
        )
        assertEquals(before, home.resolve(RULES_FILE).readText(), "a test saves nothing")
        assertEquals(Rules.DEFAULT.toJson(), json("/rules"))

        assertEquals(
            2,
            okJson(call(HttpMethod.Post, "/rules/test", """{"rules":$CANDIDATE,"lastN":2}"""))
                .getValue("tested")
                .jsonPrimitive
                .int,
        )
        listOf("0", "-1", "\"ten\"", "${MAX_EXCHANGES_LIMIT + 1}").forEach {
            val body = """{"rules":$CANDIDATE,"lastN":$it}"""
            assertProblem(call(HttpMethod.Post, "/rules/test", body), 400)
        }
        assertContains(
            assertProblem(call(HttpMethod.Post, "/rules/test", """{"lastN":2}"""), 400),
            "rules",
        )
    }

    @Test
    fun `the rules test reads live recordings only, never an imported cassette's rows`() =
        withProxy {
            relay("""{"model":"m","messages":[]}""")
            awaitRecordings(1)
            okJson(call(HttpMethod.Post, "/cassettes/export", EXPORT))
            val file = home.resolve(CASSETTES_DIR).resolve("demo.jsonl")
            okJson(call(HttpMethod.Post, "/cassettes/import", path(file)))
            assertEquals(2, store.list().size, "the recording, and the cassette's copy of it")

            val result = okJson(call(HttpMethod.Post, "/rules/test", """{"rules":$CANDIDATE}"""))
            assertEquals(
                1,
                result.getValue("tested").jsonPrimitive.int,
                "an imported row carries another machine's fingerprint and a redacted body",
            )
        }

    @Test
    fun `a dry run previews the redaction and writes nothing, and a real export imports back`() =
        withProxy {
            relay("""{"model":"m","messages":[{"role":"user","content":"use $KEY"}]}""")
            awaitRecordings(1)
            val id = store.list().single().exchange.id

            val dry = okJson(call(HttpMethod.Post, "/cassettes/export?dryRun=true", EXPORT))
            assertEquals(1, dry.getValue("exchanges").jsonPrimitive.int)
            assertEquals(1, dry.getValue("redactions").jsonPrimitive.int)
            val hit = dry.getValue("preview").jsonArray.single().jsonObject
            assertEquals(id, hit["exchangeId"]?.text())
            assertEquals("request /messages/0/content", hit["where"]?.text())
            assertEquals("[REDACTED]", hit["becomes"]?.text())
            assertContains(hit.getValue("matched").jsonPrimitive.content, "(${KEY.length} chars)")
            assertFalse(KEY in hit.toString(), "a preview masks what it matched")
            assertFalse(Files.exists(home.resolve(CASSETTES_DIR)), "a dry run writes nothing")

            val wrote = okJson(call(HttpMethod.Post, "/cassettes/export", EXPORT))
            val file = home.resolve(CASSETTES_DIR).resolve("demo.jsonl")
            assertEquals(file.toString(), wrote["path"]?.text())
            assertEquals(1, wrote.getValue("exchanges").jsonPrimitive.int)
            assertFalse(KEY in file.readText(), "the cassette carries no key")

            val imported = okJson(call(HttpMethod.Post, "/cassettes/import", path(file)))
            assertEquals("demo", imported["name"]?.text())
            assertEquals(1, imported.getValue("exchanges").jsonPrimitive.int)

            val listed = json("/cassettes").getValue("cassettes").jsonArray.single().jsonObject
            assertEquals("demo", listed["name"]?.text())
            assertEquals(1, listed.getValue("exchanges").jsonPrimitive.int)
            assertEquals(file.toString(), listed["path"]?.text())
            assertEquals(Files.size(file), listed.getValue("bytes").jsonPrimitive.content.toLong())
            assertTrue("modified" in listed, "$listed")
        }

    @Test
    fun `an export or import that cannot be done is a 400 naming why`() = withProxy {
        val junk = Files.createTempFile("not-a-cassette", ".jsonl")
        Files.writeString(junk, "{}\n")
        // A file that is not text at all: readable bytes, no UTF-8 in them.
        val binary = Files.createTempFile("binary", ".jsonl")
        Files.write(binary, byteArrayOf(-1, -2, 0, -1))
        listOf(
                "/cassettes/export" to """{"name":"../etc"}""",
                "/cassettes/export" to """{"name":7}""",
                "/cassettes/export" to "{}",
                "/cassettes/import" to """{"path":""}""",
                "/cassettes/import" to """{"path":"no-such-file.jsonl"}""",
                "/cassettes/import" to path(junk),
                "/cassettes/import" to path(binary),
            )
            .forEach { (path, body) -> assertProblem(call(HttpMethod.Post, path, body), 400) }
        assertFalse(Files.exists(home.resolve(CASSETTES_DIR)), "nothing was written")
    }

    @Test
    fun `shutdown answers first, then the server stops accepting`() = withProxy {
        // What `serve` does: waiting on the same deferred, and closing as soon as it completes. It
        // is racing the answer from the moment the handler completes it, so a shutdown that stopped
        // the engine before writing would lose the body here.
        val stopped = coroutineScope {
            launch {
                withTimeout(SHUTDOWN_TIMEOUT) { control.stopping.await() }
                server.close()
            }
            okJson(call(HttpMethod.Post, "/shutdown"))
        }
        assertTrue(stopped.getValue("stopping").jsonPrimitive.content.toBoolean(), "$stopped")
        assertFails { client.get("$base/health") }
        assertEquals(0, upstream.received.size, "stopping is never relayed")
    }

    /** A `{"path": ...}` body, with the separators a JSON string needs on Windows. */
    private fun path(of: java.nio.file.Path) = """{"path":${JsonPrimitive(of.toString())}}"""

    private companion object {
        /** Long enough for the default redaction rule, which wants 20 key characters. */
        const val KEY = "sk-ant-api03-ABCDEFGHIJKLMNOPQRSTUVWX"
        const val EXPORT = """{"name":"demo"}"""
        const val SHUTDOWN_TIMEOUT = 5_000L
        /** `/metadata` no longer ignored, `/trace` now ignored: one split and one collision. */
        const val CANDIDATE =
            """{"keepHeaders":["content-type"],"ignorePointers":["/trace"],"replace":[]}"""
    }
}
