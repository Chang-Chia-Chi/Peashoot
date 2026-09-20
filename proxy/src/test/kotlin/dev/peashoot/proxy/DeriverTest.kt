package dev.peashoot.proxy

import dev.peashoot.core.FrameParser
import dev.peashoot.core.Usage
import dev.peashoot.core.text
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** The event line, seen where it lands: the events file, the event table, and the session view. */
class DeriverTest {
    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/anthropic-messages/$name")) { name }
            .readBytes()

    private fun home(): Path = Files.createTempDirectory("peashoot-home")

    private fun streamReply(name: String, headers: Map<String, List<String>> = emptyMap()) =
        FakeUpstream.Reply(
            contentType = ContentType.Text.EventStream,
            frames = FrameParser.parse(fixture(name)).map { it.raw },
            headers = headers,
        )

    /** The lines the deriver wrote, parsed. Read once the server has stopped. */
    private fun events(home: Path): List<JsonObject> =
        home.resolve(EVENTS_FILE).readLines().map { Json.parseToJsonElement(it).jsonObject }

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun JsonObject.text(key: String): String? = this[key].text()

    /** A recorder and a deriver over [store], relaying to a fake upstream that answers [reply]. */
    private suspend fun withProxy(
        store: Store,
        home: Path,
        reply: FakeUpstream.Reply,
        gource: GourceLog? = null,
        block: suspend (String) -> Unit,
    ) {
        FakeUpstream().use { upstream ->
            upstream.reply = { reply }
            val config = ProxyConfig(port = 0, anthropicUpstream = upstream.url)
            val deriver = Deriver(store, home.resolve(EVENTS_FILE), gource = gource)
            val chain = listOf(Recorder(store), deriver)
            ProxyServer(config, chain).use { proxy -> block(proxy.url) }
        }
    }

    private suspend fun post(url: String, body: String, headers: Map<String, String>) =
        HttpClient(CIO).use { client ->
            val response =
                client.post("$url/v1/messages") {
                    headers.forEach { (name, value) -> header(name, value) }
                    setBody(body)
                }
            response.status.value to response.bodyAsText()
        }

    @Test
    fun `a Claude Code turn writes a started and a completed line carrying the whole event`() =
        runBlocking {
            val home = home()
            Store(home).use { store ->
                val reply = streamReply("stream-with-file-tools.sse", RATE_LIMIT_HEADERS)
                withProxy(store, home, reply) { url ->
                    assertEquals(200, post(url, CLAUDE_CODE_REQUEST, CLAUDE_CODE_HEADERS).first)
                }

                val lines = events(home)
                assertEquals(2, lines.size, "$lines")
                val (started, completed) = lines
                assertStarted(started)
                assertCompleted(completed)
                assertEquals(started.text("exchangeId"), completed.text("exchangeId"))
                assertEquals(
                    lines,
                    store.events().values.toList(),
                    "the event table mirrors the file",
                )
                assertSession(store.sessions().single())
                assertTrue(Files.notExists(home.resolve(GOURCE_FILE)), "the flag was off")
            }
        }

    private fun assertStarted(started: JsonObject) {
        assertEquals("exchange.started", started.text("event"))
        assertNotEquals("", assertNotNull(started.text("exchangeId")))
        Instant.parse(assertNotNull(started.text("ts"))) // ISO-8601, or this throws
        assertEquals("sess-1", started.text("session"))
        assertEquals("agent-1", started.text("agent"))
        assertEquals("parent-1", started.text("parentAgent"))
        assertEquals("claude-code", started.text("client"))
        assertEquals("anthropic-messages", started.text("surface"))
        assertEquals(MODEL, started.text("model"))
        assertEquals(DEFAULT_ROUTE, started.text("route"))
        assertEquals("record", started.text("mode"))
        // The last message feeds back one tool_result: 11 bytes of "package dev".
        assertEquals(json("""[{"name":"Read","bytes":11}]"""), started.getValue("toolResults"))
    }

