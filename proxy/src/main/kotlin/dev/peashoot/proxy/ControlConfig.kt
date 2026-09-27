package dev.peashoot.proxy

import dev.peashoot.core.DEFAULT_PRICES
import dev.peashoot.core.Price
import dev.peashoot.core.Route as Routing
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.netty.NettyApplicationCall
import io.ktor.server.response.header
import io.ktor.server.routing.Route
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.peashoot.proxy")

/**
 * The keys this process only reads at start: the file takes them, the run does not. Everything else
 * the relay reads per request or the route table holds, so a `PUT` applies it to the next request.
 * [keepingRunning] is the other half of this list and must name the same values; the `PUT` tests
 * walk every key here and fail if the two come apart.
 *
 * `resume.pingIntervalSeconds` is here because the event feed's keep-alive is set when the server
 * starts, though the relay would pick a new one up. `secretHeaders` is here on purpose, not for
 * want of plumbing: design section 14 says a secret header is never stored, and a running proxy
 * that could be told to start keeping one is a proxy that can be told to capture a credential.
 */
internal val RESTART_REQUIRED =
    setOf("port", "host", "secretHeaders", "replay", "resume", "gource", "pricing")

/** The keys an environment variable sets alone: answered here, never written to the file. */
internal val ENV_ONLY =
    mapOf("dumpFrames" to "PEASHOOT_DUMP_FRAMES", "cassetteFile" to "PEASHOOT_CASSETTE")

/** The keys an environment variable overrides at every start, so the file's value never runs. */
private val ENV_WINS =
    mapOf(
        "port" to "PEASHOOT_PORT",
        "host" to "PEASHOOT_HOST",
        "surfaces" to "PEASHOOT_ANTHROPIC_UPSTREAM",
    )

/** A key TOML can write bare; anything else is quoted, as a model-prefix price key may need. */
private val BARE_KEY = Regex("[A-Za-z0-9_-]+")

/**
 * `GET /config`, the config as this proxy runs it, with no secret in it: the token is not a config
 * value at all, and `secretHeaders` is names only, which is all it ever holds. The routes are the
 * table's, since the table is what a request reads.
 *
 * `PUT /config` replaces the keys it names, leaf by leaf, rewrites `peashoot.toml` from the result,
 * and applies what can change without a restart. It is validated by loading the file it just wrote,
 * so a value the next start would refuse is refused here in the loader's own words and the file
 * that runs goes back. The answer names what only a restart will pick up, and which of those an
 * environment variable will override even then.
 */
internal fun Route.configuration(api: ControlApi) {
    endpoint(HttpMethod.Get, "config") { call.json(api.configJson()) }
    endpoint(HttpMethod.Put, "config") {
        val body = call.bodyObject()
        val running = api.configJson()
        val unknown = body.keys - running.keys
        if (unknown.isNotEmpty()) {
            badRequest("no such config key: ${unknown.sorted().joinToString()}")
        }
        val fixed = ENV_ONLY.filterKeys { body[it] !in setOf(null, JsonNull) }
        if (fixed.isNotEmpty()) {
            badRequest(
                "${fixed.keys.joinToString()} is set by ${fixed.values.joinToString()} alone, " +
                    "never by the config file"
            )
        }
        // One writer at a time: the file is read, written, loaded back, and sometimes put back,
        // and two puts interleaving there would leave the file and the live config disagreeing.
        api.writing.withLock { api.rewrite(body, running) }
        call.json(
            buildJsonObject {
                put("config", api.configJson())
                put(
                    "restartRequired",
                    body.keys.that { it in RESTART_REQUIRED && body[it] != running[it] },
                )
                put(
                    "environmentWins",
                    body.keys.mapNotNull(ENV_WINS::get).that { api.env(it) != null },
                )
            }
        )
    }
}

/** The keys of this collection that [holds], sorted, as the answer lists them. */
private fun Collection<String>.that(holds: (String) -> Boolean): JsonArray =
    JsonArray(filter(holds).sorted().map(::JsonPrimitive))

/**
 * The file rewritten from [body] over what it already holds, loaded back to check it, and applied
 * as far as a running process can. The merge is onto the file rather than onto what runs, so a key
 * that needs a restart stays as the `PUT` that set it left it; onto [running] when the file cannot
 * be loaded at all, which is how a `PUT` repairs one.
 */
private suspend fun ControlApi.rewrite(body: JsonObject, running: JsonObject) {
    val file = home.resolve(CONFIG_FILE)
    val base = runCatching { loadConfig(home, env).toJson(routes.all) }.getOrDefault(running)
    val before = if (Files.exists(file)) Files.readString(file) else null
    Files.writeString(file, JsonObject(base - ENV_ONLY.keys).merge(body).tomlText())
    val loaded = runCatching {
        loadConfig(home, env)
    }
        .getOrElse { failure ->
            // A file the loader refuses is no config at all: the one that runs goes back.
            before?.let { Files.writeString(file, it) }
            refuse(failure)
        }
    live.current = loaded.keepingRunning(live.current)
    // The table owns the routes while the proxy runs, and a put that says nothing about them must
    // not undo a PUT /routes/{name} by writing the file's back over it.
    if ("routes" in body) loaded.routes.forEach { (name, route) -> routes.put(name, route) }
    log.info("the config file is now {}", loaded)
}

/**
 * `POST /shutdown`: the answer is written, and then whoever started the server stops it. Here that
 * is `serve`, which closes the server and the store and lets the process end; a test holds the
 * server itself and closes it the same way.
 *
 * "Written" means on the wire, not handed to Ktor: Netty writes the answer from a thread of its own
 * a moment later, and an engine stopped before then closes the connection with nothing on it. So
 * the answer asks the client to close the connection, and the stop waits for that close, which
 * comes only once the answer is out; a client that keeps the connection anyway holds the stop for
 * at most [SHUTDOWN_LINGER].
 */
