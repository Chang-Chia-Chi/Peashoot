package dev.peashoot.proxy

import dev.peashoot.core.Exchange
import dev.peashoot.core.FrameParser
import dev.peashoot.core.FrameSource
import dev.peashoot.core.Interceptor
import dev.peashoot.core.Mode
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A replay route answers from the store: what it serves, in what order, and what a miss does. */
class ReplayTest {
    private val frames =
        FrameParser.parse(
                checkNotNull(
                        javaClass.getResourceAsStream(
                            "/anthropic-messages/stream-with-tool-use.sse"
                        )
                    )
                    .readBytes()
            )
            .map { it.raw }

    private fun home(): Path = Files.createTempDirectory("peashoot-home")

    private fun config(upstream: FakeUpstream, mode: Mode, strict: Boolean = false) =
        ProxyConfig(
            port = 0,
            anthropicUpstream = upstream.url,
            routes = mapOf(DEFAULT_ROUTE to Route(mode, strict)),
        )

    private fun streamReply(frames: List<String>, beforeFrame: suspend (Int) -> Unit = {}) =
        FakeUpstream.Reply(
            contentType = ContentType.Text.EventStream,
            frames = frames,
            headers = mapOf("anthropic-ratelimit-tokens-remaining" to "9"),
            beforeFrame = beforeFrame,
        )

    private suspend fun post(proxy: ProxyServer, body: String = REQUEST): HttpResponse =
        HttpClient(CIO).use { it.post("${proxy.url}/v1/messages") { setBody(body) } }

    /** The client can end its response before the recorder's write lands, so wait for the row. */
    private suspend fun awaitRecordings(store: Store, count: Int) =
        withTimeout(5_000) { while (store.list().size < count) delay(20) }

    /** Sends [times] identical requests through a record route and waits until all are stored. */
    private suspend fun record(store: Store, upstream: FakeUpstream, times: Int = 1) {
        val config = config(upstream, Mode.RECORD)
        ProxyServer(config, listOf(Replay(store, config), Recorder(store))).use { proxy ->
            repeat(times) { post(proxy).bodyAsText() }
        }
        awaitRecordings(store, times)
    }

    @Test
    fun `a replay hit serves the recorded status, headers, and frames with zero upstream calls`() =
        runBlocking {
            val home = home()
            Store(home).use { store ->
                FakeUpstream().use { upstream ->
                    upstream.reply = { streamReply(frames) }
                    record(store, upstream)
                    upstream.received.clear()

                    val config = config(upstream, Mode.REPLAY)
                    val chain =
                        listOf(
                            Replay(store, config),
                            Recorder(store),
                            Deriver(store, home.resolve(EVENTS_FILE)),
                        )
                    ProxyServer(config, chain).use { proxy ->
                        val response = post(proxy)
                        assertEquals(200, response.status.value)
                        assertEquals(frames.joinToString(""), response.bodyAsText())
                        assertEquals(
                            ContentType.Text.EventStream,
                            ContentType.parse(response.headers[HttpHeaders.ContentType]!!)
                                .withoutParameters(),
                        )
                        assertEquals("9", response.headers["anthropic-ratelimit-tokens-remaining"])
                    }
                    assertEquals(0, upstream.received.size, "a hit never reaches the upstream")
                }
                assertEquals(1, store.list().size, "a replay hit is never re-recorded")
            }
            val completed =
                home
                    .resolve(EVENTS_FILE)
                    .readLines()
                    .map { Json.parseToJsonElement(it).jsonObject }
                    .single { it["event"]?.jsonPrimitive?.content == "exchange.completed" }
            assertTrue(completed.getValue("replayHit").jsonPrimitive.boolean)
            assertEquals("0.0", completed.getValue("costUsd").jsonPrimitive.content)
        }

