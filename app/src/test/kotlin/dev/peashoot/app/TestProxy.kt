package dev.peashoot.app

import dev.peashoot.core.Exchange
import dev.peashoot.core.Mode
import dev.peashoot.core.Route
import dev.peashoot.core.TOKEN_FILE
import dev.peashoot.proxy.ControlApi
import dev.peashoot.proxy.LiveConfig
import dev.peashoot.proxy.ProxyConfig
import dev.peashoot.proxy.ProxyServer
import dev.peashoot.proxy.Recorded
import dev.peashoot.proxy.Recorder
import dev.peashoot.proxy.Replay
import dev.peashoot.proxy.RouteTable
import dev.peashoot.proxy.Store
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** How long any wait in these tests gets before it is a failure rather than a slow machine. */
internal const val PATIENCE = 10_000L

/** How long a silent feed waits before its keep-alive, short enough for a test to sit through. */
private const val PING_MS = 200L

/**
 * A real proxy on a real port, with the control API the app talks to and nothing else: the tests
 * put event lines into the store and onto the feed exactly as the deriver does, because what is
 * under test is the client, not what made the line. Started on a port of its own so [restart] can
 * take the same one back, which is what a proxy the user restarted looks like to the app.
 */
internal class TestProxy(val home: Path, upstream: String? = null) : AutoCloseable {
    val store = Store(home)
    // A short ping so an idle spell in a test still writes the keep-alive comments a real feed
    // writes: a client that cannot skip them would break on the quiet, not on the traffic.
    //
    // [upstream] is where the relay sends what it cannot answer itself. A test that never goes
    // through the relay leaves it alone and the default stands; a test that does must name one,
    // because the default is the real provider and no test here may ever reach it.
    private val config =
        ProxyConfig(
            port = freePort(),
            host = "127.0.0.1",
            anthropicUpstream = upstream ?: ProxyConfig().anthropicUpstream,
            pingInterval = PING_MS.milliseconds,
        )
    val port: Int
        get() = config.port

    val url = "http://127.0.0.1:$port"

    private var api = newApi()
    private var server = ProxyServer(config, chain(), api)

    val token: String
        get() = home.resolve(TOKEN_FILE).readText().trim()

    /** Stops the proxy and starts another on the same port, over the same store. */
    fun restart() {
        stop()
        api = newApi()
        server = ProxyServer(config, chain(), api)
    }

    fun stop() = server.close()

    /** One event line, stored and published under one id, as the deriver stores and publishes. */
    suspend fun emit(name: String): Long =
        put(
            buildJsonObject {
                put("ts", Instant.now().toString())
                put("event", name)
                put("exchangeId", name)
            }
        )

    /**
     * A whole recorded line through the same door, so a test can put the reducer's own fixtures on
     * a real feed and read what the proxy makes of them at the other end.
     */
    suspend fun put(event: JsonObject): Long =
        store.putEvent(event).also { api.feed.publish(it, event) }

    /**
     * One whole exchange as a recorder would leave it: a row in the exchange table, and the
     * `exchange.completed` line that ties it to a session. Both are needed, because `GET
     * /exchanges?session=` finds its rows through the event table and an exchange with no line
     * belongs to no session at all.
     *
     * [announce] off stores the line without publishing it, which is what an exchange that happened
     * before this window connected looks like: the endpoint has it and the feed never carried it.
     */
    suspend fun record(
        session: String,
        id: String,
        at: Instant,
        disconnected: Boolean = false,
        announce: Boolean = true,
        /** What the turn's one tool read, for a test about touches; none names no file. */
        toolPath: String? = null,
    ): JsonObject {
        val exchange =
            Exchange(
                Exchange.Request("POST", "/v1/messages", Headers.Empty, A_REQUEST.toByteArray()),
                route = "default",
                routing = Route(Mode.RECORD),
                id = id,
                receivedAt = at,
            )
        exchange.response = Exchange.Response(HttpStatusCode.OK.value, Headers.Empty)
        exchange.clientDisconnected = disconnected
        store.put(listOf(Recorded(exchange, emptyList())))
        val line = completedLine(session, id, at, disconnected, toolPath)
        if (announce) put(line) else store.putEvent(line)
        return line
    }

