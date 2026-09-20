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
import java.net.Inet6Address
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
    }

    /** Resolved and checked once, then bound as it is, so the check and the bind cannot differ. */
    private val address = loopbackAddress(config.host)

    /** The control API's when there is one, so `PUT /config` and the relay share one config. */
    private val live = control?.live ?: LiveConfig(config)

    private val upstream =
        HttpClient(ClientCIO) {
            // Streams outlive the engine's 15 s default; 0 disables the per-request timeout.
            engine { requestTimeout = 0 }
        }

    private val server: EmbeddedServer<*, *> =
        embeddedServer(Netty, port = config.port, host = address.hostAddress) {
                val routes = control?.routes ?: RouteTable(config.routes)
                relayModule(live, upstream, interceptors, routes, control)
            }
            .start(wait = false)

    /** Resolved once: port 0 is only known after start, and a getter must never block. */
    val url: String = runBlocking {
        val host = address.hostAddress.let { if (address is Inet6Address) "[$it]" else it }
        "http://$host:${server.engine.resolvedConnectors().first().port}"
    }

    override fun close() {
        server.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        upstream.close()
    }
}

/**
 * The address to bind for [host], which may be a name, an IP literal, or a bracketed IPv6 one.
 * Every address the name resolves to must be loopback, and a name that resolves to nothing is
 * refused too: the control API reads and steers every exchange, and v1 has no transport security to
 * offer anyone else.
 */
internal fun loopbackAddress(host: String): InetAddress {
    val addresses =
        try {
            InetAddress.getAllByName(host.removeSurrounding("[", "]")).toList()
        } catch (_: UnknownHostException) {
            emptyList()
        }
    check(addresses.isNotEmpty() && addresses.all { it.isLoopbackAddress }) {
        "host $host is not a loopback address; v1 serves the proxy and its control API to this " +
            "machine only"
    }
    return addresses.first()
}

/** The one upgrade token the proxy refuses, because it is the one a client falls back from. */
private const val WEBSOCKET = "websocket"

/**
 * Whether the client offered a WebSocket upgrade. `Upgrade` is a comma-separated token list, so the
 * tokens are compared one at a time and each must equal the name: a header naming only some other
 * protocol must be answered as though it were not there, and so must one whose token merely
 * contains the word, which a substring test would refuse unrecorded.
 */
private fun String?.offersWebSocket(): Boolean =
    this?.split(',')?.any { it.trim().equals(WEBSOCKET, ignoreCase = true) } == true

fun Application.relayModule(
    /** Read once per request, so a config `PUT` reaches the next one. */
    live: LiveConfig,
    upstream: HttpClient,
    interceptors: List<Interceptor>,
    /** Read once per request; the control API's own when there is one. */
    routes: RouteTable,
    control: ControlApi? = null,
) {
    val config = live.current
    install(CallLogging) {
        disableDefaultColors()
    } // method, path, status, duration; never headers or bodies
    // A request target that does not start with `/` (`@evil.com/v1/messages`) would concatenate in
    // the relay into a URL whose host is evil.com, secret headers and all. Refused before routing,
    // so no handler, present or future, sees one.
    // Likewise, anything that names the control prefix in any spelling is the control API's to
    // answer, token first, and never the relay's: it would carry the control token upstream.
    intercept(ApplicationCallPipeline.Plugins) {
        if (!call.request.uri.startsWith("/")) {
            call.respondText(
                "request target must start with /",
                status = HttpStatusCode.BadRequest,
            )
            finish()
        } else if (guardControl(call, control)) {
            finish()
        }
    }
    // The application is the scope every exchange's stream runs in, so a client that leaves does
    // not take its stream with it, and server stop ends them all.
    val relay = Relay(live, upstream, interceptors, this, routes)
    routing {
        // Claude Code's reachability probe; answered here, never relayed.
        head("/api/hello") { call.respond(HttpStatusCode.OK) }
        // Only a canonical control path gets here; the guard answered every other spelling.
        route(CONTROL_PREFIX) { controlRoutes(control, config.pingInterval) }
        route("{...}") {
            handle {
                when {
                    // Codex opens every session with a WebSocket upgrade and falls back to HTTP
                    // on a clean refusal; 426 is the only status its source treats as one, and
                    // every other answer costs it five stream retries first
                    // (docs/research/codex-responses-transport.md).
                    //
                    // Only `websocket` is refused. A client offering some other upgrade — `h2c`
                    // from `curl --http2`, say — must be answered as though the header were not
                    // there (RFC 7540 section 3.2), so those relay normally; `upgrade` is
                    // hop-by-hop, so the request reaches the provider without it either way.
                    // Refusing every token would swallow such a request, unrecorded and unbilled.
                    // This is narrower than `docs/spec.md` line 79, which says every upgrade is
                    // refused; the As-built paragraph in `docs/design.md` records the difference.
                    call.request.header(HttpHeaders.Upgrade).offersWebSocket() ->
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
