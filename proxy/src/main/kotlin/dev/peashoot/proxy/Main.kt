package dev.peashoot.proxy

/**
 * `peashoot` entry point. Configuration is environment-only until #6 adds the data directory and
 * file.
 */
fun main() {
    val config =
        ProxyConfig(
            port = System.getenv("PEASHOOT_PORT")?.toInt() ?: 8787,
            anthropicUpstream =
                System.getenv("PEASHOOT_ANTHROPIC_UPSTREAM") ?: "https://api.anthropic.com",
        )
    val server = ProxyServer(config)
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
    println("Peashoot listening on ${server.url}, relaying to ${config.anthropicUpstream}")
    Thread.currentThread().join()
}
