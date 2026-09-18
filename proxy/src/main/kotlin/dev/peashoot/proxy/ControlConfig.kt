package dev.peashoot.proxy

import dev.peashoot.core.DEFAULT_PRICES
import dev.peashoot.core.Price
import io.ktor.http.HttpMethod
import io.ktor.server.routing.Route
import java.nio.file.Files
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
 * `resume.pingIntervalSeconds` is here because the event feed's own keep-alive is set when the
 * server starts, though the relay would pick a new one up.
 */
private val RESTART_REQUIRED = setOf("port", "host", "replay", "resume", "gource", "pricing")

/** A key TOML can write bare; anything else is quoted, as a model-prefix price key may need. */
private val BARE_KEY = Regex("[A-Za-z0-9_-]+")

/**
 * `GET /config`, the config as this proxy runs it, with no secret in it: the token is not a config
 * value at all, and `secretHeaders` is names only, which is all it ever holds.
 *
 * `PUT /config` replaces the keys it names, whole, writes `peashoot.toml` from the result, and
 * applies what can change without a restart. It is validated by loading the file it just wrote, so
 * a value the next start would refuse is refused here, in the loader's own words, and the file that
 * runs is put back. The merge is onto the file rather than onto what runs, so a key that needs a
 * restart stays in the file as the `PUT` that set it left it. Environment variables still win at
 * start, so a key one of them overrides reads back as the environment's, here and after a restart.
 */
internal fun Route.configuration(api: ControlApi) {
    endpoint(HttpMethod.Get, "config") { call.json(api.live.current.toJson()) }
    endpoint(HttpMethod.Put, "config") {
        val body = call.bodyObject()
        val running = api.live.current.toJson()
        val unknown = body.keys - running.keys
        if (unknown.isNotEmpty())
            badRequest("no such config key: ${unknown.sorted().joinToString()}")
        val file = api.home.resolve(CONFIG_FILE)
        // Merged onto the file rather than onto what runs; loading it first also writes it back
        // if it has gone missing since the start read it.
        val onDisk = refusing { loadConfig(api.home, api.env) }.toJson()
        val before = Files.readString(file)
        Files.writeString(file, JsonObject(onDisk + body).tomlText())
        val loaded = runCatching {
            loadConfig(api.home, api.env)
        }
            .getOrElse { failure ->
                // A file the loader refuses is no config at all: the one that runs goes back.
                Files.writeString(file, before)
                refuse(failure)
            }
        api.live.current = loaded.keepingRunning(api.live.current)
        loaded.routes.forEach { (name, route) -> api.routes.put(name, route) }
        log.info("the config file is now {}", loaded)
        call.json(
            buildJsonObject {
                put("config", api.live.current.toJson())
                put(
                    "restartRequired",
                    JsonArray(
                        body.keys
                            .filter { it in RESTART_REQUIRED && body[it] != running[it] }
                            .sorted()
                            .map(::JsonPrimitive)
                    ),
                )
            }
        )
    }
}

/**
 * `POST /shutdown`: the answer is written, and then whoever started the server stops it. Here that
 * is `serve`, which closes the server and the store and lets the process end; a test holds the
 * server itself and closes it the same way.
 */
internal fun Route.shutdown(api: ControlApi) =
    endpoint(HttpMethod.Post, "shutdown") {
        call.json(buildJsonObject { put("stopping", true) })
        api.stopping.complete(Unit)
    }

/**
 * The reloaded config with every value this process cannot change put back to what it is really
 * running, so `GET /config` answers what the proxy does while the file holds what it will do.
 */
private fun ProxyConfig.keepingRunning(running: ProxyConfig): ProxyConfig =
    copy(
        port = running.port,
        host = running.host,
        replayCadence = running.replayCadence,
        repeatPolicy = running.repeatPolicy,
        pingInterval = running.pingInterval,
        gourceEnabled = running.gourceEnabled,
        pricing = running.pricing,
    )

/** The config in the shape [CONFIG_FILE] holds it, which is also the shape a `PUT` takes. */
internal fun ProxyConfig.toJson(): JsonObject = buildJsonObject {
    put("port", port)
    put("host", host)
    putJsonObject("surfaces") { putJsonObject("anthropic") { put("upstream", anthropicUpstream) } }
    put("secretHeaders", JsonArray(secretHeaders.map(::JsonPrimitive)))
    put("routes", routesJson(routes))
    putJsonObject("replay") {
        put("cadence", replayCadence.spelled())
        put("repeatPolicy", repeatPolicy.spelled())
    }
    putJsonObject("resume") { put("pingIntervalSeconds", pingInterval.inWholeSeconds) }
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
}

private fun Price.toJson(): JsonObject = buildJsonObject {
    put("input", input)
    put("output", output)
    put("cacheRead", cacheRead)
    put("cacheWrite", cacheWrite)
}

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
