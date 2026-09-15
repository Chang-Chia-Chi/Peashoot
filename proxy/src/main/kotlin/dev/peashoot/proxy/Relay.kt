package dev.peashoot.proxy

import dev.peashoot.core.Exchange
import dev.peashoot.core.Frame
import dev.peashoot.core.FrameParser
import dev.peashoot.core.FrameSource
import dev.peashoot.core.Interceptor
import dev.peashoot.core.Outcome
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.BadContentTypeFormatException
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.charset
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import java.io.IOException
import java.nio.channels.UnresolvedAddressException
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.peashoot.proxy")

private val hopByHop =
    setOf(
        "connection",
        "keep-alive",
        "proxy-authenticate",
        "proxy-authorization",
        "te",
        "trailer",
        "transfer-encoding",
        "upgrade",
    )

/**
 * Engine-owned or wrong-for-upstream request headers. accept-encoding goes so streams arrive
 * uncompressed.
 */
private val notForwardedToUpstream =
    hopByHop + setOf("host", "content-length", "content-type", "accept-encoding")

/**
 * Engine-owned response headers. content-type travels as the response's own property; the body is
 * re-encoded frame text, so it goes out chunked rather than under the upstream's length.
 */
private val notForwardedToClient = hopByHop + setOf("content-length", "content-type")

private fun Headers.without(excluded: Set<String>): Headers = Headers.build {
    this@without.forEach { name, values ->
        if (name.lowercase() !in excluded) values.forEach { append(name, it) }
    }
}

/**
 * Whether the frame path can carry this body, from the declared content-type alone. A missing
 * content-type passes; the frame decode stays the backstop for a body that lies about itself. A
 * declaration we cannot parse does not pass, because we cannot claim it is text.
 */
private fun HttpResponse.hasTextBody(): Boolean {
    val declared = headers[HttpHeaders.ContentType] ?: return true
    return try {
        ContentType.parse(declared).isTextBody()
    } catch (_: BadContentTypeFormatException) {
        false
    }
}

/**
 * A frame is text, so only text or JSON in UTF-8 is a text body (ADR 0001). Ktor's own `isTextType`
 * is the wrong allowlist here: it admits xml, svg, and form-urlencoded, and misses a generic
 * `+json` subtype. A charset naming no known encoding is not UTF-8 either, so the parameter's
 * presence is what we test, not whether Ktor could resolve it.
 */
private fun ContentType.isTextBody(): Boolean {
    val carried =
        match(ContentType.Text.Any) ||
            match(ContentType.Application.Json) ||
            (match(ContentType.Application.Any) &&
                contentSubtype.endsWith("+json", ignoreCase = true))
    return carried && (parameter("charset") == null || charset() == Charsets.UTF_8)
}

/**
 * Whether the declared content-type is an event stream. Only a stream splits at blank lines, and
 * only a stream is ever sent a keep-alive comment; a declaration we cannot parse is neither, so a
 * source that lies about itself still gets its completion.
 */
private fun Headers.declaresEventStream(): Boolean =
    try {
        this[HttpHeaders.ContentType]
            ?.let(ContentType::parse)
            ?.match(ContentType.Text.EventStream) == true
    } catch (_: BadContentTypeFormatException) {
        false
    }

/**
 * Every request through the chain. The first interceptor to respond is the source; otherwise the
 * upstream is, and a failure to reach it is a proxy error, never a provider one.
 */
