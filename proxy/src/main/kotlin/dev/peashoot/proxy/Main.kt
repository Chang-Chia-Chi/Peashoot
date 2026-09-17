package dev.peashoot.proxy

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.nameWithoutExtension
import kotlin.system.exitProcess
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.peashoot.proxy")

private const val USAGE =
    "usage: proxy                                             serve\n" +
        "       proxy export <name> [--dry-run] [--session <id>]  write cassettes/<name>.jsonl\n" +
        "       proxy import <file>                               import as its base name"

/** A cassette name is a file name in the data directory, so it may not name a path. */
private val CASSETTE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

/**
 * `peashoot` entry point: with no arguments it serves; `export` and `import` run once and exit, 2
 * for a usage error and 1 for a file that cannot be read.
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) return serve()
    val output =
        try {
            runBlocking { command(args.asList(), homeDir()) }
        } catch (e: IllegalArgumentException) {
            System.err.println(e.message)
            exitProcess(2)
        } catch (e: IllegalStateException) {
            System.err.println(e.message)
            exitProcess(1)
        }
    println(output)
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
            check(Files.isRegularFile(file)) { "no such cassette: $file" }
            "imported ${importCassette(store, file)} exchanges as cassette ${file.nameWithoutExtension}"
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
    val export = exportCassette(store, config, session)
    val preview =
        export.hits.map { (id, hit) -> "$id ${hit.where}: ${hit.matched} -> ${hit.becomes}" }
    val summary = "${export.count} exchanges, ${export.hits.size} redactions"
    if (dryRun) return (preview + "dry run: $summary, nothing written").joinToString("\n")
    val file = Files.createDirectories(home.resolve(CASSETTES_DIR)).resolve("$name.jsonl")
    Files.writeString(file, export.jsonl)
    return "wrote ${export.count} exchanges to $file, ${export.hits.size} redactions"
}

/** The data directory, its config, the store, and the server, until the process is stopped. */
private fun serve(): Unit = runBlocking {
    val home = homeDir()
    val config = loadConfig(home)
    Store(home).use { store ->
        config.cassetteFile?.let { file ->
            val count = importCassette(store, file)
            log.info(
                "imported {} exchanges from {} as cassette {}",
                count,
                file,
                file.nameWithoutExtension,
            )
        }
        val gource = if (config.gourceEnabled) GourceLog(home.resolve(GOURCE_FILE)) else null
        val chain =
            listOf(
                Replay(store, config),
                Recorder(store),
                Deriver(store, home.resolve(EVENTS_FILE), config.pricing, gource),
            )
        val server = ProxyServer(config, chain)
        // `use` unwinds only if the server fails to start. Ktor's own shutdown hook stops only the
        // engine, and awaitCancellation never returns, so on SIGINT/SIGTERM this hook is what
        // closes the upstream client and then the connection pool.
        Runtime.getRuntime()
            .addShutdownHook(
                Thread {
                    server.close()
                    store.close()
                }
            )
        log.info(
            "Peashoot listening on {}, relaying to {}, data in {}",
            server.url,
            config.anthropicUpstream,
            home,
        )
        awaitCancellation()
    }
}
