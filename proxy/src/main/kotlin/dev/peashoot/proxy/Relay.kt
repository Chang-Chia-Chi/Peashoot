package dev.peashoot.proxy

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import java.nio.file.Path

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
 * Engine-owned response headers; content-type and content-length travel as the response's own
 * properties.
 */
private val notForwardedToClient = hopByHop + setOf("content-length", "content-type")

private fun Headers.without(excluded: Set<String>): Headers = Headers.build {
    this@without.forEach { name, values ->
        if (name.lowercase() !in excluded) values.forEach { append(name, it) }
    }
}

/** Forward one request to the upstream and stream its response back as it arrives. */
suspend fun relay(
    call: ApplicationCall,
    upstreamBase: String,
    upstream: HttpClient,
    dumpFrames: Path?,
) {
    val body = call.receive<ByteArray>()
    val requestContentType = call.request.header(HttpHeaders.ContentType)?.let(ContentType::parse)

    upstream
        .prepareRequest(upstreamBase.trimEnd('/') + call.request.uri) {
            method = call.request.httpMethod
            headers.appendAll(call.request.headers.without(notForwardedToUpstream))
            if (body.isNotEmpty() || requestContentType != null)
                setBody(ByteArrayContent(body, requestContentType))
        }
        .execute { response ->
            call.respond(
                object : OutgoingContent.WriteChannelContent() {
                    override val status = response.status
                    override val headers = response.headers.without(notForwardedToClient)
                    override val contentType = response.contentType()
                    override val contentLength = response.contentLength()

                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        val source = response.bodyAsChannel()
                        val buffer = ByteArray(8 * 1024)
                        val dump = dumpFrames?.let {
                            FrameDump(
                                it,
                                call.request.httpMethod.value,
                                call.request.uri,
                                response.status.value,
                            )
                        }
                        try {
                            while (true) {
                                val read = source.readAvailable(buffer, 0, buffer.size)
                                if (read == -1) break
                                if (read > 0) {
                                    channel.writeFully(buffer, 0, read)
                                    channel.flush() // each chunk reaches the client as soon as it
                                    // exists
                                    dump?.append(buffer, 0, read)
                                }
                            }
                        } finally {
                            dump?.close()
                        }
                    }
                }
            )
        }
}
