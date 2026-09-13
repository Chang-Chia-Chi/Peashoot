package dev.peashoot.core

import com.github.f4b6a3.ulid.UlidCreator
import io.ktor.http.Headers
import java.time.Instant

/**
 * One request through the proxy, from receipt to completion. The request side is fixed at receipt;
 * the source fills in the response side and the sinks set the flags, so this is a context object,
 * not a value. Secret headers are stripped before construction and never appear here. Every field
 * is written and read on the exchange's own coroutine; a second coroutine (client-gone, #9) must
 * synchronize.
 */
class Exchange(val request: Request) {
    class Request(val method: String, val path: String, val headers: Headers, val body: ByteArray)

    /** A monotonic ULID: sorts by arrival, and in creation order within one millisecond. */
    val id: String = UlidCreator.getMonotonicUlid().toString()
    val receivedAt: Instant = Instant.now()

    /** Set by the source before its first frame. */
    var status: Int? = null
    var responseHeaders: Headers = Headers.Empty

    /** The client went away mid-stream. */
    var clientDisconnected: Boolean = false
}
