package dev.peashoot.proxy

import dev.peashoot.core.Exchange
import dev.peashoot.core.FrameParser
import dev.peashoot.core.Interceptor
import dev.peashoot.core.Outcome
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * What a silent upstream sends the client while it waits. The comment is for the client's watchdog
 * alone: it goes on the wire and nowhere else, and only a stream can carry one.
 */
class KeepAliveTest {
    private fun fixture(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/anthropic-messages/stream-with-tool-use.sse"))
            .readBytes()

    private fun home(): Path = Files.createTempDirectory("peashoot-home")

    /** The chain's end, so the store is read after the recorder has written. */
    private class Completion : Interceptor {
        val done = CompletableDeferred<Unit>()

        override suspend fun onComplete(exchange: Exchange, outcome: Outcome) {
            done.complete(Unit)
        }
    }

    /**
     * Relays [frames], stalling before the second one, and returns what the client read. The stall
     * is several ping intervals long, which is also the check that a long silence does not end the
     * client's read: the library reads the body to its end.
     */
    private fun stalledRelay(
        contentType: ContentType,
        frames: List<String>,
        stall: Long,
        verify: (body: String, recorded: Recorded) -> Unit,
    ) = runBlocking {
        val completion = Completion()
        val home = home()
        Store(home).use { store ->
            FakeUpstream().use { upstream ->
                upstream.reply = {
                    FakeUpstream.Reply(
                        contentType = contentType,
                        frames = frames,
                        beforeFrame = { index -> if (index == 1) delay(stall) },
                    )
                }
                val config =
                    ProxyConfig(
                        port = 0,
                        anthropicUpstream = upstream.url,
                        pingInterval = 100.milliseconds,
                    )
                ProxyServer(config, listOf(Recorder(store), completion)).use { proxy ->
                    val body =
                        HttpClient(CIO).use {
                            it.post("${proxy.url}/v1/messages") { setBody("{}") }.bodyAsText()
                        }
                    withTimeout(5_000) { completion.done.await() }
                    verify(body, store.list().single())
                }
            }
        }
    }

    @Test
    fun `comment lines fill an upstream stall at the interval, and the recording has none`() {
        val frames = FrameParser.parse(fixture()).map { it.raw }
        stalledRelay(ContentType.Text.EventStream, frames, stall = 350) { body, recorded ->
            assertEquals(frames.joinToString(""), body.replace(KEEP_ALIVE, ""))
            // Three at 100 ms over a 350 ms stall; the slack is for a loaded CI, the ceiling
            // catches a comment written on every turn of the loop.
            val comments = body.split(KEEP_ALIVE).size - 1
            assertTrue(comments in 2..6, "$comments comments over a 350 ms stall")
            assertTrue(KEEP_ALIVE in body.substringBefore(frames[1]), "the stall is before frame 1")
            assertFalse(KEEP_ALIVE in body.substringAfter(frames[1]), "nothing stalled after it")
            assertEquals(frames, recorded.frames.map { it.raw })
            assertFalse(recorded.exchange.clientDisconnected, "the client read to the end")
        }
    }

    @Test
    fun `a stalled non-streaming response gets no comment`() {
        val whole = """{"id":"msg_1"}"""
        val pieces = listOf("""{"id":""", """"msg_1"}""")
        stalledRelay(ContentType.Application.Json, pieces, stall = 300) { body, recorded ->
            assertEquals(whole, body)
            assertEquals(whole, recorded.frames.single().raw)
        }
    }

    private companion object {
        /** Spelled out here, not shared with the relay: this is the wire format under test. */
        const val KEEP_ALIVE = ": keep-alive\n\n"
    }
}
