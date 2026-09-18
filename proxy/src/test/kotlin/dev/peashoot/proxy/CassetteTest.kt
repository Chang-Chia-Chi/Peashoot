package dev.peashoot.proxy

import dev.peashoot.core.Exchange
import dev.peashoot.core.Frame
import dev.peashoot.core.FrameParser
import dev.peashoot.core.Mode
import dev.peashoot.core.Route
import dev.peashoot.core.Rules
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.content.ByteArrayContent
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Cassettes: export with redaction, import by name, and a replay route that serves only one. */
class CassetteTest {
    private val frames =
        FrameParser.parse(
                checkNotNull(
                        javaClass.getResourceAsStream(
                            "/anthropic-messages/stream-with-tool-use.sse"
                        )
                    )
                    .readBytes()
            )
            .map { it.raw }

    private val noEnv: (String) -> String? = { null }

    private fun home(): Path = Files.createTempDirectory("peashoot-home")

    private fun config(upstream: FakeUpstream, route: Route) =
        ProxyConfig(
            port = 0,
            anthropicUpstream = upstream.url,
            routes = mapOf(DEFAULT_ROUTE to route),
        )

    /** What the client saw: enough to say a replay is the same response. */
    private data class Seen(
        val status: Int,
        val contentType: String?,
        val note: String?,
        val body: String,
    )

    private suspend fun post(
        proxy: ProxyServer,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): Seen =
        HttpClient(CIO).use { client ->
            val response =
                client.post("${proxy.url}/v1/messages") {
                    headers.forEach { (name, value) -> header(name, value) }
                    setBody(
                        ByteArrayContent(body.encodeToByteArray(), ContentType.Application.Json)
                    )
                }
            Seen(
                response.status.value,
                response.headers[HttpHeaders.ContentType],
                response.headers["x-note"],
                response.bodyAsText(),
            )
        }

    /** Sends each request through a record route into [home], and waits until all are stored. */
    private suspend fun record(
        home: Path,
        upstream: FakeUpstream,
        requests: List<String>,
        headers: Map<String, String> = emptyMap(),
    ): List<Seen> =
        Store(home).use { store ->
            val before = store.list().size
            val config = config(upstream, Route(Mode.RECORD))
            val seen =
                ProxyServer(config, listOf(Replay(store, config), Recorder(store))).use { proxy ->
                    requests.map { post(proxy, it, headers) }
                }
            withTimeout(5_000) { while (store.list().size < before + requests.size) delay(20) }
            seen
        }

    private fun reply(request: FakeUpstream.Received) =
        if (request.body.contains(STREAMING)) {
            FakeUpstream.Reply(contentType = ContentType.Text.EventStream, frames = frames)
        } else FakeUpstream.Reply(body = """{"n":1}""", headers = mapOf("x-note" to "recorded"))

    @Test
    fun `an exported cassette replays byte for byte in a fresh home, with zero upstream calls`() =
        runBlocking {
            val source = home()
            val target = home()
            FakeUpstream().use { upstream ->
                upstream.reply = { reply(it) }
                val originals = record(source, upstream, listOf(STREAM_REQUEST, PLAIN_REQUEST))
                assertEquals(
                    "wrote 2 exchanges to ${source.resolve("cassettes/demo.jsonl")}, 0 redactions",
                    command(listOf("export", "demo"), source, noEnv),
                )
                val cassette = source.resolve("cassettes/demo.jsonl")
                assertTrue(cassette.readLines().all { it.startsWith("""{"v":1,""") })

                // A live recording of the same request in the target, which the cassette route
                // must not serve.
                upstream.reply = { FakeUpstream.Reply(body = """{"n":"live"}""") }
                record(target, upstream, listOf(PLAIN_REQUEST))
                repeat(2) {
                    assertEquals(
                        "imported 2 exchanges as cassette demo",
                        command(listOf("import", cassette.toString()), target, noEnv),
                    )
                }
                upstream.received.clear()

                Store(target).use { store ->
                    assertEquals(
                        2,
                        store.list(query = ExchangeQuery(cassette = "demo")).size,
                        "importing twice is once",
                    )
                    val route = Route(Mode.REPLAY, strict = true, cassette = "demo")
                    val config = config(upstream, route)
                    ProxyServer(config, listOf(Replay(store, config), Recorder(store))).use { proxy
                        ->
                        assertEquals(
                            originals,
                            listOf(post(proxy, STREAM_REQUEST), post(proxy, PLAIN_REQUEST)),
                        )
                    }
                    assertEquals(frames.joinToString(""), originals.first().body)

                    val other = config(upstream, route.copy(cassette = "other"))
                    ProxyServer(other, listOf(Replay(store, other))).use { proxy ->
                        assertEquals(409, post(proxy, PLAIN_REQUEST).status)
                    }
                }
                assertEquals(0, upstream.received.size, "a cassette hit never reaches the upstream")
            }
        }

