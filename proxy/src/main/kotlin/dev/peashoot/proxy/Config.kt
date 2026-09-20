package dev.peashoot.proxy

import dev.peashoot.core.ChatCompletions
import dev.peashoot.core.DEFAULT_PRICES
import dev.peashoot.core.Messages
import dev.peashoot.core.Mode
import dev.peashoot.core.Price
import dev.peashoot.core.REDACT_FILE
import dev.peashoot.core.RULES_FILE
import dev.peashoot.core.Redaction
import dev.peashoot.core.Responses
import dev.peashoot.core.Route
import dev.peashoot.core.Rules
import dev.peashoot.core.Surface
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.nameWithoutExtension
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.tomlj.Toml
import org.tomlj.TomlParseResult
import org.tomlj.TomlTable

const val CONFIG_FILE = "peashoot.toml"

/** The highest port a socket takes; 0 asks the system for a free one, as the tests bind. */
private const val MAX_PORT = 65535L

/**
 * The longest idle window a day boundary can mean: a day. Past that the card is not about a day at
 * all. Nothing here is about overflow — the field is an `Int`, far from any of it: what keeps a
 * larger number from truncating into a plausible one is that the file is read as a `Long` and
 * checked against this before it is narrowed.
 */
private const val MAX_IDLE_MINUTES = 1440L

const val DEFAULT_ROUTE = "default"

/**
 * The routes as they are now: the config's at start, then whatever `PUT /routes/{name}` set. The
 * relay reads it once per request and the control API writes it, so a change applies to the next
 * request. Memory only: a restart goes back to the file and the environment.
 */
class RouteTable(initial: Map<String, Route>) {
    private val current = AtomicReference(initial)

    val all: Map<String, Route>
        get() = current.get()

    operator fun get(name: String): Route = current.get().getValue(name)

    /**
     * Replaces the route [name] and says so, or leaves the table alone and returns false when there
     * is no such route: nothing adds one until routing arrives.
     */
    fun put(name: String, route: Route): Boolean {
        var known = false
        current.updateAndGet { routes ->
            known = name in routes
            if (known) routes + (name to route) else routes
        }
        return known
    }
}

/**
 * The config as it runs now: the file's and the environment's at start, then whatever `PUT /config`
 * and `PUT /rules` left. The relay reads it once per request, so a new rule set or upstream applies
 * to the next request; everything the chain and the server read at start needs a restart, which is
 * what `PUT /config` answers with. Memory only: a restart reads the file and the environment again.
 */
class LiveConfig(initial: ProxyConfig) {
    @Volatile var current: ProxyConfig = initial
}

/** How fast a replay serves its frames: all at once, or at the offsets they were recorded at. */
enum class Cadence {
    INSTANT,
    RECORDED,
}

/** Which of several recordings of one fingerprint a replay serves. */
enum class RepeatPolicy {
    /** Oldest first, one per request, then the last one again: a retry sequence replays whole. */
    IN_ORDER,
    LATEST,
}

