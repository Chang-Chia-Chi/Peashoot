package dev.peashoot.core

import com.github.f4b6a3.ulid.UlidCreator
import io.ktor.http.Headers
import java.time.Instant
import kotlinx.serialization.json.JsonObject

/** What a route does with its exchanges. */
enum class Mode {
    RECORD,
    REPLAY,
    PASSTHROUGH;

    /** How the config file, the event line, and the control API write it. */
    val spelling: String
        get() = name.lowercase()

    companion object {
        /** The mode [spelling] names, in any case, or null. */
        fun of(spelling: String): Mode? = entries.firstOrNull {
            it.spelling == spelling.lowercase()
        }
    }
}

/**
 * What a route does, whether a replay miss on it fails rather than asking the upstream, and the
 * cassette a replay serves from: only that cassette's recordings when named, any when not.
 */
data class Route(val mode: Mode, val strict: Boolean = false, val cassette: String? = null)

/**
 * One request through the proxy, from receipt to completion. The request side is fixed at receipt;
 * the source fills in the response side and the sinks set the flags, so this is a context object,
 * not a value. Secret headers are stripped before construction and never appear here. The request
 * side, at receipt, and [response], [replayHit] and [resumed], before the stream starts, are all
 * written on the call coroutine; [clientDisconnected] and [clientBytes] are set at detach, under
 * the stream's mutex, which keeps client-gone and completion apart. Nothing else is written while
 * the stream runs. The store rebuilds one from a row, which is why id and receivedAt are
 * parameters. [client] is derived from the request, once, on first use.
 */
class Exchange(
    val request: Request,
    /** The route's name, which the store keeps. */
    val route: String,
    /**
     * What that route said when the request arrived: every interceptor reads this one snapshot, so
     * a route changed mid-request never mixes two settings. A stored exchange keeps only its mode.
     */
    val routing: Route,
    /** A monotonic ULID: sorts by arrival, and in creation order within one millisecond. */
    val id: String = UlidCreator.getMonotonicUlid().toString(),
    val receivedAt: Instant = Instant.now(),
) {
    class Request(val method: String, val path: String, val headers: Headers, val body: ByteArray) {
        /** The body as a JSON object: null when it is empty, not JSON, or not an object. */
        val json: JsonObject? by lazy { jsonObjectOrNull(body.decodeToString()) }
    }

    class Response(val status: Int, val headers: Headers)

    val mode: Mode
        get() = routing.mode

    /** Who sent this: the headers decide, and the first user message when they say no session. */
    val client: Client by lazy { Client.detect(request.headers, request.json) }

    /**
     * Which provider API this request belongs to: the path decides, and the headers where two
     * surfaces share a path. Derived rather than carried, because the method, path, and headers it
     * reads are all kept by the store: a row rebuilt years later answers the surface it ran under,
     * and no column, no cassette field, and no fresh database were needed to say so.
     */
    val surface: Surface by lazy { surfaceOf(request.path, request.headers) }

    /**
     * Set once, by the source before its first frame or by the proxy-failure path; null only
     * between receipt and the source.
     */
    var response: Response? = null

    /**
     * What identifies this request under the active rule set: set at classify, before any
     * interceptor is asked, and read back from the row for a stored exchange. Null only between
     * receipt and classify, which no interceptor ever sees.
     */
    var fingerprint: String? = null

    /**
     * The normalized request [fingerprint] is the hash of (design section 6): method, path, kept
     * headers, and the body as the rule set left it. Set at classify beside the fingerprint, so
     * Resume can ask a surface about the request's shape without applying the rules a second time.
     * Null for an exchange rebuilt from the store, which keeps the hash and not what it hashed.
     */
    var normalized: JsonObject? = null

    /**
     * Replay answered from a recording, set by it before the stream exists: nothing was billed, and
     * nothing is recorded again.
     */
    var replayHit: Boolean = false

    /**
     * Resume answered from the buffer of an earlier exchange whose client left (#26), set by it
     * before the stream exists: no upstream call was made, so nothing was billed for this one. The
     * exchange it was served from is the one that carries the call and its cost.
     */
    var resumed: Boolean = false

    /**
     * Whether the engine says this request's connection has closed: asked at any time, from any
     * coroutine, without blocking. The relay sets it at receipt. It runs ahead of
     * [clientDisconnected], which waits for the client writer to look, and Resume needs the head
     * start: a re-issue arrives tens of milliseconds after the drop, often before the writer's next
     * look. Never true for an exchange rebuilt from the store.
     */
    var clientGone: () -> Boolean = { false }

    /** The client went away mid-stream. */
    var clientDisconnected: Boolean = false

    /**
     * The response bytes the client had taken when it left, counting the frames whose write and
     * flush returned, and never the keep-alive comments; set at detach, under the stream's mutex,
     * together with [clientDisconnected], and 0 for a client that stayed.
     */
    var clientBytes: Long = 0
}
