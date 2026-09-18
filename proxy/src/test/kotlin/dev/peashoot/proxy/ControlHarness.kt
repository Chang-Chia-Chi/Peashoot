package dev.peashoot.proxy

import dev.peashoot.core.Interceptor
import dev.peashoot.core.Mode
import dev.peashoot.core.Route
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** How long a silent feed waits before its keep-alive, short enough for a test to see two. */
internal const val PING = 200L

/** The proxy a control API test drives: its data directory, store, upstream, server, and client. */
internal class Proxy(
    val home: Path,
    val store: Store,
    val upstream: FakeUpstream,
    val server: ProxyServer,
    val control: ControlApi,
    val client: HttpClient,
) {
    val token: String = home.resolve(TOKEN_FILE).readText()
    val base = "${server.url}/_peashoot/v1"
}

/**
 * A proxy with the whole v1 chain and its control API, relaying to a fake upstream; [first]
 * interceptors run ahead of the chain. The data directory is loaded as a first start would load it,
 * so the config, rule, and redaction files are on disk as the control API expects them, and no
 * environment reaches the config: a `PEASHOOT_` variable on the machine running the tests must not
 * change what they see.
 */
internal fun withProxy(
    route: Route = Route(Mode.RECORD),
    buffer: Int = FEED_BUFFER,
    /** What the proxy's own environment says; nothing, unless a test is about an override. */
    env: (String) -> String? = { null },
    first: (ControlApi) -> List<Interceptor> = { emptyList() },
    block: suspend Proxy.() -> Unit,
) = runBlocking {
    val home = Files.createTempDirectory("peashoot-home")
    Store(home).use { store ->
        FakeUpstream().use { upstream ->
            val config =
                loadConfig(home, env)
                    .copy(
                        port = 0,
                        anthropicUpstream = upstream.url,
                        pingInterval = PING.milliseconds,
                        routes = mapOf(DEFAULT_ROUTE to route),
                    )
            val control =
                ControlApi(
                    store,
                    home,
                    RouteTable(config.routes),
                    LiveConfig(config),
                    EventFeed(buffer),
                    env = env,
                )
            val chain =
                first(control) +
                    listOf(
                        Replay(store, config),
                        Recorder(store),
                        Deriver(store, home.resolve(EVENTS_FILE), feed = control.feed),
                    )
            // No request timeout: a feed stays open for as long as a test reads it.
            HttpClient(CIO) { engine { requestTimeout = 0 } }
                .use { client ->
                    ProxyServer(config, chain, control).use { server ->
                        Proxy(home, store, upstream, server, control, client).block()
                    }
                }
        }
    }
}

internal suspend fun Proxy.call(
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

/** A request to any path on the proxy's port, spelled exactly as given. */
internal suspend fun Proxy.raw(method: HttpMethod, path: String, token: String?) =
    client.request("${server.url}$path") {
        this.method = method
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

internal suspend fun Proxy.json(path: String): JsonObject =
    call(HttpMethod.Get, path).let {
        assertEquals(200, it.status.value, it.bodyAsText())
        Json.parseToJsonElement(it.bodyAsText()).jsonObject
    }

/** The body of a control call that must have worked, as JSON. */
internal suspend fun okJson(response: HttpResponse): JsonObject {
    val text = response.bodyAsText()
    assertEquals(200, response.status.value, text)
    return Json.parseToJsonElement(text).jsonObject
}

internal suspend fun Proxy.relay(body: String, headers: Map<String, String> = emptyMap()) =
    client
        .post("${server.url}/v1/messages") {
            headers.forEach { (name, value) -> header(name, value) }
            setBody(body)
        }
        .let { it.status.value to it.bodyAsText() }

internal suspend fun Proxy.awaitRecordings(count: Int) =
    withTimeout(5_000) { while (store.list().size < count) delay(20) }

internal suspend fun Proxy.awaitEvents(count: Int) =
    withTimeout(5_000) { while (store.events().size < count) delay(20) }

internal suspend fun assertProblem(response: HttpResponse, status: Int): String {
    val text = response.bodyAsText()
    assertEquals(status, response.status.value, text)
    assertEquals(
        ContentType.Application.ProblemJson,
        ContentType.parse(response.headers[HttpHeaders.ContentType]!!).withoutParameters(),
    )
    val problem = Json.parseToJsonElement(text).jsonObject
    assertEquals(setOf("type", "title", "detail", "status"), problem.keys, text)
    assertEquals(status, problem.getValue("status").jsonPrimitive.int)
    return problem.getValue("detail").jsonPrimitive.content
}

/** The proxy's own log file, which the test JVM writes per run. */
internal fun appLog(): List<String> =
    Files.readAllLines(Path.of(System.getProperty("peashoot.test.appLog")))
