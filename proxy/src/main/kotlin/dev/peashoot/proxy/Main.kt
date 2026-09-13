package dev.peashoot.proxy

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.peashoot.proxy")

/** `peashoot` entry point: the data directory, its config, the store, and the server. */
fun main(): Unit = runBlocking {
    val home = homeDir()
    val config = loadConfig(home)
    val store = Store(home)
    val server = ProxyServer(config, listOf(Recorder(store)))
    // Ktor's own shutdown hook stops only the engine, and awaitCancellation below never returns,
    // so this hook is the one that closes the connection pool and the upstream client on
    // SIGINT/SIGTERM.
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
