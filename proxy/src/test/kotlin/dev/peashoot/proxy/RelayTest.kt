package dev.peashoot.proxy

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.readLine
import io.ktor.utils.io.readRemaining
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.readString

private val NL = "\n"

class RelayTest {
    @Test
    fun `relays a messages request to the upstream and returns its response unchanged`() =
        runBlocking {
            FakeUpstream().use { upstream ->
                upstream.reply = {
                    FakeUpstream.Reply(body = """{"id":"msg_1","type":"message"}""")
                }
                ProxyServer(ProxyConfig(port = 0, anthropicUpstream = upstream.url)).use { proxy ->
                    val response =
                        HttpClient(CIO).post("${proxy.url}/v1/messages") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"model":"claude","messages":[]}""")
                        }

                    assertEquals(200, response.status.value)
                    assertEquals("""{"id":"msg_1","type":"message"}""", response.bodyAsText())
                    assertEquals(
                        "application/json",
                        response.contentType()?.withoutParameters().toString(),
                    )
                    assertEquals(
                        """{"model":"claude","messages":[]}""",
                        upstream.received.single().body,
                    )
                    assertEquals("POST", upstream.received.single().method)
                    assertEquals("/v1/messages", upstream.received.single().uri)
                }
            }
        }

    @Test
    fun `forwards headers verbatim both ways, except hop-by-hop, host, and accept-encoding`() =
        runBlocking {
            FakeUpstream().use { upstream ->
                upstream.reply = {
                    FakeUpstream.Reply(
                        headers =
                            mapOf(
                                "anthropic-ratelimit-tokens-remaining" to "1000",
                                "x-codex-turn-state" to "sticky",
                            )
                    )
                }
                ProxyServer(ProxyConfig(port = 0, anthropicUpstream = upstream.url)).use { proxy ->
                    val response =
                        HttpClient(CIO).post("${proxy.url}/v1/messages") {
                            header("anthropic-beta", "oauth-2025-04-20")
                            header("x-api-key", "sk-ant-test")
                            header("authorization", "Bearer token")
                            header("accept-encoding", "gzip")
                            setBody("{}")
                        }

                    val seen = upstream.received.single().headers.mapKeys { it.key.lowercase() }
                    assertEquals(listOf("oauth-2025-04-20"), seen["anthropic-beta"])
                    assertEquals(listOf("sk-ant-test"), seen["x-api-key"])
                    assertEquals(listOf("Bearer token"), seen["authorization"])
                    assertNull(
                        seen["accept-encoding"],
                        "accept-encoding must be stripped so streams stay uncompressed",
                    )
                    assertEquals(
                        listOf(upstream.url.removePrefix("http://")),
                        seen["host"],
                        "host must be the upstream's",
                    )
                    assertEquals("1000", response.headers["anthropic-ratelimit-tokens-remaining"])
                    assertEquals("sticky", response.headers["x-codex-turn-state"])
                }
            }
        }

    @Test
    fun `streams each upstream chunk to the client as it arrives, never buffering the response`() =
        runBlocking {
            val first = "event: message_start\ndata: {}\n\n"
            val last = "event: message_stop\ndata: {}\n\n"
            FakeUpstream().use { upstream ->
                upstream.reply = {
                    FakeUpstream.Reply(
                        contentType = ContentType.Text.EventStream,
                        frames = listOf(0L to first, 1500L to last),
                    )
                }
                ProxyServer(ProxyConfig(port = 0, anthropicUpstream = upstream.url)).use { proxy ->
                    HttpClient(CIO)
                        .preparePost("${proxy.url}/v1/messages") { setBody("{}") }
                        .execute { response ->
                            val channel = response.bodyAsChannel()
                            val startedAt = System.nanoTime()
                            val firstLine = channel.readLine()
                            val firstLineAfterMillis = (System.nanoTime() - startedAt) / 1_000_000

                            assertEquals("event: message_start", firstLine)
                            assertTrue(
                                firstLineAfterMillis < 1000,
                                "first frame took ${firstLineAfterMillis} ms; it was buffered",
                            )
                            assertEquals(
                                first.removePrefix("event: message_start\n") + last,
                                channel.readRemaining().readString(),
                            )
                        }
                }
            }
        }

    @Test
    fun `refuses upgrade requests with 426 so clients fall back to plain http`() = runBlocking {
        FakeUpstream().use { upstream ->
            ProxyServer(ProxyConfig(port = 0, anthropicUpstream = upstream.url)).use { proxy ->
                // Ktor's client refuses to send Upgrade itself, so speak raw HTTP/1.1 like a
                // WebSocket client would.
                val (host, port) = proxy.url.removePrefix("http://").split(":")
                val statusLine =
                    java.net.Socket(host, port.toInt()).use { socket ->
                        socket
                            .getOutputStream()
                            .write(
                                ("GET /v1/responses HTTP/1.1\r\nHost: $host:$port\r\nConnection: Upgrade\r\n" +
                                        "Upgrade: websocket\r\nSec-WebSocket-Version: 13\r\n\r\n")
                                    .toByteArray()
                            )
                        socket.getInputStream().bufferedReader().readLine()
                    }

                assertEquals("HTTP/1.1 426 Upgrade Required", statusLine)
                assertTrue(
                    upstream.received.isEmpty(),
                    "an upgrade attempt must never reach the upstream",
                )
            }
        }
    }

    @Test
    fun `answers the hello probe locally and passes token counting and model listing through`() =
        runBlocking {
            FakeUpstream().use { upstream ->
                ProxyServer(ProxyConfig(port = 0, anthropicUpstream = upstream.url)).use { proxy ->
                    val client = HttpClient(CIO)

                    assertEquals(200, client.head("${proxy.url}/api/hello").status.value)
                    assertTrue(
                        upstream.received.isEmpty(),
                        "the hello probe is answered by the proxy itself, got ${upstream.received.map { it.method + " " + it.uri }}",
                    )

                    client.post("${proxy.url}/v1/messages/count_tokens") { setBody("{}") }
                    client.get("${proxy.url}/v1/models?limit=1000")

                    assertEquals(
                        listOf("POST /v1/messages/count_tokens", "GET /v1/models?limit=1000"),
                        upstream.received.map { "${it.method} ${it.uri}" },
                    )
                }
            }
        }

    @Test
    fun `logs the request line but never a secret header value`() = runBlocking {
        val appLog = java.nio.file.Path.of(System.getProperty("peashoot.test.appLog"))
        val marker = "probe-" + java.util.UUID.randomUUID()
        FakeUpstream().use { upstream ->
            ProxyServer(ProxyConfig(port = 0, anthropicUpstream = upstream.url)).use { proxy ->
                HttpClient(CIO).post("${proxy.url}/v1/$marker") {
                    header("x-api-key", "sk-ant-SECRET-KEY")
                    header("authorization", "Bearer SECRET-TOKEN")
                    setBody("{}")
                }
            }
        }

        // The call log is written on a worker after the client already has its reply.
        val line =
            withTimeout(5_000) {
                while (true) {
                    val hit = Files.readAllLines(appLog).firstOrNull { marker in it }
                    if (hit != null) return@withTimeout hit
                    delay(20)
                }
                @Suppress("UNREACHABLE_CODE") ""
            }
        assertContains(line, "POST - /v1/$marker", message = "requests are logged")
        assertFalse(
            Files.readString(appLog).contains("SECRET"),
            "a secret header value reached the log",
        )
    }

    @Test
    fun `dumps raw upstream frames to the fixture file when asked`() = runBlocking {
        val frames =
            listOf(
                "event: message_start" + NL + "data: {}" + NL + NL,
                "event: message_stop" + NL + "data: {}" + NL + NL,
            )
        val dump = Files.createTempFile("peashoot-frames", ".txt")
        FakeUpstream().use { upstream ->
            upstream.reply = {
                FakeUpstream.Reply(
                    contentType = ContentType.Text.EventStream,
                    frames = frames.map { 0L to it },
                )
            }
            ProxyServer(ProxyConfig(port = 0, anthropicUpstream = upstream.url, dumpFrames = dump))
                .use { proxy ->
                    HttpClient(CIO).post("${proxy.url}/v1/messages") { setBody("{}") }.bodyAsText()
                }
        }

        val dumped = Files.readString(dump)
        assertContains(dumped, "POST /v1/messages 200")
        assertContains(dumped, frames.joinToString(""))
    }
}
