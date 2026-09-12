package dev.peashoot.proxy

import java.nio.file.Path

/**
 * `peashoot` entry point. Configuration is environment-only until #6 adds the data directory and
 * file.
 */
fun main() {
    val defaults = ProxyConfig()
    val config =
        ProxyConfig(
            port = System.getenv("PEASHOOT_PORT")?.toInt() ?: defaults.port,
            anthropicUpstream =
                System.getenv("PEASHOOT_ANTHROPIC_UPSTREAM") ?: defaults.anthropicUpstream,
            dumpFrames = System.getenv("PEASHOOT_DUMP_FRAMES")?.let(Path::of),
        )
    val server = ProxyServer(config)
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
    println("Peashoot listening on ${server.url}, relaying to ${config.anthropicUpstream}")
    Thread.currentThread().join()
}
