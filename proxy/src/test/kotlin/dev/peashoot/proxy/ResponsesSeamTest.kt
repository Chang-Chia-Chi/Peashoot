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
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The OpenAI Responses surface at the proxy's HTTP boundary: what Codex sends, what comes back,
 * what the store keeps, what the event line says, and what the proxy answers an upgrade with.
 *
 * ponytail: this class is within about thirty lines of detekt's `LargeClass` ceiling of 600, and
 * there is no baseline to absorb the overrun. Upgrade: #27's cursor tests go in a file of their own
 * rather than here, which is where they belong anyway — they are about the buffer, not the surface.
 */
class ResponsesSeamTest {
    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/openai-responses/$name")) { name }.readBytes()

    private fun home(): Path = Files.createTempDirectory("peashoot-home")

    /**
     * The Anthropic upstream is a port nothing listens on, so a Responses request that took the
     * wrong surface fails loudly rather than quietly reaching the only server the test started.
     * That is this surface's "relayed to its own upstream" assertion, made by every test here.
     */
    private fun config(upstream: FakeUpstream, mode: Mode = Mode.RECORD) =
        ProxyConfig(
            port = 0,
            anthropicUpstream = DEAD_UPSTREAM,
            openaiUpstream = upstream.url,
            routes = mapOf(DEFAULT_ROUTE to Route(mode)),
        )

    private fun streamReply(name: String) =
        FakeUpstream.Reply(
            contentType = ContentType.Text.EventStream,
            frames = FrameParser.parse(fixture(name)).map { it.raw },
            headers = mapOf("x-ratelimit-remaining-tokens" to listOf("9000")),
        )

    private suspend fun post(
        proxy: ProxyServer,
        path: String = RESPONSES,
        body: String? = TURN,
        headers: Map<String, String> = CODEX_HEADERS,
    ): HttpResponse =
        HttpClient(CIO).use { client ->
            client.post("${proxy.url}$path") {
                headers.forEach { (name, value) -> header(name, value) }
                body?.let { setBody(it) }
            }
        }

