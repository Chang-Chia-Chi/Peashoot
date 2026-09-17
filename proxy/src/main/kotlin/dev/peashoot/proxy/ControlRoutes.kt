package dev.peashoot.proxy

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
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
import java.io.IOException
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
import org.jdbi.v3.core.JdbiException
import org.slf4j.LoggerFactory

internal const val DEFAULT_EXCHANGES_LIMIT = 50
internal const val MAX_EXCHANGES_LIMIT = 500

private val log = LoggerFactory.getLogger("dev.peashoot.proxy")

/** The jar's manifest version, or `dev` for a build that has none. */
private val VERSION = ControlApi::class.java.`package`?.implementationVersion ?: "dev"

/**
 * Everything under [CONTROL_PREFIX]: the v1 endpoints when there is a control API, and a 404
 * problem for any other path or method, so a request under the prefix is answered here or nowhere.
 */
internal fun Route.controlRoutes(api: ControlApi?, pingInterval: Duration) {
    if (api != null) {
        route("v1") {
            health(api)
            events(api, pingInterval)
            exchanges(api)
            sessions(api)
            routeTable(api)
        }
    }
    route("{...}") {
        handle {
            call.problem(HttpStatusCode.NotFound, "no control endpoint ${call.request.path()}")
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
 * A handler behind the token. A body it cannot use is a 400 problem and a store it cannot read is a
 * 500, both only while nothing of the response is out; after that the failure is the engine's.
 */
private fun Route.endpoint(
    api: ControlApi,
    method: HttpMethod,
    path: String,
    body: suspend RoutingContext.() -> Unit,
) {
    route(path, method) {
        handle {
            if (!api.authorized(call.request.header(HttpHeaders.Authorization))) {
                call.problem(
                    HttpStatusCode.Unauthorized,
                    "send Authorization: Bearer <the token in the data directory's token file>",
                )
                return@handle
            }
            val failure =
                try {
                    body()
                    null
                } catch (e: IllegalArgumentException) {
                    e
                } catch (e: JdbiException) {
                    e
                } catch (e: IOException) {
                    e
                }
            when {
                failure == null -> Unit
                call.response.isCommitted -> throw failure
                failure is IllegalArgumentException ->
                    call.problem(HttpStatusCode.BadRequest, failure.message ?: "bad request")
                else -> {
                    log.warn("control call {} failed: {}", call.request.path(), failure.toString())
                    call.problem(HttpStatusCode.InternalServerError, failure.toString())
                }
            }
        }
    }
}

/**
 * `GET /events`: the lines after `since`, or after the standard `Last-Event-ID` a reconnecting
 * EventSource sends, then every new one; with neither, only new ones. Each carries its table id as
 * its SSE id.
 */
private fun Route.events(api: ControlApi, pingInterval: Duration) =
    endpoint(api, HttpMethod.Get, "events") {
        val raw = call.request.queryParameters["since"] ?: call.request.header("Last-Event-ID")
        val since = raw?.let {
            requireNotNull(it.toLongOrNull()?.takeIf { id -> id >= 0 }) {
                "since must be an event id, not $it"
            }
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
        store.events(since).forEach { (id, event) ->
            send("id: $id\ndata: $event\n\n")
            last = id
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
                send("id: ${line.first}\ndata: ${line.second}\n\n")
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
 * `GET /exchanges`, newest first, a page at a time: `cursor` is the last id of the page before.
 * `GET /exchanges/{id}`, with its frames only when asked, since they are most of its size.
 */
private fun Route.exchanges(api: ControlApi) {
    endpoint(api, HttpMethod.Get, "exchanges") {
        val params = call.request.queryParameters
        val limit =
            params["limit"]?.let { raw ->
                requireNotNull(raw.toIntOrNull()?.takeIf { it in 1..MAX_EXCHANGES_LIMIT }) {
                    "limit must be 1 to $MAX_EXCHANGES_LIMIT, not $raw"
                }
            } ?: DEFAULT_EXCHANGES_LIMIT
        val query =
            ExchangeQuery(params["session"], params["client"], params["cursor"], frames = false)
        val page = api.store.list(limit, query = query)
        call.json(
            buildJsonObject {
                put("exchanges", JsonArray(page.map { it.summaryJson() }))
                put("nextCursor", page.lastOrNull()?.exchange?.id?.takeIf { page.size == limit })
            }
        )
    }
    endpoint(api, HttpMethod.Get, "exchanges/{id}") {
        val id = call.parameters["id"].orEmpty()
        when (val recorded = api.store.get(id)) {
            null -> call.problem(HttpStatusCode.NotFound, "no exchange $id")
            else -> {
                val frames = call.request.queryParameters["frames"] == "true"
                call.json(recorded.detailJson(frames))
            }
        }
    }
}

/** `GET /sessions`: the store's session view, as it stands. */
private fun Route.sessions(api: ControlApi) =
    endpoint(api, HttpMethod.Get, "sessions") {
        val sessions = api.store.sessions().map { it.toJson() }
        call.json(buildJsonObject { put("sessions", JsonArray(sessions)) })
    }

/**
 * `GET /routes`, and `PUT /routes/{name}`, which takes effect on the next request and lasts until
 * the proxy stops: a restart reads the config file and the environment again.
 */
private fun Route.routeTable(api: ControlApi) {
    endpoint(api, HttpMethod.Get, "routes") { call.json(routesJson(api.routes.all)) }
    endpoint(api, HttpMethod.Put, "routes/{name}") {
        val route = Json.parseToJsonElement(call.receiveText()).toRoute()
        api.routes.put(call.parameters["name"].orEmpty(), route)
        log.info("route {} is now {}", call.parameters["name"], route)
        call.json(route.toJson())
    }
}