    @Test
    fun `a dry run lists what redaction would strip and writes nothing, and the export replays redacted`() =
        runBlocking {
            val source = home()
            val leaky =
                listOf(
                    "event: message_start\ndata: {\"type\":\"message_start\"}\n\n",
                    "event: content_block_delta\ndata: {\"delta\":{\"text\":\"use $KEY\"}}\n\n",
                    "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
                )
            FakeUpstream().use { upstream ->
                upstream.reply = {
                    FakeUpstream.Reply(contentType = ContentType.Text.EventStream, frames = leaky)
                }
                val request =
                    """{"model":"m","messages":[{"role":"user","content":[{"type":"text",""" +
                        """"text":"my key is $KEY"}]}]}"""
                record(source, upstream, listOf(request))
                val id = Store(source).use { it.list().single().exchange.id }

                val preview = command(listOf("export", "demo", "--dry-run"), source, noEnv)
                assertEquals(
                    listOf(
                        "$id request /messages/0/content/0/text: sk-ant... (${KEY.length} chars) -> [REDACTED]",
                        "$id response frame 1: sk-ant... (${KEY.length} chars) -> [REDACTED]",
                        "dry run: 1 exchanges, 2 redactions, nothing written",
                    ),
                    preview.lines(),
                )
                assertFalse(
                    KEY in preview,
                    "a preview lands in CI logs, so it never shows a secret",
                )
                assertFalse(source.resolve("cassettes").exists(), "a dry run writes nothing")

                command(listOf("export", "demo"), source, noEnv)
                val text = source.resolve("cassettes/demo.jsonl").readText()
                assertFalse(KEY in text, text)
                assertContains(text, "my key is [REDACTED]")

                // The fingerprint is kept as recorded, so the redacted request still replays.
                val target = home()
                command(
                    listOf("import", "${source.resolve("cassettes/demo.jsonl")}"),
                    target,
                    noEnv,
                )
                Store(target).use { store ->
                    val config = config(upstream, Route(Mode.REPLAY, strict = true, "demo"))
                    ProxyServer(config, listOf(Replay(store, config))).use { proxy ->
                        val replayed = post(proxy, request)
                        assertEquals(200, replayed.status)
                        assertEquals(
                            leaky.joinToString("").replace(KEY, "[REDACTED]"),
                            replayed.body,
                        )
                    }
                }
            }
        }

