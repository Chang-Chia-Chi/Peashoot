package dev.peashoot.app

import dev.peashoot.core.TOKEN_FILE
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readLine
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Everything the control API serves lives under this, on the proxy's own port. */
private const val CONTROL_BASE = "/_peashoot/v1"

/** What a reconnecting feed resumes from; the proxy honours it over the `since` on the URL. */
private const val LAST_EVENT_ID = "Last-Event-ID"

/** Long enough that a restarting proxy is not hammered, short enough to feel immediate. */
private const val MIN_BACKOFF_MS = 250L
private const val MAX_BACKOFF_MS = 5_000L

/** What `GET /health` says: the one call that needs no token. */
data class Health(val version: String, val uptimeSeconds: Long, val routes: Map<String, String>)

/**
 * What is on the proxy's port. Only [Silent] means the port is free: something that answered holds
 * it whether or not it is ours, and a proxy started against it could never bind. Telling the two
 * apart is the whole point of the type, because starting a second proxy is what the app does next.
 */
sealed interface Probe {
    data class Healthy(val health: Health) : Probe

    /** Something answered, but not a Peashoot control API: a wrong port, or a wrong process. */
    data class Foreign(val detail: String) : Probe

    data class Silent(val detail: String) : Probe
}

/**
 * What the app hears from the feed: a stored line, or the feed's own state. Losing the proxy is
 * something the window shows, not something a collector has to catch, so this is the only way the
 * client reports a connection failure.
 */
sealed interface Feed {
    data class Line(val id: Long, val event: JsonObject) : Feed

    data class State(val connected: Boolean, val detail: String) : Feed
}

/**
 * The app's one way to the proxy: REST and the SSE feed over `/_peashoot/v1/`. It holds the bearer
 * token, never a database handle; every number the window shows came over this.
 */
class ControlClient(
    private val baseUrl: String,
    /**
     * Read once per feed connection rather than once per app, so a proxy the app has only just
     * started is read from the token file it has only just written.
     */
    private val token: () -> String,
    private val client: HttpClient =
        // A feed stays open for hours, so the engine must not time the request out under it.
        HttpClient(CIO) { engine { requestTimeout = 0 } },
) : AutoCloseable {

    /**
     * What is on the port, and what it says about itself. Never throws: whether to start a proxy
     * turns on the answer, and an exception would flatten "nothing is there" together with "a 500",
     * which are opposite decisions.
     */
    suspend fun probe(): Probe =
        try {
            val response = client.get("$baseUrl$CONTROL_BASE/health")
            val body = response.bodyAsText()
            when (response.status) {
                HttpStatusCode.OK -> parseHealth(body)
                else -> Probe.Foreign("$baseUrl answered ${response.status} to health")
            }
        } catch (e: IOException) {
            Probe.Silent("nothing is listening on $baseUrl: ${e.message}")
        }

    /**
     * Every line after [since], and the feed's connection as it changes, until the collector stops.
     * A dropped feed or a restarted proxy reconnects from the last id it delivered, so the ids
     * continue where they left off and no line goes missing. A refused token is the one failure
     * another attempt cannot mend: it is reported and the feed ends.
     */
    fun events(since: Long? = null): Flow<Feed> = flow {
        val cursor = Cursor(since)
        var wait = MIN_BACKOFF_MS
        var open = true
        while (open) {
            val ending = connect(cursor)
            emit(Feed.State(connected = false, detail = ending.detail))
            open = ending.retry
            if (open) {
                // A connection that streamed starts the backoff over; then wait, and only then
                // lengthen, so the first retry waits the minimum rather than twice it.
                if (ending.streamed) wait = MIN_BACKOFF_MS
                delay(wait)
                wait = (wait * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    override fun close() = client.close()

    private fun parseHealth(body: String): Probe = runCatching {
        val json = Json.parseToJsonElement(body).jsonObject
        Health(
            version = json.string("version"),
            uptimeSeconds = json.string("uptimeSeconds").toLong(),
            routes =
                json.getValue("routes").jsonObject.mapValues { it.value.jsonObject.string("mode") },
        )
    }
        .fold(
            { Probe.Healthy(it) },
            { Probe.Foreign("$baseUrl answered health that is not Peashoot's: ${it.message}") },
        )

    /** One attempt at the feed, from the first byte to whatever ended it. */
    private suspend fun FlowCollector<Feed>.connect(cursor: Cursor): Ending {
        // Per attempt, not per feed: a proxy that has gone for good must keep backing off, even
        // though earlier attempts streamed.
        cursor.streamed = false
        val bearer = runCatching {
            "Bearer ${token()}"
        }
            .getOrElse {
                val detail = it.message ?: "the control API token cannot be read"
                return Ending(detail, retry = false, streamed = false)
            }
        return try {
            client
                .prepareGet("$baseUrl$CONTROL_BASE/events") {
                    header(HttpHeaders.Authorization, bearer)
                    cursor.last?.let { header(LAST_EVENT_ID, it.toString()) }
                }
                .execute { read(it, cursor) }
        } catch (e: IOException) {
            // The cursor is deliberately not touched here: a stream cut mid-flight has already
            // moved it to the last line it delivered, and resuming from anywhere earlier would
            // send those lines a second time.
            Ending("no proxy at $baseUrl: ${e.message}", retry = true, streamed = cursor.streamed)
        }
    }

    /**
     * The SSE stream as the proxy writes it: `id:` then `data:` per line, and comment lines while
     * it is idle. The cursor moves as each line is delivered, so every way out of here — the end of
     * the stream, a refusal, or a reset thrown from underneath — leaves it on the last id the
     * collector actually saw.
     */
    private suspend fun FlowCollector<Feed>.read(response: HttpResponse, cursor: Cursor): Ending {
        if (response.status != HttpStatusCode.OK) {
            val detail =
                "the proxy answered ${response.status} to the feed: ${response.bodyAsText()}"
            return Ending(detail, retry = false, streamed = false)
        }
        emit(Feed.State(connected = true, detail = "connected to $baseUrl"))
        cursor.streamed = true
        var id: Long? = null
        val channel = response.bodyAsChannel()
        while (true) {
            val line = channel.readLine() ?: break
            when {
                line.startsWith("id: ") -> id = line.removePrefix("id: ").toLongOrNull()
                line.startsWith("data: ") && id != null -> {
                    val event = Json.parseToJsonElement(line.removePrefix("data: ")).jsonObject
                    emit(Feed.Line(id, event))
                    cursor.last = id
                }
            }
        }
        return Ending("the feed from $baseUrl ended", retry = true, streamed = true)
    }
}

/**
 * How far the feed has got, across every attempt at it. A field rather than a return value because
 * the connection that most needs to be resumed from is the one that threw on the way out.
 */
private class Cursor(var last: Long?) {
    /** Whether any attempt has got as far as a stream, which is what resets the backoff. */
    var streamed = false
}

/** Why one feed connection ended: what to show, and whether another attempt could do better. */
private data class Ending(val detail: String, val retry: Boolean, val streamed: Boolean)

/** A field that has to be there and has to be a plain value, or this is not Peashoot answering. */
private fun JsonObject.string(name: String): String = (getValue(name) as JsonPrimitive).content

/**
 * The bearer token the proxy wrote into its data directory. Absent means no proxy has ever started
 * here, which is worth saying plainly rather than sending an empty token and reading back a 401.
 */
fun readToken(home: Path): String {
    val file = home.resolve(TOKEN_FILE)
    check(Files.isRegularFile(file)) { "no control API token at $file; start the proxy once" }
    return Files.readString(file).trim()
}
