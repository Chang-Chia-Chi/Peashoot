package dev.peashoot.core

import io.ktor.http.Headers
import java.time.Instant
import kotlin.random.Random

/**
 * One request through the proxy, from receipt to completion. The request side is fixed at receipt;
 * the source fills in the response side and the sinks set the flags, so this is a context object,
 * not a value. Secret headers are stripped before construction and never appear here.
 */
class Exchange(val request: Request) {
    class Request(val method: String, val path: String, val headers: Headers, val body: ByteArray)

    /** A ULID, so the store's indexes and cursors sort by arrival. */
    val id: String = ulid()
    val receivedAt: Instant = Instant.now()

    /** Set by the source before its first frame. */
    var status: Int? = null
    var responseHeaders: Headers = Headers.Empty

    /** The client went away mid-stream. */
    var clientDisconnected: Boolean = false
}

private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
private const val BITS_PER_CHAR = 5
private const val CHAR_MASK = 31L
private const val TIME_CHARS = 10
private const val RANDOM_CHARS = 16

/** 48 bits of milliseconds then 80 random bits, Crockford base32: 26 chars that sort by time. */
internal fun ulid(nowMillis: Long = System.currentTimeMillis(), random: Random = Random): String {
    val chars = CharArray(TIME_CHARS + RANDOM_CHARS)
    var time = nowMillis
    for (i in TIME_CHARS - 1 downTo 0) {
        chars[i] = CROCKFORD[(time and CHAR_MASK).toInt()]
        time = time ushr BITS_PER_CHAR
    }
    for (i in TIME_CHARS until chars.size) chars[i] = CROCKFORD[random.nextInt(CROCKFORD.length)]
    return String(chars)
}
