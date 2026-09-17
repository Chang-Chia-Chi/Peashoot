package dev.peashoot.proxy

import dev.peashoot.core.DEFAULT_PRICES
import dev.peashoot.core.Exchange
import dev.peashoot.core.Frame
import dev.peashoot.core.FrameSource
import dev.peashoot.core.Interceptor
import dev.peashoot.core.Messages
import dev.peashoot.core.Outcome
import dev.peashoot.core.Price
import dev.peashoot.core.ToolCall
import dev.peashoot.core.Usage
import dev.peashoot.core.costUsd
import io.ktor.http.Headers
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jdbi.v3.core.JdbiException
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.peashoot.proxy")

const val EVENTS_FILE = "events.jsonl"

/**
 * The event line: one `exchange.started` when the request is heard, one `exchange.client_gone` when
 * the client leaves mid-stream, and one `exchange.completed` when the response ends, to the events
 * file, the event table, and the control API's live feed, and the file tools of every turn to the
 * Gource log when that flag is on. Never throws: an event that cannot be written is one WARN line,
 * never a failed request.
 */
class Deriver(
    private val store: Store,
    private val eventsFile: Path,
    private val prices: Map<String, Price> = DEFAULT_PRICES,
    /** Only when `gource.enabled` is on: the file tools of every turn, for Gource to animate. */
    private val gource: GourceLog? = null,
    /** The control API's live feed: every line the table stored, under the id it got there. */
    private val feed: EventFeed? = null,
) : Interceptor {
    /**
     * What the frames have said so far, for an exchange whose response began. Only that exchange's
     * drive coroutine touches its turn, and only completion removes it.
     */
    private class Turn {
        val reader = Messages.Reader()
        var firstByteAt: Instant? = null
    }

    private val turns = ConcurrentHashMap<String, Turn>()

    /**
     * One events file per deriver, so one lock keeps concurrent exchanges from interleaving. It
     * holds the table insert and the feed too, so the feed hears ids in the order they were given:
     * a subscriber that skips what it already sent then never skips what it has not.
     */
    private val lock = Mutex()

    /** Model ids seen without a price: the ledger's silence is explained once, not every turn. */
    private val unpriced = ConcurrentHashMap.newKeySet<String>()

    override suspend fun onRequest(exchange: Exchange): FrameSource? {
        emit(exchange, startedEvent(exchange))
        // The deriver only watches; it never answers.
        return null
    }

    override fun onFrames(exchange: Exchange, frames: Flow<Frame>): Flow<Frame> {
        val turn = Turn()
        // Registered when collection starts, so a response that never began leaves no entry: the
        // chain promises a completion only for an exchange the proxy answered.
        return frames
            .onStart { turns[exchange.id] = turn }
            .onEach { frame ->
                if (turn.firstByteAt == null) turn.firstByteAt = Instant.now()
                turn.reader.read(frame)
            }
    }

    /** The departure is its own line: the completed one still follows, once the stream ends. */
    override suspend fun onClientGone(exchange: Exchange) =
        emit(
            exchange,
            buildJsonObject {
                put("ts", Instant.now().toString())
                put("event", "exchange.client_gone")
                put("exchangeId", exchange.id)
                put("session", exchange.client.session)
                put("bytesSoFar", exchange.clientBytes)
            },
        )

    /** An exchange whose response never started has nothing to report but its own ending. */
    override suspend fun onComplete(exchange: Exchange, outcome: Outcome) {
        val turn = turns.remove(exchange.id) ?: Turn()
        emit(exchange, completedEvent(exchange, outcome, turn))
        gource?.append(exchange.client.session, turn.reader.tools)
    }

    private fun startedEvent(exchange: Exchange): JsonObject = buildJsonObject {
        put("ts", exchange.receivedAt.toString())
        put("event", "exchange.started")
        putExchange(exchange, Messages.model(exchange.request.json))
    }

    /**
     * `model` is the model the response named when it named one, which resolves an alias the
     * request asked for, and the request's otherwise. A replay hit was billed nothing, so it costs
     * 0 whatever usage the recording reports. `resumed` is not emitted: nothing sets it until
     * resume (#26) exists, and a field that is always false says less than an absent one.
     */
    private fun completedEvent(exchange: Exchange, outcome: Outcome, turn: Turn): JsonObject {
        val reader = turn.reader
        val now = Instant.now()
        val model = reader.model ?: Messages.model(exchange.request.json)
        // Subscription traffic is billed by the plan, not by the token: it has no cost here.
        val cost =
            when {
                exchange.replayHit -> 0.0
                Messages.isOAuth(exchange.request.headers) -> null
                else -> priced(model, reader.usage)
            }
        return buildJsonObject {
            put("ts", now.toString())
            put("event", "exchange.completed")
            putExchange(exchange, model)
            put("tools", toolsJson(reader.tools))
            put("usage", usageJson(reader.usage))
            put("costUsd", cost)
            put("stopReason", reader.stopReason)
            put("status", outcome.status)
            put("firstByteMs", turn.firstByteAt?.let { millisSince(exchange.receivedAt, it) })
            put("latencyMs", millisSince(exchange.receivedAt, now))
            put("replayHit", exchange.replayHit)
            put("clientDisconnected", exchange.clientDisconnected)
            put("rateLimit", rateLimitJson(exchange.response?.headers))
        }
    }

    /**
     * A null cost with a model and usage in hand means the table does not know the model: a new
     * family rolled out, or a local one. Said once per model, with the config key that fixes it.
     */
    private fun priced(model: String?, usage: Usage?): Double? {
        if (model == null || usage == null) return null
        val cost = costUsd(model, usage, prices)
        if (cost == null && unpriced.add(model)) {
            log.warn(
                "no price for {}: costUsd is null; add [pricing.\"{}\"] to {}",
                model,
                model,
                CONFIG_FILE,
            )
        }
        return cost
    }

    /** The fields both lines carry, in the order both lines carry them. */
    private fun JsonObjectBuilder.putExchange(exchange: Exchange, model: String?) {
        val client = exchange.client
        put("exchangeId", exchange.id)
        put("session", client.session)
        put("agent", client.agent)
        put("parentAgent", client.parentAgent)
        put("client", client.type)
        put("surface", Messages.SURFACE)
        put("model", model)
        put("route", exchange.route)
        put("mode", exchange.mode.spelling)
        put("toolResults", toolResultsJson(exchange))
    }

    /**
     * Both sinks are tried, whichever fails: the file is what a tail watches and the table is what
     * the farm queries, and neither is worth the other. The feed carries only what the table
     * stored, since a line without an id could never be backfilled. Publishing never waits on a
     * subscriber.
     */
    private suspend fun emit(exchange: Exchange, event: JsonObject): Unit =
        withContext(Dispatchers.IO + NonCancellable) {
            lock.withLock {
                try {
                    Files.writeString(eventsFile, "$event\n", CREATE, APPEND)
                } catch (e: IOException) {
                    warn(event, exchange, e)
                }
                try {
                    val id = store.putEvent(event)
                    feed?.publish(id, event)
                } catch (e: IOException) {
                    warn(event, exchange, e)
                } catch (e: JdbiException) {
                    warn(event, exchange, e)
                }
            }
        }

    private fun warn(event: JsonObject, exchange: Exchange, e: Exception) =
        log.warn(
            "{} for exchange {} not written: {}",
            event.getValue("event").jsonPrimitive.content,
            exchange.id,
            e.toString(),
        )
}

