package dev.peashoot.proxy

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.utils.io.writeStringUtf8
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking

/**
 * The provider on the far side of the seam: replays frames with test-controlled timing, cuts the
 * connection mid-stream, and answers with any status and body.
 */
class FakeUpstream : AutoCloseable {
    class Received(
        val method: String,
        val uri: String,
        val headers: Map<String, List<String>>,
        val body: String,
    )

    /**
     * [frames] streams each chunk, calling [beforeFrame] with its index first so a test can hold
     * one back or delay it; when empty, [body] is sent whole. [cutAfterFrame] closes the socket
     * once that many frames are on the wire.
     */
    class Reply(
        val status: Int = 200,
        val contentType: ContentType = ContentType.Application.Json,
        val body: String = "{}",
        val headers: Map<String, String> = emptyMap(),
        val frames: List<String> = emptyList(),
        val beforeFrame: suspend (index: Int) -> Unit = {},
        val cutAfterFrame: Int? = null,
    )

    /** Handlers append from Netty threads while the test thread reads. */
    val received = CopyOnWriteArrayList<Received>()
    var reply: (Received) -> Reply = { Reply() }

    // The engine call is intercepted directly, not through routing, so a cut can reach the socket.
    private val server: EmbeddedServer<*, *> =
        embeddedServer(Netty, port = 0, host = "127.0.0.1") {
                intercept(ApplicationCallPipeline.Call) {
                    val req =
                        Received(
                            call.request.httpMethod.value,
                            call.request.uri,
                            call.request.headers.entries().associate { it.key to it.value },
                            call.receiveText(),
                        )
                    received += req
                    val r = reply(req)
                    r.headers.forEach { (name, value) -> call.response.headers.append(name, value) }
                    if (r.frames.isEmpty()) {
                        call.respondText(r.body, r.contentType, HttpStatusCode.fromValue(r.status))
                    } else {
                        val socket = (call as NettyApplicationCall).context.channel()
                        call.respondBytesWriter(r.contentType, HttpStatusCode.fromValue(r.status)) {
                            r.frames.forEachIndexed { index, frame ->
                                if (index == r.cutAfterFrame) {
                                    // Netty would finish the chunked body on an exception, so
                                    // close the socket itself: the client sees EOF before the
                                    // final chunk.
                                    socket.close()
                                    return@respondBytesWriter
                                }
                                r.beforeFrame(index)
                                writeStringUtf8(frame)
                                flush()
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
