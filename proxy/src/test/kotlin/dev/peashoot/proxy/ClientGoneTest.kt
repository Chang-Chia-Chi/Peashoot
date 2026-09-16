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
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** What the chain and the store see when the client leaves before the stream ends. */
class ClientGoneTest {
    private fun fixture(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/anthropic-messages/stream-with-tool-use.sse"))
            .readBytes()

    private fun home(): Path = Files.createTempDirectory("peashoot-home")

    /** The lines the deriver wrote, parsed. Read once the server has stopped. */
    private fun events(home: Path): List<JsonObject> =
        home.resolve(EVENTS_FILE).readLines().map { Json.parseToJsonElement(it).jsonObject }

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
     * Posts over a raw socket and leaves as soon as the upstream has the request, while the relay
     * is still parked waiting for its headers: no byte of the answer exists yet, so no write can
     * fail, and the departure is heard from the engine or not at all (#51).
     */
    private suspend fun leaveOnceReceived(proxy: ProxyServer, received: CompletableDeferred<Unit>) {
        val (host, port) = proxy.url.removePrefix("http://").split(":")
        Socket(host, port.toInt()).use { socket ->
            val body = """{"model":"claude"}"""
            socket.getOutputStream().apply {
                write(
                    ("POST /v1/messages HTTP/1.1\r\nHost: $host:$port\r\n" +
                            "Content-Length: ${body.length}\r\n\r\n$body")
                        .toByteArray()
                )
                flush()
            }
            // A regression that never reaches the upstream fails here, not by hanging forever.
            withTimeout(5_000) { received.await() }
        }
    }

    /**
     * The upstream is held until the client has left, and the frames after it are spaced out. The
     * spacing buys time, not detection: the engine now reports the close on its own channel, but
     * the departure still has to arrive before the drive runs out of frames, or it lands on an
     * exchange already completed and is dropped as a departure after the end. Client-gone comes
     * before completion because the stream's lock serialises the two and completion refuses to
     * wait, so a shorter gap would only make a real departure go unheard.
     */
    private fun clientLeavesMidStream(reset: Boolean) = runBlocking {
        val frames = FrameParser.parse(fixture()).map { it.raw }
        val readBeforeLeaving = 6
        val reached = CopyOnWriteArrayList<Int>()
        val clientGone = CompletableDeferred<Unit>()
        val observer = Observer()
        val home = home()
        Store(home).use { store ->
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
                val chain =
                    listOf(Recorder(store), Deriver(store, home.resolve(EVENTS_FILE)), observer)
                ProxyServer(config, chain).use { proxy ->
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
        assertEvents(home, frames, readBeforeLeaving)
    }

    /**
     * The three lines of an exchange the client left: the departure is its own line, between the
     * start and the completion, and it names the bytes the client had taken.
     */
    private fun assertEvents(home: Path, frames: List<String>, readBeforeLeaving: Int) {
        val lines = events(home)
        assertEquals(
            listOf("exchange.started", "exchange.client_gone", "exchange.completed"),
            lines.map { it.getValue("event").jsonPrimitive.content },
        )
        assertEquals(1, lines.mapTo(mutableSetOf()) { it.getValue("exchangeId") }.size, "one id")
        assertTrue(lines.last().getValue("clientDisconnected").jsonPrimitive.boolean)
        val gone = lines[1]
        assertEquals(
            listOf("ts", "event", "exchangeId", "session", "bytesSoFar"),
            gone.keys.toList(),
        )
        val bytes = gone.getValue("bytesSoFar").jsonPrimitive.long
        val taken = frames.take(readBeforeLeaving).joinToString("").toByteArray().size.toLong()
        // The count is what the writer put on the wire, which runs ahead of what the client read:
        // a write to a closed channel still succeeds, so a frame or two past it may be counted,
        // whether the close is heard from the channel or from the reset a later write provokes.
        // The 50 ms spacing leaves three frames of slack before the count would say it stayed.
        val ceiling =
            frames.take(readBeforeLeaving + 3).joinToString("").toByteArray().size.toLong()
        assertTrue(
            bytes in taken..ceiling,
            "bytesSoFar $bytes, client read $taken, at most $ceiling",
        )
    }

    @Test
    fun `a client that closes mid-stream is stored complete and flagged, gone then complete`() =
        clientLeavesMidStream(reset = false)

    @Test
    fun `a client that resets mid-stream is stored complete and flagged, gone then complete`() =
        clientLeavesMidStream(reset = true)

    /**
     * The departure no write can report: the client leaves while the relay is parked in the
     * connect, so the whole response is written to a channel Netty has already closed and discards
     * silently. Nothing had gone out, so the byte count is zero, and the exchange is still recorded
     * whole: the departure is observed, never a reason to stop consuming (#51).
     */
    @Test
    fun `a client that leaves before the first byte is flagged, gone then complete`() =
        runBlocking {
            val frames = FrameParser.parse(fixture()).map { it.raw }
            val received = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val observer = Observer()
            val home = home()
            Store(home).use { store ->
                FakeUpstream().use { upstream ->
                    upstream.reply = {
                        received.complete(Unit)
                        // Held before its headers, so the relay has nothing to write yet.
                        release.await()
                        FakeUpstream.Reply(
                            contentType = ContentType.Text.EventStream,
                            frames = frames,
                        )
                    }
                    val config = ProxyConfig(port = 0, anthropicUpstream = upstream.url)
                    val chain =
                        listOf(Recorder(store), Deriver(store, home.resolve(EVENTS_FILE)), observer)
                    ProxyServer(config, chain).use { proxy ->
                        leaveOnceReceived(proxy, received)
                        release.complete(Unit)
                        withTimeout(5_000) { observer.completed.await() }
                    }
                }
                val recorded = store.list().single()
                assertEquals(frames, recorded.frames.map { it.raw }, "every frame was consumed")
                assertEquals(200, recorded.exchange.response?.status)
                assertTrue(recorded.exchange.clientDisconnected, "the client left before a byte")
            }
            assertEquals(listOf("client-gone", "complete 200"), observer.log)
            val lines = events(home)
            assertEquals(
                listOf("exchange.started", "exchange.client_gone", "exchange.completed"),
                lines.map { it.getValue("event").jsonPrimitive.content },
            )
            assertEquals(0L, lines[1].getValue("bytesSoFar").jsonPrimitive.long, "nothing went out")
        }

    /**
     * The issue's other trigger: nothing streams, so the whole answer is one burst of writes into a
     * channel Netty has already closed, every one of them discarded without an error. The departure
     * is known before the first of them, so no write ever had to fail for it (#51).
     */
    @Test
    fun `a client that left is flagged for a response written in one burst`() = runBlocking {
        val received = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val observer = Observer()
        val home = home()
        val answer = """{"id":"msg_1","content":[{"type":"text","text":"hi"}]}"""
        Store(home).use { store ->
            FakeUpstream().use { upstream ->
                upstream.reply = {
                    received.complete(Unit)
                    release.await()
                    FakeUpstream.Reply(body = answer)
                }
                val config = ProxyConfig(port = 0, anthropicUpstream = upstream.url)
                val chain =
                    listOf(Recorder(store), Deriver(store, home.resolve(EVENTS_FILE)), observer)
                ProxyServer(config, chain).use { proxy ->
                    leaveOnceReceived(proxy, received)
                    release.complete(Unit)
                    withTimeout(5_000) { observer.completed.await() }
                }
            }
            val recorded = store.list().single()
            assertEquals(listOf(answer), recorded.frames.map { it.raw }, "the answer was recorded")
            assertTrue(recorded.exchange.clientDisconnected, "no write failed, and it still knew")
        }
        assertEquals(listOf("client-gone", "complete 200"), observer.log)
    }

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