    @Test
    fun `no secret header reaches a cassette, not even one the rules would keep`() = runBlocking {
        val source = home()
        FakeUpstream().use { upstream ->
            upstream.reply = { reply(it) }
            record(
                source,
                upstream,
                listOf(PLAIN_REQUEST),
                mapOf(
                    HttpHeaders.Authorization to "Bearer $AUTH_CANARY",
                    "x-api-key" to API_CANARY,
                ),
            )
        }
        // Stored behind the proxy's back, and kept by the rules: only the export's own drop is
        // left.
        val keepingSecrets =
            ProxyConfig(
                rules = Rules.DEFAULT.copy(keepHeaders = setOf("authorization", "x-api-key"))
            )
        Store(source).use { store ->
            val headers = Headers.build {
                append("Authorization", "Bearer $AUTH_CANARY")
                append("X-Api-Key", API_CANARY)
            }
            val exchange =
                Exchange(
                    Exchange.Request("POST", "/v1/messages", headers, "{}".encodeToByteArray()),
                    DEFAULT_ROUTE,
                    Route(Mode.RECORD),
                )
            exchange.fingerprint = "planted"
            exchange.response = Exchange.Response(200, headers)
            store.put(listOf(Recorded(exchange, listOf(Frame("{}", 0)))))

            command(listOf("export", "demo"), source, noEnv)
            val exported = exportCassette(store, keepingSecrets).jsonl
            for (text in listOf(source.resolve("cassettes/demo.jsonl").readText(), exported)) {
                listOf("authorization", "x-api-key", AUTH_CANARY, API_CANARY).forEach {
                    assertFalse(it in text.lowercase(), "$it in $text")
                }
            }
            assertEquals(2, exported.lines().count { it.isNotEmpty() })
        }
    }

    @Test
    fun `an export for one session holds only that session's exchanges`() = runBlocking {
        val source = home()
        FakeUpstream().use { upstream ->
            upstream.reply = { reply(it) }
            record(source, upstream, listOf(PLAIN_REQUEST), mapOf("x-peashoot-session" to "a"))
            record(source, upstream, listOf(STREAM_REQUEST), mapOf("x-peashoot-session" to "b"))
        }
        assertEquals(
            "wrote 1 exchanges to ${source.resolve("cassettes/a.jsonl")}, 0 redactions",
            command(listOf("export", "a", "--session", "a"), source, noEnv),
        )
        assertContains(source.resolve("cassettes/a.jsonl").readText(), """{\"n\":1}""")
    }

    /** A hand-written record: headers as a plain string and as a list, secrets on both sides. */
    private fun handWritten(fingerprint: String) =
        """{"v":1,"fingerprint":"$fingerprint",""" +
            """"request":{"method":"POST","path":"/v1/messages",""" +
            """"headers":{"Authorization":"Bearer $AUTH_CANARY","content-type":"application/json"},""" +
            """"body":{"model":"m"}},""" +
            """"response":{"status":200,"headers":{"x-api-key":["$API_CANARY"],""" +
            """"content-type":"application/json","x-note":["recorded"]},"body":"{\"n\":1}"},""" +
            """"meta":{"recordedAt":"2026-09-12T10:00:00Z"}}"""

    @Test
    fun `an imported cassette's secret headers are dropped, never stored or replayed`() =
        runBlocking {
            val home = home()
            // The fingerprint the proxy computes for the request replay will send.
            val fingerprint =
                Rules.DEFAULT.fingerprint(
                    "POST",
                    "/v1/messages",
                    Headers.build { append(HttpHeaders.ContentType, "application/json") },
                    buildJsonObject { put("model", JsonPrimitive("m")) },
                )
            val file =
                home.resolve("leaky.jsonl").also { it.writeText(handWritten(fingerprint) + "\n") }
            assertEquals(
                "imported 1 exchanges as cassette leaky",
                command(listOf("import", "$file"), home, noEnv),
            )
            Store(home).use { store ->
                val stored = store.list(query = ExchangeQuery(cassette = "leaky")).single().exchange
                for (headers in
                    listOf(stored.request.headers, checkNotNull(stored.response).headers)) {
                    assertEquals(null, headers[HttpHeaders.Authorization])
                    assertEquals(null, headers["x-api-key"])
                }
                assertEquals("application/json", stored.request.headers[HttpHeaders.ContentType])
                FakeUpstream().use { upstream ->
                    val config = config(upstream, Route(Mode.REPLAY, strict = true, "leaky"))
                    ProxyServer(config, listOf(Replay(store, config))).use { proxy ->
                        HttpClient(CIO).use { client ->
                            val response =
                                client.post("${proxy.url}/v1/messages") {
                                    setBody(
                                        ByteArrayContent(
                                            """{"model":"m"}""".encodeToByteArray(),
                                            ContentType.Application.Json,
                                        )
                                    )
                                }
                            assertEquals(200, response.status.value)
                            assertEquals("""{"n":1}""", response.bodyAsText())
                            assertEquals("recorded", response.headers["x-note"])
                            assertEquals(null, response.headers["x-api-key"])
                        }
                    }
                }
            }
        }

