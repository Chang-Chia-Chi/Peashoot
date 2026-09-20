package dev.peashoot.proxy

import dev.peashoot.core.FrameParser
import dev.peashoot.core.Mode
import dev.peashoot.core.Route
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The Chat Completions surface at the proxy's HTTP boundary: which upstream a request reaches, what
 * comes back, what the store keeps, and what the event line says about it.
 */
class ChatCompletionsSeamTest {
    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/openai-chat/$name")) { name }.readBytes()

    private fun frames(name: String): List<String> = FrameParser.parse(fixture(name)).map { it.raw }

    private fun home(): Path = Files.createTempDirectory("peashoot-home")

    /**
     * The Anthropic upstream is a port nothing listens on, so a request that took the wrong surface
     * fails loudly rather than quietly reaching the only server the test started.
     */
    private fun config(
        openai: FakeUpstream,
        anthropic: FakeUpstream? = null,
        mode: Mode = Mode.RECORD,
    ) =
        ProxyConfig(
            port = 0,
            anthropicUpstream = anthropic?.url ?: DEAD_UPSTREAM,
            openaiUpstream = openai.url,
            routes = mapOf(DEFAULT_ROUTE to Route(mode)),
        )

    private fun streamReply(frames: List<String>) =
        FakeUpstream.Reply(
            contentType = ContentType.Text.EventStream,
            frames = frames,
            headers = mapOf("x-ratelimit-remaining-tokens" to "9000"),
        )

