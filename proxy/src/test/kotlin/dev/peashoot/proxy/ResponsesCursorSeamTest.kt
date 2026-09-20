package dev.peashoot.proxy

import dev.peashoot.core.FrameParser
import dev.peashoot.core.Mode
import dev.peashoot.core.Route
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * `GET /v1/responses/{id}?stream=true&starting_after=N` — the resume cursor, which in #25 is
 * relayed, recorded and replayed like any other exchange and served from no buffer of ours. Its own
 * class rather than another case in [ResponsesSeamTest], which is close enough to detekt's
 * `LargeClass` ceiling to break on the next test added to it, and because #27's buffer work belongs
 * here too: this is the file that will grow when the proxy starts answering a cursor itself.
 */
class ResponsesCursorSeamTest {
    private fun home(): Path = Files.createTempDirectory("peashoot-home")

    private fun config(upstream: FakeUpstream, mode: Mode = Mode.RECORD) =
        ProxyConfig(
            port = 0,
            anthropicUpstream = DEAD_UPSTREAM,
            openaiUpstream = upstream.url,
            routes = mapOf(DEFAULT_ROUTE to Route(mode)),
        )

    private suspend fun get(proxy: ProxyServer): HttpResponse =
        HttpClient(CIO).use { client -> client.get("${proxy.url}$CURSOR") }

    private suspend fun awaitRecordings(store: Store, count: Int) =
        withTimeout(TIMEOUT_MS) { while (store.list().size < count) delay(POLL_MS) }

    @Test
    fun `a resumed stream relays, records, and replays byte-equal with zero upstream calls`() =
        runBlocking {
            val whole =
                FrameParser.parse(
                    checkNotNull(javaClass.getResourceAsStream("/openai-responses/$FIXTURE")) {
                            FIXTURE
                        }
                        .readBytes()
                )
            // What `starting_after=3` leaves: the fixture numbers its events 0..11, one per frame,
            // so dropping four means the first frame the client sees carries sequence_number 4.
            val resumed = whole.drop(SKIPPED)
            val expected = resumed.joinToString("") { it.raw }
            var live = ""
            var asked = ""
            Store(home()).use { store ->
                FakeUpstream().use { upstream ->
                    upstream.reply = {
                        FakeUpstream.Reply(
                            contentType = ContentType.Text.EventStream,
                            frames = resumed.map { it.raw },
                        )
                    }
                    val record = config(upstream)
                    ProxyServer(record, listOf(Recorder(store))).use { proxy ->
                        live = get(proxy).bodyAsText()
                    }
                    awaitRecordings(store, 1)
                    asked = upstream.received.single().uri
                    upstream.received.clear()

                    val replay = config(upstream, Mode.REPLAY)
                    ProxyServer(replay, listOf(Replay(store, replay), Recorder(store))).use { proxy
                        ->
                        val response = get(proxy)
                        assertEquals(200, response.status.value)
                        assertEquals(live, response.bodyAsText(), "the replay is the recording")
                        assertEquals(
                            ContentType.Text.EventStream.toString(),
                            response.headers[HttpHeaders.ContentType],
                            "a replayed resume is still a stream, not a JSON body",
                        )
                    }
                    assertEquals(
                        0,
                        upstream.received.size,
                        "the resumed stream replayed from the store, cursor and all",
                    )
                }
                assertEquals(1, store.list().size, "a replay hit is never re-recorded")
            }
            assertEquals(CURSOR, asked, "the query string reached the upstream untouched")
            // Byte-equal to the frames the upstream sent, `event:` lines and blank separators
            // included: a resumed stream is recorded and served like any other response.
            assertContentEquals(expected.toByteArray(), live.toByteArray())
            assertTrue(
                resumed.first().raw.contains("\"sequence_number\":$FIRST_RESUMED"),
                "a resume starts at the event after the cursor: ${resumed.first().raw.take(80)}",
            )
        }

    private companion object {
        const val FIXTURE = "stream-with-function-call.sse"
        const val SKIPPED = 4
        const val FIRST_RESUMED = 4
        const val CURSOR = "/v1/responses/resp_REDACTED?stream=true&starting_after=3"
        const val DEAD_UPSTREAM = "http://127.0.0.1:1"
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 20L
    }
}