internal fun Route.shutdown(api: ControlApi) =
    endpoint(HttpMethod.Post, "shutdown") {
        call.response.header(HttpHeaders.Connection, "close")
        call.json(buildJsonObject { put("stopping", true) })
        val connection = (call.pipelineCall.engineCall as? NettyApplicationCall)?.context?.channel()
        if (connection == null) {
            api.stopping.complete(Unit)
        } else {
            connection.closeFuture().addListener { api.stopping.complete(Unit) }
            connection
                .eventLoop()
                .schedule(
                    { api.stopping.complete(Unit) },
                    SHUTDOWN_LINGER.inWholeMilliseconds,
                    TimeUnit.MILLISECONDS,
                )
        }
    }

/** How long `POST /shutdown` waits for its client to take the answer and hang up. */
private val SHUTDOWN_LINGER = 2.seconds

/**
 * The reloaded config with every value this process cannot change put back to what it is really
 * running, so `GET /config` answers what the proxy does while the file holds what it will do. One
 * per key in [RESTART_REQUIRED].
 */
private fun ProxyConfig.keepingRunning(running: ProxyConfig): ProxyConfig =
    copy(
        port = running.port,
        host = running.host,
        secretHeaders = running.secretHeaders,
        replayCadence = running.replayCadence,
        repeatPolicy = running.repeatPolicy,
        pingInterval = running.pingInterval,
        resumeWindow = running.resumeWindow,
        maxBufferedExchanges = running.maxBufferedExchanges,
        gourceEnabled = running.gourceEnabled,
        pricing = running.pricing,
    )

/** What the proxy runs: its config, and the routes the table holds rather than the file's. */
internal fun ControlApi.configJson(): JsonObject = live.current.toJson(routes.all)

/**
 * The config in the shape [CONFIG_FILE] holds it, which is also the shape a `PUT` takes, plus the
 * two values only the environment sets: they are part of what the proxy runs, so an answer that
 * left them out would be answering less than it claims.
 */
internal fun ProxyConfig.toJson(routes: Map<String, Routing> = this.routes): JsonObject =
    buildJsonObject {
        put("port", port)
        put("host", host)
        putJsonObject("surfaces") {
            putJsonObject("anthropic") { put("upstream", anthropicUpstream) }
            putJsonObject("openai") { put("upstream", openaiUpstream) }
        }
        put("secretHeaders", JsonArray(secretHeaders.map(::JsonPrimitive)))
        // Not in [RESTART_REQUIRED] and not in [keepingRunning]: nothing in this process reads it,
        // so there is nothing here a restart could change. A put is live the moment it is served.
        put("idleSessionMinutes", idleSessionMinutes)
        put("routes", routesJson(routes))
        putJsonObject("replay") {
            put("cadence", replayCadence.spelled())
            put("repeatPolicy", repeatPolicy.spelled())
        }
        // Seconds as the file writes them, and as a fraction where the interval is not whole
        // seconds, so what this answers loads back as the same interval.
        putJsonObject("resume") {
            put("pingIntervalSeconds", pingInterval.toDouble(DurationUnit.SECONDS))
            put("windowSeconds", resumeWindow.toDouble(DurationUnit.SECONDS))
            put("maxBufferedExchanges", maxBufferedExchanges)
        }
        putJsonObject("gource") { put("enabled", gourceEnabled) }
        // Overrides only: the price table is bundled, and a row in the file only ever replaces one.
        put(
            "pricing",
            JsonObject(
                pricing
                    .filterNot { DEFAULT_PRICES[it.key] == it.value }
                    .mapValues { (_, price) -> price.toJson() }
            ),
        )
        put("dumpFrames", dumpFrames?.toString())
        put("cassetteFile", cassetteFile?.toString())
    }

private fun Price.toJson(): JsonObject = buildJsonObject {
    put("input", input)
    put("output", output)
    put("cacheRead", cacheRead)
    put("cacheWrite", cacheWrite)
}

/**
 * [over]'s leaves on top of this object's, so a `PUT` names only the keys it changes and a table it
 * touches keeps its other keys. A null replaces a value with nothing, which the file leaves out and
 * the loader reads as that key's default.
 */
private fun JsonObject.merge(over: JsonObject): JsonObject =
    JsonObject(
        this +
            over.mapValues { (key, value) ->
                val mine = this[key]
                if (mine is JsonObject && value is JsonObject) mine.merge(value) else value
            }
    )

/**
 * This object as TOML: the scalars and lists of a table first, then a table per object under it,
 * which is the order the format needs. A null is left out, since TOML has none and the loader reads
 * an absent key as its default. The comments the first start wrote are not kept: a `PUT` writes the
 * config it was given, and `GET /config` is where to read it back.
 */
private fun JsonObject.tomlText(path: String = ""): String {
    val (tables, scalars) = entries.partition { it.value is JsonObject }
    return buildString {
        if (path.isEmpty()) appendLine("# Peashoot, written by PUT /config. See docs/design.md.")
        scalars.forEach { (key, value) ->
            if (value != JsonNull) appendLine("${key.tomlKey()} = $value")
        }
        tables.forEach { (key, value) ->
            val table = listOf(path, key.tomlKey()).filter { it.isNotEmpty() }.joinToString(".")
            appendLine()
            appendLine("[$table]")
            append((value as JsonObject).tomlText(table))
        }
    }
}

private fun String.tomlKey(): String =
    if (BARE_KEY.matches(this)) this else JsonPrimitive(this).toString()
