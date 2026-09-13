package dev.peashoot.proxy

import dev.peashoot.core.Mode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
                """
                    .trimIndent()
            )

        val fromFile = loadConfig(home, env())
        assertEquals(9999, fromFile.port)
        assertEquals("http://localhost:11434", fromFile.anthropicUpstream)
        assertEquals(setOf("authorization", "x-api-key", "x-goog-api-key"), fromFile.secretHeaders)
        assertEquals(mapOf("default" to Mode.PASSTHROUGH), fromFile.routes)

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
    fun `a non-string secretHeaders entry is refused with a typed message`() {
        val home = Files.createTempDirectory("peashoot-home")
        home.resolve("peashoot.toml").writeText("""secretHeaders = [1, true]""")

        val error = assertFailsWith<IllegalStateException> { loadConfig(home, env()) }
        assertContains(error.message.orEmpty(), "secretHeaders must be a list of strings")
    }
}
