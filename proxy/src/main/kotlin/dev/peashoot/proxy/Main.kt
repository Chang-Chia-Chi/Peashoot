package dev.peashoot.proxy

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.peashoot.proxy")

/** `peashoot` entry point: the data directory, its config, the store, and the server. */
fun main(): Unit = runBlocking {
    val home = homeDir()
    val config = loadConfig(home)
    Store(home).use { store ->
        val gource = if (config.gourceEnabled) GourceLog(home.resolve(GOURCE_FILE)) else null
        val chain =
            listOf(
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
