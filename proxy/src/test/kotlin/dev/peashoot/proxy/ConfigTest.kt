package dev.peashoot.proxy

import dev.peashoot.core.DEFAULT_PRICES
import dev.peashoot.core.Mode
import dev.peashoot.core.Price
import dev.peashoot.core.Route
import dev.peashoot.core.homeDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
        assertContains(file.readText(), "strict = false")
        assertContains(file.readText(), "repeatPolicy = \"inOrder\"")
        val rules = home.resolve("rules.json")
        assertTrue(Files.isRegularFile(rules), "$rules")
        assertContains(rules.readText(), "keepHeaders")
        assertContains(rules.readText(), "/messages/*/content/*/text")
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
                windowSeconds = 90
                maxBufferedExchanges = 7
                """
                    .trimIndent()
            )

        val fromFile = loadConfig(home, env())
        assertEquals(9999, fromFile.port)
        assertTrue(fromFile.gourceEnabled)
        assertEquals("http://localhost:11434", fromFile.anthropicUpstream)
        assertEquals(setOf("authorization", "x-api-key", "x-goog-api-key"), fromFile.secretHeaders)
        assertEquals(mapOf("default" to Route(Mode.PASSTHROUGH)), fromFile.routes)
        assertEquals(500.milliseconds, fromFile.pingInterval)
        assertEquals(90.seconds, fromFile.resumeWindow)
        assertEquals(7, fromFile.maxBufferedExchanges)

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
    fun `the file sets a strict replay route, the cadence, and the repeat policy`() {
        val home = Files.createTempDirectory("peashoot-home")
        home
            .resolve("peashoot.toml")
            .writeText(
                """
                [routes.default]
                mode = "replay"
                strict = true

                [replay]
                cadence = "recorded"
                repeatPolicy = "latest"
                """
                    .trimIndent()
            )

        val config = loadConfig(home, env())

        assertEquals(mapOf(DEFAULT_ROUTE to Route(Mode.REPLAY, strict = true)), config.routes)
        assertEquals(Cadence.RECORDED, config.replayCadence)
        assertEquals(RepeatPolicy.LATEST, config.repeatPolicy)
    }

    @Test
    fun `the environment sets the default route's mode, strict flag, and cassette, and the port`() {
        val home = Files.createTempDirectory("peashoot-home")
        home
            .resolve("peashoot.toml")
            .writeText(
                """
                [routes.default]
                mode = "record"
                cassette = "nightly"
                """
                    .trimIndent()
            )
        assertEquals(
            Route(Mode.RECORD, cassette = "nightly"),
            loadConfig(home, env()).routes[DEFAULT_ROUTE],
        )
        val cassette = home.resolve("ci.jsonl").also { it.writeText("") }

        val config =
            loadConfig(
                home,
                env(
                    "PEASHOOT_MODE" to "replay",
                    "PEASHOOT_STRICT" to "true",
                    "PEASHOOT_CASSETTE" to "$cassette",
                    "PEASHOOT_PORT" to "4321",
                ),
            )

        assertEquals(
            mapOf(DEFAULT_ROUTE to Route(Mode.REPLAY, strict = true, cassette = "ci")),
            config.routes,
        )
        assertEquals(cassette, config.cassetteFile)
        assertEquals(4321, config.port)
    }

    @Test
    fun `an invalid environment value is refused, naming the variable`() {
        val home = Files.createTempDirectory("peashoot-home")
        listOf(
                "PEASHOOT_MODE" to "fast",
                "PEASHOOT_STRICT" to "yes",
                "PEASHOOT_PORT" to "http",
            )
            .forEach { (name, value) ->
                val error =
                    assertFailsWith<IllegalStateException> { loadConfig(home, env(name to value)) }
                assertContains(error.message.orEmpty(), name)
                assertContains(error.message.orEmpty(), value)
            }
    }

    @Test
    fun `the host is loopback by default, and a non-loopback one is refused from file or env`() {
        val home = Files.createTempDirectory("peashoot-home")
        assertEquals("127.0.0.1", loadConfig(home, env()).host)
        assertContains(home.resolve("peashoot.toml").readText(), "host = \"127.0.0.1\"")
        assertEquals("localhost", loadConfig(home, env("PEASHOOT_HOST" to "localhost")).host)
        listOf("0.0.0.0", "192.0.2.1", "no-such-host.invalid").forEach { host ->
            val error =
                assertFailsWith<IllegalStateException> {
                    loadConfig(home, env("PEASHOOT_HOST" to host))
                }
            assertContains(error.message.orEmpty(), host)
        }
        home.resolve("peashoot.toml").writeText("host = \"0.0.0.0\"\n")
        assertFailsWith<IllegalStateException> { loadConfig(home, env()) }
        assertEquals("::1", loadConfig(home, env("PEASHOOT_HOST" to "::1")).host)
    }

    @Test
    fun `first start writes the redaction file, and a malformed one is refused naming the rule`() {
        val home = Files.createTempDirectory("peashoot-home")
        loadConfig(home, env())
        assertContains(home.resolve("redact.json").readText(), "[REDACTED]")

        home
            .resolve("redact.json")
            .writeText("""[{"pointer":"/x","pattern":"(","replacement":""}]""")

        val message = assertFailsWith<IllegalStateException> { loadConfig(home, env()) }.message
        assertContains(message.orEmpty(), "redact.json: [0].pattern")
    }

    @Test
    fun `an unknown repeat policy is refused, naming the ones there are`() {
        val home = Files.createTempDirectory("peashoot-home")
        home
            .resolve("peashoot.toml")
            .writeText(
                """
                [replay]
                repeatPolicy = "random"
                """
                    .trimIndent()
            )

        val error = assertFailsWith<IllegalStateException> { loadConfig(home, env()) }
        assertContains(error.message.orEmpty(), "must be one of inOrder, latest, not random")
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

    /**
     * Zero is how the file turns resume off, so only a negative one is refused; a fraction of an
     * exchange is refused too, and each message names the key, which tomlj's typed getters do not.
     */
    @Test
    fun `a resume bound the proxy could not honour is refused, by name`() {
        listOf(
                "windowSeconds = -1" to "resume.windowSeconds must not be negative",
                "maxBufferedExchanges = -1" to "resume.maxBufferedExchanges must be 0 to",
                "maxBufferedExchanges = 100.5" to "must be a whole number",
                """windowSeconds = "300"""" to "must be a number",
            )
            .forEach { (line, message) ->
                val home = Files.createTempDirectory("peashoot-home")
                home.resolve("peashoot.toml").writeText("[resume]\n$line\n")

                val error = assertFailsWith<IllegalStateException> { loadConfig(home, env()) }
                assertContains(error.message.orEmpty(), message, message = "for $line")
            }
    }

    /** Zero on either key is legal and is what turns resume off; `ResumeBoundsTest` runs it. */
    @Test
    fun `zero on either resume bound loads`() {
        val home = Files.createTempDirectory("peashoot-home")
        home
            .resolve("peashoot.toml")
            .writeText("[resume]\nwindowSeconds = 0\nmaxBufferedExchanges = 0\n")

        val config = loadConfig(home, env())
        assertEquals(Duration.ZERO, config.resumeWindow)
        assertEquals(0, config.maxBufferedExchanges)
    }

    @Test
    fun `a port no socket could take is refused, from the file or the environment`() {
        // 5000000000 truncates to a plausible int, and 99999 binds nothing: both would have run
        // to the first bind and died there, long after the config was called good.
        listOf("65536", "99999", "5000000000", "-1").forEach { port ->
            val home = Files.createTempDirectory("peashoot-home")
            home.resolve("peashoot.toml").writeText("port = $port")

            val fromFile = assertFailsWith<IllegalStateException> { loadConfig(home, env()) }
            assertContains(fromFile.message.orEmpty(), "port")

            val fromEnv =
                assertFailsWith<IllegalStateException> {
                    loadConfig(home, env("PEASHOOT_PORT" to port))
                }
            assertContains(fromEnv.message.orEmpty(), "port")
        }
    }

    @Test
    fun `a malformed rule file is refused at startup, naming the rule`() {
        val home = Files.createTempDirectory("peashoot-home")
        home.resolve("rules.json").writeText("""{"ignorePointers":["metadata"]}""")

        val message = assertFailsWith<IllegalStateException> { loadConfig(home, env()) }.message

        assertContains(message.orEmpty(), "ignorePointers[0]")
    }
}