    private suspend fun get(proxy: ProxyServer, path: String): HttpResponse =
        HttpClient(CIO).use { client ->
            client.get("${proxy.url}$path") {
                CODEX_HEADERS.forEach { (name, value) -> header(name, value) }
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

    /** The response id, which is what a chained call names and what a replay must serve back. */
    private fun idOf(body: String): String =
        Json.parseToJsonElement(body).jsonObject.getValue("id").jsonPrimitive.content

    /**
     * The upgrade handshake Codex opens with, written to the socket rather than built by a client,
     * because an HTTP client of our own would be free to rewrite the very headers under test.
     * Returns the status line.
     */
    private fun upgradeStatusLine(proxy: ProxyServer, upgrade: String = "websocket"): String {
        val url = URI(proxy.url)
        val handshake =
            if (upgrade != "websocket") emptyList()
            else
                listOf(
                    "Sec-WebSocket-Version: 13",
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==",
                    "OpenAI-Beta: responses_websockets=2026-02-06",
                )
        val request =
            (listOf(
                    "GET $RESPONSES HTTP/1.1",
                    "Host: ${url.host}:${url.port}",
                    "Upgrade: $upgrade",
                    "Connection: Upgrade",
                ) + handshake + listOf("", ""))
                .joinToString("\r\n")
        Socket(url.host, url.port).use { socket ->
            socket.getOutputStream().apply {
                write(request.toByteArray())
                flush()
            }
            return socket.getInputStream().bufferedReader().readLine()
        }
    }

    @Test
    fun `a streamed turn records, then replays byte-equal with zero upstream calls`() =
        runBlocking {
            val home = home()
            val recorded = home.resolve("recording-$EVENTS_FILE")
            val replayed = home.resolve(EVENTS_FILE)
            // What the client was given on the way past the real upstream. The replay is compared
            // to this and not only to the file: the claim is that a replay reproduces a recording.
            var live = ""
            Store(home).use { store ->
                FakeUpstream().use { upstream ->
                    upstream.reply = { streamReply(STREAM_FIXTURE) }
                    val record = config(upstream)
                    val recording =
                        listOf(Replay(store, record), Recorder(store), Deriver(store, recorded))
                    ProxyServer(record, recording).use { proxy -> live = post(proxy).bodyAsText() }
                    awaitRecordings(store, 1)
                    upstream.received.clear()

                    val replay = config(upstream, Mode.REPLAY)
                    val chain =
                        listOf(Replay(store, replay), Recorder(store), Deriver(store, replayed))
                    ProxyServer(replay, chain).use { proxy ->
                        val response = post(proxy)
                        assertEquals(200, response.status.value)
                        assertEquals(live, response.bodyAsText(), "the replay is the recording")
                    }
                    assertEquals(0, upstream.received.size, "a hit never reaches the upstream")
                }
                assertEquals(1, store.list().size, "a replay hit is never re-recorded")
            }
            // And what the client was given is the upstream's own bytes: every `event:` line, every
            // `data:` line, and the blank line that ends each block, so both passes are byte-equal.
            assertContentEquals(fixture(STREAM_FIXTURE), live.toByteArray())

            assertCodexTurn(completedEvent(recorded))
            assertCodexTurn(completedEvent(replayed))
            assertTrue(
                completedEvent(replayed).getValue("replayHit").jsonPrimitive.content.toBoolean()
            )
        }

    /** What the deriver read out of that turn's frames, which is the whole point of the surface. */
    private fun assertCodexTurn(completed: JsonObject) {
        assertEquals("openai-responses", completed.getValue("surface").jsonPrimitive.content)
        assertEquals("codex", completed.getValue("client").jsonPrimitive.content)
        assertEquals("cx-1", completed.getValue("session").jsonPrimitive.content)
        assertEquals("gpt-5-codex-2026-03-01", completed.getValue("model").jsonPrimitive.content)
        assertEquals("completed", completed.getValue("stopReason").jsonPrimitive.content)
        assertEquals(
            """{"input":28,"output":48,"cacheRead":64,"cacheWrite":0}""",
            completed.getValue("usage").toString(),
            "92 input tokens of which 64 cached, counted once",
        )
        val tools = completed.getValue("tools").jsonArray
        assertEquals(
            listOf("shell", "Read"),
            tools.map { it.jsonObject.getValue("name").jsonPrimitive.content },
            "both calls, assembled from deltas that interleaved between the two items",
        )
        assertEquals("echo peashoot", tools.first().jsonObject["command"]?.jsonPrimitive?.content)
        assertEquals("src/main.kt", tools.last().jsonObject["path"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a chained pair of calls replays with zero upstream calls and no id mapping`() =
        runBlocking {
            val home = home()
            Store(home).use { store ->
                FakeUpstream().use { upstream ->
                    upstream.reply = { received ->
                        FakeUpstream.Reply(
                            body =
                                if (FIRST_ID in received.body) SECOND_RESPONSE else FIRST_RESPONSE
                        )
                    }
                    val record = config(upstream)
                    val first =
                        ProxyServer(record, listOf(Recorder(store))).use { proxy ->
                            val one = idOf(post(proxy).bodyAsText())
                            post(proxy, body = chained(one)).bodyAsText()
                            one
                        }
                    awaitRecordings(store, 2)
                    assertEquals(FIRST_ID, first)
                    upstream.received.clear()

                    val replay = config(upstream, Mode.REPLAY)
                    ProxyServer(replay, listOf(Replay(store, replay), Recorder(store))).use { proxy
                        ->
                        // Call 2's body is built from what the REPLAY of call 1 handed back, which
                        // is the whole claim: the recorded id is served verbatim, so the chained
                        // fingerprint matches with nothing rewriting an id anywhere.
                        val one = idOf(post(proxy).bodyAsText())
                        assertEquals(FIRST_ID, one, "the recorded id comes back as it was")
                        val two = post(proxy, body = chained(one))
                        assertEquals(SECOND_ID, idOf(two.bodyAsText()))
                    }
                    assertEquals(0, upstream.received.size, "neither call reached the upstream")
                }
                assertEquals(2, store.list().size, "one row per call, and no replay re-recorded")
            }
        }

    @Test
    fun `a recorded response's headers come back on replay verbatim, repeats included`() =
        runBlocking {
            val home = home()
            Store(home).use { store ->
                FakeUpstream().use { upstream ->
                    upstream.reply = {
                        FakeUpstream.Reply(body = FIRST_RESPONSE, headers = RESPONSE_HEADERS)
                    }
                    val record = config(upstream)
                    val recorded = ProxyServer(record, listOf(Recorder(store))).use { post(it) }
                    awaitRecordings(store, 1)
                    upstream.received.clear()

                    val replay = config(upstream, Mode.REPLAY)
                    ProxyServer(replay, listOf(Replay(store, replay), Recorder(store))).use { proxy
                        ->
                        assertHeaders(recorded, post(proxy))
                    }
                    assertEquals(0, upstream.received.size)
                    assertEquals(
                        RESPONSE_HEADERS.getValue(HttpHeaders.Trailer),
                        store
                            .list()
                            .single()
                            .exchange
                            .response
                            ?.headers
                            ?.getAll(HttpHeaders.Trailer),
                        "the upstream really did send the hop-by-hop header neither pass forwarded",
                    )
                }
            }
        }

    /**
     * Every header the upstream set arrives again on the replay — name, value, and multiplicity —
     * except the ones the design gives the proxy: hop-by-hop, and the length of a body it
     * re-encodes. Content-type is excluded from the forwarded set only because it travels as the
     * response's own property instead, so it must still arrive. A secret header is the third kind
     * (#98): the recording pass relays it to the caller whole and keeps none of it, so there is
     * nothing of it in the store for a replay to hand back.
     */
    private fun assertHeaders(recorded: HttpResponse, replayed: HttpResponse) {
        RESPONSE_HEADERS.keys.forEach { name ->
            val was = recorded.headers.getAll(name)
            when (name) {
                in HOP_BY_HOP -> {
                    assertNull(was, "$name is hop-by-hop, so no pass forwarded it at all")
                    assertNull(replayed.headers.getAll(name), name)
                }
                in NOT_KEPT -> {
                    assertEquals(
                        RESPONSE_HEADERS.getValue(name),
                        assertNotNull(was, "the recording pass relays $name to the caller"),
                    )
                    assertNull(
                        replayed.headers.getAll(name),
                        "$name is a secret header: relayed, never stored, so never replayed",
                    )
                }
                else -> {
                    assertEquals(
                        RESPONSE_HEADERS.getValue(name),
                        assertNotNull(was, "the recording pass must have carried $name"),
                    )
                    assertEquals(was, replayed.headers.getAll(name), name)
                }
            }
        }
        assertEquals(
            listOf("""199 - "first"""", """199 - "second""""),
            replayed.headers.getAll(HttpHeaders.Warning),
            "a header the upstream repeated is repeated back, not folded into one",
        )
        assertEquals(
            ContentType.Application.Json.toString(),
            replayed.headers[HttpHeaders.ContentType],
            "content-type is the response's own property, so a replay still declares it",
        )
        assertNull(replayed.headers[HttpHeaders.ContentLength], "the proxy owns the length")
    }

    @Test
    fun `the sticky turn-state header round-trips and never enters the fingerprint`() =
        runBlocking {
            val home = home()
            Store(home).use { store ->
                FakeUpstream().use { upstream ->
                    upstream.reply = {
                        FakeUpstream.Reply(
                            body = FIRST_RESPONSE,
                            headers = mapOf(TURN_STATE to listOf("issued-by-server")),
                        )
                    }
                    val record = config(upstream)
                    ProxyServer(record, listOf(Recorder(store))).use { proxy ->
                        val response =
                            post(proxy, headers = CODEX_HEADERS + (TURN_STATE to "sent-1"))
                        assertEquals(
                            "issued-by-server",
                            response.headers[TURN_STATE],
                            "the server's sticky token reaches the client, which sends it back next",
                        )
                    }
                    awaitRecordings(store, 1)
                    assertEquals(
                        listOf("sent-1"),
                        upstream.received
                            .single()
                            .headers
                            .entries
                            .single { it.key.equals(TURN_STATE, ignoreCase = true) }
                            .value,
                        "and the one the client sent reaches the upstream on the way in",
                    )
                    upstream.received.clear()

                    val replay = config(upstream, Mode.REPLAY)
                    ProxyServer(replay, listOf(Replay(store, replay), Recorder(store))).use { proxy
                        ->
                        // A different token on the way in, because it is a different turn. It is
                        // not
                        // in `keepHeaders`, so it is not in the fingerprint: if it were, no second
                        // turn of a recorded session could ever replay.
                        val response =
                            post(proxy, headers = CODEX_HEADERS + (TURN_STATE to "sent-2"))
                        assertEquals("issued-by-server", response.headers[TURN_STATE])
                    }
                    assertEquals(
                        0,
                        upstream.received.size,
                        "the turn-state header did not break the hit",
                    )
                }
                assertEquals(
                    listOf("issued-by-server"),
                    store.list().single().exchange.response?.headers?.getAll(TURN_STATE),
                    "and the store kept it, which is what the replay served from",
                )
            }
        }

    @Test
    fun `get by id carries its cursor into the fingerprint, so two cursors do not collide`() =
        runBlocking {
            Store(home()).use { store ->
                FakeUpstream().use { upstream ->
                    // A GET has no body at all, so nothing about it may assume one: the cursor in
                    // the query string is the only thing telling these two requests apart.
                    upstream.reply = { FakeUpstream.Reply(body = """{"at":"${it.uri.last()}"}""") }
                    val record = config(upstream)
                    ProxyServer(record, listOf(Recorder(store))).use { proxy ->
                        assertEquals("""{"at":"3"}""", get(proxy, cursor(3)).bodyAsText())
                        assertEquals("""{"at":"7"}""", get(proxy, cursor(7)).bodyAsText())
                    }
                    awaitRecordings(store, 2)
                    upstream.received.clear()

                    val replay = config(upstream, Mode.REPLAY)
                    ProxyServer(replay, listOf(Replay(store, replay), Recorder(store))).use { proxy
                        ->
                        assertEquals("""{"at":"7"}""", get(proxy, cursor(7)).bodyAsText())
                        assertEquals("""{"at":"3"}""", get(proxy, cursor(3)).bodyAsText())
                    }
                    assertEquals(0, upstream.received.size, "both cursors replayed from the store")
                }
                assertEquals(
                    2,
                    store.list().size,
                    "two fingerprints, not one: the query string is part of the path",
                )
            }
        }

    @Test
    fun `a cancel with no body is relayed, recorded, and replayed like any other exchange`() =
        runBlocking {
            val home = home()
            Store(home).use { store ->
                FakeUpstream().use { upstream ->
                    upstream.reply = { FakeUpstream.Reply(body = CANCELLED) }
                    val record = config(upstream)
                    val events = home.resolve(EVENTS_FILE)
                    ProxyServer(record, listOf(Recorder(store), Deriver(store, events))).use { proxy
                        ->
                        assertEquals(CANCELLED, post(proxy, CANCEL, body = null).bodyAsText())
                    }
                    awaitRecordings(store, 1)
                    assertEquals("", upstream.received.single().body, "a cancel sends no body")
                    upstream.received.clear()

                    val replay = config(upstream, Mode.REPLAY)
                    ProxyServer(replay, listOf(Replay(store, replay), Recorder(store))).use { proxy
                        ->
                        assertEquals(CANCELLED, post(proxy, CANCEL, body = null).bodyAsText())
                    }
                    assertEquals(0, upstream.received.size)
                    val completed = completedEvent(events)
                    assertEquals(
                        "openai-responses",
                        completed.getValue("surface").jsonPrimitive.content,
                    )
                    assertEquals(
                        "cancelled",
                        completed.getValue("stopReason").jsonPrimitive.content,
                    )
                }
            }
        }

    @Test
    fun `a websocket upgrade is refused 426, and the post right after it is answered normally`() =
        runBlocking {
            FakeUpstream().use { upstream ->
                val config = config(upstream, Mode.PASSTHROUGH)
                ProxyServer(config).use { proxy ->
                    // 426 is the one status Codex treats as "this provider speaks HTTP": anything
                    // else costs it five stream retries first. See
                    // docs/research/codex-responses-transport.md.
                    // One handshake per line: `assertTrue`'s message argument is eager, so
                    // passing the call itself would open a second connection every time and
                    // quietly double the upstream counts this test then asserts on.
                    val refused = upgradeStatusLine(proxy)
                    assertTrue(refused.startsWith("HTTP/1.1 426"), refused)
                    assertEquals(
                        0,
                        upstream.received.size,
                        "a refused upgrade is never relayed, so it bills nothing",
                    )
                    // Only the `websocket` token is refused. `h2c` from `curl --http2` must be
                    // answered as though the header were absent, not swallowed unrecorded, and
                    // neither may a token that merely contains the word.
                    val h2c = upgradeStatusLine(proxy, "h2c")
                    assertTrue(h2c.startsWith("HTTP/1.1 200"), h2c)
                    val lookalike = upgradeStatusLine(proxy, "notwebsocket")
                    assertTrue(lookalike.startsWith("HTTP/1.1 200"), lookalike)
                    assertEquals(200, post(proxy).status.value)
                }
                assertEquals(
                    List(3) { RESPONSES },
                    upstream.received.map { it.uri },
                    "the refusal swallowed nothing: both odd upgrades and the post got through",
                )
            }
        }

    @Test
    fun `Codex is detected from its headers, and its key is never kept`() = runBlocking {
        val home = home()
        Store(home).use { store ->
            FakeUpstream().use { upstream ->
                upstream.reply = { streamReply(STREAM_FIXTURE) }
                val config = config(upstream)
                val events = home.resolve(EVENTS_FILE)
                ProxyServer(config, listOf(Recorder(store), Deriver(store, events))).use { proxy ->
                    post(
                        proxy,
                        headers =
                            CODEX_HEADERS +
                                mapOf(
                                    HttpHeaders.Authorization to "Bearer $SECRET",
                                    "x-codex-parent-thread-id" to "th-0",
                                    "x-openai-subagent" to "review",
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
        assertEquals("codex", completed.getValue("client").jsonPrimitive.content)
        assertEquals("cx-1", completed.getValue("session").jsonPrimitive.content)
        assertEquals("th-1", completed.getValue("agent").jsonPrimitive.content)
        assertEquals("th-0", completed.getValue("parentAgent").jsonPrimitive.content)
        assertFalse(home.resolve(EVENTS_FILE).readText().contains(SECRET), "nor on the event line")
    }

    private companion object {
        const val RESPONSES = "/v1/responses"
        const val CANCEL = "/v1/responses/resp_REDACTED/cancel"
        const val STREAM_FIXTURE = "stream-with-function-call.sse"
        const val TURN_STATE = "x-codex-turn-state"
        const val FIRST_ID = "resp_one"
        const val SECOND_ID = "resp_two"

        fun cursor(after: Int) = "/v1/responses/resp_REDACTED?stream=true&starting_after=$after"

        const val TURN =
            """{"model":"gpt-5-codex","stream":true,""" +
                """"input":[{"role":"user","content":"run it"}]}"""

        /** Call 2 of a chain: the id it names is the one call 1's response carried. */
        fun chained(previous: String) =
            """{"model":"gpt-5-codex","previous_response_id":"$previous",""" +
                """"input":[{"role":"user","content":"and again"}]}"""

        fun response(id: String) =
            """{"id":"$id","object":"response","status":"completed",""" +
                """"model":"gpt-5-codex","output":[],"usage":null}"""

        val FIRST_RESPONSE = response(FIRST_ID)
        val SECOND_RESPONSE = response(SECOND_ID)
        const val CANCELLED =
            """{"id":"resp_REDACTED","object":"response","status":"cancelled",""" +
                """"model":"gpt-5-codex","output":[],"usage":null}"""

        /** What Codex sends on every Responses request; header names verified from its source. */
        val CODEX_HEADERS =
            mapOf(
                "originator" to "codex_cli_rs",
                "session-id" to "cx-1",
                "thread-id" to "th-1",
                HttpHeaders.UserAgent to "codex_cli_rs/0.5.0 (Mac OS 14.5; arm64) Terminal",
            )

        /** A real provider response's headers, the one Codex reads back and a repeat among them. */
        val RESPONSE_HEADERS =
            mapOf(
                "x-request-id" to listOf("req_REDACTED"),
                "openai-processing-ms" to listOf("742"),
                "x-ratelimit-limit-requests" to listOf("10000"),
                "x-ratelimit-remaining-tokens" to listOf("9000"),
                "x-ratelimit-reset-tokens" to listOf("6m0s"),
                TURN_STATE to listOf("issued-by-server"),
                // Repeated on purpose: a real response sends a header name more than once, and
                // the multiplicity has to survive the store.
                HttpHeaders.Warning to listOf("""199 - "first"""", """199 - "second""""),
                // A secret header (#98): relayed to the caller, never kept, so a replay has none.
                HttpHeaders.SetCookie to listOf("sess=peashoot-canary; Path=/"),
                // Hop-by-hop: the proxy owns it and never passes it on, in either pass.
                HttpHeaders.Trailer to listOf("x-checksum"),
            )

        val HOP_BY_HOP = setOf(HttpHeaders.Trailer)

        /** Relayed whole and stored not at all, so the replay has nothing to hand back (#98). */
        val NOT_KEPT = setOf(HttpHeaders.SetCookie)

        /** A port nothing listens on: a request that took the wrong surface fails, not passes. */
        const val DEAD_UPSTREAM = "http://127.0.0.1:1"
        const val SECRET = "sk-proj-notarealkey000000000000"
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 20L
    }
}
