package dev.peashoot.proxy

import dev.peashoot.core.text
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readLine
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The control API on the proxy's own port: token, health, events, exchanges, sessions, routes. */
class ControlApiTest {
    private class Proxy(
        val home: Path,
        val store: Store,
        val upstream: FakeUpstream,
        val server: ProxyServer,
    ) {
        val token: String = home.resolve(TOKEN_FILE).readText()
        val base = "${server.url}/_peashoot/v1"
    }

    /** A proxy with the whole v1 chain and its control API, relaying to a fake upstream. */
    private fun withProxy(block: suspend Proxy.() -> Unit) = runBlocking {
        val home = Files.createTempDirectory("peashoot-home")
        Store(home).use { store ->
            FakeUpstream().use { upstream ->
                val config =
                    ProxyConfig(
                        port = 0,
                        anthropicUpstream = upstream.url,
                        pingInterval = PING.milliseconds,
                    )
                val control = ControlApi(store, home, config)
                val chain =
                    listOf(
                        Replay(store, config, control.routes),
                        Recorder(store),
                        Deriver(store, home.resolve(EVENTS_FILE), feed = control.feed),
                    )
                ProxyServer(config, chain, control).use { server ->
                    Proxy(home, store, upstream, server).block()
                }
            }
        }
    }

    /** No request timeout: a feed stays open for as long as a test reads it. */
    private val client = HttpClient(CIO) { engine { requestTimeout = 0 } }

    @AfterTest fun closeClient() = client.close()

    private suspend fun Proxy.call(
        method: HttpMethod,
        path: String,
        body: String? = null,
        token: String? = this.token,
    ): HttpResponse =
        client.request("$base$path") {
            this.method = method
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            body?.let { setBody(it) }
        }

    private suspend fun Proxy.json(path: String): JsonObject =
        call(HttpMethod.Get, path).let {
            assertEquals(200, it.status.value, it.bodyAsText())
            Json.parseToJsonElement(it.bodyAsText()).jsonObject
        }

    private suspend fun Proxy.relay(body: String, headers: Map<String, String> = emptyMap()) =
        client
            .post("${server.url}/v1/messages") {
                headers.forEach { (name, value) -> header(name, value) }
                setBody(body)
            }
            .let { it.status.value to it.bodyAsText() }

    private suspend fun Proxy.awaitRecordings(count: Int) =
        withTimeout(5_000) { while (store.list().size < count) delay(20) }

    private suspend fun Proxy.awaitEvents(count: Int) =
        withTimeout(5_000) { while (store.events().size < count) delay(20) }

    private suspend fun assertProblem(response: HttpResponse, status: Int) {
        val text = response.bodyAsText()
        assertEquals(status, response.status.value, text)
        assertEquals(
            ContentType.Application.ProblemJson,
            ContentType.parse(response.headers[HttpHeaders.ContentType]!!).withoutParameters(),
        )
        val problem = Json.parseToJsonElement(text).jsonObject
        assertEquals(setOf("type", "title", "detail", "status"), problem.keys, text)
        assertEquals(status, problem.getValue("status").jsonPrimitive.int)
    }

    @Test
    fun `a missing or wrong token gets a 401 problem on everything except health`() = withProxy {
        val guarded =
            listOf(
                HttpMethod.Get to "/events",
                HttpMethod.Get to "/exchanges",
                HttpMethod.Get to "/exchanges/01ABC",
                HttpMethod.Get to "/sessions",
                HttpMethod.Get to "/routes",
                HttpMethod.Put to "/routes/default",
            )
        guarded.forEach { (method, path) ->
            listOf(null, "wrong", "$token-and-more", "").forEach { token ->
                val body = """{"mode":"replay"}""".takeIf { method == HttpMethod.Put }
                assertProblem(call(method, path, body, token), 401)
            }
        }
        val raw = client.get("$base/routes") { header(HttpHeaders.Authorization, token) }
        assertProblem(raw, 401)

        val health = call(HttpMethod.Get, "/health", token = null)
        assertEquals(200, health.status.value)
        val body = Json.parseToJsonElement(health.bodyAsText()).jsonObject
        assertTrue("version" in body && "uptimeSeconds" in body, "$body")
        assertEquals(
            "record",
            body.getValue("routes").jsonObject.getValue("default").jsonObject["mode"]?.text(),
        )
        assertEquals(0, upstream.received.size, "a control call is never relayed")
        assertEquals(emptyMap(), store.events(), "nor derived")
    }

    @Test
    fun `unknown control paths are 404 problems, never relayed, recorded, or derived`() =
        withProxy {
            assertProblem(call(HttpMethod.Get, "/nothing"), 404)
            assertProblem(client.post("${server.url}/_peashoot/v2/messages"), 404)
            assertProblem(client.get("${server.url}/_peashoot"), 404)
            assertProblem(call(HttpMethod.Get, "/exchanges/01NOPE"), 404)
            assertEquals(0, upstream.received.size)
            assertEquals(emptyList(), store.list())
            assertEquals(emptyMap(), store.events())
            // Still a relay for everything else.
            assertEquals(200, relay(REQUEST).first)
            assertEquals(1, upstream.received.size)
        }

