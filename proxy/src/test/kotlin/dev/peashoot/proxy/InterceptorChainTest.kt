package dev.peashoot.proxy

import dev.peashoot.core.Decision
import dev.peashoot.core.Exchange
import dev.peashoot.core.Frame
import dev.peashoot.core.FrameParser
import dev.peashoot.core.FrameSource
import dev.peashoot.core.Interceptor
import dev.peashoot.core.Outcome
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.readRemaining
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.io.readByteArray

/** The chain, seen from a client: what reaches it, and what the hooks see on the way. */
class InterceptorChainTest {
    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/anthropic-messages/$name")) { name }
            .readBytes()

    /** Records what the chain shows it and changes nothing. */
    private class Observer : Interceptor {
        val log = CopyOnWriteArrayList<String>()
        lateinit var exchange: Exchange

        override suspend fun onRequest(exchange: Exchange): Decision {
            this.exchange = exchange
            log += "request ${exchange.request.method} ${exchange.request.path}"
            return Decision.Continue
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
                ProxyServer(ProxyConfig(port = 0, anthropicUpstream = upstream.url), chain).use {
                    proxy ->
                    val body =
                        HttpClient(CIO)
                            .post("${proxy.url}/v1/messages") {
                                header("x-api-key", "sk-ant-SECRET")
                                setBody("{}")
                            }
                            .bodyAsChannel()
                            .readRemaining()
                            .readByteArray()
                    assertContentEquals(fixture, body)
                }
            }

            val frames = FrameParser.parse(fixture).map { "frame ${it.event}" }
            assertEquals(
                listOf("request POST /v1/messages") + frames + "complete 200",
                observer.log,
            )
            assertNull(
                observer.exchange.request.headers.keys.firstOrNull {
                    it.equals("x-api-key", ignoreCase = true)
                },
                "a secret header must never reach the exchange",
            )
        }

    @Test
    fun `an interceptor that responds with its own source short-circuits the upstream`() =
        runBlocking {
            val fixture = fixture("stream-with-tool-use.sse")
            val replay =
                object : Interceptor {
                    override suspend fun onRequest(exchange: Exchange) =
                        Decision.Respond(
                            object : FrameSource {
                                override val status = 200
                                override val headers =
                                    mapOf(
                                        "content-type" to listOf("text/event-stream"),
                                        "x-peashoot-replay" to listOf("true"),
                                    )

                                override fun frames() = FrameParser.parse(fixture).asFlow()
                            }
                        )
                }
            val observer = Observer()
            FakeUpstream().use { upstream ->
                val chain = listOf(replay, observer)
                ProxyServer(ProxyConfig(port = 0, anthropicUpstream = upstream.url), chain).use {
                    proxy ->
                    val response =
                        HttpClient(CIO).post("${proxy.url}/v1/messages") { setBody("{}") }
                    assertEquals(200, response.status.value)
                    assertEquals("true", response.headers["x-peashoot-replay"])
                    assertContentEquals(
                        fixture,
                        response.bodyAsChannel().readRemaining().readByteArray(),
                    )
                }
                assertEquals(0, upstream.received.size, "the upstream must not be called")
            }
            // Later interceptors are not asked, but they still see the frames and the outcome.
            val frames = FrameParser.parse(fixture).map { "frame ${it.event}" }
            assertEquals(frames + "complete 200", observer.log)
        }

    @Test
    fun `an upstream error relays verbatim and the chain sees its status and body`() = runBlocking {
        val body = """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""
        val observer = Observer()
        FakeUpstream().use { upstream ->
            upstream.reply = {
                FakeUpstream.Reply(status = 529, body = body, headers = mapOf("retry-after" to "3"))
            }
            ProxyServer(ProxyConfig(port = 0, anthropicUpstream = upstream.url), listOf(observer))
                .use { proxy ->
                    val response =
                        HttpClient(CIO).post("${proxy.url}/v1/messages") { setBody("{}") }
                    assertEquals(529, response.status.value)
                    assertEquals(body, response.bodyAsText())
                    assertEquals("3", response.headers["retry-after"])
                }
        }
        assertEquals(
            listOf("request POST /v1/messages", "frame $body", "complete 529"),
            observer.log,
        )
        assertEquals(529, observer.exchange.status)
    }

    @Test
    fun `a proxy-side failure returns 502 typed as a peashoot error and completes the exchange`() =
        runBlocking {
            val closedPort = ServerSocket(0).use { it.localPort }
            val observer = Observer()
            val config = ProxyConfig(port = 0, anthropicUpstream = "http://127.0.0.1:$closedPort")
            ProxyServer(config, listOf(observer)).use { proxy ->
                val response = HttpClient(CIO).post("${proxy.url}/v1/messages") { setBody("{}") }
                assertEquals(502, response.status.value)
                assertEquals(
                    "application/json",
                    response.contentType()?.withoutParameters().toString(),
                )
                assertContains(response.bodyAsText(), "\"type\":\"peashoot_error\"")
            }
            assertEquals(listOf("request POST /v1/messages", "complete 502"), observer.log)
        }
}