    private suspend fun post(
        proxy: ProxyServer,
        body: String = REQUEST,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse =
        HttpClient(CIO).use { client ->
            client.post("${proxy.url}/v1/chat/completions") {
                headers.forEach { (name, value) -> header(name, value) }
                setBody(body)
            }
        }

    /** The client can end its response before the recorder's write lands, so wait for the row. */
    private suspend fun awaitRecordings(store: Store, count: Int) =
        withTimeout(TIMEOUT_MS) { while (store.list().size < count) delay(POLL_MS) }

    private fun completedEvent(events: Path): JsonObject =
        events
            .readLines()
            .map { Json.parseToJsonElement(it).jsonObject }
            .single { it["event"]?.jsonPrimitive?.content == "exchange.completed" }

    @Test
    fun `a streamed turn records, then replays byte-equal with zero upstream calls`() =
        runBlocking {
            val home = home()
            val recorded = home.resolve("recording-$EVENTS_FILE")
            val replayed = home.resolve(EVENTS_FILE)
            val frames = frames("stream-with-tool-calls.sse")
            // What the client was given on the way past the real upstream. The replay is compared
            // to this, not only to the file: the claim is that a replay reproduces the recording.
            var live = ""
            Store(home).use { store ->
                FakeUpstream().use { upstream ->
                    upstream.reply = { streamReply(frames) }
                    val record = config(upstream)
                    val recording =
                        listOf(Replay(store, record), Recorder(store), Deriver(store, recorded))
                    ProxyServer(record, recording).use { proxy -> live = post(proxy).bodyAsText() }
                    awaitRecordings(store, 1)
                    upstream.received.clear()

                    val replay = config(upstream, mode = Mode.REPLAY)
                    val chain =
                        listOf(Replay(store, replay), Recorder(store), Deriver(store, replayed))
                    ProxyServer(replay, chain).use { proxy ->
                        val response = post(proxy)
                        assertEquals(200, response.status.value)
                        assertEquals(live, response.bodyAsText(), "the replay is the recording")
                        assertEquals(
                            "9000",
                            response.headers["x-ratelimit-remaining-tokens"],
                            "a replay serves the recorded headers too",
                        )
                    }
                    assertEquals(0, upstream.received.size, "a hit never reaches the upstream")
                }
                assertEquals(1, store.list().size, "a replay hit is never re-recorded")
            }
            // And what the client was given is the upstream's own bytes, the done marker and the
            // blank line after it included, so both passes are byte-equal to the provider.
            assertContentEquals(fixture("stream-with-tool-calls.sse"), live.toByteArray())
            assertTrue(frames.last().trimEnd().endsWith("[DONE]"), frames.last())

            assertToolTurn(completedEvent(recorded))
            assertToolTurn(completedEvent(replayed))
            assertBilling(completedEvent(recorded), completedEvent(replayed))
        }

    /**
     * The recording reported usage and still costs nothing to name, because the bundled price table
     * carries no OpenAI model: an unpriced model is a null cost, never a free turn. The replay of
     * it costs 0.0 for the other reason — it was billed nothing at all.
     */
    private fun assertBilling(recorded: JsonObject, replayed: JsonObject) {
        assertNotEquals(JsonNull, recorded.getValue("usage"), "the recording read the usage chunk")
        assertEquals(JsonNull, recorded.getValue("costUsd"), "and no gpt model has a price here")
        assertFalse(recorded.getValue("replayHit").jsonPrimitive.boolean)
        assertTrue(replayed.getValue("replayHit").jsonPrimitive.boolean)
        assertEquals("0.0", replayed.getValue("costUsd").jsonPrimitive.content)
    }

    /** What the deriver read out of that turn's frames, which is the whole point of the surface. */
    private fun assertToolTurn(completed: JsonObject) {
        assertEquals("openai-chat", completed.getValue("surface").jsonPrimitive.content)
        assertEquals("gpt-4o-mini-2024-07-18", completed.getValue("model").jsonPrimitive.content)
        assertEquals("tool_calls", completed.getValue("stopReason").jsonPrimitive.content)
        assertEquals(
            """{"input":28,"output":48,"cacheRead":64,"cacheWrite":0}""",
            completed.getValue("usage").toString(),
            "92 prompt tokens of which 64 cached, counted once",
        )
        val tools = completed.getValue("tools").jsonArray
        assertEquals(
            listOf("Read", "Bash"),
            tools.map { it.jsonObject.getValue("name").jsonPrimitive.content },
            "both calls, each assembled from fragments under its own index",
        )
        assertEquals("src/main.kt", tools.first().jsonObject["path"]?.jsonPrimitive?.content)
        assertEquals(
            "9000",
            completed
                .getValue("rateLimit")
                .jsonObject
                .getValue("remainingTokens")
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun `a stream that reported no usage says so on the event line, never zero`() = runBlocking {
        val home = home()
        Store(home).use { store ->
            FakeUpstream().use { upstream ->
                upstream.reply = { streamReply(frames("stream-without-usage.sse")) }
                val config = config(upstream)
                ProxyServer(
                        config,
                        listOf(Recorder(store), Deriver(store, home.resolve(EVENTS_FILE))),
                    )
                    .use { proxy -> post(proxy).bodyAsText() }
                awaitRecordings(store, 1)
            }
        }
        val completed = completedEvent(home.resolve(EVENTS_FILE))
        assertEquals(JsonNull, completed.getValue("usage"))
        assertEquals(
            JsonNull,
            completed.getValue("costUsd"),
            "no usage is no cost, not a free turn",
        )
        assertEquals("stop", completed.getValue("stopReason").jsonPrimitive.content)
        assertEquals(0, completed.getValue("tools").jsonArray.size)
    }

    @Test
    fun `a non-streaming body gives its usage, finish reason, and tool calls`() = runBlocking {
        val home = home()
        val body = fixture("non-streaming-tool-calls.json").decodeToString()
        Store(home).use { store ->
            FakeUpstream().use { upstream ->
                upstream.reply = { FakeUpstream.Reply(body = body) }
                val config = config(upstream)
                ProxyServer(
                        config,
                        listOf(Recorder(store), Deriver(store, home.resolve(EVENTS_FILE))),
                    )
                    .use { proxy ->
                        assertEquals(body, post(proxy, NON_STREAMING_REQUEST).bodyAsText())
                    }
                awaitRecordings(store, 1)
            }
        }
        val completed = completedEvent(home.resolve(EVENTS_FILE))
        assertEquals(
            """{"input":80,"output":17,"cacheRead":0,"cacheWrite":0}""",
            completed.getValue("usage").toString(),
        )
        assertEquals("tool_calls", completed.getValue("stopReason").jsonPrimitive.content)
        assertEquals(
            "echo peashoot",
            completed
                .getValue("tools")
                .jsonArray
                .single()
                .jsonObject
                .getValue("command")
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun `an upstream 429 is recorded and replayed with its own status and body`() = runBlocking {
        Store(home()).use { store ->
            FakeUpstream().use { upstream ->
                upstream.reply = { FakeUpstream.Reply(status = 429, body = RATE_LIMITED) }
                val record = config(upstream)
                ProxyServer(record, listOf(Recorder(store))).use { proxy ->
                    assertEquals(429, post(proxy).status.value)
                }
                awaitRecordings(store, 1)
                upstream.received.clear()

                val replay = config(upstream, mode = Mode.REPLAY)
                ProxyServer(replay, listOf(Replay(store, replay), Recorder(store))).use { proxy ->
                    val response = post(proxy)
                    assertEquals(429, response.status.value)
                    assertEquals(RATE_LIMITED, response.bodyAsText())
                }
                assertEquals(0, upstream.received.size)
            }
        }
    }

    @Test
    fun `each surface is relayed to its own upstream`() = runBlocking {
        FakeUpstream().use { openai ->
            FakeUpstream().use { anthropic ->
                val config = config(openai, anthropic, Mode.PASSTHROUGH)
                ProxyServer(config).use { proxy ->
                    HttpClient(CIO).use { client ->
                        client.post("${proxy.url}/v1/chat/completions") { setBody(REQUEST) }
                        client.post("${proxy.url}/v1/messages") { setBody(MESSAGES_REQUEST) }
                    }
                }
                assertEquals(listOf("/v1/chat/completions"), openai.received.map { it.uri })
                assertEquals(listOf("/v1/messages"), anthropic.received.map { it.uri })
            }
        }
    }

    @Test
    fun `model listing passes through to the upstream its sender names`() = runBlocking {
        FakeUpstream().use { openai ->
            FakeUpstream().use { anthropic ->
                val config = config(openai, anthropic, Mode.PASSTHROUGH)
                ProxyServer(config).use { proxy ->
                    HttpClient(CIO).use { client ->
                        client.get("${proxy.url}/v1/models") {
                            header("x-stainless-lang", "python")
                            header(HttpHeaders.UserAgent, "OpenAI/Python 1.109.1")
                        }
                        client.get("${proxy.url}/v1/models") {
                            header("anthropic-version", "2023-06-01")
                        }
                        client.get("${proxy.url}/v1/models")
                    }
                }
                assertEquals(1, openai.received.size, "the SDK's own headers name the OpenAI one")
                assertEquals(
                    2,
                    anthropic.received.size,
                    "Anthropic's version header, and the request that said nothing",
                )
            }
        }
    }

    @Test
    fun `the OpenAI SDK is detected from its headers, and its key is never kept`() = runBlocking {
        val home = home()
        Store(home).use { store ->
            FakeUpstream().use { upstream ->
                upstream.reply = { streamReply(frames("stream-without-usage.sse")) }
                val config = config(upstream)
                ProxyServer(
                        config,
                        listOf(Recorder(store), Deriver(store, home.resolve(EVENTS_FILE))),
                    )
                    .use { proxy ->
                        post(
                            proxy,
                            headers =
                                mapOf(
                                    HttpHeaders.Authorization to "Bearer $SECRET",
                                    "x-stainless-lang" to "python",
                                    "x-stainless-package-version" to "1.109.1",
                                    HttpHeaders.UserAgent to "OpenAI/Python 1.109.1",
                                ),
                        )
                    }
                awaitRecordings(store, 1)
                assertEquals(
                    listOf("Bearer $SECRET"),
                    upstream.received
                        .single()
                        .headers
                        .entries
                        .single { it.key.equals(HttpHeaders.Authorization, ignoreCase = true) }
                        .value,
                    "the key is sent upstream, which is the only thing it is for",
                )
            }
            assertNull(
                store.list().single().exchange.request.headers[HttpHeaders.Authorization],
                "and it is not in the row",
            )
        }
        val completed = completedEvent(home.resolve(EVENTS_FILE))
        assertEquals("sdk-python", completed.getValue("client").jsonPrimitive.content)
        assertFalse(home.resolve(EVENTS_FILE).readText().contains(SECRET), "nor on the event line")
    }

    private companion object {
        const val REQUEST =
            """{"model":"gpt-4o-mini","stream":true,"stream_options":{"include_usage":true},""" +
                """"messages":[{"role":"user","content":"say peashoot"}]}"""
        const val NON_STREAMING_REQUEST =
            """{"model":"gpt-4o-mini","stream":false,""" +
                """"messages":[{"role":"user","content":"run it"}]}"""
        const val MESSAGES_REQUEST = """{"model":"claude-sonnet-4-5","messages":[]}"""
        const val RATE_LIMITED =
            """{"error":{"message":"Rate limit reached","type":"tokens","code":"rate_limit"}}"""
        /** A port nothing listens on: a request that took the wrong surface fails, not passes. */
        const val DEAD_UPSTREAM = "http://127.0.0.1:1"
        const val SECRET = "sk-proj-notarealkey000000000000"
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 20L
    }
}
