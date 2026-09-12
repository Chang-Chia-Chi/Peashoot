package dev.peashoot.proxy

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/** The provider on the far side of the seam. Minimal for #4; #5 adds timing, drops, and errors. */
class FakeUpstream : AutoCloseable {
    class Received(
        val method: String,
        val uri: String,
        val headers: Map<String, List<String>>,
        val body: String,
    )

    /** [frames] streams each chunk after its delay; when empty, [body] is sent whole. */
    class Reply(
        val status: Int = 200,
        val contentType: ContentType = ContentType.Application.Json,
        val body: String = "{}",
        val headers: Map<String, String> = emptyMap(),
        val frames: List<Pair<Long, String>> = emptyList(),
    )

    val received = mutableListOf<Received>()
    var reply: (Received) -> Reply = { Reply() }

    private val server: EmbeddedServer<*, *> =
        embeddedServer(CIO, port = 0, host = "127.0.0.1") {
                routing {
                    route("{...}") {
                        handle {
                            val req =
                                Received(
                                    call.request.httpMethod.value,
                                    call.request.uri,
                                    call.request.headers.entries().associate { it.key to it.value },
                                    call.receiveText(),
                                )
                            received += req
                            val r = reply(req)
                            r.headers.forEach { (name, value) ->
                                call.response.headers.append(name, value)
                            }
                            if (r.frames.isEmpty()) {
                                call.respondText(
                                    r.body,
                                    r.contentType,
                                    HttpStatusCode.fromValue(r.status),
                                )
                            } else {
                                call.respondBytesWriter(
                                    r.contentType,
                                    HttpStatusCode.fromValue(r.status),
                                ) {
                                    for ((delayMillis, frame) in r.frames) {
                                        delay(delayMillis)
                                        writeStringUtf8(frame)
                                        flush()
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .start(wait = false)

    val url: String = runBlocking {
        "http://127.0.0.1:${server.engine.resolvedConnectors().first().port}"
    }

    override fun close() = server.stop(100, 500)
}
