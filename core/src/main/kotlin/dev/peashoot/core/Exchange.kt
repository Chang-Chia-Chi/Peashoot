package dev.peashoot.core

import java.time.Instant
import java.util.UUID

/**
 * One request through the proxy, from receipt to completion. The request side is fixed at receipt;
 * the source fills in the response side and the sinks set the flags, so this is a context object,
 * not a value. Secret headers are stripped before construction and never appear here.
 */
class Exchange(val request: Request) {
    class Request(
        val method: String,
        val path: String,
        val headers: Map<String, List<String>>,
        val body: ByteArray,
    )

    val id: String = UUID.randomUUID().toString()
    val receivedAt: Instant = Instant.now()

    /** Set by the source before its first frame. */
    var status: Int? = null
    var responseHeaders: Map<String, List<String>> = emptyMap()

    /** The client went away mid-stream. */
    var clientDisconnected: Boolean = false
}
