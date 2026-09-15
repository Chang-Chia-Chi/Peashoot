package dev.peashoot.proxy

import dev.peashoot.core.Exchange
import dev.peashoot.core.Frame
import dev.peashoot.core.Interceptor
import dev.peashoot.core.Outcome
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * An exchange the proxy never answered still ends: it heard the request, so the chain hears the
 * end, whether the relay refused to forward it or the proxy stopped with it in flight. A refusal is
 * ours, so it wears the peashoot error shape and never a provider's.
 */
class UnansweredTest {
    private fun home(): Path = Files.createTempDirectory("peashoot-home")

    /** The lines the deriver wrote, parsed. Read once the server has stopped. */
    private fun events(home: Path): List<JsonObject> =
        home.resolve(EVENTS_FILE).readLines().map { Json.parseToJsonElement(it).jsonObject }

    /** Records what the chain tells it about the ending, and changes nothing. */
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
     * Posts to the proxy over a raw socket with [extraHeaders] verbatim: a client library refuses
     * to send a content-type it cannot parse, and never holds a connection open on command. The
     * socket is the caller's, and stays open until it closes it.
     */
    /** Breaks the contract on purpose: the relay must still end the exchange it began. */
    private class Broken : Interceptor {
        override fun onFrames(exchange: Exchange, frames: Flow<Frame>): Flow<Frame> =
            error("broken on purpose")
    }

    private fun openPost(proxy: ProxyServer, extraHeaders: String = ""): Socket {
        val (host, port) = proxy.url.removePrefix("http://").split(":")
        val body = """{"model":"claude"}"""
        val socket = Socket(host, port.toInt())
        // A regression that never answers hangs for this long, not forever.
        socket.soTimeout = 5_000
        socket.getOutputStream().apply {
            write(
                ("POST /v1/messages HTTP/1.1\r\nHost: $host:$port\r\n$extraHeaders" +
                        "Content-Length: ${body.length}\r\n\r\n$body")
                    .toByteArray()
            )
            flush()
        }
        return socket
    }

    /** The status line and the body, which a proxy failure always sends under a content-length. */
    private fun Socket.readResponse(): Pair<String, String> {
        val reader = getInputStream().bufferedReader()
        val statusLine = reader.readLine()
        // Every header line is read, not only the one we want: the body starts after the last.
        val headers = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
        val length =
            headers
                .first { it.startsWith("Content-Length:", ignoreCase = true) }
                .substringAfter(":")
                .trim()
                .toInt()
        return statusLine to String(CharArray(length).also { reader.read(it) })
    }

    @Test
    fun `a request whose content-type does not parse is refused with a 400, chain hears the end`() =
        runBlocking {
            val observer = Observer()
            val home = home()
            Store(home).use { store ->
                FakeUpstream().use { upstream ->
                    val config = ProxyConfig(port = 0, anthropicUpstream = upstream.url)
                    val chain = listOf(Deriver(store, home.resolve(EVENTS_FILE)), observer)
                    ProxyServer(config, chain).use { proxy ->
                        val (statusLine, body) =
                            openPost(proxy, "Content-Type: text\r\n").use { it.readResponse() }
                        assertEquals("HTTP/1.1 400 Bad Request", statusLine)
                        assertContains(body, """"type":"peashoot_error"""")
                        assertContains(body, """"error":"bad_content_type"""")
                        withTimeout(5_000) { observer.completed.await() }
                    }
                    assertTrue(upstream.received.isEmpty(), "a refused request is never forwarded")
                }
            }

            assertEquals(listOf("complete 400"), observer.log)
            val lines = events(home)
            assertEquals(
                listOf("exchange.started", "exchange.completed"),
                lines.map { it.getValue("event").jsonPrimitive.content },
            )
            val completed = lines.last()
            assertEquals(400, completed.getValue("status").jsonPrimitive.int)
            assertFalse(completed.getValue("clientDisconnected").jsonPrimitive.boolean)
        }

    @Test
    fun `stopping the proxy with a request in flight still ends its line`() = runBlocking {
        val answered = CompletableDeferred<Unit>()
        val observer = Observer()
        val home = home()
        Store(home).use { store ->
            FakeUpstream().use { upstream ->
                // Held before its headers, so the request is still in flight when the proxy stops.
                upstream.reply = {
                    answered.await()
                    FakeUpstream.Reply()
                }
                val config = ProxyConfig(port = 0, anthropicUpstream = upstream.url)
                val chain =
                    listOf(Recorder(store), Deriver(store, home.resolve(EVENTS_FILE)), observer)
                val proxy = ProxyServer(config, chain)
                // The client stays: the stop is what must end the call, not a departure.
                openPost(proxy).use {
                    withTimeout(5_000) { while (upstream.received.isEmpty()) delay(20) }
                    proxy.close()
                    withTimeout(5_000) { observer.completed.await() }
                }
                // Only now, so the held handler finishes before the fake stops.
                answered.complete(Unit)
            }
            assertTrue(store.list().isEmpty(), "no source answered, so nothing is stored")
        }

        assertEquals(listOf("complete null"), observer.log)
        val lines = events(home)
        assertEquals(
            listOf("exchange.started", "exchange.completed"),
            lines.map { it.getValue("event").jsonPrimitive.content },
        )
        val completed = lines.last()
        assertEquals(JsonNull, completed.getValue("status"), "no status ever existed")
        assertFalse(completed.getValue("clientDisconnected").jsonPrimitive.boolean)
    }

    @Test
    fun `an interceptor that throws while wrapping the frames ends the exchange as a 500`() =
        runBlocking {
            val observer = Observer()
            val home = home()
            Store(home).use { store ->
                FakeUpstream().use { upstream ->
                    val config = ProxyConfig(port = 0, anthropicUpstream = upstream.url)
                    val deriver = Deriver(store, home.resolve(EVENTS_FILE))
                    val chain = listOf(Recorder(store), deriver, Broken(), observer)
                    ProxyServer(config, chain).use { proxy ->
                        // Ktor's own answer to an unhandled throw; only its status line matters.
                        val statusLine =
                            openPost(proxy).use { it.getInputStream().bufferedReader().readLine() }
                        assertEquals("HTTP/1.1 500 Internal Server Error", statusLine)
                        withTimeout(5_000) { observer.completed.await() }
                    }
                }
                assertTrue(store.list().isEmpty(), "no stream ran, so nothing is stored")
            }

            assertEquals(listOf("complete 500"), observer.log)
            val lines = events(home)
            assertEquals(
                listOf("exchange.started", "exchange.completed"),
                lines.map { it.getValue("event").jsonPrimitive.content },
            )
            assertEquals(500, lines.last().getValue("status").jsonPrimitive.int)
        }
}
