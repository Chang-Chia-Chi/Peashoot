package dev.peashoot.proxy

import dev.peashoot.core.Exchange
import dev.peashoot.core.Frame
import dev.peashoot.core.FrameParser
import dev.peashoot.core.Mode
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
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

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
                    assertEquals(2, store.list(cassette = "demo").size, "importing twice is once")
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
                        "$id request /messages/0/content/0/text: $KEY -> [REDACTED]",
                        "$id response frame 1: $KEY -> [REDACTED]",
                        "dry run: 1 exchanges, 2 redactions, nothing written",
                    ),
                    preview.lines(),
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
                    Mode.RECORD,
                )
            exchange.fingerprint = "planted"
            exchange.response = Exchange.Response(200, headers)
            store.put(listOf(Recorded(exchange, listOf(Frame("{}", 0)))))

            command(listOf("export", "demo"), source, noEnv)
            val exported = exportCassette(store, keepingSecrets, session = null).jsonl
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
