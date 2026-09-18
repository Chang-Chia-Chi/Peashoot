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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

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

    /** Whether the proxy is up, what it is, and what each route does. Throws when it is not. */
    suspend fun health(): Health {
        val body = client.get("$baseUrl$CONTROL_BASE/health").bodyAsText()
        val json = Json.parseToJsonElement(body).jsonObject
        return Health(
            version = json.getValue("version").jsonPrimitive.content,
            uptimeSeconds = json.getValue("uptimeSeconds").jsonPrimitive.long,
            routes =
                json.getValue("routes").jsonObject.mapValues {
                    it.value.jsonObject.getValue("mode").jsonPrimitive.content
                },
        )
    }

    /**
     * Every line after [since], and the feed's connection as it changes, until the collector stops.
     * A dropped feed or a restarted proxy reconnects with the last id it saw, so the ids continue
     * where they left off and no line goes missing. A refused token is the one failure another
     * attempt cannot mend: it is reported and the feed ends.
     */
    fun events(since: Long? = null): Flow<Feed> = flow {
        var last = since
        var wait = MIN_BACKOFF_MS
        var open = true
        while (open) {
            val ending = connect(last)
            last = ending.last
            emit(Feed.State(connected = false, detail = ending.detail))
            wait = if (ending.connected) MIN_BACKOFF_MS else (wait * 2).coerceAtMost(MAX_BACKOFF_MS)
            open = ending.retry
            if (open) delay(wait)
        }
    }

    override fun close() = client.close()

    /** One attempt at the feed, from the first byte to whatever ended it. */
    private suspend fun FlowCollector<Feed>.connect(since: Long?): Ending {
        val bearer = runCatching {
            "Bearer ${token()}"
        }
            .getOrElse {
                val detail = it.message ?: "the control API token cannot be read"
                return Ending(since, detail, retry = false, connected = false)
            }
        return try {
            client
                .prepareGet("$baseUrl$CONTROL_BASE/events") {
                    header(HttpHeaders.Authorization, bearer)
                    since?.let { header(LAST_EVENT_ID, it.toString()) }
                }
                .execute { read(it, since) }
        } catch (e: IOException) {
            Ending(since, "no proxy at $baseUrl: ${e.message}", retry = true, connected = false)
        }
    }

    /**
     * The SSE stream as the proxy writes it: `id:` then `data:` per line, and comment lines while
     * it is idle. The id of the last line read is what the next attempt resumes from.
     */
    private suspend fun FlowCollector<Feed>.read(response: HttpResponse, since: Long?): Ending {
        if (response.status != HttpStatusCode.OK) {
            val detail =
                "the proxy answered ${response.status} to the feed: ${response.bodyAsText()}"
            return Ending(since, detail, retry = false, connected = false)
        }
        emit(Feed.State(connected = true, detail = "connected to $baseUrl"))
        var last = since
        var id: Long? = null
        val channel = response.bodyAsChannel()
        while (true) {
            val line = channel.readLine() ?: break
            when {
                line.startsWith("id: ") -> id = line.removePrefix("id: ").toLongOrNull()
                line.startsWith("data: ") && id != null -> {
                    emit(
                        Feed.Line(
                            id,
                            Json.parseToJsonElement(line.removePrefix("data: ")).jsonObject,
                        )
                    )
                    last = id
                }
            }
        }
        return Ending(last, "the feed from $baseUrl ended", retry = true, connected = true)
    }
}

/** Why one feed connection ended: where to resume, what to show, and whether to try again. */
private data class Ending(
    val last: Long?,
    val detail: String,
    val retry: Boolean,
    val connected: Boolean,
)

/**
 * The bearer token the proxy wrote into its data directory. Absent means no proxy has ever started
 * here, which is worth saying plainly rather than sending an empty token and reading back a 401.
 */
fun readToken(home: Path): String {
    val file = home.resolve(TOKEN_FILE)
    check(Files.isRegularFile(file)) { "no control API token at $file; start the proxy once" }
    return Files.readString(file).trim()
}
