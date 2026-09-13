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
        ProxyServer(config, listOf(Recorder(store))).use { server ->
            log.info(
                "Peashoot listening on {}, relaying to {}, data in {}",
                server.url,
                config.anthropicUpstream,
                home,
            )
            awaitCancellation() // Ktor's own shutdown hook stops the server on SIGINT/SIGTERM.
        }
    }
}
