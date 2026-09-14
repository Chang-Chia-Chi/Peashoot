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
import kotlinx.serialization.json.put
import org.jdbi.v3.core.JdbiException
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.peashoot.proxy")

const val EVENTS_FILE = "events.jsonl"

/**
 * The event line: one `exchange.started` when the request is heard and one `exchange.completed`
 * when the response ends, to the events file and the event table. Never throws: an event that
 * cannot be written is one WARN line, never a failed request.
 */
class Deriver(
    private val store: Store,
    private val eventsFile: Path,
    private val prices: Map<String, Price> = DEFAULT_PRICES,
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

    /** One events file per deriver, so one lock keeps concurrent exchanges from interleaving. */
    private val lock = Mutex()

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

    /** An exchange whose response never started has nothing to report but its own ending. */
    override suspend fun onComplete(exchange: Exchange, outcome: Outcome) =
        emit(exchange, completedEvent(exchange, outcome, turns.remove(exchange.id) ?: Turn()))

    private fun startedEvent(exchange: Exchange): JsonObject = buildJsonObject {
        put("ts", exchange.receivedAt.toString())
        put("event", "exchange.started")
        putExchange(exchange, Messages.model(exchange.request.json))
    }

    /**
     * `model` is the model the response named when it named one, which resolves an alias the
     * request asked for, and the request's otherwise. `replayHit` and `resumed` are not emitted:
     * nothing sets them until replay (#12) and resume (#26) exist, and a field that is always false
     * says less than an absent one.
     */
    private fun completedEvent(exchange: Exchange, outcome: Outcome, turn: Turn): JsonObject {
        val reader = turn.reader
        val now = Instant.now()
        val model = reader.model ?: Messages.model(exchange.request.json)
        // Subscription traffic is billed by the plan, not by the token: it has no cost here.
        val cost =
            if (Messages.isOAuth(exchange.request.headers)) null
            else costUsd(model, reader.usage, prices)
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
            put("clientDisconnected", exchange.clientDisconnected)
            put("rateLimit", rateLimitJson(exchange.response?.headers))
        }
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
        put("mode", exchange.mode.name.lowercase())
        put("toolResults", toolResultsJson(exchange))
    }

    /**
     * Both sinks are tried, whichever fails: the file is what a tail watches and the table is what
     * the farm queries, and neither is worth the other.
     */
    private suspend fun emit(exchange: Exchange, event: JsonObject) =
        withContext(Dispatchers.IO + NonCancellable) {
            try {
                lock.withLock { Files.writeString(eventsFile, "$event\n", CREATE, APPEND) }
            } catch (e: IOException) {
                warn(event, exchange, e)
            }
            try {
                store.putEvent(event)
            } catch (e: IOException) {
                warn(event, exchange, e)
            } catch (e: JdbiException) {
                warn(event, exchange, e)
            }
        }

    private fun warn(event: JsonObject, exchange: Exchange, e: Exception) =
        log.warn("{} for exchange {} not written: {}", event["event"], exchange.id, e.toString())
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

private fun usageJson(usage: Usage?): JsonElement =
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