    @Test
    fun `an export holds live recordings only, never a cassette the home imported`() = runBlocking {
        val home = home()
        val imported = home.resolve("old.jsonl").also { it.writeText(handWritten("f") + "\n") }
        command(listOf("import", "$imported"), home, noEnv)
        FakeUpstream().use { upstream ->
            upstream.reply = { reply(it) }
            record(home, upstream, listOf(PLAIN_REQUEST))
        }
        assertEquals(
            "wrote 1 exchanges to ${home.resolve("cassettes/new.jsonl")}, 0 redactions",
            command(listOf("export", "new"), home, noEnv),
        )
        assertFalse(""""fingerprint":"f"""" in home.resolve("cassettes/new.jsonl").readText())
    }

    @Test
    fun `a malformed cassette or an unusable name fails naming the file and line`() = runBlocking {
        val home = home()
        val good = handWritten("f")
        val broken =
            listOf(
                "not json",
                good.replace(""""v":1""", """"v":2"""),
                good.replace(""""status":200""", """"status":"ok""""),
                good.replace(""""method":"POST"""", """"method":["POST"]"""),
                good.replace("2026-09-12T10:00:00Z", "yesterday"),
                good.replace(""""body":"{\"n\":1}"""", """"frames":[{"t":"soon","raw":"x"}]"""),
                good.replace(""""x-note":["recorded"]""", """"x-note":[1,{}]"""),
            )
        broken.forEach { line ->
            val file = home.resolve("broken.jsonl").also { it.writeText("$good\n$line\n") }
            val error =
                assertFailsWith<IllegalStateException>(line) {
                    command(listOf("import", "$file"), home, noEnv)
                }
            assertContains(error.message.orEmpty(), "$file:2: ")
        }
        Store(home).use { assertEquals(emptyList(), it.list(), "a failed import stores nothing") }
        for (name in listOf(".jsonl", "my cassette.jsonl")) {
            val file = home.resolve(name).also { it.writeText("$good\n") }
            val error =
                assertFailsWith<IllegalStateException> {
                    command(listOf("import", "$file"), home, noEnv)
                }
            assertContains(error.message.orEmpty(), "is not a usable cassette name")
        }
    }

    @Test
    fun `a database from before cassettes is refused at open, not left failing every insert`() {
        val home = home()
        DriverManager.getConnection("jdbc:sqlite:${home.resolve("peashoot.db")}").use {
            it.createStatement().execute("CREATE TABLE exchange (id TEXT PRIMARY KEY)")
        }
        val error = assertFailsWith<IllegalStateException> { Store(home).close() }
        assertContains(error.message.orEmpty(), "predates cassettes")
    }

    private companion object {
        const val STREAMING = "stream me"
        const val STREAM_REQUEST =
            """{"model":"claude-sonnet-4-5","stream":true,""" +
                """"messages":[{"role":"user","content":"$STREAMING"}]}"""
        const val PLAIN_REQUEST =
            """{"model":"claude-sonnet-4-5","messages":[{"role":"user","content":"plain"}]}"""
        const val KEY = "sk-ant-api03-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789"
        const val AUTH_CANARY = "peashoot-authorization-canary"
        const val API_CANARY = "peashoot-api-key-canary"
    }
}
