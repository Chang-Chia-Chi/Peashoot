package dev.peashoot.proxy

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking

data class ProxyConfig(
    val port: Int = 8787,
    val bindHost: String = "127.0.0.1",
    val anthropicUpstream: String = "https://api.anthropic.com",
)

/** The headless proxy: one Ktor server relaying every request to the configured upstream. */
class ProxyServer(private val config: ProxyConfig) : AutoCloseable {
    private val upstream =
        HttpClient(ClientCIO) {
            // Streams outlive the engine's 15 s default; 0 disables the per-request timeout.
            engine { requestTimeout = 0 }
        }

    private val server: EmbeddedServer<*, *> =
        embeddedServer(ServerCIO, port = config.port, host = config.bindHost) {
                relayModule(config, upstream)
            }
            .start(wait = false)

    val url: String
        get() = runBlocking {
            "http://${config.bindHost}:${server.engine.resolvedConnectors().first().port}"
        }

    override fun close() {
        server.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        upstream.close()
    }
}

fun Application.relayModule(config: ProxyConfig, upstream: HttpClient) {
    install(CallLogging) // method, path, status, duration; never headers or bodies
    routing { route("{...}") { handle { relay(call, config.anthropicUpstream, upstream) } } }
}
