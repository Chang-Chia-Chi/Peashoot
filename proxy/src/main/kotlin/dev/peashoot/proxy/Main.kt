package dev.peashoot.proxy

import java.nio.file.Path
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.peashoot.proxy")

/**
 * `peashoot` entry point. Configuration is environment-only until #6 adds the data directory and
 * file.
 */
fun main(): Unit = runBlocking {
    val defaults = ProxyConfig()
    val config =
        ProxyConfig(
            port = System.getenv("PEASHOOT_PORT")?.toInt() ?: defaults.port,
            anthropicUpstream =
                System.getenv("PEASHOOT_ANTHROPIC_UPSTREAM") ?: defaults.anthropicUpstream,
            dumpFrames = System.getenv("PEASHOOT_DUMP_FRAMES")?.let(Path::of),
        )
    ProxyServer(config).use { server ->
        log.info("Peashoot listening on {}, relaying to {}", server.url, config.anthropicUpstream)
        awaitCancellation() // Ktor's own shutdown hook stops the server on SIGINT/SIGTERM.
    }
}