private fun millisSince(from: Instant, to: Instant): Long = Duration.between(from, to).toMillis()

private fun toolResultsJson(exchange: Exchange): JsonArray = buildJsonArray {
    Messages.toolResults(exchange.request.json).forEach { result ->
        add(
            buildJsonObject {
                put("name", result.name)
                put("bytes", result.bytes)
            }
        )
    }
}

/** A tool names only what it had: a file tool has no command, a shell tool has no path. */
private fun toolsJson(tools: List<ToolCall>): JsonArray = buildJsonArray {
    tools.forEach { call ->
        add(
            buildJsonObject {
                put("name", call.name)
                call.path?.let { put("path", it) }
                call.command?.let { put("command", it) }
            }
        )
    }
}

internal fun usageJson(usage: Usage?): JsonElement =
    if (usage == null) JsonNull
    else
        buildJsonObject {
            put("input", usage.input)
            put("output", usage.output)
            put("cacheRead", usage.cacheRead)
            put("cacheWrite", usage.cacheWrite)
        }

private fun rateLimitJson(headers: Headers?): JsonElement {
    val limit = headers?.let(Messages::rateLimit) ?: return JsonNull
    return buildJsonObject {
        put("remainingTokens", limit.remainingTokens)
        put("remainingRequests", limit.remainingRequests)
        put("resetAt", limit.resetAt)
    }
}
