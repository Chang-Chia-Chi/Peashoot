package dev.peashoot.proxy

import dev.peashoot.core.Exchange
import dev.peashoot.core.FrameSource
import dev.peashoot.core.Interceptor
import dev.peashoot.core.Mode
import dev.peashoot.core.Route
import dev.peashoot.core.TOKEN_FILE
import dev.peashoot.core.text
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readLine
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The control API on the proxy's own port: token, health, events, exchanges, sessions, routes. */
class ControlApiTest {
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
                HttpMethod.Get to "/rules",
                HttpMethod.Put to "/rules",
                HttpMethod.Post to "/rules/test",
                HttpMethod.Get to "/cassettes",
                HttpMethod.Post to "/cassettes/export",
                HttpMethod.Post to "/cassettes/import",
                HttpMethod.Get to "/config",
                HttpMethod.Put to "/config",
                // A shutdown nobody may ask for without the token: the server is still up after.
                HttpMethod.Post to "/shutdown",
            )
        val unknown =
            listOf(
                HttpMethod.Get to "/nothing",
                HttpMethod.Post to "/events",
                HttpMethod.Delete to "/routes/default",
                HttpMethod.Post to "/health",
            )
        (guarded + unknown).forEach { (method, path) ->
            listOf(null, "wrong", "$token-and-more", "").forEach { token ->
                val body = """{"mode":"replay"}""".takeIf { method == HttpMethod.Put }
                assertProblem(call(method, path, body, token), 401)
            }
        }
        unknown.forEach { (method, path) ->
            val status = call(method, path).status.value
            assertTrue(status == 404 || status == 405, "$method $path with the token: $status")
            assertProblem(call(method, path), status)
        }
        assertProblem(raw(HttpMethod.Get, "/_peashoot/", token = null), 401)
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
            assertProblem(raw(HttpMethod.Post, "/_peashoot/v2/messages", token), 404)
            assertProblem(raw(HttpMethod.Get, "/_peashoot", token), 404)
            assertProblem(call(HttpMethod.Get, "/exchanges/01NOPE"), 404)
            assertEquals(0, upstream.received.size)
            assertEquals(emptyList(), store.list())
            assertEquals(emptyMap(), store.events())
            // Still a relay for everything else.
            assertEquals(200, relay(REQUEST).first)
            assertEquals(1, upstream.received.size)
        }

    @Test
    fun `a path that only resembles the prefix is refused, never relayed, recorded, or derived`() =
        withProxy {
            val disguised =
                listOf(
                    "/_Peashoot/v1/events",
                    "/_PEASHOOT/v1/routes",
                    "/_peashoot%2Fv1%2Fevents",
                    "/%5Fpeashoot/v1/events",
                    "//_peashoot/v1/routes",
                    "/v1/_peashoot/v1/events",
                    "/_peashoot",
                    "/_peashoot/",
                )
            disguised.forEach { path ->
                assertProblem(raw(HttpMethod.Get, path, token), 404)
                assertProblem(raw(HttpMethod.Post, path, token = null), 401)
            }
            assertEquals(emptyList(), upstream.received.map { it.uri }, "nothing was relayed")
            assertEquals(emptyList(), store.list())
            assertEquals(emptyMap(), store.events())
        }

    @Test
    fun `Last-Event-ID wins over since, as an EventSource reconnect sends both`() = withProxy {
        relay(REQUEST)
        awaitEvents(2)
        val seen = store.events().keys.first()
        val resumed = feed("?since=0", mapOf("Last-Event-ID" to "$seen")) { it.take(1) }
        assertEquals(seen + 1, resumed.single().first)
    }

    @Test
    fun `a request keeps the route it arrived under when the table changes mid-request`() =
        withProxy(
            route = Route(Mode.REPLAY),
            first = { control ->
                listOf(
                    object : Interceptor {
                        override suspend fun onRequest(exchange: Exchange): FrameSource? {
                            control.routes.put(DEFAULT_ROUTE, Route(Mode.REPLAY, strict = true))
                            return null
                        }
                    }
                )
            },
        ) {
            assertEquals(200, relay(REQUEST).first, "arrived lenient, so the miss is let through")
            assertEquals(1, upstream.received.size)
            assertEquals(409, relay("""{"model":"x","messages":[]}""").first, "the next is strict")
        }

    @Test
    fun `a store that fails is a 500 problem that does not echo the failure`() = withProxy {
        store.close()
        val response = call(HttpMethod.Get, "/sessions")
        assertProblem(response, 500)
        val detail = Json.parseToJsonElement(response.bodyAsText()).jsonObject["detail"]?.text()
        assertTrue(
            detail != null && "Exception" !in detail && "Hikari" !in detail,
            "a generic detail: $detail",
        )
        assertTrue(appLog().any { "control call /_peashoot/v1/sessions failed" in it })
        assertTrue(appLog().any { "HikariDataSource" in it }, "the failure itself is logged")
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
    fun `a subscriber that falls behind is cut off and resumes with Last-Event-ID`() =
        withProxy(buffer = 1) {
            // Published straight to the feed, faster than any reader drains it: what the deriver
            // would do to a subscriber that stopped reading.
            val lines =
                (1..FLOOD).associate { n ->
                    val event = event(n)
                    store.putEvent(event) to event
                }
            val cut = feed { events ->
                lines.forEach { (id, event) -> control.feed.publish(id, event) }
                events.takeUntilEnd()
            }
            assertTrue(cut.size < lines.size, "the feed was cut off after ${cut.size} lines")
            val resumed =
                feed(headers = mapOf("Last-Event-ID" to "${cut.last().first}")) {
                    it.take(lines.size - cut.size)
                }
            assertEquals(lines.keys.toList(), (cut + resumed).map { it.first }, "no line is lost")
            assertEquals(lines.values.toList(), (cut + resumed).map { it.second })
        }

    @Test
    fun `a subscriber that goes is forgotten`() = withProxy {
        feed { assertEquals(1, control.feed.subscriberCount) }
        withTimeout(5_000) { while (control.feed.subscriberCount > 0) delay(20) }
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
            )
            .forEach { (path, body) -> assertProblem(call(HttpMethod.Put, path, body), 400) }
        assertProblem(call(HttpMethod.Put, "/routes/other", """{"mode":"replay"}"""), 404)
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
            listOf("01ZZZZZZZZZZZZZZZZZZZZZZZZ", "not-an-id", "").forEach {
                assertProblem(call(HttpMethod.Get, "/exchanges?cursor=$it"), 400)
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
        val token = loadToken(home)
        assertTrue(token.length >= 43 && token.all { it.isLetterOrDigit() || it in "-_" })
        assertEquals(token, loadToken(home), "an existing token is kept")
        assertOwnerOnly(home.resolve(TOKEN_FILE))
    }

    @Test
    fun `an existing token readable by others is tightened, with a warning that omits it`() {
        val home = Files.createTempDirectory("peashoot-home")
        val file = home.resolve(TOKEN_FILE)
        val token = "a".repeat(43)
        // Written plainly, so it takes the directory's inherited entries or the umask's mode.
        Files.writeString(file, token)
        if ("posix" in file.fileSystem.supportedFileAttributeViews()) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"))
        }
        assertEquals(token, loadToken(home))
        assertOwnerOnly(file)
        assertEquals(token, file.readText(), "the token itself is kept")
        val warning = appLog().filter { file.toString() in it }
        assertTrue(warning.isNotEmpty(), "the tightening is warned about")
        assertTrue(appLog().none { token in it }, "the token is never logged")
    }

    @Test
    fun `an empty or implausibly short token file stops startup, naming the file`() {
        listOf("", "\n", "short").forEach { content ->
            val home = Files.createTempDirectory("peashoot-home")
            Files.writeString(home.resolve(TOKEN_FILE), content)
            val error = assertFailsWith<IllegalStateException> { loadToken(home) }
            assertContains(error.message.orEmpty(), home.resolve(TOKEN_FILE).toString())
        }
    }

    @Test
    fun `simultaneous first starts end with one token`() {
        val home = Files.createTempDirectory("peashoot-home")
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(STARTS)
        val tokens =
            try {
                List(STARTS) { pool.submit(Callable { start.await().let { loadToken(home) } }) }
                    .also { start.countDown() }
                    .map { it.get() }
            } finally {
                pool.shutdown()
            }
        assertEquals(setOf(home.resolve(TOKEN_FILE).readText()), tokens.toSet(), "one token")
        assertEquals(
            listOf(home.resolve(TOKEN_FILE)),
            home.listDirectoryEntries("token*"),
            "no staging file is left",
        )
    }

    private fun assertOwnerOnly(file: Path) {
        val views = file.fileSystem.supportedFileAttributeViews()
        if ("posix" in views) {
            assertEquals(
                "rw-------",
                PosixFilePermissions.toString(Files.getPosixFilePermissions(file)),
            )
        } else if ("acl" in views) {
            val acl = Files.getFileAttributeView(file, AclFileAttributeView::class.java)
            assertEquals(listOf(Files.getOwner(file)), acl.acl.map { it.principal() })
        }
    }

    @Test
    fun `binding to a non-loopback address is refused`() {
        listOf("0.0.0.0", "192.0.2.1", "[192.0.2.1]", "no-such-host.invalid").forEach { host ->
            val error =
                assertFailsWith<IllegalStateException> {
                    ProxyServer(ProxyConfig(port = 0, host = host))
                }
            assertContains(error.message.orEmpty(), host)
        }
    }

    @Test
    fun `a bracketed IPv6 loopback host binds and serves`() = runBlocking {
        ProxyServer(ProxyConfig(port = 0, host = "[::1]")).use { server ->
            assertTrue(server.url.startsWith("http://[0:0:0:0:0:0:0:1]:"), server.url)
            HttpClient(CIO).use { client ->
                assertEquals(200, client.head("${server.url}/api/hello").status.value)
            }
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

        suspend fun take(count: Int): List<Pair<Long, JsonObject>> =
            List(count) { checkNotNull(next()) }

        /** Every line until the server ends the response. */
        suspend fun takeUntilEnd(): List<Pair<Long, JsonObject>> = buildList {
            while (true) add(next() ?: break)
        }

        /** The next line, or null once the feed has ended. */
        private suspend fun next(): Pair<Long, JsonObject>? {
            var id: Long? = null
            var data: JsonObject? = null
            while (true) {
                val line = channel.readLine() ?: return null
                when {
                    line.startsWith("id: ") -> id = line.removePrefix("id: ").toLong()
                    line.startsWith("data: ") ->
                        data = Json.parseToJsonElement(line.removePrefix("data: ")).jsonObject
                    line.isEmpty() && data != null -> return checkNotNull(id) to data
                }
            }
        }
    }

    /** An event line of the shape the deriver writes, which the store can keep. */
    private fun event(n: Int): JsonObject = buildJsonObject {
        put("ts", Instant.now().toString())
        put("event", "exchange.started")
        put("exchangeId", "flood-$n")
        put("session", "s1")
    }

    private fun List<Pair<Long, JsonObject>>.names() = map { it.second["event"]?.text() }

    private suspend fun Proxy.ids(path: String): List<String?> =
        json(path).getValue("exchanges").jsonArray.map { it.jsonObject["id"]?.text() }

    private companion object {
        const val REQUEST = """{"model":"claude-sonnet-4-5","messages":[]}"""
        const val STARTS = 8
        /**
         * Enough lines that a one-line buffer cannot hold them while the reader writes them out.
         */
        const val FLOOD = 200
    }
}