    @Test
    fun `the event feed delivers lines live and backfills what a reconnect missed`() = withProxy {
        val first = feed { events ->
            relay(REQUEST)
            events.take(2)
        }
        assertEquals(listOf("exchange.started", "exchange.completed"), first.names())
        val seen = first.last().first

        // While nobody listens.
        relay(REQUEST)
        awaitEvents(4)

        listOf("?since=$seen" to emptyMap(), "" to mapOf("Last-Event-ID" to "$seen")).forEach {
            (query, headers) ->
            val before = store.events().keys.max()
            val resumed =
                feed(query, headers) { events ->
                    val backfill = events.take(before - seen)
                    relay(REQUEST)
                    backfill + events.take(2)
                }
            val ids = resumed.map { it.first }
            assertEquals(((seen + 1)..(before + 2)).toList(), ids, "no gap, no duplicate")
            assertEquals(store.events().keys.filter { it > seen }, ids)
            resumed.forEach { (id, event) -> assertEquals(store.events()[id], event) }
        }
    }

    @Test
    fun `a malformed since is a 400 problem`() = withProxy {
        assertProblem(call(HttpMethod.Get, "/events?since=yesterday"), 400)
    }

    @Test
    fun `putting the default route to strict replay changes behaviour without a restart`() =
        withProxy {
            upstream.reply = { FakeUpstream.Reply(body = """{"recorded":true}""") }
            assertEquals(200 to """{"recorded":true}""", relay(REQUEST))
            awaitRecordings(1)

            val put =
                call(
                    HttpMethod.Put,
                    "/routes/default",
                    """{"mode":"replay","strict":true,"cassette":null}""",
                )
            assertEquals(200, put.status.value, put.bodyAsText())
            val route = json("/routes").getValue("default").jsonObject
            assertEquals("replay", route["mode"]?.text())
            assertTrue(route.getValue("strict").jsonPrimitive.boolean)

            assertEquals(200 to """{"recorded":true}""", relay(REQUEST))
            assertEquals(1, upstream.received.size, "the hit never reached the upstream")
            assertEquals(409, relay("""{"model":"claude-sonnet-4-5","messages":[1]}""").first)
            assertEquals(1, upstream.received.size, "nor did the strict miss")
            val health = call(HttpMethod.Get, "/health", token = null).bodyAsText()
            assertContains(health, "\"replay\"")
        }

    @Test
    fun `a route body that does not say what a route is gets a 400 problem`() = withProxy {
        listOf(
                "/routes/default" to "not json",
                "/routes/default" to "[]",
                "/routes/default" to """{"strict":true}""",
                "/routes/default" to """{"mode":"fast"}""",
                "/routes/default" to """{"mode":1}""",
                "/routes/default" to """{"mode":"replay","strict":"yes"}""",
                "/routes/default" to """{"mode":"replay","cassette":"../etc"}""",
                "/routes/default" to """{"mode":"replay","cassette":7}""",
                "/routes/other" to """{"mode":"replay"}""",
            )
            .forEach { (path, body) -> assertProblem(call(HttpMethod.Put, path, body), 400) }
        assertEquals("record", json("/routes").getValue("default").jsonObject["mode"]?.text())
    }

    @Test
    fun `exchanges list newest first, filter by session and client, and page by cursor`() =
        withProxy {
            val requests =
                listOf(
                    mapOf("x-claude-code-session-id" to "s1", "x-claude-code-agent-id" to "a1"),
                    mapOf("originator" to "codex_cli_rs", "session-id" to "cx1"),
                    mapOf("x-claude-code-session-id" to "s2"),
                )
            requests.forEach { relay(REQUEST, it) }
            awaitRecordings(3)
            awaitEvents(6)
            val (a, b, c) = store.list().asReversed().map { it.exchange.id }

            assertEquals(listOf(c, b, a), ids("/exchanges"))
            assertEquals(listOf(a), ids("/exchanges?session=s1"))
            assertEquals(listOf(c, a), ids("/exchanges?client=claude-code"))
            assertEquals(listOf(b), ids("/exchanges?client=codex&session=cx1"))
            assertEquals(emptyList(), ids("/exchanges?client=codex&session=s1"))
            assertEquals(listOf(c), ids("/exchanges?limit=1"))
            assertEquals(listOf(b), ids("/exchanges?limit=1&cursor=$c"))
            assertEquals(listOf(a), ids("/exchanges?cursor=$b"))
            assertEquals(b, json("/exchanges?limit=1&cursor=$c")["nextCursor"]?.text())
            assertNull(json("/exchanges?cursor=$b")["nextCursor"]?.text())

            val row = json("/exchanges?session=s1").getValue("exchanges").jsonArray.single()
            val fields = row.jsonObject
            assertEquals("s1", fields["session"]?.text())
            assertEquals("a1", fields["agent"]?.text())
            assertEquals("claude-code", fields["client"]?.text())
            assertEquals("record", fields["mode"]?.text())
            assertEquals("/v1/messages", fields["path"]?.text())
            assertEquals(200, fields.getValue("status").jsonPrimitive.int)

            listOf("0", "-1", "ten", "${MAX_EXCHANGES_LIMIT + 1}").forEach {
                assertProblem(call(HttpMethod.Get, "/exchanges?limit=$it"), 400)
            }
        }