class Relay(
    private val config: ProxyConfig,
    private val upstream: HttpClient,
    private val interceptors: List<Interceptor>,
    /**
     * The Ktor application: its job is a SupervisorJob, so one failing stream cancels nothing else,
     * and nothing outlives server stop.
     */
    private val streams: CoroutineScope,
) {
    suspend fun handle(call: ApplicationCall) {
        val body = call.receive<ByteArray>()
        val exchange =
            Exchange(
                Exchange.Request(
                    call.request.httpMethod.value,
                    call.request.uri,
                    call.request.headers.without(config.lowercaseSecretHeaders),
                    body,
                ),
                route = DEFAULT_ROUTE,
                mode = config.routes.getValue(DEFAULT_ROUTE),
            )
        // Every interceptor hears the request; the first source offered wins. mapNotNull is eager
        // on purpose: firstNotNullOfOrNull would stop asking at the first answer.
        val offered = interceptors.mapNotNull { it.onRequest(exchange) }.firstOrNull()
        var cancelled = false
        try {
            if (offered != null) respondFrom(call, exchange, offered)
            else askUpstream(call, exchange, body)
        } catch (e: CancellationException) {
            cancelled = true
            throw e
        } finally {
            // No response means no stream, so no drive will end this one. The chain heard the
            // request, so it hears the end.
            if (exchange.response == null) completeUnanswered(exchange, cancelled)
        }
    }

    /**
     * Nothing was offered, so the upstream is asked. A request content-type we cannot parse is
     * refused rather than forwarded: parsing it any earlier would keep the request from ever
     * becoming an exchange, and forwarding it throws past the chain.
     */
    private suspend fun askUpstream(call: ApplicationCall, exchange: Exchange, body: ByteArray) {
        val declared = call.request.header(HttpHeaders.ContentType)
        val requestContentType =
            try {
                declared?.let(ContentType::parse)
            } catch (_: BadContentTypeFormatException) {
                respondProxyFailure(
                    call,
                    exchange,
                    HttpStatusCode.BadRequest,
                    "bad_content_type",
                    declared.orEmpty(),
                )
                return
            }
        // The target starts with `/` (relayModule refuses others), so it can only extend the path.
        val statement =
            upstream.prepareRequest(config.upstreamBase + call.request.uri) {
                method = call.request.httpMethod
                headers.appendAll(call.request.headers.without(notForwardedToUpstream))
                if (body.isNotEmpty() || requestContentType != null)
                    setBody(ByteArrayContent(body, requestContentType))
            }
        val failure =
            try {
                statement.execute { response -> relayUpstream(call, exchange, response) }
                null
            } catch (e: IOException) {
                e
            } catch (e: UnresolvedAddressException) {
                e
            }
        if (failure != null) {
            // Once the headers are out the client already sees the break; before them, we name it.
            if (call.response.isCommitted) throw failure
            respondProxyFailure(
                call,
                exchange,
                HttpStatusCode.BadGateway,
                "upstream_unreachable",
                failure.toString(),
            )
        }
    }

    /**
     * The one ending nothing else can give: neither a source nor a refusal took the exchange, so
     * the completion runs here. A cancelled call is the proxy stopping before a status existed, not
     * a client leaving: the engine does not report a departure this early (#51). Anything else is a
     * failure Ktor turns into a 500 for a client that is still there. The response stays null
     * either way: the status says what happened.
     */
    private suspend fun completeUnanswered(exchange: Exchange, cancelled: Boolean) =
        withContext(NonCancellable) {
            val status = if (cancelled) null else HttpStatusCode.InternalServerError.value
            interceptors.forEach { it.onComplete(exchange, Outcome(status)) }
        }

    /**
     * The upstream answered. A body the frame path cannot carry is refused here, before the
     * response starts, rather than truncated into a 200: the declared content-type decides, and the
     * body is never read for it.
     */
    private suspend fun relayUpstream(
        call: ApplicationCall,
        exchange: Exchange,
        response: HttpResponse,
    ) {
        if (response.hasTextBody()) {
            respondFrom(call, exchange, UpstreamSource(response, exchange, config.dumpFrames))
            return
        }
        val declared = response.headers[HttpHeaders.ContentType].orEmpty()
        respondProxyFailure(
            call,
            exchange,
            HttpStatusCode.BadGateway,
            "unsupported_content_type",
            declared,
        )
    }

    /**
     * Starts the exchange's own stream, then offers it to the client. The client is a sink like any
     * other: if it never arrives or leaves early the stream still runs to its end.
     */
    private suspend fun respondFrom(
        call: ApplicationCall,
        exchange: Exchange,
        source: FrameSource,
    ) {
        exchange.response = Exchange.Response(source.status, source.headers)
        val stream = ExchangeStream(exchange, source, interceptors, config.pingInterval)
        val drive = streams.launch(brokenInterceptor(exchange)) { stream.drive() }
        var delivered = false
        try {
            call.respond(
                object : OutgoingContent.WriteChannelContent() {
                    override val status = HttpStatusCode.fromValue(source.status)
                    override val headers = source.headers.without(notForwardedToClient)
                    override val contentType =
                        source.headers[HttpHeaders.ContentType]?.let(ContentType::parse)

                    override suspend fun writeTo(channel: ByteWriteChannel) =
                        stream.writeTo(channel)
                }
            )
            delivered = true
        } finally {
            // The upstream body lives only inside the client library's execute block, so the call
            // waits for the drive even when it was cancelled. An abandoned exchange therefore holds
            // its call coroutine until the upstream finishes: the cost of keeping the body open
            // without internal APIs.
            withContext(NonCancellable) {
                // The price of taking any failed respond for a departure: an engine that refuses
                // the response header is recorded as a client that left, flag and all. It is also
                // what keeps the drive from wedging on its very first send.
                if (!delivered) stream.detach()
                drive.join()
            }
        }
    }

    /**
     * An interceptor that breaks the must-not-throw contract dies on the stream's own coroutine,
     * whose SupervisorJob would send it to the JVM's default handler instead of the log. The one
     * place a stack trace earns its keep: it means a sink is broken.
     */
    private fun brokenInterceptor(exchange: Exchange) = CoroutineExceptionHandler { _, e ->
        log.error(
            "interceptor threw for {} {}",
            exchange.request.method,
            exchange.request.path,
            e,
        )
    }

    /**
     * Never imitates a provider's error shape: a client's retry logic must not mistake us for one.
     * Whether we refused to forward the request, the upstream was unreachable, or its body was
     * unusable, the exchange never had a source, so it never had a stream: this is the one
     * completion the drive does not run. It runs here instead, in a finally, so the once-per-
     * exchange guarantee holds by construction. A refusal to a client that has already left does
     * not throw on the Netty engine, which discards the write on a channel it has closed; the
     * completion rests neither on that nor on the call surviving the write uncancelled.
     */
    private suspend fun respondProxyFailure(
        call: ApplicationCall,
        exchange: Exchange,
        status: HttpStatusCode,
        error: String,
        detail: String,
    ) {
        log.warn("{} for {} {}: {}", error, exchange.request.method, exchange.request.path, detail)
        exchange.response = Exchange.Response(status.value, Headers.Empty)
        val body = buildJsonObject {
            put("type", "peashoot_error")
            put("error", error)
            put("detail", detail)
        }
        try {
            call.respondText(body.toString(), ContentType.Application.Json, status)
        } finally {
            // Whether or not the refusal reached the client, the chain hears the end once: the
            // guarantee the drive's finally gives a stream.
            withContext(NonCancellable) {
                val outcome = Outcome(status.value)
                interceptors.forEach { it.onComplete(exchange, outcome) }
            }
        }
    }
}

