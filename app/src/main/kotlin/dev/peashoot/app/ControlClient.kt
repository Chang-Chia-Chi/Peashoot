package dev.peashoot.app

import dev.peashoot.core.TOKEN_FILE
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readLine
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
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

/**
 * How long a one-shot control call waits before it is a failure rather than a slow proxy.
 *
 * The client is built with the engine's own request timeout disabled, because a feed stays open for
 * hours; every other call inherits that, and a call that never comes back would leave the panel
 * that made it busy for ever, with its buttons dead until the window closed. This is the bound that
 * puts back.
 *
 * Thirty seconds, and not a figure that feels responsive: these calls are all on loopback and take
 * milliseconds, but two of them are bounded by the store rather than the socket — `GET /cassettes`
 * counts rows by reading them all, and an export writes every matching recording to a file — so a
 * value chosen for the common case would abandon the one call a large store makes slow. Nothing
 * here is on the window's thread, so waiting costs a disabled button and no frames.
 */
private const val CALL_TIMEOUT_MS = 30_000L

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
 * The proxy answered, and said no: the status it said it with, and the `detail` out of its problem
 * object, which is the sentence to put in front of the user word for word. An [IOException] like
 * every other failure a call can come back with, so a screen that only means to say "that did not
 * work" needs no special case, while one that must tell a refusal from an unreachable proxy asks
 * what this is.
 */
internal class Refused(val status: Int, val detail: String) :
    IOException("the proxy refused: $detail")

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
    /**
     * What a one-shot call waits, which is [CALL_TIMEOUT_MS] for the window. A parameter only so
     * that the test for a proxy that accepts and never answers does not cost the build half a
     * minute — the same reason `TestProxy` shortens the feed's keep-alive interval.
     */
    private val callTimeoutMs: Long = CALL_TIMEOUT_MS,
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
     * One control API `GET` under [path], as text, or why there is none. Never throws, for the
     * reason [probe] does not: what asks for these is a click, and a pane with an error in it is a
     * window that still works — an exception out of a click handler is not.
     *
     * The caller parses it. Everything under `/_peashoot/v1/` answers JSON, and what each endpoint
     * answers is that endpoint's business rather than this class's.
     */
    suspend fun get(path: String): Result<String> {
        // The token read is its own `runCatching` — as it is in [connect] — and the request is
        // caught on IOException alone. Widening that catch to IllegalStateException would swallow
        // cancellation with it, since `CancellationException` is one: a load cancelled by the next
        // click would come back as a failure and write its message over the newer load's.
        val bearer = runCatching {
            "Bearer ${token()}"
        }
            .getOrElse {
                return Result.failure(it)
            }
        return try {
            withTimeout(callTimeoutMs) {
                val response =
                    client.get("$baseUrl$CONTROL_BASE$path") {
                        header(HttpHeaders.Authorization, bearer)
                    }
                // Read either way, as [probe] reads it, so the connection is released rather than
                // held until something collects it — and then deliberately dropped on a refusal: a
                // refusal's detail is about the request, and the one thing a pane must never put on
                // screen or in a log is what came back.
                val body = response.bodyAsText()
                if (response.status == HttpStatusCode.OK) Result.success(body)
                else Result.failure(IOException("the proxy answered ${response.status} to $path"))
            }
        } catch (e: TimeoutCancellationException) {
            Result.failure(timedOut(path, e))
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    /**
     * One control API call of any method, with a JSON body when there is one, answering the proxy's
     * own text. Never throws, for the reason [probe] and [get] do not: what asks for these is a
     * click.
     *
     * A refusal comes back as [Refused], carrying the status and the `detail` the proxy wrote, and
     * that is deliberately the opposite of what [get] does with one. What [get] fetches is an
     * exchange, whose body is a prompt nobody asked to see; what is refused here is a draft the
     * user has this moment typed, and the parser's own words about it are the only thing that says
     * which rule to fix. Every other failure stays the [IOException] it was, so a screen can tell a
     * proxy that said no from a proxy that was never there.
     */
    internal suspend fun send(
        method: HttpMethod,
        path: String,
        body: String? = null,
    ): Result<String> {
        // The token read is its own `runCatching` and the request is caught on IOException alone,
        // for the reason [get] spells out: `CancellationException` is an `IllegalStateException`.
        val bearer = runCatching {
            "Bearer ${token()}"
        }
            .getOrElse {
                return Result.failure(it)
            }
        return try {
            withTimeout(callTimeoutMs) {
                val response =
                    client.request("$baseUrl$CONTROL_BASE$path") {
                        this.method = method
                        header(HttpHeaders.Authorization, bearer)
                        if (body != null) {
                            contentType(ContentType.Application.Json)
                            setBody(body)
                        }
                    }
                // Read either way, as [probe] and [get] read it, so the connection is released.
                val answer = response.bodyAsText()
                if (response.status.isSuccess()) Result.success(answer)
                else Result.failure(Refused(response.status.value, problemDetail(answer)))
            }
        } catch (e: TimeoutCancellationException) {
            Result.failure(timedOut(path, e))
        } catch (e: IOException) {
            Result.failure(e)
        }
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

    /**
     * A call that ran out of time, as the failure a panel already knows how to report. An
     * [IOException], so it reads as a proxy that could not be reached rather than as one that said
     * no — which is what it is: nothing came back. Deliberately *not* the
     * [TimeoutCancellationException] itself, because that is a `CancellationException`, and letting
     * one out of here would cancel the caller instead of answering it and take the panel's whole
     * scope down with it.
     */
    private fun timedOut(path: String, cause: Throwable): IOException =
        IOException("the proxy did not answer $path within ${callTimeoutMs}ms", cause)

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
