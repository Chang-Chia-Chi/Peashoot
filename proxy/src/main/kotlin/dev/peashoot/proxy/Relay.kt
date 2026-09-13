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
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeStringUtf8
import java.io.IOException
import java.nio.channels.UnresolvedAddressException
import java.nio.file.Path
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
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
        if (offered != null) {
            respondFrom(call, exchange, offered)
            return
        }

        val requestContentType =
            call.request.header(HttpHeaders.ContentType)?.let(ContentType::parse)
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
                statement.execute { response ->
                    respondFrom(
                        call,
                        exchange,
                        UpstreamSource(response, exchange, config.dumpFrames),
                    )
                }
                null
            } catch (e: IOException) {
                e
            } catch (e: UnresolvedAddressException) {
                e
            }
        if (failure != null) {
            // Once the headers are out the client already sees the break; before them, we name it.
            if (call.response.isCommitted) throw failure
            respondProxyFailure(call, exchange, failure)
        }
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
        val stream = ExchangeStream(exchange, source, interceptors)
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
     * The exchange still completes, with the 502, so nothing that started goes unfinished. It never
     * had a source, so it never had a stream: this is the one completion the drive does not run.
     */
    private suspend fun respondProxyFailure(
        call: ApplicationCall,
        exchange: Exchange,
        cause: Exception,
    ) {
        log.warn(
            "upstream unreachable for {} {}: {}",
            exchange.request.method,
            exchange.request.path,
            cause.toString(),
        )
        exchange.response = Exchange.Response(HttpStatusCode.BadGateway.value, Headers.Empty)
        val body = buildJsonObject {
            put("type", "peashoot_error")
            put("error", "upstream_unreachable")
            put("detail", cause.toString())
        }
        call.respondText(body.toString(), ContentType.Application.Json, HttpStatusCode.BadGateway)
        val outcome = Outcome(HttpStatusCode.BadGateway.value)
        interceptors.forEach { it.onComplete(exchange, outcome) }
    }
}

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
) {
    private val status = source.status
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

    /** The client sink: each frame goes out as soon as it exists, in the call's own coroutine. */
    suspend fun writeTo(channel: ByteWriteChannel) {
        // Keep-alive pings (#10) are a timeout on this receive, not a clause here: a write that
        // fails is how a clean close is heard.
        var drained = false
        try {
            for (frame in handoff) {
                channel.writeStringUtf8(frame.raw)
                channel.flush()
            }
            drained = true
        } finally {
            // The writer is the hand-off's only receiver, so a writer that stopped early is a
            // client that left. Detaching here frees the drive at once, and the flag and
            // client-gone still go under the same lock, before any completion can take it.
            if (!drained) withContext(NonCancellable) { detach() }
        }
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
            // #10's client-gone event wants the bytes sent so far: this is where it would set
            // that field on the exchange.
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
        val streaming = response.contentType()?.match(ContentType.Text.EventStream) == true
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
