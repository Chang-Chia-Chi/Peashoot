package dev.peashoot.proxy

import dev.peashoot.core.Exchange
import dev.peashoot.core.Frame
import dev.peashoot.core.FrameParser
import dev.peashoot.core.FrameSource
import dev.peashoot.core.Interceptor
import dev.peashoot.core.Outcome
import dev.peashoot.core.fingerprintOf
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
import io.ktor.server.netty.NettyApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.RoutingPipelineCall
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
import kotlinx.coroutines.flow.emptyFlow
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
 * uncompressed, and `x-peashoot-session` goes because it is ours: a client injects it to tell *this
 * proxy* which session a turn belongs to (design section 9), it means nothing to any provider, and
 * forwarding it would hand a third party a stable identifier tying a user's turns together that
 * they would not otherwise have. Stripping is the privacy-preserving default and costs nothing. It
 * is removed here and not at receipt, so client detection still reads it; and it is no part of the
 * fingerprint either, being absent from `Rules.DEFAULT.keepHeaders`, so two turns of one session
 * match a recording exactly as two turns of another do.
 */
private val notForwardedToUpstream =
    hopByHop +
        setOf("host", "content-length", "content-type", "accept-encoding", "x-peashoot-session")

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
    return declared.toContentTypeOrNull()?.isTextBody() == true
}

