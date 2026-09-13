package dev.peashoot.proxy

import dev.peashoot.core.Interceptor
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.header
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.head
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking

/**
 * The headless proxy: one Ktor server, loopback only, relaying every request to the configured
 * upstream.
 */
class ProxyServer(
    private val config: ProxyConfig,
    interceptors: List<Interceptor> = emptyList(),
) : AutoCloseable {
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
        embeddedServer(Netty, port = config.port, host = "127.0.0.1") {
                relayModule(config, upstream, interceptors)
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
    // A request-target that does not start with `/` (e.g. `@evil.com/v1/messages`, which would
    // otherwise concatenate into a URL whose host is evil.com, secret headers and all) never
    // reaches routing: it fails to match "{...}" and would 404 rather than 400. Refused here,
    // before routing runs, so the secret headers never leave this process for the wrong host.
    intercept(ApplicationCallPipeline.Plugins) {
        if (!call.request.uri.startsWith("/")) {
            call.respondText(
                "request target must start with /",
                status = HttpStatusCode.BadRequest,
            )
            finish()
        }
    }
    routing {
        // Claude Code's reachability probe; answered here, never relayed.
        head("/api/hello") { call.respond(HttpStatusCode.OK) }
        route("{...}") {
            handle {
                when {
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
