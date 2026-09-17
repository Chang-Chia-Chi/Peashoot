package dev.peashoot.proxy

import dev.peashoot.core.Mode
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The JSON the control API answers with, and the route body it accepts. */
internal suspend fun ApplicationCall.json(body: JsonObject) =
    respondText(body.toString(), ContentType.Application.Json)

/**
 * RFC 9457's shape for every control API error. `about:blank` says the status is the whole story,
 * and [detail] is what to fix.
 */
internal suspend fun ApplicationCall.problem(status: HttpStatusCode, detail: String) =
    respondText(
        buildJsonObject {
            put("type", "about:blank")
            put("title", status.description)
            put("detail", detail)
            put("status", status.value)
        }
            .toString(),
        ContentType.Application.ProblemJson,
        status,
    )

internal fun routesJson(routes: Map<String, Route>): JsonObject = buildJsonObject {
    routes.forEach { (name, route) -> put(name, route.toJson()) }
}

internal fun Route.toJson(): JsonObject = buildJsonObject {
    put("mode", mode.name.lowercase())
    put("strict", strict)
    put("cassette", cassette)
}

/** A `PUT /routes/{name}` body; anything that does not say what a route is fails naming why. */
internal fun JsonElement.toRoute(): Route {
    val body = requireNotNull(this as? JsonObject) { "a route is a JSON object" }
    val name = body["mode"].string("mode")
    val mode =
        requireNotNull(Mode.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }) {
            "mode must be record, replay, or passthrough, not $name"
        }
    val strict =
        body["strict"]?.let {
            requireNotNull((it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.booleanOrNull) {
                "strict must be true or false, not $it"
            }
        } ?: false
    val cassette = body["cassette"]?.takeUnless { it is JsonNull }?.string("cassette")
    require(cassette == null || CASSETTE_NAME.matches(cassette)) {
        "cassette must be a cassette name matching $CASSETTE_NAME, not $cassette"
    }
    return Route(mode, strict, cassette)
}

internal fun Recorded.summaryJson(): JsonObject = buildJsonObject { putSummary(this@summaryJson) }

/**
 * The summary plus what was asked and answered. ponytail: the body is read as UTF-8 text, which
 * every v1 surface sends. Upgrade: base64 for a body that is not, if a binary surface arrives.
 */
internal fun Recorded.detailJson(withFrames: Boolean): JsonObject = buildJsonObject {
    putSummary(this@detailJson)
    put("parentAgent", exchange.client.parentAgent)
    put("requestHeaders", exchange.request.headers.toJson())
    put("requestBody", exchange.request.body.decodeToString())
    put("responseHeaders", exchange.response?.headers?.toJson() ?: JsonNull)
    if (withFrames) put("frames", frames.toJson())
}

/**
 * What a row of the exchange list shows. The session, agent, and client are derived from the stored
 * request headers, as they were when the deriver first saw the request.
 */
private fun JsonObjectBuilder.putSummary(recorded: Recorded) {
    val exchange = recorded.exchange
    val client = exchange.client
    put("id", exchange.id)
    put("receivedAt", exchange.receivedAt.toString())
    put("route", exchange.route)
    put("mode", exchange.mode.name.lowercase())
    put("method", exchange.request.method)
    put("path", exchange.request.path)
    put("status", exchange.response?.status)
    put("fingerprint", exchange.fingerprint)
    put("session", client.session)
    put("agent", client.agent)
    put("client", client.type)
    put("cassette", recorded.cassette)
    put("clientDisconnected", exchange.clientDisconnected)
}

internal fun Session.toJson(): JsonObject = buildJsonObject {
    put("session", session)
    put("agent", agent)
    put("exchanges", exchanges)
    put("usage", usageJson(usage))
    put("costUsd", costUsd)
    put("lastSeen", lastSeen.toString())
}