    private fun assertCompleted(completed: JsonObject) {
        assertEquals("exchange.completed", completed.text("event"))
        assertEquals("claude-code", completed.text("client"))
        assertEquals(MODEL, completed.text("model"))
        assertEquals(json(TOOLS), completed.getValue("tools"))
        assertEquals(json(USAGE), completed.getValue("usage"))
        // 1200 in at $3/M, 95 out at $15/M, 8000 cache reads at $0.30/M, 300 writes at $3.75/M.
        assertEquals(0.00855, completed.getValue("costUsd").jsonPrimitive.double, TOLERANCE)
        assertEquals("tool_use", completed.text("stopReason"))
        assertEquals(200, completed.getValue("status").jsonPrimitive.int)
        val firstByte = completed.getValue("firstByteMs").jsonPrimitive.long
        val latency = completed.getValue("latencyMs").jsonPrimitive.long
        assertTrue(firstByte in 0..latency, "firstByteMs $firstByte, latencyMs $latency")
        assertFalse(completed.getValue("clientDisconnected").jsonPrimitive.boolean)
        assertEquals(json(RATE_LIMIT), completed.getValue("rateLimit"))
    }

    private fun assertSession(row: Session) {
        assertEquals("sess-1", row.session)
        assertEquals("agent-1", row.agent)
        assertEquals(1, row.exchanges)
        assertEquals(FIXTURE_USAGE, row.usage)
        assertEquals(0.00855, assertNotNull(row.costUsd), TOLERANCE)
    }

    @Test
    fun `oauth traffic is completed with its usage and no cost`() = runBlocking {
        val home = home()
        Store(home).use { store ->
            val oauth = CLAUDE_CODE_HEADERS + ("anthropic-beta" to "oauth-2025-04-20")
            withProxy(store, home, streamReply("stream-with-file-tools.sse")) { url ->
                assertEquals(200, post(url, CLAUDE_CODE_REQUEST, oauth).first)
            }

            val completed = events(home).last()
            assertEquals(json(USAGE), completed.getValue("usage"))
            assertEquals(
                JsonNull,
                completed.getValue("costUsd"),
                "subscription traffic is unpriced",
            )
            assertNull(store.sessions().single().costUsd)
        }
    }

    @Test
    fun `an SDK with no session header shares one fallback session across a conversation`() =
        runBlocking {
            val home = home()
            Store(home).use { store ->
                val headers = mapOf("x-stainless-lang" to "python")
                withProxy(store, home, streamReply("stream-with-file-tools.sse")) { url ->
                    assertEquals(200, post(url, ONE_TURN, headers).first)
                    assertEquals(200, post(url, TWO_TURNS, headers).first)
                    assertEquals(200, post(url, OTHER_CONVERSATION, headers).first)
                }

                val started = events(home).filter { it.text("event") == "exchange.started" }
                assertEquals(3, started.size, "$started")
                assertEquals(List(3) { "sdk-python" }, started.map { it.text("client") })
                val sessions = started.map { it.text("session") }
                assertEquals(sessions[0], sessions[1], "one conversation, two turns")
                assertNotEquals(sessions[0], sessions[2], "another first message, another session")
                assertEquals(listOf(2, 1), store.sessions().map { it.exchanges }.sortedDescending())
            }
        }

    @Test
    fun `the session view aggregates tokens and cost per session and agent`() = runBlocking {
        val home = home()
        Store(home).use { store ->
            val first = mapOf("x-claude-code-session-id" to "S", "x-claude-code-agent-id" to "A")
            val second = mapOf("x-claude-code-session-id" to "S", "x-claude-code-agent-id" to "B")
            withProxy(store, home, streamReply("stream-with-file-tools.sse")) { url ->
                assertEquals(200, post(url, CLAUDE_CODE_REQUEST, first).first)
                assertEquals(200, post(url, CLAUDE_CODE_REQUEST, first).first)
                assertEquals(200, post(url, CLAUDE_CODE_REQUEST, second).first)
            }

            val rows = store.sessions()
            assertEquals(listOf("S", "S"), rows.map { it.session })
            assertEquals(listOf("A", "B"), rows.map { it.agent })
            assertEquals(listOf(2, 1), rows.map { it.exchanges })
            assertEquals(
                Usage(input = 2400, output = 190, cacheRead = 16000, cacheWrite = 600),
                rows[0].usage,
            )
            assertEquals(FIXTURE_USAGE, rows[1].usage)
            assertEquals(0.0171, assertNotNull(rows[0].costUsd), TOLERANCE)
            assertEquals(0.00855, assertNotNull(rows[1].costUsd), TOLERANCE)
        }
    }

