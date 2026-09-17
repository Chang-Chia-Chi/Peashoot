package dev.peashoot.proxy

import dev.peashoot.core.Mode
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.ByteArrayContent
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * The replay-demo workflow's cassette and request, under the rules that ship: a rule change that
 * would make the workflow's request miss fails here first, not on main.
 */
class CiReplayTest {
    private val example = Path.of("..", "examples", "ci-replay")

    private suspend fun post(proxy: ProxyServer, body: ByteArray): HttpResponse =
        HttpClient(CIO).use { client ->
            client.post("${proxy.url}/v1/messages") {
                // The headers the workflow's curl sends; the rest are not in the fingerprint.
                header("anthropic-version", "2023-06-01")
                setBody(ByteArrayContent(body, ContentType.Application.Json))
            }
        }

    @Test
    fun `the committed cassette replays for the committed request, and nothing else`() =
        runBlocking {
            val home = Files.createTempDirectory("peashoot-home")
            // The environment the workflow sets, read the way the proxy reads it.
            val config =
                loadConfig(
                    home,
                    mapOf(
                        "PEASHOOT_PORT" to "0",
                        "PEASHOOT_MODE" to "replay",
                        "PEASHOOT_STRICT" to "true",
                        "PEASHOOT_CASSETTE" to "${example.resolve("tool-use.jsonl")}",
                        "PEASHOOT_ANTHROPIC_UPSTREAM" to "http://127.0.0.1:9",
                    )::get,
                )
            assertEquals(
                Route(Mode.REPLAY, strict = true, cassette = "tool-use"),
                config.routes[DEFAULT_ROUTE],
            )
            val fixture =
                checkNotNull(
                        javaClass.getResourceAsStream(
                            "/anthropic-messages/stream-with-tool-use.sse"
                        )
                    )
                    .readBytes()
                    .decodeToString()
            Store(home).use { store ->
                importCassette(store, config, checkNotNull(config.cassetteFile))
                ProxyServer(config, listOf(Replay(store, config), Recorder(store))).use { proxy ->
                    val hit = post(proxy, example.resolve("request.json").readBytes())
                    assertEquals(200, hit.status.value)
                    assertEquals(fixture, hit.bodyAsText())
                    assertEquals(
                        409,
                        post(proxy, """{"model":"unrecorded"}""".encodeToByteArray()).status.value,
                    )
                }
            }
        }
}
