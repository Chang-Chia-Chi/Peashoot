package dev.peashoot.proxy

import dev.peashoot.core.RULES_FILE
import dev.peashoot.core.Rules
import io.ktor.http.HttpMethod
import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import java.nio.file.Files
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.peashoot.proxy")

/**
 * `GET /rules` and `PUT /rules`, a whole replacement validated by the code that reads the file at
 * start, so a pointer, a pattern, a replacement group, or a key the file could not hold is refused
 * here in the same words. The file is written before the live rule set changes, under the lock
 * every writing endpoint takes, so what a restart would read and what the next request is
 * fingerprinted under cannot come apart.
 */
internal fun Route.rules(api: ControlApi) {
    endpoint(HttpMethod.Get, "rules") { call.json(api.live.current.rules.toJson()) }
    endpoint(HttpMethod.Put, "rules") {
        val rules = refusing { Rules.parse(call.receiveText()) }
        api.writing.withLock {
            Files.writeString(api.home.resolve(RULES_FILE), rules.toJsonText())
            api.live.current = api.live.current.copy(rules = rules)
        }
        log.info("the rule set is now {}", rules)
        call.json(rules.toJson())
    }
    rulesTest(api)
}

/**
 * `POST /rules/test`: what a candidate rule set would do to the last `lastN` live recordings,
 * saving nothing. An imported cassette's rows are left out, as they are left out of an export: they
 * carry the fingerprint the machine that recorded them computed, under its rules, over a body
 * redaction may since have changed, so re-fingerprinting them here would invent differences.
 */
private fun Route.rulesTest(api: ControlApi) =
    endpoint(HttpMethod.Post, "rules/test") {
        val body = call.bodyObject()
        val candidate = refusing {
            Rules.parse(body["rules"]?.toString() ?: badRequest("rules is required"))
        }
        val lastN = bounded("lastN", body["lastN"]?.toString())
        call.json(candidate.differences(api.store.list(lastN, ExchangeQuery(live = true))))
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
