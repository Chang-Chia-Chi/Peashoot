package dev.peashoot.proxy

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

internal const val DEFAULT_EXCHANGES_LIMIT = 50
internal const val MAX_EXCHANGES_LIMIT = 500

/** The one control path served without a token, and only under exactly this spelling. */
private const val HEALTH_PATH = "$CONTROL_PREFIX/v1/health"

private val log = LoggerFactory.getLogger("dev.peashoot.proxy")

/** The jar's manifest version, or `dev` for a build that has none. */
private val VERSION = ControlApi::class.java.`package`?.implementationVersion ?: "dev"

/**
 * Whether this request was the control API's and is already answered, which is what keeps the relay
 * from ever seeing one. A request whose path names [CONTROL_PREFIX] once decoded and case-folded
 * belongs here however it is spelled, because relaying it would carry the control token upstream;
 * only the canonical spelling goes on to the routes, and every other one is a 404. The token is
 * checked first, so an unknown path or a wrong method says no more to a caller without one than a
 * known path does.
 */
internal suspend fun guardControl(call: ApplicationCall, control: ControlApi?): Boolean {
    val path = call.request.path()
    val plain = runCatching { path.decodeURLPart() }.getOrDefault(path)
    val canonical = path == plain && (path == CONTROL_PREFIX || path.startsWith("$CONTROL_PREFIX/"))
    return when {
        CONTROL_PREFIX !in plain.lowercase() -> false
        canonical && path == HEALTH_PATH && call.request.httpMethod == HttpMethod.Get -> false
        control == null -> {
            call.problem(HttpStatusCode.NotFound, "this proxy serves no control API")
            true
        }
        !control.authorized(call.request.header(HttpHeaders.Authorization)) -> {
            call.problem(
                HttpStatusCode.Unauthorized,
                "send Authorization: Bearer <the token in the data directory's token file>",
            )
            true
        }
        canonical -> false
        else -> {
            call.problem(HttpStatusCode.NotFound, "no control endpoint at $path")
            true
        }
    }
}

/**
 * Everything under [CONTROL_PREFIX] that [guardControl] let through: the v1 endpoints, and a 404
 * problem for any other path or method.
 */
internal fun Route.controlRoutes(api: ControlApi?, pingInterval: Duration) {
    if (api != null) {
        route("v1") {
            health(api)
            events(api, pingInterval)
            exchanges(api)
            sessions(api)
            routeTable(api)
            rules(api)
            cassettes(api)
            configuration(api)
            shutdown(api)
        }
    }
    route("{...}") {
        handle {
            call.problem(HttpStatusCode.NotFound, "no control endpoint at ${call.request.path()}")
        }
    }
}

/** The one call without a token: whether the proxy is up, and what its routes do. */
private fun Route.health(api: ControlApi) {
    get("health") {
        call.json(
            buildJsonObject {
                put("status", "ok")
                put("version", VERSION)
                put("uptimeSeconds", api.started.elapsedNow().inWholeSeconds)
                put("routes", routesJson(api.routes.all))
            }
        )
    }
}

/**
 * A control handler's failures, as problem objects. Only what the caller sent can be a 400; a store
 * or a parse failure of ours is a 500 that says nothing but where to look, since its message is
 * about our internals. Once the response has begun, the failure is the engine's to end.
 */
internal fun Route.endpoint(
    method: HttpMethod,
    path: String,
    body: suspend RoutingContext.() -> Unit,
) {
    route(path, method) {
        handle {
            val failure = runCatching { body() }.exceptionOrNull()
            when {
                failure == null -> Unit
                failure !is Exception || failure is CancellationException -> throw failure
                call.response.isCommitted -> throw failure
                failure is BadRequestException ->
                    call.problem(
                        HttpStatusCode.BadRequest,
                        failure.message ?: "the request cannot be used",
                    )
                else -> {
                    log.error("control call {} failed", call.request.path(), failure)
                    call.problem(
                        HttpStatusCode.InternalServerError,
                        "the control API failed; the proxy log says how",
                    )
                }
            }
        }
    }
}

/**
 * `GET /events`: the lines after `Last-Event-ID`, which a reconnecting EventSource sends and which
 * wins over the `since` on the URL it reconnects to, then every new one; with neither, only new
 * ones. Each carries its table id as its SSE id.
 */
private fun Route.events(api: ControlApi, pingInterval: Duration) =
    endpoint(HttpMethod.Get, "events") {
        val raw = call.request.header("Last-Event-ID") ?: call.request.queryParameters["since"]
        val since = raw?.let {
            it.toLongOrNull()?.takeIf { id -> id >= 0 }
                ?: badRequest("since must be an event id, not $it")
        }
        val gone = call.clientGone()
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.respondBytesWriter(ContentType.Text.EventStream) {
            api.feed.subscribe { live -> feed(api.store, since, live, pingInterval, gone) }
        }
    }