    @Test
    fun `an upstream error completes with its status and nothing to derive`() = runBlocking {
        val home = home()
        val body = """{"type":"error","error":{"type":"overloaded_error"}}"""
        Store(home).use { store ->
            val gource = GourceLog(home.resolve(GOURCE_FILE))
            withProxy(store, home, FakeUpstream.Reply(status = 529, body = body), gource) { url ->
                assertEquals(529, post(url, CLAUDE_CODE_REQUEST, CLAUDE_CODE_HEADERS).first)
            }

            val completed = events(home).last()
            assertEquals(529, completed.getValue("status").jsonPrimitive.int)
            assertEquals(JsonNull, completed.getValue("usage"))
            assertEquals(JsonNull, completed.getValue("costUsd"))
            assertEquals(JsonNull, completed.getValue("stopReason"))
            assertEquals(json("[]"), completed.getValue("tools"))
            assertTrue(Files.notExists(home.resolve(GOURCE_FILE)), "no file tool, no Gource line")
            val row = store.sessions().single()
            assertEquals(Usage(input = 0, output = 0, cacheRead = 0, cacheWrite = 0), row.usage)
            assertNull(row.costUsd, "a session whose every exchange was unpriced has no cost")
        }
    }

    @Test
    fun `a proxy-side failure still writes both lines, with no first byte`() = runBlocking {
        val home = home()
        val closedPort = ServerSocket(0).use { it.localPort }
        Store(home).use { store ->
            val config = ProxyConfig(port = 0, anthropicUpstream = "http://127.0.0.1:$closedPort")
            val chain = listOf(Recorder(store), Deriver(store, home.resolve(EVENTS_FILE)))
            ProxyServer(config, chain).use { proxy ->
                assertEquals(502, post(proxy.url, CLAUDE_CODE_REQUEST, CLAUDE_CODE_HEADERS).first)
            }

            val lines = events(home)
            assertEquals(
                listOf("exchange.started", "exchange.completed"),
                lines.map { it.text("event") },
            )
            val completed = lines.last()
            assertEquals(502, completed.getValue("status").jsonPrimitive.int)
            assertEquals(JsonNull, completed.getValue("firstByteMs"), "no frame ever arrived")
            assertEquals(JsonNull, completed.getValue("rateLimit"))
        }
    }

    @Test
    fun `a model the price table does not know is warned about once, and its cost is null`() =
        runBlocking {
            val appLog = Path.of(System.getProperty("peashoot.test.appLog"))
            val home = home()
            val model = "claude-unreleased-9"
            val frames =
                FrameParser.parse(fixture("stream-with-file-tools.sse")).map {
                    it.raw.replace(MODEL, model)
                }
            Store(home).use { store ->
                val reply =
                    FakeUpstream.Reply(contentType = ContentType.Text.EventStream, frames = frames)
                withProxy(store, home, reply) { url ->
                    repeat(2) {
                        assertEquals(200, post(url, CLAUDE_CODE_REQUEST, CLAUDE_CODE_HEADERS).first)
                    }
                }

                val completed = events(home).filter { it.text("event") == "exchange.completed" }
                assertEquals(List(2) { model }, completed.map { it.text("model") })
                assertEquals(List(2) { JsonNull }, completed.map { it.getValue("costUsd") })
                assertEquals(
                    json(USAGE),
                    completed.first().getValue("usage"),
                    "usage still reported",
                )
            }
            // Written inside onComplete, which the server's close waits for.
            val warnings = Files.readAllLines(appLog).filter { "no price for $model" in it }
            assertEquals(1, warnings.size, "one warning per unknown model, however many turns")
            assertContains(warnings.single(), "[pricing.\"$model\"]")
        }

