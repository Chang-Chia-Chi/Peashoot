package dev.peashoot.proxy

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.readLine
import io.ktor.utils.io.readRemaining
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.io.readString

class RelayTest {
    @Test
    fun `relays a messages request to the upstream and returns its response unchanged`() =
        runBlocking {
            FakeUpstream().use { upstream ->
                upstream.reply = {
                    FakeUpstream.Reply(body = """{"id":"msg_1","type":"message"}""")
                }
                ProxyServer(ProxyConfig(port = 0, anthropicUpstream = upstream.url)).use { proxy ->
                    val response =
                        HttpClient(CIO).post("${proxy.url}/v1/messages") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"model":"claude","messages":[]}""")
                        }

                    assertEquals(200, response.status.value)
                    assertEquals("""{"id":"msg_1","type":"message"}""", response.bodyAsText())
                    assertEquals(
                        "application/json",
                        response.contentType()?.withoutParameters().toString(),
                    )
                    assertEquals(
                        """{"model":"claude","messages":[]}""",
                        upstream.received.single().body,
                    )
                    assertEquals("POST", upstream.received.single().method)
                    assertEquals("/v1/messages", upstream.received.single().uri)
                }
            }
        }

    @Test
    fun `forwards headers verbatim both ways, except hop-by-hop, host, and accept-encoding`() =
        runBlocking {
            FakeUpstream().use { upstream ->
                upstream.reply = {
                    FakeUpstream.Reply(
                        headers =
                            mapOf(
                                "anthropic-ratelimit-tokens-remaining" to "1000",
                                "x-codex-turn-state" to "sticky",
                            )
                    )
                }
                ProxyServer(ProxyConfig(port = 0, anthropicUpstream = upstream.url)).use { proxy ->
                    val response =
                        HttpClient(CIO).post("${proxy.url}/v1/messages") {
                            header("anthropic-beta", "oauth-2025-04-20")
                            header("x-api-key", "sk-ant-test")
                            header("authorization", "Bearer token")
                            header("accept-encoding", "gzip")
                            setBody("{}")
                        }

                    val seen = upstream.received.single().headers.mapKeys { it.key.lowercase() }
                    assertEquals(listOf("oauth-2025-04-20"), seen["anthropic-beta"])
                    assertEquals(listOf("sk-ant-test"), seen["x-api-key"])
                    assertEquals(listOf("Bearer token"), seen["authorization"])
                    assertNull(
                        seen["accept-encoding"],
                        "accept-encoding must be stripped so streams stay uncompressed",
                    )
                    assertEquals(
                        listOf(upstream.url.removePrefix("http://")),
                        seen["host"],
                        "host must be the upstream's",
                    )
                    assertEquals("1000", response.headers["anthropic-ratelimit-tokens-remaining"])
                    assertEquals("sticky", response.headers["x-codex-turn-state"])
                }
            }
        }

    @Test
    fun `streams each upstream chunk to the client as it arrives, never buffering the response`() =
        runBlocking {
            val first = "event: message_start\ndata: {}\n\n"
            val last = "event: message_stop\ndata: {}\n\n"
            FakeUpstream().use { upstream ->
                upstream.reply = {
                    FakeUpstream.Reply(
                        contentType = ContentType.Text.EventStream,
                        frames = listOf(0L to first, 1500L to last),
                    )
                }
                ProxyServer(ProxyConfig(port = 0, anthropicUpstream = upstream.url)).use { proxy ->
                    HttpClient(CIO)
                        .preparePost("${proxy.url}/v1/messages") { setBody("{}") }
                        .execute { response ->
                            val channel = response.bodyAsChannel()
                            val startedAt = System.nanoTime()
                            val firstLine = channel.readLine()
                            val firstLineAfterMillis = (System.nanoTime() - startedAt) / 1_000_000

                            assertEquals("event: message_start", firstLine)
                            assertTrue(
                                firstLineAfterMillis < 1000,
                                "first frame took ${firstLineAfterMillis} ms; it was buffered",
                            )
                            assertEquals(
                                first.removePrefix("event: message_start\n") + last,
                                channel.readRemaining().readString(),
                            )
                        }
                }
            }
        }
}
