package dev.peashoot.proxy

import dev.peashoot.core.Interceptor
import dev.peashoot.core.Mode
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

data class ProxyConfig(
    val port: Int = 8787,
    val anthropicUpstream: String = "https://api.anthropic.com",
    /** Debug: append every raw upstream response to this file, for building fixtures. */
    val dumpFrames: Path? = null,
    /** Sent upstream, never kept: not on the Exchange, not in any log or file. Any case. */
    val secretHeaders: Set<String> = setOf("authorization", "x-api-key"),
    /** What each route does; every request takes [DEFAULT_ROUTE] until routing arrives. */
    val routes: Map<String, Mode> = mapOf(DEFAULT_ROUTE to Mode.RECORD),
)

const val DEFAULT_ROUTE = "default"

/**
 * The headless proxy: one Ktor server, loopback only, relaying every request to the configured
 * upstream.
 */
class ProxyServer(config: ProxyConfig, interceptors: List<Interceptor> = emptyList()) :
    AutoCloseable {
    // Header names compare case-insensitively; normalize the configured secrets once. Named apart
    // from the parameter: in the initializers below, the parameter would shadow a same-named
    // property.
    private val applied =
        config.copy(secretHeaders = config.secretHeaders.map(String::lowercase).toSet())

    init {
        require(DEFAULT_ROUTE in config.routes) {
            "route '$DEFAULT_ROUTE' is not configured; every request takes it until routing arrives"
        }
    }

    private val upstream =
        HttpClient(ClientCIO) {
            // Streams outlive the engine's 15 s default; 0 disables the per-request timeout.
            engine { requestTimeout = 0 }
        }

    private val server: EmbeddedServer<*, *> =
        embeddedServer(Netty, port = applied.port, host = "127.0.0.1") {
                relayModule(applied, upstream, interceptors)
            }
            .start(wait = false)

    /** Resolved once: port 0 is only known after start, and a getter must never block. */
    val url: String = runBlocking {
        "http://127.0.0.1:${server.engine.resolvedConnectors().first().port}"
    }

    override fun close() {
        server.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        upstream.close()
    }
}

fun Application.relayModule(
    config: ProxyConfig,
    upstream: HttpClient,
    interceptors: List<Interceptor>,
) {
    install(CallLogging) {
        disableDefaultColors()
    } // method, path, status, duration; never headers or bodies
    routing {
        route("{...}") {
            handle {
                when {
                    // Claude Code's reachability probe; answered here, never relayed.
                    call.request.httpMethod == HttpMethod.Head &&
                        call.request.path() == "/api/hello" -> call.respond(HttpStatusCode.OK)
                    // Codex tries a WebSocket upgrade first and falls back to HTTP on a clean
                    // refusal.
                    call.request.header(HttpHeaders.Upgrade) != null ->
                        call.respondText(
                            "Peashoot speaks plain HTTP; upgrade refused",
                            status = HttpStatusCode.UpgradeRequired,
                        )
                    else -> relay(call, config, upstream, interceptors)
                }
            }
        }
    }
}
