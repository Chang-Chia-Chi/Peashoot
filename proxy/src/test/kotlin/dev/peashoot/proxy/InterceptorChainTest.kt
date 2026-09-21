package dev.peashoot.proxy

import dev.peashoot.core.Exchange
import dev.peashoot.core.Frame
import dev.peashoot.core.FrameParser
import dev.peashoot.core.FrameSource
import dev.peashoot.core.Interceptor
import dev.peashoot.core.Outcome
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.http.withCharset
import io.ktor.utils.io.readBuffer
import java.io.IOException
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The chain, seen from a client: what reaches it, and what the hooks see on the way. */
class InterceptorChainTest {
    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/anthropic-messages/$name")) { name }
            .readBytes()

    /** Records what the chain shows it and changes nothing. */
    private class Observer : Interceptor {
        val log = CopyOnWriteArrayList<String>()
        lateinit var exchange: Exchange

        override suspend fun onRequest(exchange: Exchange): FrameSource? {
            this.exchange = exchange
            log += "request ${exchange.request.method} ${exchange.request.path}"
            return null
        }

        override fun onFrames(exchange: Exchange, frames: Flow<Frame>) = frames.onEach {
            log += "frame ${it.event ?: it.raw}"
        }

        override suspend fun onComplete(exchange: Exchange, outcome: Outcome) {
            log += "complete ${outcome.status}"
        }
    }

    @Test
    fun `with no-op interceptors the stream relays byte-equal and every hook sees it, minus secrets`() =
        runBlocking {
            val fixture = fixture("stream-with-tool-use.sse")
            val observer = Observer()
            FakeUpstream().use { upstream ->
                upstream.reply = {
                    FakeUpstream.Reply(
                        contentType = ContentType.Text.EventStream,
                        frames = FrameParser.parse(fixture).map { it.raw },
                    )
                }
                val chain = listOf(object : Interceptor {}, observer)
                val config =
                    ProxyConfig(
                        port = 0,
                        anthropicUpstream = upstream.url,
                        secretHeaders = setOf("authorization", "x-api-key", "X-Custom-Secret"),
                    )
                ProxyServer(config, chain).use { proxy ->
                    val body =
                        HttpClient(CIO).use {
                            it.post("${proxy.url}/v1/messages") {
                                    header("x-api-key", "sk-ant-SECRET")
                                    header("X-CUSTOM-SECRET", "also-SECRET")
                                    header("anthropic-beta", "kept")
                                    setBody("{}")
                                }
                                .bodyAsChannel()
                                .readBuffer()
                                .readByteArray()
                        }
                    assertContentEquals(fixture, body)
                }
                val sent = upstream.received.single().headers.mapKeys { it.key.lowercase() }
                assertEquals(
                    listOf("sk-ant-SECRET"),
                    sent["x-api-key"],
                    "secrets still go upstream",
                )
                assertEquals(listOf("also-SECRET"), sent["x-custom-secret"])
            }

            val frames = FrameParser.parse(fixture).map { "frame ${it.event}" }
            assertEquals(
                listOf("request POST /v1/messages") + frames + "complete 200",
                observer.log,
            )
            val kept = observer.exchange.request.headers
            assertEquals("kept", kept["anthropic-beta"])
            assertNull(kept["x-api-key"], "a secret header must never reach the exchange")
            assertNull(
                kept["x-custom-secret"],
                "a configured secret header must never reach the exchange, whatever its case",
            )
        }

    @Test
    fun `an interceptor that responds with its own source short-circuits the upstream`() =
        runBlocking {
            val fixture = fixture("stream-with-tool-use.sse")
            val replay =
                object : Interceptor {
                    override suspend fun onRequest(exchange: Exchange) =
                        object : FrameSource {
                            override val status = 200
                            override val headers =
                                headersOf(
                                    "content-type" to listOf("text/event-stream"),
                                    "x-peashoot-replay" to listOf("true"),
                                )

                            override fun frames() = FrameParser.parse(fixture).asFlow()
                        }
                }
            val observer = Observer()
            FakeUpstream().use { upstream ->
                val chain = listOf(replay, observer)
                ProxyServer(ProxyConfig(port = 0, anthropicUpstream = upstream.url), chain).use {
                    proxy ->
                    HttpClient(CIO).use { client ->
                        val response = client.post("${proxy.url}/v1/messages") { setBody("{}") }
                        assertEquals(200, response.status.value)
                        assertEquals("true", response.headers["x-peashoot-replay"])
                        assertContentEquals(
                            fixture,
                            response.bodyAsChannel().readBuffer().readByteArray(),
                        )
                    }
                }
                assertEquals(0, upstream.received.size, "the upstream must not be called")
            }
            // Later interceptors still hear the request, the frames, and the outcome.
            val frames = FrameParser.parse(fixture).map { "frame ${it.event}" }
            assertEquals(
                listOf("request POST /v1/messages") + frames + "complete 200",
                observer.log,
            )
        }

