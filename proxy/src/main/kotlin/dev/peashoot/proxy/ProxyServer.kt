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
import java.net.InetAddress
import java.net.UnknownHostException
import kotlinx.coroutines.runBlocking

/**
 * The headless proxy: one Ktor server, loopback only, relaying every request to the configured
 * upstream, and serving the control API under [CONTROL_PREFIX] when given one.
 */
class ProxyServer(
    private val config: ProxyConfig,
    interceptors: List<Interceptor> = emptyList(),
    control: ControlApi? = null,
) : AutoCloseable {
    init {
        require(DEFAULT_ROUTE in config.routes) {
            "route '$DEFAULT_ROUTE' is not configured; every request takes it until routing arrives"
        }
        // The loader refuses it too; this holds for a config built in code.
        notLoopback(config.host)?.let { throw IllegalArgumentException(it) }
    }

    private val upstream =
        HttpClient(ClientCIO) {
            // Streams outlive the engine's 15 s default; 0 disables the per-request timeout.
            engine { requestTimeout = 0 }
        }

    private val server: EmbeddedServer<*, *> =
        embeddedServer(Netty, port = config.port, host = config.host) {
                relayModule(config, upstream, interceptors, control)
            }
            .start(wait = false)

    /** Resolved once: port 0 is only known after start, and a getter must never block. */
    val url: String = runBlocking {
        val host = if (':' in config.host) "[${config.host}]" else config.host
        "http://$host:${server.engine.resolvedConnectors().first().port}"
    }

    override fun close() {
        server.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        upstream.close()
    }
}

/**
 * Why [host] cannot be bound, or null when every address it names is loopback. A name that resolves
 * to nothing is refused too. The control API reads and steers every exchange, and v1 has no
 * transport security to offer anyone else.
 */
internal fun notLoopback(host: String): String? {
    val loopback =
        try {
            InetAddress.getAllByName(host).all { it.isLoopbackAddress }
        } catch (_: UnknownHostException) {
            false
        }
    return if (loopback) null
    else
        "host $host is not a loopback address; v1 serves the proxy and its control API to this " +
            "machine only"
}

fun Application.relayModule(
    config: ProxyConfig,
    upstream: HttpClient,
    interceptors: List<Interceptor>,
    control: ControlApi? = null,
) {
    install(CallLogging) {
        disableDefaultColors()
    } // method, path, status, duration; never headers or bodies
    // A request target that does not start with `/` (`@evil.com/v1/messages`) would concatenate in
    // the relay into a URL whose host is evil.com, secret headers and all. Refused before routing,
    // so no handler, present or future, sees one.
    intercept(ApplicationCallPipeline.Plugins) {
        if (!call.request.uri.startsWith("/")) {
            call.respondText(
                "request target must start with /",
                status = HttpStatusCode.BadRequest,
            )
            finish()
        }
    }
    // The application is the scope every exchange's stream runs in, so a client that leaves does
    // not take its stream with it, and server stop ends them all.
    val relay = Relay(config, upstream, interceptors, this, control?.routes)
    routing {
        // Claude Code's reachability probe; answered here, never relayed.
        head("/api/hello") { call.respond(HttpStatusCode.OK) }
        // A constant segment outranks the tailcard below, so nothing under the prefix, known or
        // not, ever reaches the relay, the recorder, or the deriver.
        route(CONTROL_PREFIX) { controlRoutes(control, config.pingInterval) }
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
                    else -> relay.handle(call)
                }
            }
        }
    }
}