data class ProxyConfig(
    val port: Int = 8787,
    /** The listen address. Loopback only: the control API reads and steers all traffic. */
    val host: String = "127.0.0.1",
    val anthropicUpstream: String = "https://api.anthropic.com",
    /**
     * Where the OpenAI surfaces go. Its own key because a local OpenAI-compatible server — Ollama
     * is the free one — is the whole point of having it: pointing it at `127.0.0.1:11434` must not
     * drag the Anthropic traffic along with it.
     */
    val openaiUpstream: String = "https://api.openai.com",
    /** Debug: append every raw upstream response to this file, for building fixtures. */
    val dumpFrames: Path? = null,
    /**
     * Sent upstream, never kept: not on the Exchange, not in any log or file, in either direction.
     * Any case. What belongs here is a credential by nature rather than an identifier — whoever
     * holds one of these *is* the caller. `set-cookie` and `cookie` are the two halves of one
     * session credential (#98), and a cassette is a file written to be committed and handed around;
     * `proxy-authorization` is `authorization` under the name a forward proxy uses.
     *
     * `openai-organization` and `openai-project` are deliberately not here, though an export must
     * not carry them either. This list is applied at receipt, before `Exchange.surface` is read,
     * and `surfaceOf` routes a request whose path no surface owns — a bare `GET /v1/models` — by
     * any `openai-` header the sender put on it. Dropping them here would send an OpenAI client's
     * model listing to the Anthropic upstream, which is why #77 redacts them on the way out
     * instead. A header this list names is a header the router may not read.
     */
    val secretHeaders: Set<String> =
        setOf("authorization", "proxy-authorization", "x-api-key", "cookie", "set-cookie"),
    /** What each route does; every request takes [DEFAULT_ROUTE] until routing arrives. */
    val routes: Map<String, Route> = mapOf(DEFAULT_ROUTE to Route(Mode.RECORD)),
    /** `replay.cadence` in the file. */
    val replayCadence: Cadence = Cadence.INSTANT,
    /** `replay.repeatPolicy` in the file. */
    val repeatPolicy: RepeatPolicy = RepeatPolicy.IN_ORDER,
    /** The bundled price table, with any model-prefix override from the file on top of it. */
    val pricing: Map<String, Price> = DEFAULT_PRICES,
    /** Whether a completed turn's file tools are also appended to [GOURCE_FILE]. */
    val gourceEnabled: Boolean = false,
    /**
     * While a streaming upstream is silent this long, the proxy writes an SSE comment line to the
     * client so byte-counting watchdogs stay alive. `resume.pingIntervalSeconds` in the file.
     */
    val pingInterval: Duration = 15.seconds,
    /**
     * What makes two requests the same request, read from [RULES_FILE]. Its own file rather than a
     * table in the config: it is the one thing here meant to be edited as data, and #11's `POST
     * /rules/test` will write it back.
     */
    val rules: Rules = Rules.DEFAULT,
    /** What a cassette export strips, read from [REDACT_FILE]. */
    val redaction: Redaction = Redaction.DEFAULT,
    /**
     * `PEASHOOT_CASSETTE`: imported on start under its base name, which the default route then
     * replays from, so a CI job needs the file and no database.
     */
    val cassetteFile: Path? = null,
    /**
     * How long a session must be quiet before the app ends its day with a card. The one key here
     * nothing in this process reads: it belongs to the traffic the proxy sees rather than to the
     * window watching it, so it is configured beside its neighbours and served on `GET /config`.
     *
     * Bounded 1 to [MAX_IDLE_MINUTES] at load: zero or less ends every session's day on the app's
     * every tick, which is a card that means nothing, and longer than a day is not an end of day.
     */
    val idleSessionMinutes: Int = 30,
) {
    /** [secretHeaders] lower-cased once, since header names compare case-insensitively. */
    val lowercaseSecretHeaders: Set<String> = secretHeaders.map(String::lowercase).toSet()

    /**
     * Where [surface] is relayed to, without a trailing slash so a request path appends directly.
     */
    fun upstreamBase(surface: Surface): String =
        when (surface) {
            // Every surface is named and none is assumed, which is why `Surface` is sealed: a
            // fourth one would otherwise be relayed to whichever provider this `else` last
            // happened to mean, with its key, instead of failing to compile until someone says.
            Messages -> anthropicUpstream
            ChatCompletions,
            Responses -> openaiUpstream
        }.trimEnd('/')
}

/**
 * Creates the directory and a default config file on first start, then loads the file with the
 * environment on top for the keys CI needs. The file is rendered from [ProxyConfig]'s own defaults,
 * so the two cannot drift.
 */