    @Test
    fun `one exchange carries its request, and its frames when asked`() = withProxy {
        relay(REQUEST, mapOf("x-claude-code-session-id" to "s1"))
        awaitRecordings(1)
        val id = store.list().single().exchange.id

        val plain = json("/exchanges/$id")
        assertEquals(id, plain["id"]?.text())
        assertEquals(REQUEST, plain["requestBody"]?.text())
        val headers = plain.getValue("requestHeaders").jsonObject
        assertEquals("s1", headers.getValue("x-claude-code-session-id").jsonArray.single().text())
        assertTrue("frames" !in plain, "$plain")

        val frames = json("/exchanges/$id?frames=true").getValue("frames") as JsonArray
        assertEquals("{}", frames.single().jsonObject["raw"]?.text())
        assertTrue("t" in frames.single().jsonObject)
    }

    @Test
    fun `sessions are the store's session view`() = withProxy {
        relay(REQUEST, mapOf("x-claude-code-session-id" to "s1"))
        relay(REQUEST, mapOf("x-claude-code-session-id" to "s1"))
        relay(REQUEST, mapOf("x-claude-code-session-id" to "s2"))
        awaitEvents(6)
        val sessions = json("/sessions").getValue("sessions").jsonArray.map { it.jsonObject }
        assertEquals(listOf("s1", "s2"), sessions.map { it["session"]?.text() })
        assertEquals(2, sessions.first().getValue("exchanges").jsonPrimitive.int)
    }

    @Test
    fun `the token is created once, owner only, and reused on restart`() {
        val home = Files.createTempDirectory("peashoot-home")
        Store(home).use { store ->
            val first = ControlApi(store, home, ProxyConfig()).let { home.resolve(TOKEN_FILE) }
            val token = first.readText()
            assertTrue(token.length >= 43 && token.all { it.isLetterOrDigit() || it in "-_" })
            ControlApi(store, home, ProxyConfig())
            assertEquals(token, first.readText(), "an existing token is kept")
            val views = first.fileSystem.supportedFileAttributeViews()
            if ("posix" in views) {
                assertEquals(
                    "rw-------",
                    PosixFilePermissions.toString(Files.getPosixFilePermissions(first)),
                )
            } else if ("acl" in views) {
                val acl = Files.getFileAttributeView(first, AclFileAttributeView::class.java)
                assertEquals(setOf(Files.getOwner(first)), acl.acl.map { it.principal() }.toSet())
            }
        }
    }

    @Test
    fun `binding to a non-loopback address is refused`() {
        listOf("0.0.0.0", "192.0.2.1").forEach { host ->
            val error =
                assertFailsWith<IllegalArgumentException> {
                    ProxyServer(ProxyConfig(port = 0, host = host))
                }
            assertContains(error.message.orEmpty(), host)
        }
    }

    /**
     * Opens the feed, waits for the comment that says it is subscribed, and hands [block] a reader
     * of `(id, event)` pairs; closes the connection when [block] returns.
     */
    private suspend fun <T> Proxy.feed(
        query: String = "",
        headers: Map<String, String> = emptyMap(),
        block: suspend (Reader) -> T,
    ): T =
        client
            .prepareGet("$base/events$query") {
                header(HttpHeaders.Authorization, "Bearer $token")
                headers.forEach { (name, value) -> header(name, value) }
            }
            .execute { response ->
                assertEquals(200, response.status.value)
                val channel = response.bodyAsChannel()
                withTimeout(5_000) {
                    while (channel.readLine()?.startsWith(":") != true) continue
                    block(Reader(channel))
                }
            }

    private class Reader(private val channel: ByteReadChannel) {
        suspend fun take(count: Long): List<Pair<Long, JsonObject>> = take(count.toInt())

        suspend fun take(count: Int): List<Pair<Long, JsonObject>> = List(count) { next() }

        private suspend fun next(): Pair<Long, JsonObject> {
            var id: Long? = null
            var data: JsonObject? = null
            while (true) {
                val line = checkNotNull(channel.readLine()) { "the feed ended" }
                when {
                    line.startsWith("id: ") -> id = line.removePrefix("id: ").toLong()
                    line.startsWith("data: ") ->
                        data = Json.parseToJsonElement(line.removePrefix("data: ")).jsonObject
                    line.isEmpty() && data != null -> return checkNotNull(id) to data
                }
            }
        }
    }

    private fun List<Pair<Long, JsonObject>>.names() = map { it.second["event"]?.text() }

    private suspend fun Proxy.ids(path: String): List<String?> =
        json(path).getValue("exchanges").jsonArray.map { it.jsonObject["id"]?.text() }

    private companion object {
        const val REQUEST = """{"model":"claude-sonnet-4-5","messages":[]}"""
        const val PING = 200L
    }
}
