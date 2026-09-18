package dev.peashoot.app

import dev.peashoot.core.TOKEN_FILE
import dev.peashoot.proxy.ControlApi
import dev.peashoot.proxy.LiveConfig
import dev.peashoot.proxy.ProxyConfig
import dev.peashoot.proxy.ProxyServer
import dev.peashoot.proxy.RouteTable
import dev.peashoot.proxy.Store
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** How long any wait in these tests gets before it is a failure rather than a slow machine. */
internal const val PATIENCE = 10_000L

/**
 * A real proxy on a real port, with the control API the app talks to and nothing else: the tests
 * put event lines into the store and onto the feed exactly as the deriver does, because what is
 * under test is the client, not what made the line. Started on a port of its own so [restart] can
 * take the same one back, which is what a proxy the user restarted looks like to the app.
 */
internal class TestProxy(val home: Path) : AutoCloseable {
    val store = Store(home)
    private val config = ProxyConfig(port = freePort(), host = "127.0.0.1")
    val url = "http://127.0.0.1:${config.port}"

    private var api = newApi()
    private var server = ProxyServer(config, control = api)

    val token: String
        get() = home.resolve(TOKEN_FILE).readText().trim()

    /** Stops the proxy and starts another on the same port, over the same store. */
    fun restart() {
        stop()
        api = newApi()
        server = ProxyServer(config, control = api)
    }

    fun stop() = server.close()

    /** One event line, stored and published under one id, as the deriver stores and publishes. */
    suspend fun emit(name: String): Long {
        val event = buildJsonObject {
            put("ts", Instant.now().toString())
            put("event", name)
            put("exchangeId", name)
        }
        return store.putEvent(event).also { api.feed.publish(it, event) }
    }

    override fun close() {
        stop()
        store.close()
    }

    private fun newApi() = ControlApi(store, home, RouteTable(config.routes), LiveConfig(config))
}

/** A port nothing holds right now: the proxy takes it, gives it up, and takes it again. */
private fun freePort(): Int = ServerSocket(0).use { it.localPort }

/**
 * A proxy and a fresh data directory for one test. `runBlocking`, not `runTest`: every wait here is
 * on a real socket, where a virtual clock would fire the timeouts before the bytes arrived.
 */
internal fun withTestProxy(block: suspend (TestProxy) -> Unit) = runBlocking {
    val home = Files.createTempDirectory("peashoot-app")
    TestProxy(home).use { block(it) }
}

/** Waits until [condition] holds, or fails the test by timing out. */
internal suspend fun until(condition: () -> Boolean) =
    withTimeout(PATIENCE) { while (!condition()) delay(20) }

/** The name an event line carries, for a test that only cares which line it got. */
internal fun JsonObject.eventName(): String = getValue("event").toString().trim('"')
