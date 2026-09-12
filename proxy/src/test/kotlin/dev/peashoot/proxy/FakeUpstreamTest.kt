package dev.peashoot.proxy

import dev.peashoot.core.FrameParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.utils.io.readLine
import io.ktor.utils.io.readRemaining
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.io.readByteArray
import kotlinx.io.readString

/** What a client sees through the proxy under each condition the fake upstream can produce. */
class FakeUpstreamTest {
    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/anthropic-messages/$name")) { name }
            .readBytes()

    private fun streamOf(
        frames: List<String>,
        cutAfterFrame: Int? = null,
        beforeFrame: suspend (Int) -> Unit = {},
    ) =
        FakeUpstream.Reply(
            contentType = ContentType.Text.EventStream,
            frames = frames,
            beforeFrame = beforeFrame,
            cutAfterFrame = cutAfterFrame,
        )

    @Test
    fun `a fixture stream replayed by the fake upstream reaches the client byte-equal`() =
        runBlocking {
            val fixture = fixture("stream-with-tool-use.sse")
            FakeUpstream().use { upstream ->
                upstream.reply = { streamOf(FrameParser.parse(fixture).map { it.raw }) }
                ProxyServer(ProxyConfig(port = 0, anthropicUpstream = upstream.url)).use { proxy ->
                    val body =
                        HttpClient(CIO)
                            .post("${proxy.url}/v1/messages") { setBody("{}") }
                            .bodyAsChannel()
                            .readRemaining()
                            .readByteArray()
                    assertContentEquals(fixture, body)
                }
            }
        }

    @Test
    fun `returns 429, 529, and 500 with the given bodies, forwarded unchanged`() = runBlocking {
        FakeUpstream().use { upstream ->
            ProxyServer(ProxyConfig(port = 0, anthropicUpstream = upstream.url)).use { proxy ->
                for (status in listOf(429, 529, 500)) {
                    val body = """{"type":"error","error":{"type":"status_$status"}}"""
                    upstream.reply = { FakeUpstream.Reply(status = status, body = body) }

                    val response =
                        HttpClient(CIO).post("${proxy.url}/v1/messages") { setBody("{}") }

                    assertEquals(status, response.status.value)
                    assertEquals(body, response.bodyAsText())
                }
            }
        }
    }

    @Test
    fun `delays chunks by the time a test asks for`() = runBlocking {
        val frames =
            listOf("event: message_start\ndata: {}\n\n", "event: message_stop\ndata: {}\n\n")
        FakeUpstream().use { upstream ->
            upstream.reply = { streamOf(frames) { index -> if (index == 1) delay(300) } }
            ProxyServer(ProxyConfig(port = 0, anthropicUpstream = upstream.url)).use { proxy ->
                HttpClient(CIO)
                    .preparePost("${proxy.url}/v1/messages") { setBody("{}") }
                    .execute { response ->
                        val channel = response.bodyAsChannel()
                        channel.readLine()
                        val firstAt = TimeSource.Monotonic.markNow()
                        channel.readRemaining().readString()
                        assertTrue(firstAt.elapsedNow().inWholeMilliseconds >= 250, "$firstAt")
                    }
            }
        }
    }

    @Test
    fun `cuts the connection mid-stream, closing the socket after the given frame with no final chunk`() {
        // Ktor's client reads a cut as a clean end, so only a raw socket can see the missing
        // chunked-encoding terminator.
        val frames =
            listOf("event: message_start\ndata: {}\n\n", "event: message_stop\ndata: {}\n\n")
        FakeUpstream().use { upstream ->
            upstream.reply = { streamOf(frames, cutAfterFrame = 1) }
            val (host, port) = upstream.url.removePrefix("http://").split(":")
            val wire =
                Socket(host, port.toInt()).use { socket ->
                    socket.soTimeout = 5_000 // a regression that leaves the socket open fails here
                    socket
                        .getOutputStream()
                        .write(
                            "POST /v1/messages HTTP/1.1\r\nHost: $host\r\nContent-Length: 0\r\n\r\n"
                                .toByteArray()
                        )
                    socket.getInputStream().readBytes().decodeToString()
                }

            assertContains(wire, frames[0])
            assertFalse(wire.contains("message_stop"), wire)
            assertFalse(
                wire.trimEnd().endsWith("\r\n0"),
                "the final chunk must never be sent: $wire",
            )
        }
    }
}