/** What a silent stream sends the client, so a byte-counting watchdog is not tripped by us. */
private const val KEEP_ALIVE = ": keep-alive\n\n"

/**
 * One exchange's frames on their way to the sinks. The drive is the only collector, so the client
 * is a sink and not the pump: it takes frames through a rendezvous while it is there, and the drive
 * keeps collecting once it is gone. A resume tap (#26) is an interceptor with its own buffer and
 * needs exactly that.
 */
private class ExchangeStream(
    private val exchange: Exchange,
    source: FrameSource,
    private val interceptors: List<Interceptor>,
    private val pingInterval: Duration,
) {
    private val status = source.status

    /** Only a stream is ever sent a comment: one inside a JSON body would corrupt it. */
    private val streaming = source.headers.declaresEventStream()

    private val frames =
        interceptors.fold(source.frames()) { acc, interceptor ->
            interceptor.onFrames(exchange, acc)
        }

    /** One frame in flight: the drive does not pull the next until the writer took the last. */
    private val handoff = Channel<Frame>(Channel.RENDEZVOUS)

    /** Client-gone and completion take it in turn, and it publishes the flag to the chain. */
    private val lock = Mutex()

    private var completed = false
    private var detached = false

    /**
     * Frame bytes the client has taken, comments never among them. Written by the writer and read
     * by [detach] on that same call coroutine, so a plain field is enough.
     */
    private var sent = 0L

    /**
     * The one collector: the source is cold and single-use. A source or interceptor that fails
     * mid-stream ends the frames where they are and the exchange still completes; the missing
     * terminal frame says what happened.
     */
    suspend fun drive() {
        var writerGone = false
        try {
            frames
                .catch { e ->
                    log.warn(
                        "stream failed for {} {}: {}",
                        exchange.request.method,
                        exchange.request.path,
                        e.toString(),
                    )
                }
                .collect { frame ->
                    if (!writerGone) {
                        try {
                            handoff.send(frame)
                        } catch (_: ClosedSendChannelException) {
                            // The writer left; the rest of the response is for the other sinks.
                            writerGone = true
                        }
                    }
                }
        } finally {
            // Ends the client's response as soon as the last frame is out, before the sinks work.
            handoff.close()
            withContext(NonCancellable) {
                lock.withLock {
                    completed = true
                    interceptors.forEach { it.onComplete(exchange, Outcome(status)) }
                }
            }
        }
    }

    /**
     * The client sink: each frame goes out as soon as it exists, in the call's own coroutine, and a
     * stream that has said nothing for the interval gets a comment instead. A write that fails is
     * still how a departed client is heard.
     */
    suspend fun writeTo(channel: ByteWriteChannel) {
        var drained = false
        try {
            coroutineScope {
                while (!drained) {
                    // The wait is a select clause, which takes the frame atomically: a
                    // withTimeoutOrNull around receive could be cancelled after the drive had
                    // handed one over and lose it, and select's own onTimeout needs an opt-in.
                    val silence = launch { delay(pingInterval) }
                    val next = select {
                        handoff.onReceiveCatching { it }
                        silence.onJoin { null }
                    }
                    // A live delay would hold this scope open after the last frame.
                    silence.cancel()
                    when {
                        // The interval passed with nothing to send.
                        next == null -> if (streaming) channel.deliver(KEEP_ALIVE)
                        next.isClosed -> drained = true
                        else -> sent += channel.deliver(next.getOrThrow().raw)
                    }
                }
            }
        } finally {
            // The writer is the hand-off's only receiver, so a writer that stopped early is a
            // client that left. Detaching here frees the drive at once, and the flag and
            // client-gone still go under the same lock, before any completion can take it.
            if (!drained) withContext(NonCancellable) { detach() }
        }
    }

    /** One write and its flush; the count is what the client took. */
    private suspend fun ByteWriteChannel.deliver(text: String): Int {
        val bytes = text.toByteArray()
        writeFully(bytes)
        flush()
        return bytes.size
    }

    /**
     * The client left before the response ended, however it left. Idempotent, and never after
     * completion: a client that leaves once the stream is over is not a mid-stream disconnect, and
     * completion never waits to hear, or a client that vanished quietly would wedge the exchange.
     * That leaves one accepted window: a write of the final frame that fails after the drive has
     * finished collecting is stored as a clean completion, with the flag unset.
     */
    suspend fun detach() {
        lock.withLock {
            if (detached || completed) return
            detached = true
            // Closed, never cancelled: the drive's pending send then fails on its own instead of
            // taking the collection down with it.
            handoff.close()
            // Both under this lock, so the chain never reads a flag without its byte count.
            exchange.clientBytes = sent
            exchange.clientDisconnected = true
            interceptors.forEach { it.onClientGone(exchange) }
        }
    }
}

