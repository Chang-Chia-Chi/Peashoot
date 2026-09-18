package dev.peashoot.proxy

import dev.peashoot.core.RULES_FILE
import dev.peashoot.core.Rules
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import java.nio.file.Files
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import org.tomlj.TomlInvalidTypeException

private val log = LoggerFactory.getLogger("dev.peashoot.proxy")

/**
 * A body, a file, or a rule the caller sent that cannot be used, as a 400 naming it. The writing
 * endpoints all validate by running the code that reads the same thing at start, so what they must
 * turn into a problem object is what that code throws: a failed `check` or `require`, and tomlj's
 * own refusal of a value of the wrong type. Anything else is still ours and still a 500.
 */
internal inline fun <T> refusing(block: () -> T): T = runCatching(block).getOrElse { refuse(it) }

internal fun refuse(failure: Throwable): Nothing =
    when (failure) {
        // A cancelled call is nobody's mistake, and CancellationException is an
        // IllegalStateException.
        is CancellationException -> throw failure
        is IllegalStateException,
        is IllegalArgumentException,
        is TomlInvalidTypeException -> badRequest(failure.message ?: "the request cannot be used")
        else -> throw failure
    }

/** The request body as the JSON object every writing endpoint but `PUT /rules` takes. */
internal suspend fun ApplicationCall.bodyObject(): JsonObject =
    refusing { Json.parseToJsonElement(receiveText()) } as? JsonObject
        ?: badRequest("the body must be a JSON object")

/**
 * `GET /rules` and `PUT /rules`, a whole replacement validated by the code that reads the file at
 * start, so a pointer, a pattern, a replacement group, or a key the file could not hold is refused
 * here in the same words. The file is written first and the live rule set second: what a restart
 * would read and what the next request is fingerprinted under are the same rules.
 */
internal fun Route.rules(api: ControlApi) {
    endpoint(HttpMethod.Get, "rules") { call.json(api.live.current.rules.toJson()) }
    endpoint(HttpMethod.Put, "rules") {
        val rules = refusing { Rules.parse(call.receiveText()) }
        Files.writeString(api.home.resolve(RULES_FILE), rules.toJsonText())
        api.live.current = api.live.current.copy(rules = rules)
        log.info("the rule set is now {}", rules)
        call.json(rules.toJson())
    }
    rulesTest(api)
}

/**
 * `POST /rules/test`: what a candidate rule set would do to the last `lastN` recordings, saving
 * nothing. The answer is the two ways a rule change goes wrong, by exchange id, which is what `GET
 * /exchanges/{id}` takes.
 */
private fun Route.rulesTest(api: ControlApi) =
    endpoint(HttpMethod.Post, "rules/test") {
        val body = call.bodyObject()
        val candidate = refusing {
            Rules.parse(body["rules"]?.toString() ?: badRequest("rules is required"))
        }
        val lastN =
            body["lastN"]?.let { asked ->
                (asked as? JsonPrimitive)
                    ?.takeUnless { it.isString }
                    ?.intOrNull
                    ?.takeIf { it in 1..MAX_EXCHANGES_LIMIT }
                    ?: badRequest("lastN must be 1 to $MAX_EXCHANGES_LIMIT, not $asked")
            } ?: DEFAULT_EXCHANGES_LIMIT
        val recorded = api.store.list(lastN, ExchangeQuery(frames = false))
        call.json(candidate.differences(recorded))
    }

/**
 * What this rule set would change about [recorded], against the fingerprints they were stored
 * under: a collision is exchanges that would share a fingerprint and do not now, which is a replay
 * answering the wrong request, and a split is exchanges that share one now and would not, which is
 * a replay that stops hitting. A rule change is worth making when both lists are empty, or when
 * every group in them is one you meant.
 */
private fun Rules.differences(recorded: List<Recorded>): JsonObject {
    val now = recorded.associate { it.exchange.id to it.exchange.fingerprint }
    val under = recorded.associate { (exchange) ->
        exchange.id to
            fingerprint(
                exchange.request.method,
                exchange.request.path,
                exchange.request.headers,
                exchange.request.json,
                exchange.request.body,
            )
    }
    val collisions =
        under.entries.groupBy({ it.value }, { it.key }).filterValues { ids ->
            ids.mapTo(mutableSetOf(), now::getValue).size > 1
        }
    val splits =
        now.entries.groupBy({ it.value }, { it.key }).filterValues { ids ->
            ids.mapTo(mutableSetOf(), under::getValue).size > 1
        }
    return buildJsonObject {
        put("tested", recorded.size)
        put(
            "collisions",
            JsonArray(
                collisions.map { (fingerprint, ids) ->
                    buildJsonObject {
                        put("fingerprint", fingerprint)
                        put("exchangeIds", JsonArray(ids.map(::JsonPrimitive)))
                    }
                }
            ),
        )
        put(
            "splits",
            JsonArray(
                splits.map { (fingerprint, ids) ->
                    buildJsonObject {
                        put("fingerprint", fingerprint)
                        put(
                            "groups",
                            JsonArray(
                                ids.groupBy(under::getValue).values.map { group ->
                                    JsonArray(group.map(::JsonPrimitive))
                                }
                            ),
                        )
                    }
                }
            ),
        )
    }
}