    @Test
    fun `a store that cannot write costs the event table only, never the file or the client`() =
        runBlocking {
            val home = home()
            val fixture = fixture("stream-with-file-tools.sse")
            val closed = Store(home()).also { it.close() } // every write fails from here on
            withProxy(closed, home, streamReply("stream-with-file-tools.sse")) { url ->
                val (status, body) = post(url, CLAUDE_CODE_REQUEST, CLAUDE_CODE_HEADERS)
                assertEquals(200, status)
                assertEquals(fixture.decodeToString(), body)
            }

            assertEquals(
                listOf("exchange.started", "exchange.completed"),
                events(home).map { it.text("event") },
            )
        }

    @Test
    fun `with the Gource flag on, a completed turn appends one custom-format line per file tool`() =
        runBlocking {
            val home = home()
            Store(home).use { store ->
                val headers = CLAUDE_CODE_HEADERS + ("x-claude-code-session-id" to GOURCE_SESSION)
                val gource = GourceLog(home.resolve(GOURCE_FILE))
                val before = Instant.now().epochSecond
                withProxy(store, home, streamReply("stream-with-file-tools.sse"), gource) { url ->
                    assertEquals(200, post(url, CLAUDE_CODE_REQUEST, headers).first)
                }
                val after = Instant.now().epochSecond

                val lines = home.resolve(GOURCE_FILE).readLines()
                assertEquals(2, lines.size, "$lines")
                assertEquals(GOURCE_READ_THEN_EDIT, lines.map { it.substringAfter('|') })
                val stamp = lines.first().substringBefore('|').toLong()
                assertTrue(stamp in before..after, "$stamp not in $before..$after")
                assertEquals(2, events(home).size, "the events file still holds its two lines")
            }
        }

    @Test
    fun `the Gource log maps every file tool, slashes Windows paths, and skips a tool with no file it can carry`() =
        runBlocking {
            val home = home()
            Store(home).use { store ->
                val gource = GourceLog(home.resolve(GOURCE_FILE))
                val headers = mapOf("x-stainless-lang" to "python")
                val reply = FakeUpstream.Reply(body = EVERY_TOOL_REPLY)
                withProxy(store, home, reply, gource) { url ->
                    assertEquals(200, post(url, HELLO_REQUEST, headers).first)
                }

                val lines = home.resolve(GOURCE_FILE).readLines()
                assertEquals(GOURCE_EVERY_TOOL, lines.map { it.substringAfter('|') })
                val stamps = lines.map { it.substringBefore('|') }.toSet()
                assertEquals(1, stamps.size, "one timestamp for the whole turn: $stamps")
            }
        }