    @Test
    fun `a source that fails mid-stream ends the client's response and still completes the exchange`() =
        runBlocking {
            val fixture = fixture("stream-with-tool-use.sse")
            val dropBeforeStop =
                object : Interceptor {
                    override fun onFrames(exchange: Exchange, frames: Flow<Frame>) = frames.map {
                        if (it.event == "message_stop") throw IOException("upstream dropped")
                        it
                    }
                }
            val observer = Observer()
            FakeUpstream().use { upstream ->
                upstream.reply = {
                    FakeUpstream.Reply(
                        contentType = ContentType.Text.EventStream,
                        frames = FrameParser.parse(fixture).map { it.raw },
                    )
                }
                val chain = listOf(dropBeforeStop, observer)
                ProxyServer(ProxyConfig(port = 0, anthropicUpstream = upstream.url), chain).use {
                    proxy ->
                    val body =
                        HttpClient(CIO).use {
                            it.post("${proxy.url}/v1/messages") { setBody("{}") }.bodyAsText()
                        }
                    val relayed = FrameParser.parse(fixture).dropLast(1)
                    assertEquals(relayed.joinToString("") { it.raw }, body)
                }
            }
            val relayed = FrameParser.parse(fixture).dropLast(1).map { "frame ${it.event}" }
            assertEquals(
                listOf("request POST /v1/messages") + relayed + "complete 200",
                observer.log,
            )
        }

    @Test
    fun `an upstream error relays verbatim and the chain sees its status and body`() = runBlocking {
        val body = """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""
        val observer = Observer()
        FakeUpstream().use { upstream ->
            upstream.reply = {
                FakeUpstream.Reply(
                    status = 529,
                    body = body,
                    headers = mapOf("retry-after" to listOf("3")),
                )
            }
            ProxyServer(ProxyConfig(port = 0, anthropicUpstream = upstream.url), listOf(observer))
                .use { proxy ->
                    val response =
                        HttpClient(CIO).use {
                            it.post("${proxy.url}/v1/messages") { setBody("{}") }
                        }
                    assertEquals(529, response.status.value)
                    assertEquals(body, response.bodyAsText())
                    assertEquals("3", response.headers["retry-after"])
                }
        }
        assertEquals(
            listOf("request POST /v1/messages", "frame $body", "complete 529"),
            observer.log,
        )
        assertEquals(529, observer.exchange.response?.status)
    }

    @Test
    fun `a proxy-side failure returns 502 typed as a peashoot error and completes the exchange`() =
        runBlocking {
            val closedPort = ServerSocket(0).use { it.localPort }
            val observer = Observer()
            val config = ProxyConfig(port = 0, anthropicUpstream = "http://127.0.0.1:$closedPort")
            ProxyServer(config, listOf(observer)).use { proxy ->
                val response =
                    HttpClient(CIO).use { it.post("${proxy.url}/v1/messages") { setBody("{}") } }
                assertEquals(502, response.status.value)
                assertEquals(
                    "application/json",
                    response.contentType()?.withoutParameters().toString(),
                )
                assertContains(response.bodyAsText(), "\"type\":\"peashoot_error\"")
            }
            assertEquals(listOf("request POST /v1/messages", "complete 502"), observer.log)
        }

    @Test
    fun `a non-text upstream body is refused with a typed 502 and completes the exchange`() =
        runBlocking {
            val types =
                listOf(
                    ContentType.Image.PNG,
                    ContentType.Application.Pdf,
                    ContentType.Text.Plain.withCharset(Charsets.ISO_8859_1),
                    // Goes out as `png/`, a declaration the proxy cannot parse.
                    ContentType("png", ""),
                )
            val observer = Observer()
            Store(Files.createTempDirectory("peashoot-home")).use { store ->
                FakeUpstream().use { upstream ->
                    val config = ProxyConfig(port = 0, anthropicUpstream = upstream.url)
                    ProxyServer(config, listOf(Recorder(store), observer)).use { proxy ->
                        HttpClient(CIO).use { client ->
                            types.forEachIndexed { index, type ->
                                upstream.reply = { FakeUpstream.Reply(contentType = type) }

                                val response = client.get("${proxy.url}/v1/files/f1/content")

                                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                                assertEquals(502, response.status.value, "$type")
                                assertEquals(
                                    "peashoot_error",
                                    body.getValue("type").jsonPrimitive.content,
                                )
                                assertEquals(
                                    "unsupported_content_type",
                                    body.getValue("error").jsonPrimitive.content,
                                )
                                assertContains(
                                    body.getValue("detail").jsonPrimitive.content,
                                    "${type.contentType}/${type.contentSubtype}",
                                )
                                // The request did reach the upstream; the refusal is not a
                                // short-circuit before it.
                                assertEquals(index + 1, upstream.received.size)
                            }
                        }
                    }
                }
                // The exchange never had a source, so the recorder never had a buffer.
                assertTrue(store.list().isEmpty(), "a refused body is never recorded")
            }
            // Read after the server stops: the 502 is written before the chain completes, so a
            // client can see it before onComplete has run.
            assertEquals(
                types.flatMap { listOf("request GET /v1/files/f1/content", "complete 502") },
                observer.log,
            )
        }
}
