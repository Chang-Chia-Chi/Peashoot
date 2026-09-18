package dev.peashoot.proxy

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.nameWithoutExtension
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.peashoot.proxy")

private const val USAGE =
    "usage: proxy                                             serve\n" +
        "       proxy export <name> [--dry-run] [--session <id>]  write cassettes/<name>.jsonl\n" +
        "       proxy import <file>                               import as its base name"

/** How much of a redacted match a preview shows, at most: enough to recognise, not to use. */
private const val SHOWN_OF_MATCH = 6

/**
 * `peashoot` entry point: with no arguments it serves; `export` and `import` run once and exit. A
 * usage error exits 2, and a config or file that cannot be used exits 1, both with the message
 * alone: the trace would only bury which file or variable to fix.
 */
fun main(args: Array<String>) {
    try {
        if (args.isEmpty()) serve() else println(runBlocking { command(args.asList(), homeDir()) })
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        exitProcess(if (args.isEmpty()) 1 else 2)
    } catch (e: IllegalStateException) {
        System.err.println(e.message)
        exitProcess(1)
    }
}

/**
 * `export` and `import`, as the control API (#15) will call them: what to print, or an
 * IllegalArgumentException carrying the usage. They open the store the server uses, so they run
 * beside a live proxy as any second reader does.
 */
suspend fun command(
    args: List<String>,
    home: Path,
    env: (String) -> String? = System::getenv,
): String {
    val verb = args.firstOrNull()
    require(verb == "export" || (verb == "import" && args.size == 2)) { USAGE }
    val config = loadConfig(home, env)
    return Store(home).use { store ->
        if (verb == "import") {
            val file = Path.of(args[1])
            val count = importCassette(store, config, file)
            "imported $count exchanges as cassette ${file.nameWithoutExtension}"
        } else export(store, config, home, args.drop(1))
    }
}

/** `export <name> [--dry-run] [--session <id>]`, flags in any order. */
private suspend fun export(
    store: Store,
    config: ProxyConfig,
    home: Path,
    args: List<String>,
): String {
    var name: String? = null
    var dryRun = false
    var session: String? = null
    val rest = args.iterator()
    while (rest.hasNext()) {
        when (val arg = rest.next()) {
            "--dry-run" -> dryRun = true
            "--session" ->
                session =
                    rest.takeIf { it.hasNext() }?.next() ?: throw IllegalArgumentException(USAGE)
            else -> {
                require(name == null && CASSETTE_NAME.matches(arg)) { USAGE }
                name = arg
            }
        }
    }
    requireNotNull(name) { USAGE }
    val export = exportCassette(store, config, sessions = listOfNotNull(session))
    val redactions = export.hits.values.sumOf { it.size }
    if (dryRun) {
        val preview =
            export.hits.flatMap { (id, hits) ->
                hits.map { "$id ${it.where}: ${masked(it.matched)} -> ${it.becomes}" }
            }
        val summary = "dry run: ${export.count} exchanges, $redactions redactions, nothing written"
        return (preview + summary).joinToString("\n")
    }
    val file = Files.createDirectories(home.resolve(CASSETTES_DIR)).resolve("$name.jsonl")
    Files.writeString(file, export.jsonl)
    return "wrote ${export.count} exchanges to $file, $redactions redactions"
}

/**
 * A match as a preview shows it: a preview lands in terminals and CI logs, and what a rule matched
 * is usually the secret it exists to strip. Half of a short match at most.
 */
internal fun masked(match: String): String =
    "${match.take(minOf(SHOWN_OF_MATCH, match.length / 2))}... (${match.length} chars)"

/** The data directory, its config, the store, and the server, until the process is stopped. */
private fun serve(): Unit = runBlocking {
    val home = homeDir()
    val config = loadConfig(home)
    Store(home).use { store ->
        config.cassetteFile?.let { file ->
            // Checked here, not in the config: an export beside it has no use for the file.
            check(Files.isRegularFile(file)) { "PEASHOOT_CASSETTE names no file: $file" }
            val count = importCassette(store, config, file)
            log.info(
                "imported {} exchanges from {} as cassette {}",
                count,
                file,
                file.nameWithoutExtension,
            )
        }
        val gource = if (config.gourceEnabled) GourceLog(home.resolve(GOURCE_FILE)) else null
        // One route table, one config, and one feed, shared by the chain and the control API.
        val control = ControlApi(store, home, RouteTable(config.routes), LiveConfig(config))
        val chain =
            listOf(
                Replay(store, config),
                Recorder(store),
                Deriver(store, home.resolve(EVENTS_FILE), config.pricing, gource, control.feed),
            )
        val server = ProxyServer(config, chain, control)
        // `use` unwinds if the server fails to start, and when the control API is asked to stop.
        // Ktor's own shutdown hook stops only the engine, so on SIGINT/SIGTERM this hook is what
        // closes the upstream client and then the connection pool.
        Runtime.getRuntime()
            .addShutdownHook(
                Thread {
                    server.close()
                    store.close()
                }
            )
        log.info(
            "Peashoot listening on {}, relaying to {}, data in {}, control API at {}{}/v1 " +
                "with the token in {}",
            server.url,
            config.anthropicUpstream,
            home,
            server.url,
            CONTROL_PREFIX,
            home.resolve(TOKEN_FILE),
        )
        // `POST /shutdown` completes this once its answer is written; nothing else does, so every
        // other way out is still the shutdown hook's. Returning here unwinds both `use` blocks.
        control.stopping.await()
        log.info("stopping: the control API was asked to")
        server.close()
    }
}
