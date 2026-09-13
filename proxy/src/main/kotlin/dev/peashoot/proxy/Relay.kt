package dev.peashoot.proxy

import dev.peashoot.core.Decision
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
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
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
 * One request through the chain. The first interceptor to respond is the source; otherwise the
 * upstream is, and a failure to reach it is a proxy error, never a provider one.
 */
suspend fun relay(
    call: ApplicationCall,
    config: ProxyConfig,
    upstream: HttpClient,
    interceptors: List<Interceptor>,
) {
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
    // Every interceptor hears the request; the first source offered wins.
    val offered =
        interceptors
            .map { it.onRequest(exchange) }
            .firstNotNullOfOrNull { (it as? Decision.Respond)?.source }
    if (offered != null) {
        respondFrom(call, exchange, offered, interceptors)
        return
    }

    val requestContentType = call.request.header(HttpHeaders.ContentType)?.let(ContentType::parse)
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
                val dump =
                    config.dumpFrames?.let {
                        FrameDump(
                            it,
                            exchange.request.method,
                            exchange.request.path,
                            response.status.value,
                        )
                    }
                respondFrom(call, exchange, UpstreamSource(response, dump), interceptors)
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
        respondProxyFailure(call, exchange, interceptors, failure)
    }
}

private suspend fun respondFrom(
    call: ApplicationCall,
    exchange: Exchange,
    source: FrameSource,
    interceptors: List<Interceptor>,
) {
    exchange.status = source.status
    exchange.responseHeaders = source.headers
    val frames =
        interceptors.fold(source.frames()) { acc, interceptor ->
            interceptor.onFrames(exchange, acc)
        }
    call.respond(
        object : OutgoingContent.WriteChannelContent() {
            override val status = HttpStatusCode.fromValue(source.status)
            override val headers = source.headers.without(notForwardedToClient)
            override val contentType =
                source.headers[HttpHeaders.ContentType]?.let(ContentType::parse)

            override suspend fun writeTo(channel: ByteWriteChannel) =
                writeFrames(channel, exchange, frames, interceptors)
        }
    )
}

/**
 * The client sink: each frame goes out as soon as it exists, then the chain hears the outcome. A
 * source or interceptor that fails mid-stream ends the client's response where it is and still
 * completes the exchange; the missing terminal frame says what happened.
 */
private suspend fun writeFrames(
    channel: ByteWriteChannel,
    exchange: Exchange,
    frames: Flow<Frame>,
    interceptors: List<Interceptor>,
) {
    var clientGone = false
    try {
        frames.collect { frame ->
            channel.writeStringUtf8(frame.raw)
            channel.flush()
        }
    } catch (e: CancellationException) {
        // The engine cancels the writer when the client goes away. #9 verifies and extends this.
        clientGone = true
        exchange.clientDisconnected = true
        withContext(NonCancellable) { interceptors.forEach { it.onClientGone(exchange) } }
        throw e
    } finally {
        if (!clientGone) complete(exchange, interceptors)
    }
}

private suspend fun complete(exchange: Exchange, interceptors: List<Interceptor>) {
    val outcome = Outcome(checkNotNull(exchange.status))
    interceptors.forEach { it.onComplete(exchange, outcome) }
}

/**
 * Never imitates a provider's error shape: a client's retry logic must not mistake us for one. The
 * exchange still completes, with the 502, so nothing that started goes unfinished.
 */
private suspend fun respondProxyFailure(
    call: ApplicationCall,
    exchange: Exchange,
    interceptors: List<Interceptor>,
    cause: Exception,
) {
    log.warn(
        "upstream unreachable for {} {}: {}",
        exchange.request.method,
        exchange.request.path,
        cause.toString(),
    )
    exchange.status = HttpStatusCode.BadGateway.value
    call.respondText(
        """{"type":"peashoot_error","error":"upstream_unreachable","detail":${JsonPrimitive(cause.toString())}}""",
        ContentType.Application.Json,
        HttpStatusCode.BadGateway,
    )
    complete(exchange, interceptors)
}

/** The terminal source: the provider's response, parsed into frames as its bytes arrive. */
private class UpstreamSource(private val response: HttpResponse, private val dump: FrameDump?) :
    FrameSource {
    override val status = response.status.value
    override val headers = response.headers
    private var collected = false

    override fun frames(): Flow<Frame> = flow {
        // A second collection would read an exhausted body and silently yield nothing.
        check(!collected) { "the upstream body can be collected once" }
        collected = true
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
                    parser.feed(buffer.copyOf(read), offset).forEach { emit(it) }
                }
            }
            parser.end(start.elapsedNow().inWholeMilliseconds)?.let { emit(it) }
        } finally {
            dump?.close()
        }
    }
}
