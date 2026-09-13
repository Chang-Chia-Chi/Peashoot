package dev.peashoot.proxy

import dev.peashoot.core.Mode
import java.nio.file.Files
import java.nio.file.Path
import org.tomlj.Toml
import org.tomlj.TomlTable

const val CONFIG_FILE = "peashoot.toml"

private val defaultConfig =
    """
    # Peashoot. PEASHOOT_PORT and PEASHOOT_ANTHROPIC_UPSTREAM override port and upstream.
    port = 8787
    secretHeaders = ["authorization", "x-api-key"]

    [surfaces.anthropic]
    upstream = "https://api.anthropic.com"

    [routes.default]
    mode = "record"
    """
        .trimIndent() + "\n"

/** The data directory: `PEASHOOT_HOME`, else `.peashoot` under the user's home. */
fun homeDir(env: (String) -> String? = System::getenv): Path =
    env("PEASHOOT_HOME")?.let(Path::of) ?: Path.of(System.getProperty("user.home"), ".peashoot")

/**
 * Creates the directory and a default config file on first start, then loads the file with the
 * environment on top for the keys CI needs.
 */
fun loadConfig(home: Path, env: (String) -> String? = System::getenv): ProxyConfig {
    Files.createDirectories(home)
    val file = home.resolve(CONFIG_FILE)
    if (Files.notExists(file)) Files.writeString(file, defaultConfig)
    val toml = Toml.parse(file)
    check(!toml.hasErrors()) { "$file: " + toml.errors().joinToString { it.toString() } }
    val defaults = ProxyConfig()
    return ProxyConfig(
        port = env("PEASHOOT_PORT")?.toInt() ?: toml.getLong("port")?.toInt() ?: defaults.port,
        anthropicUpstream =
            env("PEASHOOT_ANTHROPIC_UPSTREAM")
                ?: toml.getString("surfaces.anthropic.upstream")
                ?: defaults.anthropicUpstream,
        dumpFrames = env("PEASHOOT_DUMP_FRAMES")?.let(Path::of),
        secretHeaders =
            toml.getArray("secretHeaders")?.toList()?.map { it.toString() }?.toSet()
                ?: defaults.secretHeaders,
        routes = defaults.routes + toml.getTable("routes")?.routes().orEmpty(),
    )
}

private fun TomlTable.routes(): Map<String, Mode> =
    keySet().associateWith { name ->
        val mode = getString("$name.mode") ?: error("routes.$name.mode is required")
        Mode.entries.firstOrNull { it.name.equals(mode, ignoreCase = true) }
            ?: error("routes.$name.mode must be record, replay, or passthrough, not $mode")
    }
