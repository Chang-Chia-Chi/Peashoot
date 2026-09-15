package dev.peashoot.core

import com.github.f4b6a3.ulid.UlidCreator
import io.ktor.http.Headers
import java.time.Instant
import kotlinx.serialization.json.JsonObject

/** What a route does with its exchanges. */
enum class Mode {
    RECORD,
    REPLAY,
    PASSTHROUGH,
}

/**
 * One request through the proxy, from receipt to completion. The request side is fixed at receipt;
 * the source fills in the response side and the sinks set the flags, so this is a context object,
 * not a value. Secret headers are stripped before construction and never appear here. The request
 * side, at receipt, and [response], before the stream starts, are both written on the call
 * coroutine; [clientDisconnected] and [clientBytes] are set at detach, under the stream's mutex,
 * which keeps client-gone and completion apart. Nothing else is written while the stream runs. The
 * store rebuilds one from a row, which is why id and receivedAt are parameters. [client] is derived
 * from the request, once, on first use.
 */
class Exchange(
    val request: Request,
    val route: String,
    val mode: Mode,
    /** A monotonic ULID: sorts by arrival, and in creation order within one millisecond. */
    val id: String = UlidCreator.getMonotonicUlid().toString(),
    val receivedAt: Instant = Instant.now(),
) {
    class Request(val method: String, val path: String, val headers: Headers, val body: ByteArray) {
        /** The body as a JSON object: null when it is empty, not JSON, or not an object. */
        val json: JsonObject? by lazy { jsonObjectOrNull(body.decodeToString()) }
    }

    class Response(val status: Int, val headers: Headers)

    /** Who sent this: the headers decide, and the first user message when they say no session. */
    val client: Client by lazy { Client.detect(request.headers, request.json) }

    /**
     * Set once, by the source before its first frame or by the proxy-failure path; null only
     * between receipt and the source.
     */
    var response: Response? = null

    /** The client went away mid-stream. */
    var clientDisconnected: Boolean = false

    /**
     * The response bytes the client had taken when it left, counting the frames whose write and
     * flush returned, and never the keep-alive comments; set at detach, under the stream's mutex,
     * together with [clientDisconnected], and 0 for a client that stayed.
     */
    var clientBytes: Long = 0
}