    private companion object {
        /** Dollars, so anything this close is the same money. */
        const val TOLERANCE = 1e-12

        const val MODEL = "claude-sonnet-4-5-20250929"

        val FIXTURE_USAGE = Usage(input = 1200, output = 95, cacheRead = 8000, cacheWrite = 300)

        val CLAUDE_CODE_HEADERS =
            mapOf(
                "x-claude-code-session-id" to "sess-1",
                "x-claude-code-agent-id" to "agent-1",
                "x-claude-code-parent-agent-id" to "parent-1",
                "anthropic-beta" to "kept",
            )

        val RATE_LIMIT_HEADERS =
            mapOf(
                "anthropic-ratelimit-tokens-remaining" to listOf("9000"),
                "anthropic-ratelimit-requests-remaining" to listOf("50"),
                "anthropic-ratelimit-tokens-reset" to listOf("2026-09-14T09:00:00Z"),
            )

        const val RATE_LIMIT =
            """{"remainingTokens":9000,"remainingRequests":50,""" +
                """"resetAt":"2026-09-14T09:00:00Z"}"""

        const val USAGE = """{"input":1200,"output":95,"cacheRead":8000,"cacheWrite":300}"""

        const val TOOLS =
            """[{"name":"Read","path":"src/Main.kt"},""" +
                """{"name":"Edit","path":"src/Main.kt"},""" +
                """{"name":"Bash","command":"./gradlew test"}]"""

        /** A turn that feeds back the result of the Read the assistant asked for. */
        const val CLAUDE_CODE_REQUEST =
            """{"model":"$MODEL","messages":[""" +
                """{"role":"user","content":"read src/Main.kt"},""" +
                """{"role":"assistant","content":[{"type":"tool_use","id":"toolu_1",""" +
                """"name":"Read","input":{"file_path":"src/Main.kt"}}]},""" +
                """{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1",""" +
                """"content":"package dev"}]}]}"""

        /** Turn one: the cache breakpoint sits on the first user message, because it is newest. */
        const val ONE_TURN =
            """{"model":"$MODEL","messages":[{"role":"user","content":[""" +
                """{"type":"text","text":"hello peashoot",""" +
                """"cache_control":{"type":"ephemeral"}}]}]}"""

        /** Turn two: the breakpoint has moved on, and the first message is bare again. */
        const val TWO_TURNS =
            """{"model":"$MODEL","messages":[""" +
                """{"role":"user","content":[{"type":"text","text":"hello peashoot"}]},""" +
                """{"role":"assistant","content":"hi"},""" +
                """{"role":"user","content":"and again"}]}"""

        const val OTHER_CONVERSATION =
            """{"model":"$MODEL","messages":[{"role":"user","content":"something else"}]}"""

        /** A Claude Code session id; the Gource user is the first eight characters of it. */
        const val GOURCE_SESSION = "0f8fad5b-d9cb-469f-a165-70867728950e"

        /** The Read and the Edit of `stream-with-file-tools.sse`; its Bash has no file. */
        val GOURCE_READ_THEN_EDIT =
            listOf("0f8fad5b|A|src/Main.kt|4CAF50", "0f8fad5b|M|src/Main.kt|FF9800")

        /** No session header, so the user is the first eight hex of SHA-256("hello"). */
        const val HELLO_REQUEST =
            """{"model":"$MODEL","messages":[{"role":"user","content":"hello"}]}"""

        /**
         * A non-streaming answer whose tool_use blocks cover every mapping the log makes, plus a
         * path with a pipe in it, which no line can carry.
         */
        const val EVERY_TOOL_REPLY =
            """{"type":"message","model":"$MODEL","content":[""" +
                """{"type":"tool_use","id":"t1","name":"Grep","input":{"path":"src"}},""" +
                """{"type":"tool_use","id":"t2","name":"Glob","input":{"path":"src/main"}},""" +
                """{"type":"tool_use","id":"t3","name":"Write",""" +
                """"input":{"file_path":"C:\\dev\\x\\A.kt"}},""" +
                """{"type":"tool_use","id":"t4","name":"NotebookEdit",""" +
                """"input":{"notebook_path":"nb.ipynb"}},""" +
                """{"type":"tool_use","id":"t5","name":"MultiEdit",""" +
                """"input":{"file_path":"b.kt"}},""" +
                """{"type":"tool_use","id":"t6","name":"Bash","input":{"command":"ls"}},""" +
                """{"type":"tool_use","id":"t7","name":"Fetch","input":{"path":"docs/x.md"}},""" +
                """{"type":"tool_use","id":"t8","name":"Read","input":{"file_path":"a|b.kt"}}],""" +
                """"usage":{"input_tokens":1,"output_tokens":2,""" +
                """"cache_read_input_tokens":3,"cache_creation_input_tokens":4},""" +
                """"stop_reason":"tool_use"}"""

        /**
         * SHA-256("hello") is 2cf24dba5fb0a30e26e83b2ac5b9e29e…, so the user is `2cf24dba`. Fetch
         * is not in the palette, so its line has no colour field and Gource colours it by
         * extension.
         */
        val GOURCE_EVERY_TOOL =
            listOf(
                "2cf24dba|A|src|26A69A",
                "2cf24dba|A|src/main|26C6DA",
                "2cf24dba|M|C:/dev/x/A.kt|F44336",
                "2cf24dba|M|nb.ipynb|FF9800",
                "2cf24dba|M|b.kt|FF9800",
                "2cf24dba|A|docs/x.md",
            )
    }
}