/** The declared content-type as Ktor reads it, or null for one it cannot parse. */
private fun String.toContentTypeOrNull(): ContentType? =
    try {
        ContentType.parse(this)
    } catch (_: BadContentTypeFormatException) {
        null
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
internal fun Headers.declaresEventStream(): Boolean =
    this[HttpHeaders.ContentType]?.toContentTypeOrNull()?.match(ContentType.Text.EventStream) ==
        true

/**
 * The end, heard once by every interceptor and never cut short: it runs where a stream, a call, or
 * a refusal ended, cancelled or not.
 */
private suspend fun List<Interceptor>.complete(exchange: Exchange, status: Int?) =
    withContext(NonCancellable) { forEach { it.onComplete(exchange, Outcome(status)) } }

/**
 * Every request through the chain. The first interceptor to respond is the source; otherwise the
 * upstream is, and a failure to reach it is a proxy error, never a provider one.
 */
class Relay(
    /**
     * Read at the top of every request, so what `PUT /config` can change without a restart, the
     * rule set among it, applies to the next request rather than the next start.
     */
    private val live: LiveConfig,
    private val upstream: HttpClient,
    private val interceptors: List<Interceptor>,
    /**
     * The Ktor application: its job is a SupervisorJob, so one failing stream cancels nothing else,
     * and nothing outlives server stop.
     */
    private val streams: CoroutineScope,
    /** Read once per request, so a route change applies to the next one. */
    private val routes: RouteTable,
) {
    /**
     * The config this request runs under. A change between two reads within one request can only
     * pick a fresh upstream or ping interval for work not yet started; what a recording must keep
     * consistent is its route, and the exchange carries that.
     */
    private val config: ProxyConfig
        get() = live.current

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
                routing = routes[DEFAULT_ROUTE],
            )
        // Classify: what this request is, before anyone is asked to answer it. Every interceptor
        // sees it, so it is set before the chain rather than by whoever needs it first. The
        // normalized request is kept beside its hash because Resume reads its shape (#26), and
        // the rules are not cheap enough to apply twice to a request of a few hundred kilobytes.
        val normalized =
            config.rules.normalized(
                exchange.request.method,
                exchange.request.path,
                exchange.request.headers,
                exchange.request.json,
                body,
            )
        exchange.normalized = normalized
        exchange.fingerprint = fingerprintOf(normalized)
        // The engine's word on whether this client is still there, for Resume to ask of an
        // exchange a re-issue has just arrived for, ahead of the writer's next look.
        exchange.clientGone = call.clientGone()
        var cancelled = false
        try {
            // Every interceptor hears the request; the first source offered wins. mapNotNull is
            // eager on purpose: firstNotNullOfOrNull would stop asking at the first answer.
            when (val offered = interceptors.mapNotNull { it.onRequest(exchange) }.firstOrNull()) {
                null -> askUpstream(call, exchange, body)
                is Refusal ->
                    respondProxyFailure(
                        call,
                        exchange,
                        HttpStatusCode.fromValue(offered.status),
                        offered.error,
                        offered.fields,
                    )
                else -> respondFrom(call, exchange, offered)
            }
        } catch (e: CancellationException) {
            cancelled = true
            throw e
        } finally {
            // A source or a refusal sets the response before its own completion runs, and nothing
            // else sets it, so a null one here is an exchange nobody ended. The chain heard the
            // request, so it hears the end: with no status when the call was cancelled, which is
            // the proxy stopping, never the client, whose departure is heard on the channel and
            // never cancels the call, and otherwise with 500, Ktor's answer to an unhandled throw
            // with no status pages on.
            if (exchange.response == null) {
                val status = if (cancelled) null else HttpStatusCode.InternalServerError.value
                interceptors.complete(exchange, status)
            }
        }
    }

    /**
     * Nothing was offered, so the upstream is asked. A request content-type we cannot parse is
     * refused rather than forwarded: parsing it any earlier would keep the request from ever
     * becoming an exchange, and forwarding it throws past the chain.
     */
    private suspend fun askUpstream(call: ApplicationCall, exchange: Exchange, body: ByteArray) {
        val declared = call.request.header(HttpHeaders.ContentType)
        val requestContentType = declared?.toContentTypeOrNull()
        if (declared != null && requestContentType == null) {
            respondProxyFailure(
                call,
                exchange,
                HttpStatusCode.BadRequest,
                "bad_content_type",
                mapOf("detail" to declared),
            )
            return
        }
        // The target starts with `/` (relayModule refuses others), so it can only extend the path.
        // Which provider it extends is the surface's, so an OpenAI request reaches the OpenAI
        // upstream — a local one, when the config points it at an OpenAI-compatible server.
        val statement =
            upstream.prepareRequest(config.upstreamBase(exchange.surface) + call.request.uri) {
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
            // Once the headers are out the client already sees the break, and once a source took
            // the exchange its completion has run; before either, we name it.
            if (call.response.isCommitted || exchange.response != null) throw failure
            respondProxyFailure(
                call,
                exchange,
                HttpStatusCode.BadGateway,
                "upstream_unreachable",
                mapOf("detail" to failure.toString()),
            )
        }
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
            mapOf("detail" to declared),
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
        // Wrapping runs every onFrames first: a wrapper that throws leaves the response unset, so
        // the call's finally ends the exchange as the failure it is.
        val clientGone = call.clientGone()
        val stream = ExchangeStream(exchange, source, interceptors, config.pingInterval, clientGone)
        // What is relayed is whole; what is kept is filtered. The caller gets the provider's
        // headers as they were, because a proxy in the middle is transparent — but the exchange is
        // what the store and every cassette are written from, and a `set-cookie` in there is a
        // credential in a file made to be shared (#98). The request side has been dropped at
        // receipt since the start; this is the same drop on the way back, in the same one place.
        exchange.response =
            Exchange.Response(source.status, source.headers.without(config.lowercaseSecretHeaders))
        // Asked once before the drive exists, because the sink only asks while it waits for a
        // frame: a source with no frames at all completes the exchange without the sink ever
        // looking, and a client already gone would go unheard (#54).
        if (clientGone()) stream.detach()
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
                // what frees a drive already parked on its very first send, which nothing else
                // would ever receive.
                if (!delivered) stream.detach()
                drive.join()
                // A drive launched into a scope already stopping never ran, so it never ended the
                // exchange; the stream's completion is idempotent for exactly this.
                stream.complete()
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
     * unusable, the exchange never had a source, so it never had a stream: the completion runs here
     * instead, in a finally, so the once-per-exchange guarantee holds for a refusal as the drive's
     * finally holds it for a stream. A refusal to a client that has already left does not throw on
     * the Netty engine, which discards the write on a channel it has closed; the completion rests
     * neither on that nor on the call surviving the write uncancelled. [fields] follow `type` and
     * `error` in the body, in their own order.
     */
    private suspend fun respondProxyFailure(
        call: ApplicationCall,
        exchange: Exchange,
        status: HttpStatusCode,
        error: String,
        fields: Map<String, String>,
    ) {
        log.warn("{} for {} {}: {}", error, exchange.request.method, exchange.request.path, fields)
        exchange.response = Exchange.Response(status.value, Headers.Empty)
        val body = buildJsonObject {
            put("type", "peashoot_error")
            put("error", error)
            fields.forEach { (name, value) -> put(name, value) }
        }
        try {
            call.respondText(body.toString(), ContentType.Application.Json, status)
        } finally {
            // Whether or not the refusal reached the client, the chain hears the end once: the
            // guarantee the drive's finally gives a stream.
            interceptors.complete(exchange, status.value)
        }
    }
}