/**
 * Subscribed before the backfill is read, so a line stored in between arrives twice, and skipping
 * ids already sent drops the copy: no gap and no duplicate, since the deriver publishes in id
 * order. The first comment tells the client it is subscribed. The engine discards writes to a
 * client that left, so the loop asks the channel rather than waiting for a write to fail, and waits
 * with a select for the reason the relay's writer does.
 */
private suspend fun ByteWriteChannel.feed(
    store: Store,
    since: Long?,
    live: ReceiveChannel<Pair<Long, JsonObject>>,
    pingInterval: Duration,
    gone: () -> Boolean,
) = coroutineScope {
    send(KEEP_ALIVE)
    var last = since ?: 0
    if (since != null) {
        store.events(since).forEach { line ->
            send(sseEvent(line.toPair()))
            last = line.key
        }
    }
    var open = true
    while (open && !gone()) {
        val silence = launch { delay(pingInterval) }
        val next = select {
            live.onReceiveCatching { it }
            silence.onJoin { null }
        }
        silence.cancel()
        val line = next?.getOrNull()
        when {
            next == null -> send(KEEP_ALIVE)
            // Cut off for falling behind, or the proxy is stopping: the client reconnects.
            line == null -> open = false
            line.first > last -> {
                send(sseEvent(line))
                last = line.first
            }
        }
    }
}

private suspend fun ByteWriteChannel.send(text: String) {
    writeStringUtf8(text)
    flush()
}

/**
 * `GET /exchanges`, newest first, a page at a time: `cursor` is the last id of the page before, and
 * one naming no exchange is refused rather than answered with nothing. `GET /exchanges/{id}`, with
 * its frames only when asked, since they are most of its size.
 */
private fun Route.exchanges(api: ControlApi) {
    endpoint(HttpMethod.Get, "exchanges") {
        val params = call.request.queryParameters
        val limit =
            params["limit"]?.let { raw ->
                raw.toIntOrNull()?.takeIf { it in 1..MAX_EXCHANGES_LIMIT }
                    ?: badRequest("limit must be 1 to $MAX_EXCHANGES_LIMIT, not $raw")
            } ?: DEFAULT_EXCHANGES_LIMIT
        val cursor =
            params["cursor"]?.also {
                if (api.store.get(it, frames = false) == null) badRequest("no exchange $it")
            }
        val query =
            ExchangeQuery(
                session = params["session"],
                client = params["client"],
                cursor = cursor,
                frames = false,
            )
        val page = api.store.list(limit, query)
        call.json(
            buildJsonObject {
                put("exchanges", JsonArray(page.map { it.summaryJson() }))
                put("nextCursor", page.lastOrNull()?.exchange?.id?.takeIf { page.size == limit })
            }
        )
    }
    endpoint(HttpMethod.Get, "exchanges/{id}") {
        val id = call.parameters["id"].orEmpty()
        val frames = call.request.queryParameters["frames"] == "true"
        when (val recorded = api.store.get(id, frames)) {
            null -> call.problem(HttpStatusCode.NotFound, "no exchange $id")
            else -> call.json(recorded.detailJson(frames))
        }
    }
}

/** `GET /sessions`: the store's session view, as it stands. */
private fun Route.sessions(api: ControlApi) =
    endpoint(HttpMethod.Get, "sessions") {
        val sessions = api.store.sessions().map { it.toJson() }
        call.json(buildJsonObject { put("sessions", JsonArray(sessions)) })
    }

/**
 * `GET /routes`, and `PUT /routes/{name}`, which takes effect on the next request and lasts until
 * the proxy stops: a restart reads the config file and the environment again. Only a route that
 * exists can be replaced, so an unknown name is a 404 rather than a route invented here.
 */
private fun Route.routeTable(api: ControlApi) {
    endpoint(HttpMethod.Get, "routes") { call.json(routesJson(api.routes.all)) }
    endpoint(HttpMethod.Put, "routes/{name}") {
        val name = call.parameters["name"].orEmpty()
        val route =
            try {
                Json.parseToJsonElement(call.receiveText()).toRoute()
            } catch (e: IllegalArgumentException) {
                badRequest(e.message ?: "the body does not say what a route is")
            }
        when {
            api.routes.put(name, route) -> {
                log.info("route {} is now {}", name, route)
                call.json(route.toJson())
            }
            else ->
                call.problem(
                    HttpStatusCode.NotFound,
                    "no route named '$name'; only '$DEFAULT_ROUTE' exists until routing arrives",
                )
        }
    }
}
