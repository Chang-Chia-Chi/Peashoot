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
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.readString

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
                                "proxy-authenticate" to "Basic",
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
                    assertNull(
                        response.headers["proxy-authenticate"],
                        "hop-by-hop response headers are not forwarded",
                    )
                }
            }
        }

    @Test
    fun `streams each upstream chunk to the client as it arrives, never buffering the response`() =
        runBlocking {
            val first = "event: message_start\ndata: {}\n\n"
            val last = "event: message_stop\ndata: {}\n\n"
            val releaseLast = CompletableDeferred<Unit>()
            FakeUpstream().use { upstream ->
                upstream.reply = {
                    FakeUpstream.Reply(
                        contentType = ContentType.Text.EventStream,
                        frames = listOf(first, last),
                        beforeFrame = { index -> if (index == 1) releaseLast.await() },
                    )
                }
                ProxyServer(ProxyConfig(port = 0, anthropicUpstream = upstream.url)).use { proxy ->
                    HttpClient(CIO)
                        .preparePost("${proxy.url}/v1/messages") { setBody("{}") }
                        .execute { response ->
                            val channel = response.bodyAsChannel()
                            // The last frame is held back, so this line can only arrive if the
                            // first was relayed alone.
                            val firstLine = withTimeout(5_000) { channel.readLine() }
                            assertEquals("event: message_start", firstLine)

                            releaseLast.complete(Unit)
                            assertEquals(
                                first.removePrefix("event: message_start\n") + last,
                                channel.readRemaining().readString(),
                            )
                        }
                }
            }
        }

    @Test
    fun `refuses upgrade requests with a plain 426 so clients fall back to plain http`() =
        runBlocking {
            FakeUpstream().use { upstream ->
                ProxyServer(ProxyConfig(port = 0, anthropicUpstream = upstream.url)).use { proxy ->
                    // Ktor's client refuses to send Upgrade itself, so speak raw HTTP/1.1 like a
                    // WebSocket client would.
                    val (host, port) = proxy.url.removePrefix("http://").split(":")
                    val (statusLine, headers, body) =
                        Socket(host, port.toInt()).use { socket ->
                            socket
                                .getOutputStream()
                                .write(
                                    ("GET /v1/responses HTTP/1.1\r\nHost: $host:$port\r\nConnection: Upgrade\r\n" +
                                            "Upgrade: websocket\r\nSec-WebSocket-Version: 13\r\n\r\n")
                                        .toByteArray()
                                )
                            val reader = socket.getInputStream().bufferedReader()
                            val statusLine = reader.readLine()
                            val headers = generateSequence {
                                reader.readLine()
                            }
                                .takeWhile { it.isNotEmpty() }
                                .toList()
                            val length = headers.first {
                                it.startsWith("Content-Length:", ignoreCase = true)
                            }
                            val body =
                                CharArray(length.substringAfter(":").trim().toInt()).also {
                                    reader.read(it)
                                }
                            Triple(statusLine, headers, String(body))
                        }

                    assertEquals("HTTP/1.1 426 Upgrade Required", statusLine)
                    assertTrue(
                        headers.any {
                            it.startsWith("Content-Type: text/plain", ignoreCase = true)
                        },
                        "$headers",
                    )
                    assertContains(body, "upgrade refused")
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
                        "the hello probe is answered by the proxy itself",
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
        val appLog = Path.of(System.getProperty("peashoot.test.appLog"))
        val marker = "probe-" + UUID.randomUUID()
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
        withTimeout(5_000) { while (Files.readAllLines(appLog).none { marker in it }) delay(20) }
        val line = Files.readAllLines(appLog).first { marker in it }
        assertContains(line, "POST - /v1/$marker", message = "requests are logged")
        assertFalse(
            Files.readString(appLog).contains("SECRET"),
            "a secret header value reached the log",
        )
    }

    @Test
    fun `dumps raw upstream responses to the fixture file, whole, one after another, without secrets`() =
        runBlocking {
            val frames =
                listOf("event: message_start\ndata: {}\n\n", "event: message_stop\ndata: {}\n\n")
            val dump = Files.createTempFile("peashoot-frames", ".txt")
            val releaseSecondFrames = CompletableDeferred<Unit>()
            FakeUpstream().use { upstream ->
                upstream.reply = { request ->
                    val tag = request.uri.substringAfterLast('/')
                    FakeUpstream.Reply(
                        contentType = ContentType.Text.EventStream,
                        frames = frames.map { "$tag $it" },
                        beforeFrame = { index -> if (index == 1) releaseSecondFrames.await() },
                    )
                }
                ProxyServer(
                        ProxyConfig(port = 0, anthropicUpstream = upstream.url, dumpFrames = dump)
                    )
                    .use { proxy ->
                        val client = HttpClient(CIO)
                        // Two responses stream at once; their first frames are on the wire before
                        // either second frame.
                        val a = async {
                            client
                                .post("${proxy.url}/v1/a") {
                                    header("x-api-key", "sk-ant-SECRET")
                                    setBody("{}")
                                }
                                .bodyAsText()
                        }
                        val b = async {
                            client.post("${proxy.url}/v1/b") { setBody("{}") }.bodyAsText()
                        }
                        withTimeout(5_000) { while (upstream.received.size < 2) delay(20) }
                        releaseSecondFrames.complete(Unit)
                        a.await()
                        b.await()

                        // And one non-streaming response.
                        upstream.reply = { FakeUpstream.Reply(body = """{"type":"message"}""") }
                        client.post("${proxy.url}/v1/messages") { setBody("{}") }.bodyAsText()
                    }
            }

            val dumped = Files.readString(dump)
            for (tag in listOf("a", "b")) {
                assertContains(dumped, "POST /v1/$tag 200")
                assertContains(
                    dumped,
                    frames.joinToString("") { "$tag $it" },
                    message = "response $tag must be contiguous",
                )
            }
            assertContains(dumped, "POST /v1/messages 200")
            assertContains(dumped, """{"type":"message"}""")
            assertFalse(dumped.contains("SECRET"), "a secret header value reached the dump file")
        }
}