fun loadConfig(home: Path, env: (String) -> String? = System::getenv): ProxyConfig {
    Files.createDirectories(home)
    val file = home.resolve(CONFIG_FILE)
    val defaults = ProxyConfig()
    val rulesFile = home.resolve(RULES_FILE)
    val redactFile = home.resolve(REDACT_FILE)
    mapOf(
            file to defaults::toToml,
            rulesFile to Rules::defaultJson,
            redactFile to Redaction::defaultJson,
        )
        .forEach { (path, text) -> if (Files.notExists(path)) Files.writeString(path, text()) }
    val toml = Toml.parse(file)
    check(!toml.hasErrors()) { "$file: " + toml.errors().joinToString { it.toString() } }
    val cassetteFile = env("PEASHOOT_CASSETTE")?.let(Path::of)
    val routes = defaults.routes + toml.getTable("routes")?.routes().orEmpty()
    val host = listOfNotNull(env("PEASHOOT_HOST"), toml.getString("host"), defaults.host).first()
    // Refused here too, so a bad file fails before anything else starts; the server binds to what
    // its own call of this returns.
    loopbackAddress(host)
    // Read and checked as a Long, beside the host: 5000000000 truncates into a plausible port and
    // 99999 is one nothing can bind, so either would pass for a config and kill the start that
    // tried to use it. 0 is the one special value: it asks the system for a free port.
    val port =
        env("PEASHOOT_PORT")?.let {
            it.toLongOrNull() ?: error("PEASHOOT_PORT must be a port number, not $it")
        } ?: toml.getLong("port") ?: defaults.port.toLong()
    check(port in 0..MAX_PORT) { "port must be 0 to $MAX_PORT, not $port" }
    // Read and checked as a Long beside the port, and for the same reason: a number of minutes
    // larger than an Int would truncate into a plausible one. No environment override — nothing in
    // CI runs the window this is for, and a variable nobody reads is one more thing to keep true.
    val idleMinutes = toml.getLong("idleSessionMinutes") ?: defaults.idleSessionMinutes.toLong()
    check(idleMinutes in 1..MAX_IDLE_MINUTES) {
        "idleSessionMinutes must be 1 to $MAX_IDLE_MINUTES, not $idleMinutes"
    }
    return ProxyConfig(
        port = port.toInt(),
        host = host,
        anthropicUpstream =
            env("PEASHOOT_ANTHROPIC_UPSTREAM")
                ?: toml.upstream("anthropic", defaults.anthropicUpstream),
        // No environment override: nothing in CI points at an OpenAI upstream, and a variable
        // nobody reads is one more thing to keep true. The file and `PUT /config` set it.
        openaiUpstream = toml.upstream("openai", defaults.openaiUpstream),
        dumpFrames = env("PEASHOOT_DUMP_FRAMES")?.let(Path::of),
        secretHeaders =
            toml
                .getArray("secretHeaders")
                ?.toList()
                ?.map { it as? String ?: error("secretHeaders must be a list of strings, not $it") }
                ?.toSet() ?: defaults.secretHeaders,
        routes =
            routes + (DEFAULT_ROUTE to routes.getValue(DEFAULT_ROUTE).withEnv(env, cassetteFile)),
        pricing = DEFAULT_PRICES + toml.getTable("pricing")?.prices().orEmpty(),
        replayCadence = toml.choice("replay.cadence") ?: defaults.replayCadence,
        repeatPolicy = toml.choice("replay.repeatPolicy") ?: defaults.repeatPolicy,
        gourceEnabled = toml.getBoolean("gource.enabled") ?: defaults.gourceEnabled,
        // A rule file that cannot be read stops the proxy: every fingerprint would be wrong, and
        // a wrong fingerprint is a silently missed replay rather than a visible failure.
        rules = Rules.parse(Files.readString(rulesFile)),
        // Read at start though only an export uses it: a broken file is found before the export
        // that would have leaked what it meant to strip.
        redaction = Redaction.parse(Files.readString(redactFile)),
        pingInterval = toml.pingInterval(defaults.pingInterval),
        cassetteFile = cassetteFile,
        idleSessionMinutes = idleMinutes.toInt(),
    )
}

/** Where one surface's traffic goes: `[surfaces.<name>] upstream`, or [default]. */
private fun TomlParseResult.upstream(name: String, default: String): String =
    getString("surfaces.$name.upstream") ?: default

/** The default route with `PEASHOOT_MODE`, `PEASHOOT_STRICT`, and `PEASHOOT_CASSETTE` on top. */
private fun Route.withEnv(env: (String) -> String?, cassetteFile: Path?): Route =
    Route(
        mode = env("PEASHOOT_MODE")?.let { parseMode(it, "PEASHOOT_MODE") } ?: mode,
        strict =
            env("PEASHOOT_STRICT")?.let {
                it.toBooleanStrictOrNull()
                    ?: error("PEASHOOT_STRICT must be true or false, not $it")
            } ?: strict,
        cassette = cassetteFile?.nameWithoutExtension ?: cassette,
    )

private fun parseMode(raw: String, key: String): Mode =
    Mode.of(raw) ?: error("$key must be record, replay, or passthrough, not $raw")

/** Any positive TOML number of seconds, or the default when the file says nothing. */
private fun TomlParseResult.pingInterval(default: Duration): Duration =
    when (val raw = get("resume.pingIntervalSeconds")) {
        null -> default
        is Number -> {
            check(raw.toDouble() > 0) { "resume.pingIntervalSeconds must be positive, not $raw" }
            raw.toDouble().seconds
        }
        else -> error("resume.pingIntervalSeconds must be a number, not $raw")
    }

/**
 * The entry a string names, spelled the way the file spells it: `inOrder` is
 * [RepeatPolicy.IN_ORDER]. Null when the file says nothing.
 */
private inline fun <reified T : Enum<T>> TomlParseResult.choice(key: String): T? {
    val raw = getString(key) ?: return null
    return enumValues<T>().firstOrNull { it.spelled() == raw }
        ?: error("$key must be one of ${enumValues<T>().joinToString { it.spelled() }}, not $raw")
}