    @Test
    fun `recorded cadence waits out each frame's recorded offset`() = runBlocking {
        Store(home()).use { store ->
            FakeUpstream().use { upstream ->
                upstream.reply = {
                    streamReply(frames) { index -> if (index == frames.lastIndex) delay(GAP_MS) }
                }
                record(store, upstream)
                val offsets = store.list().single().frames.map { it.offsetMillis }
                assertTrue(offsets.last() >= GAP_MS, "the recording holds the gap: $offsets")

                val config = config(upstream, Mode.REPLAY).copy(replayCadence = Cadence.RECORDED)
                ProxyServer(config, listOf(Replay(store, config))).use { proxy ->
                    val start = TimeSource.Monotonic.markNow()
                    assertEquals(frames.joinToString(""), post(proxy).bodyAsText())
                    val elapsed = start.elapsedNow().inWholeMilliseconds
                    assertTrue(elapsed >= offsets.last(), "took $elapsed ms for $offsets")
                    assertTrue(elapsed < offsets.last() + SLACK_MS, "took $elapsed ms")
                }
            }
        }
    }

    @Test
    fun `identical recordings replay in recorded order, and the last repeats once exhausted`() =
        runBlocking {
            Store(home()).use { store ->
                FakeUpstream().use { upstream ->
                    var served = 0
                    upstream.reply = {
                        served++
                        FakeUpstream.Reply(body = """{"n":$served}""")
                    }
                    record(store, upstream, times = 3)

                    val config = config(upstream, Mode.REPLAY)
                    ProxyServer(config, listOf(Replay(store, config))).use { proxy ->
                        assertEquals(
                            listOf(1, 2, 3, 3).map { """{"n":$it}""" },
                            List(4) { post(proxy).bodyAsText() },
                        )
                    }
                    // The cursor lives in the interceptor, so a new proxy starts over.
                    ProxyServer(config, listOf(Replay(store, config))).use { proxy ->
                        assertEquals("""{"n":1}""", post(proxy).bodyAsText())
                    }
                    val latest = config.copy(repeatPolicy = RepeatPolicy.LATEST)
                    ProxyServer(latest, listOf(Replay(store, latest))).use { proxy ->
                        assertEquals(
                            List(2) { """{"n":3}""" },
                            List(2) { post(proxy).bodyAsText() },
                        )
                    }
                    assertEquals(3, upstream.received.size)
                }
            }
        }

    @Test
    fun `a strict miss returns the documented 409 and never calls the upstream`() = runBlocking {
        Store(home()).use { store ->
            FakeUpstream().use { upstream ->
                val config = config(upstream, Mode.REPLAY, strict = true)
                val seen =
                    object : Interceptor {
                        var fingerprint: String? = null

                        override suspend fun onRequest(exchange: Exchange): FrameSource? {
                            fingerprint = exchange.fingerprint
                            return null
                        }
                    }
                ProxyServer(config, listOf(Replay(store, config), Recorder(store), seen)).use {
                    proxy ->
                    val response = post(proxy)
                    assertEquals(409, response.status.value)
                    assertEquals(
                        ContentType.Application.Json,
                        ContentType.parse(response.headers[HttpHeaders.ContentType]!!)
                            .withoutParameters(),
                    )
                    assertEquals(
                        """{"type":"peashoot_error","error":"replay_miss",""" +
                            """"fingerprint":"${seen.fingerprint}","route":"default"}""",
                        response.bodyAsText(),
                    )
                }
                assertEquals(0, upstream.received.size)
                assertEquals(emptyList(), store.list(), "a refusal is never recorded")
            }
        }
    }

    @Test
    fun `a lenient miss calls the upstream once, records it, and the next request hits`() =
        runBlocking {
            Store(home()).use { store ->
                FakeUpstream().use { upstream ->
                    upstream.reply = { streamReply(frames) }
                    val config = config(upstream, Mode.REPLAY)
                    ProxyServer(config, listOf(Replay(store, config), Recorder(store))).use { proxy
                        ->
                        assertEquals(frames.joinToString(""), post(proxy).bodyAsText())
                        awaitRecordings(store, 1)
                        assertEquals(frames.joinToString(""), post(proxy).bodyAsText())
                    }
                    assertEquals(1, upstream.received.size)
                }
                assertEquals(Mode.REPLAY, store.list().single().exchange.mode)
            }
        }

    private companion object {
        const val REQUEST = """{"model":"claude-sonnet-4-5","messages":[]}"""
        const val GAP_MS = 150L
        /** Generous: a loaded CI machine is slow, never early. */
        const val SLACK_MS = 3_000L
    }
}
