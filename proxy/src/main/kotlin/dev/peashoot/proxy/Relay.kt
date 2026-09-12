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

/** Engine-owned response headers; content-type travels as the response's own content type. */
private val notForwardedToClient = hopByHop + setOf("content-length", "content-type")

/** Forward one request to the upstream and stream its response back as it arrives. */
suspend fun relay(
    call: ApplicationCall,
    upstreamBase: String,
    upstream: HttpClient,
    dumpFrames: Path? = null,
) {
    val body = call.receive<ByteArray>()
    val requestContentType = call.request.header(HttpHeaders.ContentType)?.let(ContentType::parse)

    upstream
        .prepareRequest(upstreamBase.trimEnd('/') + call.request.uri) {
            method = call.request.httpMethod
            call.request.headers.forEach { name, values ->
                if (name.lowercase() !in notForwardedToUpstream)
                    values.forEach { headers.append(name, it) }
            }
            if (body.isNotEmpty()) setBody(ByteArrayContent(body, requestContentType))
        }
        .execute { response ->
            val forwarded = Headers.build {
                response.headers.forEach { name, values ->
                    if (name.lowercase() !in notForwardedToClient)
                        values.forEach { append(name, it) }
                }
            }
            call.respond(
                object : OutgoingContent.WriteChannelContent() {
                    override val status = response.status
                    override val headers = forwarded
                    override val contentType = response.contentType()

                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        val source = response.bodyAsChannel()
                        val buffer = ByteArray(8 * 1024)
                        val dump = dumpFrames?.let {
                            FrameDump.open(
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
                                    dump?.write(buffer, 0, read)
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
