package dev.peashoot.app

import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * A socket in front of the proxy that can be cut the way a killed process cuts one: a reset rather
 * than an orderly close, so a client reading the feed fails mid-stream instead of reaching the end
 * of it. A graceful server stop is the other path, and the one the rest of these tests take; this
 * one exists because the two leave the client in different places, and only one of them was tested.
 */
internal class Wire(private val proxyPort: Int) : AutoCloseable {
    private val listener = ServerSocket(0)
    private val live = Collections.synchronizedList(mutableListOf<Socket>())
    private val threads = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }

    val port: Int = listener.localPort
    val url = "http://127.0.0.1:$port"

    init {
        threads.submit(::accept)
    }

    /** Cuts every connection open right now with a reset, and keeps accepting new ones. */
    fun crash() {
        synchronized(live) {
            live.forEach { socket ->
                // Zero linger turns close into RST: the peer's next read throws rather than ending.
                runCatching { socket.setSoLinger(true, 0) }
                runCatching { socket.close() }
            }
            live.clear()
        }
    }

    override fun close() {
        crash()
        runCatching { listener.close() }
        threads.shutdownNow()
    }

    private fun accept() {
        while (!listener.isClosed) {
            val downstream = runCatching { listener.accept() }.getOrNull() ?: break
            // The proxy may be stopped behind this wire; the client then sees the same refusal it
            // would see without one.
            val upstream = runCatching { Socket("127.0.0.1", proxyPort) }.getOrNull()
            if (upstream == null) {
                runCatching { downstream.close() }
            } else {
                live += downstream
                live += upstream
                threads.submit { pump(downstream, upstream) }
                threads.submit { pump(upstream, downstream) }
            }
        }
    }

    /** Byte for byte, unbuffered: an SSE line has to reach the reader when it is written. */
    private fun pump(from: Socket, to: Socket) {
        runCatching { from.getInputStream().copyTo(to.getOutputStream()) }
        runCatching { to.shutdownOutput() }
    }
}

/**
 * Something on the port that is not Peashoot: one fixed HTTP answer, whatever is asked. A port that
 * answers at all is a port no second proxy could bind, which is the distinction the app has to make
 * before it decides to start one.
 */
internal class Impostor(private val status: String, private val body: String) : AutoCloseable {
    private val listener = ServerSocket(0)
    private val threads = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }

    val url = "http://127.0.0.1:${listener.localPort}"

    init {
        threads.submit(::serve)
    }

    override fun close() {
        runCatching { listener.close() }
        threads.shutdownNow()
    }

    private fun serve() {
        while (!listener.isClosed) {
            val socket = runCatching { listener.accept() }.getOrNull() ?: break
            threads.submit { answer(socket) }
        }
    }

    private fun answer(socket: Socket) {
        runCatching {
            socket.use {
                it.getInputStream().bufferedReader().readLine()
                val head =
                    "HTTP/1.1 $status\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n"
                it.getOutputStream().write((head + body).toByteArray())
                it.getOutputStream().flush()
            }
        }
    }
}

/**
 * A provider that is not a provider: one fixed 200 to every request, and a count of how many it was
 * asked for. The count is the point. A route in replay mode serves from the store and never asks
 * the upstream, so the same request answered twice with this number moving once is the difference a
 * mode switch makes, seen from outside the proxy rather than read back out of its config.
 *
 * Its own class and not [Impostor] because this one reads the request to the end before it answers:
 * a relayed POST carries a body, and a server that replies and closes on a body still being written
 * hands the proxy a broken pipe where the test wanted an answer.
 */
internal class Upstream(private val body: String) : AutoCloseable {
    private val listener = ServerSocket(0)
    private val threads = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }

    /** How many requests have reached it. Written on its own threads, read from the test's. */
    val calls = AtomicInteger()

    val url = "http://127.0.0.1:${listener.localPort}"

    init {
        threads.submit(::serve)
    }

    override fun close() {
        runCatching { listener.close() }
        threads.shutdownNow()
    }

    private fun serve() {
        while (!listener.isClosed) {
            val socket = runCatching { listener.accept() }.getOrNull() ?: break
            threads.submit { answer(socket) }
        }
    }

    private fun answer(socket: Socket) {
        runCatching {
            socket.use {
                val reader = it.getInputStream().bufferedReader()
                var length = 0
                var line = reader.readLine()
                while (!line.isNullOrEmpty()) {
                    if (line.startsWith(CONTENT_LENGTH, ignoreCase = true)) {
                        length = line.substringAfter(':').trim().toInt()
                    }
                    line = reader.readLine()
                }
                // Characters and not bytes, which is the same count only because every body these
                // tests send is ASCII; a fake upstream is not the place for a charset.
                repeat(length) { reader.read() }
                calls.incrementAndGet()
                val head =
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n"
                it.getOutputStream().write((head + body).toByteArray())
                it.getOutputStream().flush()
            }
        }
    }
}

private const val CONTENT_LENGTH = "Content-Length:"
