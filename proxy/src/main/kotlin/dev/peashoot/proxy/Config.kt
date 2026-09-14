package dev.peashoot.proxy

import dev.peashoot.core.DEFAULT_PRICES
import dev.peashoot.core.Mode
import dev.peashoot.core.Price
import java.nio.file.Files
import java.nio.file.Path
import org.tomlj.Toml
import org.tomlj.TomlTable

const val CONFIG_FILE = "peashoot.toml"

const val DEFAULT_ROUTE = "default"

data class ProxyConfig(
    val port: Int = 8787,
    val anthropicUpstream: String = "https://api.anthropic.com",
    /** Debug: append every raw upstream response to this file, for building fixtures. */
    val dumpFrames: Path? = null,
    /** Sent upstream, never kept: not on the Exchange, not in any log or file. Any case. */
    val secretHeaders: Set<String> = setOf("authorization", "x-api-key"),
    /** What each route does; every request takes [DEFAULT_ROUTE] until routing arrives. */
    val routes: Map<String, Mode> = mapOf(DEFAULT_ROUTE to Mode.RECORD),
    /** The bundled price table, with any model-prefix override from the file on top of it. */
    val pricing: Map<String, Price> = DEFAULT_PRICES,
) {
    /** [secretHeaders] lower-cased once, since header names compare case-insensitively. */
    val lowercaseSecretHeaders: Set<String> = secretHeaders.map(String::lowercase).toSet()

    /** [anthropicUpstream] without a trailing slash, so a request path appends directly. */
    val upstreamBase: String = anthropicUpstream.trimEnd('/')
}

/** The data directory: `PEASHOOT_HOME`, else `.peashoot` under the user's home. */
fun homeDir(env: (String) -> String? = System::getenv): Path =
    env("PEASHOOT_HOME")?.let(Path::of) ?: Path.of(System.getProperty("user.home"), ".peashoot")

/**
 * Creates the directory and a default config file on first start, then loads the file with the
 * environment on top for the keys CI needs. The file is rendered from [ProxyConfig]'s own defaults,
 * so the two cannot drift.
 */
fun loadConfig(home: Path, env: (String) -> String? = System::getenv): ProxyConfig {
    Files.createDirectories(home)
    val file = home.resolve(CONFIG_FILE)
    val defaults = ProxyConfig()
    if (Files.notExists(file)) Files.writeString(file, defaults.toToml())
    val toml = Toml.parse(file)
    check(!toml.hasErrors()) { "$file: " + toml.errors().joinToString { it.toString() } }
    return ProxyConfig(
        port =
            env("PEASHOOT_PORT")?.let {
                it.toIntOrNull() ?: error("PEASHOOT_PORT must be a port number, not $it")
            } ?: toml.getLong("port")?.toInt() ?: defaults.port,
        anthropicUpstream =
            env("PEASHOOT_ANTHROPIC_UPSTREAM")
                ?: toml.getString("surfaces.anthropic.upstream")
                ?: defaults.anthropicUpstream,
        dumpFrames = env("PEASHOOT_DUMP_FRAMES")?.let(Path::of),
        secretHeaders =
            toml
                .getArray("secretHeaders")
                ?.toList()
                ?.map { it as? String ?: error("secretHeaders must be a list of strings, not $it") }
                ?.toSet() ?: defaults.secretHeaders,
        routes = defaults.routes + toml.getTable("routes")?.routes().orEmpty(),
        pricing = DEFAULT_PRICES + toml.getTable("pricing")?.prices().orEmpty(),
    )
}

/** The file written on first start: the defaults, in the shape a hand edit keeps. */
private fun ProxyConfig.toToml(): String = buildString {
    appendLine(
        "# Peashoot. PEASHOOT_PORT and PEASHOOT_ANTHROPIC_UPSTREAM override port and upstream."
    )
    appendLine("port = $port")
    appendLine("secretHeaders = [${secretHeaders.joinToString { "\"$it\"" }}]")
    appendLine()
    appendLine("[surfaces.anthropic]")
    appendLine("upstream = \"$anthropicUpstream\"")
    appendLine()
    // The price table is bundled, so no row is written here: an entry only ever overrides one.
    appendLine(
        "# Override a price: [pricing.\"claude-sonnet-4-5\"] with input, output, cacheRead, " +
            "cacheWrite in USD per million tokens."
    )
    routes.forEach { (name, mode) ->
        appendLine()
        appendLine("[routes.$name]")
        appendLine("mode = \"${mode.name.lowercase()}\"")
    }
}

/** `[pricing."<model prefix>"]`: all four rates, or the file is wrong and says which key. */
private fun TomlTable.prices(): Map<String, Price> =
    keySet().associateWith { model ->
        Price(
            input = rate(model, "input"),
            output = rate(model, "output"),
            cacheRead = rate(model, "cacheRead"),
            cacheWrite = rate(model, "cacheWrite"),
        )
    }

private fun TomlTable.rate(model: String, key: String): Double =
    getDouble(listOf(model, key)) ?: error("pricing.\"$model\".$key is required")

private fun TomlTable.routes(): Map<String, Mode> =
    keySet().associateWith { name ->
        check(name == DEFAULT_ROUTE) {
            "routes.$name: routing is not wired yet; only the '$DEFAULT_ROUTE' route exists"
        }
        val modeString = getString(listOf(name, "mode")) ?: error("routes.$name.mode is required")
        Mode.entries.firstOrNull { it.name.equals(modeString, ignoreCase = true) }
            ?: error("routes.$name.mode must be record, replay, or passthrough, not $modeString")
    }
