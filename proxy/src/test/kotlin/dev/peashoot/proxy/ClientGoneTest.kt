package dev.peashoot.proxy

import dev.peashoot.core.Exchange
import dev.peashoot.core.FrameParser
import dev.peashoot.core.Interceptor
import dev.peashoot.core.Outcome
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** What the chain and the store see when the client leaves before the stream ends. */
class ClientGoneTest {
    private fun fixture(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/anthropic-messages/stream-with-tool-use.sse"))
            .readBytes()

    private fun home(): Path = Files.createTempDirectory("peashoot-home")

    /** Records what the chain tells it about the client leaving, and changes nothing. */
    private class Observer : Interceptor {
        /** The chain's hooks run off the test thread. */
        val log = CopyOnWriteArrayList<String>()
        val completed = CompletableDeferred<Unit>()

        override suspend fun onClientGone(exchange: Exchange) {
            log += "client-gone"
        }

        override suspend fun onComplete(exchange: Exchange, outcome: Outcome) {
            log += "complete ${outcome.status}"
            completed.complete(Unit)
        }
    }

    /**
     * Posts to the proxy over a raw socket, reads until [untilFrames] frames are out, then leaves:
     * with a reset when [reset] is set, otherwise plainly. No client library exposes either.
     */
    private fun leaveAfterFrames(proxy: ProxyServer, untilFrames: Int, reset: Boolean) {
        val (host, port) = proxy.url.removePrefix("http://").split(":")
        Socket(host, port.toInt()).use { socket ->
            // A regression that never sends the frames hangs for this long, not forever.
            socket.soTimeout = 5_000
            val body = """{"model":"claude"}"""
            socket.getOutputStream().apply {
                write(
                    ("POST /v1/messages HTTP/1.1\r\nHost: $host:$port\r\n" +
                            "Content-Length: ${body.length}\r\n\r\n$body")
                        .toByteArray()
                )
                flush()
            }
            val input = socket.getInputStream()
            val buffer = ByteArray(4096)
            var seen = 0
            var previous = 0
            while (seen < untilFrames) {
                val read = input.read(buffer)
                check(read != -1) { "the response ended after $seen frames" }
                for (i in 0 until read) {
                    val byte = buffer[i].toInt()
                    // A frame ends in a blank line; chunked framing, `<hex>\r\n...\r\n`, never
                    // puts two newlines together.
                    if (byte == '\n'.code && previous == '\n'.code) seen++
                    previous = byte
                }
            }
            if (reset) {
                socket.setSoLinger(true, 0)
            } else {
                // Bytes left unread would make the close a reset too.
                while (input.available() > 0) input.read(buffer)
            }
        }
    }

    /**
     * The upstream is held until the client has left, and the frames after it are spaced out. The
     * engine hears a clean close only when a write to the departed client provokes its reset, and
     * that takes a round trip to come back, so those frames must not all go out in one burst. The
     * spacing buys detection, nothing else: the ordering of client-gone before completion is the
     * stream's lock, not this timing, so a shorter gap would only make the departure go unheard.
     */
    private fun clientLeavesMidStream(reset: Boolean) = runBlocking {
        val frames = FrameParser.parse(fixture()).map { it.raw }
        val readBeforeLeaving = 6
        val reached = CopyOnWriteArrayList<Int>()
        val clientGone = CompletableDeferred<Unit>()
        val observer = Observer()
        Store(home()).use { store ->
            FakeUpstream().use { upstream ->
                upstream.reply = {
                    FakeUpstream.Reply(
                        contentType = ContentType.Text.EventStream,
                        frames = frames,
                        beforeFrame = { index ->
                            reached += index
                            if (index == readBeforeLeaving) clientGone.await()
                            // Slow on purpose, and not a race guard: shortening it only risks the
                            // departure going unheard.
                            if (index > readBeforeLeaving) delay(50)
                        },
                    )
                }
                val config = ProxyConfig(port = 0, anthropicUpstream = upstream.url)
                ProxyServer(config, listOf(Recorder(store), observer)).use { proxy ->
                    leaveAfterFrames(proxy, untilFrames = readBeforeLeaving, reset = reset)
                    clientGone.complete(Unit)
                    withTimeout(5_000) { observer.completed.await() }
                }
            }
            assertEquals(frames.indices.toList(), reached.toList(), "every frame was still pulled")
            val recorded = store.list().single()
            assertEquals(frames, recorded.frames.map { it.raw })
            assertEquals(200, recorded.exchange.response?.status)
            assertTrue(recorded.exchange.clientDisconnected, "the client left mid-stream")
        }
        assertEquals(listOf("client-gone", "complete 200"), observer.log)
    }

    @Test
    fun `a client that closes mid-stream is stored complete and flagged, gone then complete`() =
        clientLeavesMidStream(reset = false)

    @Test
    fun `a client that resets mid-stream is stored complete and flagged, gone then complete`() =
        clientLeavesMidStream(reset = true)

    @Test
    fun `a client that stays to the end is stored with the flag unset`() = runBlocking {
        val frames = FrameParser.parse(fixture()).map { it.raw }
        val observer = Observer()
        Store(home()).use { store ->
            FakeUpstream().use { upstream ->
                upstream.reply = {
                    FakeUpstream.Reply(contentType = ContentType.Text.EventStream, frames = frames)
                }
                val config = ProxyConfig(port = 0, anthropicUpstream = upstream.url)
                ProxyServer(config, listOf(Recorder(store), observer)).use { proxy ->
                    val body =
                        HttpClient(CIO).use {
                            it.post("${proxy.url}/v1/messages") { setBody("{}") }.bodyAsText()
                        }
                    assertEquals(frames.joinToString(""), body)
                    withTimeout(5_000) { observer.completed.await() }
                }
            }
            val recorded = store.list().single()
            assertEquals(frames, recorded.frames.map { it.raw })
            assertFalse(recorded.exchange.clientDisconnected, "the client stayed to the end")
        }
        assertEquals(listOf("complete 200"), observer.log)
    }
}
