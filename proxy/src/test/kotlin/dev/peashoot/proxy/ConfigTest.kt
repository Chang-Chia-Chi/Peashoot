package dev.peashoot.proxy

import dev.peashoot.core.DEFAULT_PRICES
import dev.peashoot.core.Mode
import dev.peashoot.core.Price
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** The data directory and its config file, from a first start to an edited file. */
class ConfigTest {
    private fun env(vararg pairs: Pair<String, String>): (String) -> String? = mapOf(*pairs)::get

    @Test
    fun `the home is the override when set, else dot-peashoot under the user's home`() {
        assertEquals(Path.of("/srv/peashoot"), homeDir(env("PEASHOOT_HOME" to "/srv/peashoot")))
        assertEquals(Path.of(System.getProperty("user.home"), ".peashoot"), homeDir(env()))
    }

    @Test
    fun `first start creates the directory and a default config file, and loads the defaults`() {
        val home = Files.createTempDirectory("peashoot-home").resolve("nested").resolve(".peashoot")

        val config = loadConfig(home, env())

        assertEquals(ProxyConfig(), config)
        val file = home.resolve("peashoot.toml")
        assertTrue(Files.isRegularFile(file), "$file")
        assertContains(file.readText(), "mode = \"record\"")
        assertContains(file.readText(), "[gource]")
        assertContains(file.readText(), "[resume]")
        assertEquals(
            config,
            loadConfig(home, env()),
            "the written defaults load back as themselves",
        )
    }

    @Test
    fun `the file sets port, upstream, secrets, and route modes, and the environment wins for port and upstream`() {
        val home = Files.createTempDirectory("peashoot-home")
        home
            .resolve("peashoot.toml")
            .writeText(
                """
                port = 9999
                secretHeaders = ["authorization", "x-api-key", "x-goog-api-key"]

                [surfaces.anthropic]
                upstream = "http://localhost:11434"

                [routes.default]
                mode = "passthrough"

                [gource]
                enabled = true

                [resume]
                pingIntervalSeconds = 0.5
                """
                    .trimIndent()
            )

        val fromFile = loadConfig(home, env())
        assertEquals(9999, fromFile.port)
        assertTrue(fromFile.gourceEnabled)
        assertEquals("http://localhost:11434", fromFile.anthropicUpstream)
        assertEquals(setOf("authorization", "x-api-key", "x-goog-api-key"), fromFile.secretHeaders)
        assertEquals(mapOf("default" to Mode.PASSTHROUGH), fromFile.routes)
        assertEquals(500.milliseconds, fromFile.pingInterval)

        val fromEnv =
            loadConfig(
                home,
                env(
                    "PEASHOOT_PORT" to "1234",
                    "PEASHOOT_ANTHROPIC_UPSTREAM" to "http://127.0.0.1:1",
                ),
            )
        assertEquals(1234, fromEnv.port)
        assertEquals("http://127.0.0.1:1", fromEnv.anthropicUpstream)
        assertEquals(fromFile.routes, fromEnv.routes)
    }

    @Test
    fun `a route in replay mode is refused at startup until replay is implemented`() {
        // At the server, not only the loader: a directly constructed config fails the same way.
        val config = ProxyConfig(routes = mapOf(DEFAULT_ROUTE to Mode.REPLAY))

        val error = assertFailsWith<IllegalArgumentException> { ProxyServer(config) }
        assertContains(error.message.orEmpty(), "#12")
    }

    @Test
    fun `a route other than default is refused until routing is wired`() {
        val home = Files.createTempDirectory("peashoot-home")
        home
            .resolve("peashoot.toml")
            .writeText(
                """
                [routes.ci]
                mode = "record"
                """
                    .trimIndent()
            )

        val error = assertFailsWith<IllegalStateException> { loadConfig(home, env()) }
        assertContains(error.message.orEmpty(), "routing is not wired")
    }

    @Test
    fun `a pricing entry overrides the rates it names and inherits the rest of a bundled model`() {
        val home = Files.createTempDirectory("peashoot-home")
        home
            .resolve("peashoot.toml")
            .writeText(
                """
                [pricing."claude-sonnet-4-5"]
                input = 3

                [pricing."my-local-model"]
                input = 0.5
                output = 1.5
                cacheRead = 0.05
                cacheWrite = 0.6
                """
                    .trimIndent()
            )

        val pricing = loadConfig(home, env()).pricing

        assertEquals(
            Price(input = 3.0, output = 15.0, cacheRead = 0.3, cacheWrite = 3.75),
            pricing["claude-sonnet-4-5"],
            "an integer rate is a rate, and the three it did not name stay bundled",
        )
        assertEquals(
            Price(input = 0.5, output = 1.5, cacheRead = 0.05, cacheWrite = 0.6),
            pricing["my-local-model"],
        )
        assertEquals(DEFAULT_PRICES["claude-opus-4-5"], pricing["claude-opus-4-5"])
    }

    @Test
    fun `a partial pricing entry for a model the table does not know is refused`() {
        val home = Files.createTempDirectory("peashoot-home")
        home
            .resolve("peashoot.toml")
            .writeText(
                """
                [pricing."my-local-model"]
                input = 1.0
                """
                    .trimIndent()
            )

        val error = assertFailsWith<IllegalStateException> { loadConfig(home, env()) }
        assertContains(error.message.orEmpty(), """pricing."my-local-model".output""")
    }

    @Test
    fun `a non-string secretHeaders entry is refused with a typed message`() {
        val home = Files.createTempDirectory("peashoot-home")
        home.resolve("peashoot.toml").writeText("""secretHeaders = [1, true]""")

        val error = assertFailsWith<IllegalStateException> { loadConfig(home, env()) }
        assertContains(error.message.orEmpty(), "secretHeaders must be a list of strings")
    }

    @Test
    fun `a non-positive pingIntervalSeconds is refused`() {
        val home = Files.createTempDirectory("peashoot-home")
        home
            .resolve("peashoot.toml")
            .writeText(
                """
                [resume]
                pingIntervalSeconds = 0
                """
                    .trimIndent()
            )

        val error = assertFailsWith<IllegalStateException> { loadConfig(home, env()) }
        assertContains(error.message.orEmpty(), "must be positive")
    }
}
