package dev.peashoot.proxy

import dev.peashoot.core.FrameParser
import dev.peashoot.core.Mode
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.walk
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** What a record or passthrough route leaves in the data directory, seen through the store. */
class RecorderTest {
    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/anthropic-messages/$name")) { name }
            .readBytes()

    private fun home(): Path = Files.createTempDirectory("peashoot-home")

    private fun streamReply(frames: List<String>) =
        FakeUpstream.Reply(
            contentType = ContentType.Text.EventStream,
            frames = frames,
            headers = mapOf("anthropic-ratelimit-tokens-remaining" to "9"),
        )

    @Test
    fun `record mode persists a streamed exchange with its frames and offsets, minus secrets`() =
        runBlocking {
            val fixture = fixture("stream-with-tool-use.sse")
            val home = home()
            Store(home).use { store ->
                FakeUpstream().use { upstream ->
                    upstream.reply = { streamReply(FrameParser.parse(fixture).map { it.raw }) }
                    val config = ProxyConfig(port = 0, anthropicUpstream = upstream.url)
                    ProxyServer(config, listOf(Recorder(store))).use { proxy ->
                        HttpClient(CIO)
                            .post("${proxy.url}/v1/messages") {
                                header("x-api-key", "sk-ant-SECRET")
                                header("anthropic-beta", "kept")
                                setBody("""{"model":"claude"}""")
                            }
                            .bodyAsText()
                    }
                }

                val recorded = store.list().single()
                val exchange = recorded.exchange
                assertEquals(
                    "POST /v1/messages",
                    "${exchange.request.method} ${exchange.request.path}",
                )
                assertEquals("""{"model":"claude"}""", exchange.request.body.decodeToString())
                assertEquals("kept", exchange.request.headers["anthropic-beta"])
                assertNull(exchange.request.headers["x-api-key"])
                assertEquals(Mode.RECORD, exchange.mode)
                assertEquals(200, exchange.status)
                assertEquals("9", exchange.responseHeaders["anthropic-ratelimit-tokens-remaining"])
                assertEquals(
                    FrameParser.parse(fixture).map { it.raw },
                    recorded.frames.map { it.raw },
                )
                val offsets = recorded.frames.map { it.offsetMillis }
                assertEquals(offsets.sorted(), offsets, "offsets never go backwards")
                assertTrue(offsets.all { it >= 0 })
                assertEquals(exchange.id, store.get(exchange.id)?.exchange?.id)
            }
            // Nothing under the data directory, database or spill file, holds the secret.
            val everything = home.walk().filter { Files.isRegularFile(it) }.map { it.readBytes() }
            assertFalse(everything.any { String(it, Charsets.ISO_8859_1).contains("SECRET") })
        }

    @Test
    fun `bodies over 64 KB are spilled to content-addressed files and read back identically`() =
        runBlocking {
            val bigBody = (0 until 100_000).joinToString("") { (it % 10).toString() }
            val bigFrames =
                List(2_000) {
                    "event: content_block_delta\ndata: {\"index\":$it,\"text\":\"x\"}\n\n"
                }
            val home = home()
            Store(home).use { store ->
                FakeUpstream().use { upstream ->
                    upstream.reply = { streamReply(bigFrames) }
                    val config = ProxyConfig(port = 0, anthropicUpstream = upstream.url)
                    ProxyServer(config, listOf(Recorder(store))).use { proxy ->
                        HttpClient(CIO)
                            .post("${proxy.url}/v1/messages") { setBody(bigBody) }
                            .bodyAsText()
                    }
                }

                val recorded = store.list().single()
                assertEquals(bigBody, recorded.exchange.request.body.decodeToString())
                assertEquals(bigFrames, recorded.frames.map { it.raw })
            }
            val spilled = home.resolve("bodies").listDirectoryEntries().map { it.name }.sorted()
            val sha256 = MessageDigest.getInstance("SHA-256").digest(bigBody.toByteArray())
            val bodyName = sha256.joinToString("") { "%02x".format(it) }
            assertEquals(2, spilled.size, "the body and the frames both spill: $spilled")
            assertTrue(bodyName in spilled, "spill files are named by their SHA-256")
            assertContentEquals(
                bigBody.toByteArray(),
                home.resolve("bodies").resolve(bodyName).readBytes(),
            )
        }

    @Test
    fun `a passthrough route persists nothing`() = runBlocking {
        val fixture = fixture("stream-with-tool-use.sse")
        val home = home()
        Store(home).use { store ->
            FakeUpstream().use { upstream ->
                upstream.reply = { streamReply(FrameParser.parse(fixture).map { it.raw }) }
                val config =
                    ProxyConfig(
                        port = 0,
                        anthropicUpstream = upstream.url,
                        routes = mapOf(DEFAULT_ROUTE to Mode.PASSTHROUGH),
                    )
                ProxyServer(config, listOf(Recorder(store))).use { proxy ->
                    val body =
                        HttpClient(CIO)
                            .post("${proxy.url}/v1/messages") { setBody("{}") }
                            .bodyAsText()
                    assertEquals(fixture.decodeToString(), body, "passthrough still relays")
                }
            }
            assertEquals(emptyList(), store.list())
        }
        assertEquals(emptyList(), home.resolve("bodies").listDirectoryEntries())
    }
}