    /**
     * A line straight onto the feed, bypassing the store, which only accepts the shape this version
     * of the proxy writes. A later proxy may put anything on a line, and the window has to survive
     * reading it.
     */
    fun publish(id: Long, event: JsonObject) = api.feed.publish(id, event)

    /**
     * One live recording with a body of its own, fingerprinted under the rules this proxy is
     * running, and no event line at all: what `POST /rules/test` and an export read is the exchange
     * table, and the fingerprint is what a rule test compares a candidate's against.
     */
    suspend fun recorded(id: String, body: String) {
        val request = Exchange.Request("POST", "/v1/messages", Headers.Empty, body.toByteArray())
        val exchange =
            Exchange(
                request,
                route = "default",
                routing = Route(Mode.RECORD),
                id = id,
                receivedAt = Instant.now(),
            )
        exchange.response = Exchange.Response(HttpStatusCode.OK.value, Headers.Empty)
        exchange.fingerprint =
            config.rules.fingerprint(
                request.method,
                request.path,
                request.headers,
                request.json,
                request.body,
            )
        store.put(listOf(Recorded(exchange, emptyList())))
    }

    override fun close() {
        stop()
        store.close()
    }

    private fun newApi() = ControlApi(store, home, RouteTable(config.routes), LiveConfig(config))

    /**
     * The interceptors `proxy serve` runs, minus the deriver: a request through the relay is
     * recorded and a replay serves what was recorded, which is the whole of what a route mode does
     * and the only way to see a mode switch from outside the process. The deriver is left out
     * because what it writes is event lines, and the tests that want those put them on the feed
     * themselves.
     */
    private fun chain() = listOf(Replay(store, config), Recorder(store))
}

/** Enough of a request to be a body worth looking at, and nothing a real key ever went near. */
private const val A_REQUEST = """{"model":"claude-opus-4-1","messages":[{"role":"user"}]}"""

/**
 * The `exchange.completed` line the Deriver would have written for that exchange, in its own field
 * order: what the farm folds, and what a timeline row's usage, cost and latency can only come from.
 */
private fun completedLine(
    session: String,
    id: String,
    at: Instant,
    disconnected: Boolean,
    toolPath: String?,
): JsonObject = buildJsonObject {
    put("ts", at.toString())
    put("event", "exchange.completed")
    put("exchangeId", id)
    put("session", session)
    put("agent", null as String?)
    put("client", "claude-code")
    put("model", "claude-opus-4-1")
    put("route", "default")
    put("mode", "record")
    put(
        "tools",
        buildJsonArray {
            add(
                buildJsonObject {
                    put("name", "Read")
                    toolPath?.let { put("path", it) }
                }
            )
        },
    )
    put(
        "usage",
        buildJsonObject {
            put("input", TOKENS_IN)
            put("output", TOKENS_OUT)
            put("cacheRead", 0)
            put("cacheWrite", 0)
        },
    )
    put("costUsd", A_COST)
    put("stopReason", "end_turn")
    put("status", HttpStatusCode.OK.value)
    put("latencyMs", A_LATENCY)
    put("replayHit", false)
    put("clientDisconnected", disconnected)
}

private const val TOKENS_IN = 120
private const val TOKENS_OUT = 340
private const val A_COST = 0.25
private const val A_LATENCY = 1_500L

/** A port nothing holds right now: the proxy takes it, gives it up, and takes it again. */
internal fun freePort(): Int = ServerSocket(0).use { it.localPort }

/**
 * A proxy and a fresh data directory for one test. `runBlocking`, not `runTest`: every wait here is
 * on a real socket, where a virtual clock would fire the timeouts before the bytes arrived.
 */
internal fun withTestProxy(upstream: String? = null, block: suspend (TestProxy) -> Unit) =
    runBlocking {
        val home = Files.createTempDirectory("peashoot-app")
        TestProxy(home, upstream).use { block(it) }
    }

/** Waits until [condition] holds, or fails the test by timing out. */
internal suspend fun until(condition: () -> Boolean) =
    withTimeout(PATIENCE) { while (!condition()) delay(20) }

/** The name an event line carries, for a test that only cares which line it got. */
internal fun JsonObject.eventName(): String = getValue("event").toString().trim('"')