/**
 * An interceptor's refusal to let the request through: the relay answers it as a proxy failure,
 * `peashoot_error` body and all, so it has no stream, no frames, and nothing to record. Replay's
 * strict miss is the one v1 refusal.
 */
class Refusal(override val status: Int, val error: String, val fields: Map<String, String>) :
    FrameSource {
    override val headers: Headers = Headers.Empty

    /** Never collected: the relay writes the body itself. */
    override fun frames(): Flow<Frame> = emptyFlow()
}

/**
 * The Netty call under the routing wrappers, or null for another engine. The relay runs inside a
 * route handler, so the call it is given is a [RoutingCall] over the engine's own; unwrapping is
 * the only way to the channel. A null here would silently cost every departure the write path
 * cannot hear, so the seam test that leaves before the first byte is what holds this honest.
 */
private tailrec fun ApplicationCall.engineCall(): NettyApplicationCall? =
    when (this) {
        is NettyApplicationCall -> this
        is RoutingCall -> pipelineCall.engineCall()
        is RoutingPipelineCall -> engineCall.engineCall()
        else -> null
    }

/**
 * Whether the client's connection is gone, as the engine sees it. Netty closes the channel the
 * moment the client goes and discards every later write to it without an error, so a write that
 * fails is the wrong thing to wait for: it never comes for a response written in one burst, and
 * never for a client that left before the first byte (#51). Asking the channel costs a volatile
 * read, so the writer can ask every time round its wait. Another engine leaves the channel null and
 * falls back to hearing the departure from the failing write, as before. The control API's event
 * feed asks it too: a feed that only ever writes would otherwise outlive its reader for good.
 */
internal fun ApplicationCall.clientGone(): () -> Boolean {
    val channel = engineCall()?.context?.channel()
    return { channel?.isOpen == false }
}

/** What a silent stream sends the client, so a byte-counting watchdog is not tripped by us. */
internal const val KEEP_ALIVE = ": keep-alive\n\n"

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
    /**
     * Whether the engine says the client's connection is gone; asked by the writer, never blocks.
     */
    private val clientGone: () -> Boolean,
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
            complete()
        }
    }

    /**
     * The end, once: from the drive's own finally when it ran, or from the call when the drive was
     * launched into a scope already stopping and never did. Under the lock, so it never overlaps a
     * detach; never cancelled, so a cancelled drive still ends what it began.
     */
    suspend fun complete() =
        withContext(NonCancellable) {
            lock.withLock {
                if (!completed) {
                    completed = true
                    interceptors.complete(exchange, status)
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
                    // Asked before every wait, so a departure needs no write to fail and no
                    // silence to fall. Detaching rather than breaking out keeps the one exit this
                    // loop has ever had: detach frees the drive and closes the hand-off, so the
                    // next wait reports it closed and the response ends the way a finished one
                    // does. Leaving early instead would strand a drive parked in send.
                    if (clientGone()) detach()
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
            if (!drained) detach()
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
     * That leaves two accepted windows, one either side. A write of the final frame that fails
     * after the drive has finished collecting is stored as a clean completion, with the flag unset.
     * And now that the engine's channel close is heard without a write, a client that leaves on the
     * response's last frame, while the drive still waits for the upstream body to end, is flagged
     * as gone: mid-stream is the drive's end, not the last frame's, and nothing here can tell the
     * two departures apart.
     *
     * Never cancelled, for the reason [complete] is not: this runs from the call or from the
     * writer, and a cancellation landing between two interceptors would leave half the chain never
     * hearing the departure at all.
     */
    suspend fun detach() =
        withContext(NonCancellable) {
            lock.withLock {
                if (!detached && !completed) {
                    detached = true
                    // Closed, never cancelled: a cancel would resume the drive's parked send with
                    // a CancellationException, which no catch in a flow may swallow, and the
                    // recording would end there. Closing is gentler but on its own does not free a
                    // sender already parked, only a receive does, so the one it holds is taken
                    // here: after this nothing receives again, and the drive would wait for ever.
                    handoff.close()
                    while (handoff.tryReceive().isSuccess) continue
                    // Both under this lock, so the chain never reads a flag without its bytes.
                    exchange.clientBytes = sent
                    exchange.clientDisconnected = true
                    interceptors.forEach { it.onClientGone(exchange) }
                }
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