/** `IN_ORDER` as the file writes it: `inOrder`. */
internal fun Enum<*>.spelled(): String =
    name.lowercase().split('_').let { words ->
        words.first() + words.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercase) }
    }

/** The file written on first start: the defaults, in the shape a hand edit keeps. */
private fun ProxyConfig.toToml(): String = buildString {
    appendLine(
        "# Peashoot. PEASHOOT_PORT and PEASHOOT_ANTHROPIC_UPSTREAM override port and upstream;"
    )
    appendLine("# PEASHOOT_MODE, PEASHOOT_STRICT, and PEASHOOT_CASSETTE, the default route.")
    appendLine("# PEASHOOT_HOST overrides host, which must be a loopback address.")
    appendLine("port = $port")
    appendLine("host = \"$host\"")
    appendLine("secretHeaders = [${secretHeaders.joinToString { "\"$it\"" }}]")
    appendLine()
    // A bare key, so it is written before the first table header rather than inside one.
    appendLine("# A session quiet this long ends its day with a card in the app: 1 to a day.")
    appendLine("idleSessionMinutes = $idleSessionMinutes")
    appendLine()
    appendLine("[surfaces.anthropic]")
    appendLine("upstream = \"$anthropicUpstream\"")
    appendLine()
    appendLine("# Point this at an OpenAI-compatible server to record against one for free:")
    appendLine("# Ollama serves /v1/chat/completions on http://127.0.0.1:11434.")
    appendLine("[surfaces.openai]")
    appendLine("upstream = \"$openaiUpstream\"")
    appendLine()
    appendLine("# On, every turn's file tools also go to $GOURCE_FILE, for Gource to animate.")
    appendLine("[gource]")
    appendLine("enabled = $gourceEnabled")
    appendLine()
    appendLine("# While a stream is silent this long, an SSE comment line keeps the client alive.")
    appendLine("[resume]")
    appendLine("pingIntervalSeconds = ${pingInterval.inWholeSeconds}")
    appendLine()
    appendLine("# Replay: cadence instant or recorded; repeatPolicy inOrder or latest.")
    appendLine("[replay]")
    appendLine("cadence = \"${replayCadence.spelled()}\"")
    appendLine("repeatPolicy = \"${repeatPolicy.spelled()}\"")
    appendLine()
    // The price table is bundled, so no row is written here: an entry only ever overrides one.
    appendLine(
        "# Override a price: [pricing.\"claude-sonnet-4-5\"] with input, output, cacheRead, " +
            "cacheWrite in USD per million tokens."
    )
    routes.forEach { (name, route) ->
        appendLine()
        appendLine(
            "# mode is record, replay, or passthrough; a strict replay route answers a miss 409."
        )
        appendLine("[routes.$name]")
        appendLine("mode = \"${route.mode.spelling}\"")
        appendLine("strict = ${route.strict}")
        appendLine("# cassette = \"name\" replays only what `proxy import` tagged with that name.")
        route.cassette?.let { appendLine("cassette = \"$it\"") }
    }
}

/**
 * `[pricing."<model prefix>"]`: the rates it names, over the bundled ones for that same key. A
 * model the table does not know has nothing to inherit, so it must give all four or the file is
 * wrong and says which key is missing.
 */
private fun TomlTable.prices(): Map<String, Price> =
    keySet().associateWith { model ->
        Price(
            input = rate(model, "input", Price::input),
            output = rate(model, "output", Price::output),
            cacheRead = rate(model, "cacheRead", Price::cacheRead),
            cacheWrite = rate(model, "cacheWrite", Price::cacheWrite),
        )
    }

/** Any TOML number, so `input = 3` is the rate it plainly means and not a startup failure. */
private fun TomlTable.rate(model: String, key: String, bundled: (Price) -> Double): Double =
    (get(listOf(model, key)) as? Number)?.toDouble()
        ?: DEFAULT_PRICES[model]?.let(bundled)
        ?: error("pricing.\"$model\".$key is required")

private fun TomlTable.routes(): Map<String, Route> =
    keySet().associateWith { name ->
        check(name == DEFAULT_ROUTE) {
            "routes.$name: routing is not wired yet; only the '$DEFAULT_ROUTE' route exists"
        }
        val modeString = getString(listOf(name, "mode")) ?: error("routes.$name.mode is required")
        Route(
            parseMode(modeString, "routes.$name.mode"),
            strict = getBoolean(listOf(name, "strict")) ?: false,
            cassette = getString(listOf(name, "cassette")),
        )
    }