/** The terminal source: the provider's response, parsed into frames as its bytes arrive. */
private class UpstreamSource(
    private val response: HttpResponse,
    private val exchange: Exchange,
    private val dumpPath: Path?,
) : FrameSource {
    override val status = response.status.value
    override val headers = response.headers
    private var collected = false

    override fun frames(): Flow<Frame> = flow {
        // A second collection would read an exhausted body and silently yield nothing.
        check(!collected) { "the upstream body can be collected once" }
        collected = true
        val dump = dumpPath?.let {
            FrameDump(it, exchange.request.method, exchange.request.path, status)
        }
        val streaming = response.headers.declaresEventStream()
        val parser = FrameParser(streaming)
        val start = TimeSource.Monotonic.markNow()
        val body = response.bodyAsChannel()
        val buffer = ByteArray(8 * 1024)
        try {
            while (true) {
                val read = body.readAvailable(buffer, 0, buffer.size)
                if (read == -1) break
                if (read > 0) {
                    dump?.append(buffer, 0, read)
                    val offset = start.elapsedNow().inWholeMilliseconds
                    parser.feed(buffer, read, offset).forEach { emit(it) }
                }
            }
            parser.end(start.elapsedNow().inWholeMilliseconds)?.let { emit(it) }
        } finally {
            dump?.close()
        }
    }
}
