package dev.peashoot.proxy

import dev.peashoot.core.text
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `GET /config` and `PUT /config`: what the answer says the proxy runs, what a put applies now,
 * what waits for a restart, and what the file keeps either way.
 */
class ControlConfigTest {
    @Test
    fun `a put leaves the routes the table owns alone unless it names them`() = withProxy {
        val route = call(HttpMethod.Put, "/routes/default", """{"mode":"replay","strict":true}""")
        assertEquals(200, route.status.value, route.bodyAsText())

        okJson(call(HttpMethod.Put, "/config", """{"port":9999}"""))

        assertEquals("replay", json("/routes").getValue("default").jsonObject["mode"]?.text())
        val live = json("/config").getValue("routes").jsonObject.getValue("default").jsonObject
        assertEquals("replay", live["mode"]?.text(), "the config answers with the live routes")
        assertTrue(live.getValue("strict").jsonPrimitive.content.toBoolean())
        assertEquals(409, relay(REQUEST).first, "and the route in memory is what still runs")
    }

    @Test
    fun `a port that is no port is refused, naming the key, and the file is untouched`() =
        withProxy {
            val before = home.resolve(CONFIG_FILE).readText()
            listOf("65536", "99999", "5000000000", "-1", "\"nine\"").forEach { port ->
                val refusal = call(HttpMethod.Put, "/config", """{"port":$port}""")
                assertContains(assertProblem(refusal, 400), "port")
            }
            assertEquals(before, home.resolve(CONFIG_FILE).readText())
        }

    @Test
    fun `a put repairs a config file that can no longer be loaded`() = withProxy {
        home.resolve(CONFIG_FILE).writeText("port = = 3")

        val put = okJson(call(HttpMethod.Put, "/config", """{"gource":{"enabled":true}}"""))

        assertEquals(listOf("gource"), put.getValue("restartRequired").jsonArray.map { it.text() })
        val repaired = loadConfig(home) { null }
        assertTrue(repaired.gourceEnabled, "the file loads again, holding what the put asked for")
        assertEquals(json("/config")["host"]?.text(), repaired.host, "merged onto what runs")
    }

    @Test
    fun `a put names only the keys it changes, and the file loads back as it was written`() =
        withProxy {
            okJson(call(HttpMethod.Put, "/config", """{"replay":{"cadence":"recorded"}}"""))

            val file = loadConfig(home) { null }
            assertEquals(Cadence.RECORDED, file.replayCadence)
            assertEquals(RepeatPolicy.IN_ORDER, file.repeatPolicy, "the sibling key is kept")

            // Render, load, and it is the same config: the whole answer put back verbatim.
            val running = json("/config")
            okJson(call(HttpMethod.Put, "/config", running.toString()))
            assertEquals(running, loadConfig(home) { null }.toJson())
            assertEquals(running, json("/config"))
        }

    @Test
    fun `secret headers take a restart, so no put starts storing a credential`() = withProxy {
        val put = okJson(call(HttpMethod.Put, "/config", """{"secretHeaders":[]}"""))

        assertEquals(
            listOf("secretHeaders"),
            put.getValue("restartRequired").jsonArray.map { it.text() },
        )
        assertContains(home.resolve(CONFIG_FILE).readText(), "secretHeaders = []")
        relay(REQUEST, mapOf("authorization" to "Bearer $CREDENTIAL"))
        awaitRecordings(1)
        assertNull(store.list().single().exchange.request.headers["authorization"])
        assertEquals(
            listOf("authorization", "x-api-key"),
            json("/config").getValue("secretHeaders").jsonArray.map { it.text() },
        )
    }

    @Test
    fun `an environment override wins over the file, and a put says which`() =
        withProxy(env = { name -> "8788".takeIf { name == "PEASHOOT_PORT" } }) {
            val put = okJson(call(HttpMethod.Put, "/config", """{"port":9000,"host":"::1"}"""))

            assertEquals(
                listOf("host", "port"),
                put.getValue("restartRequired").jsonArray.map { it.text() },
            )
            assertEquals(
                listOf("PEASHOOT_PORT"),
                put.getValue("environmentWins").jsonArray.map { it.text() },
                "the file's port will not run, restart or no restart",
            )
            assertContains(home.resolve(CONFIG_FILE).readText(), "port = 9000")
        }

    @Test
    fun `the values only the environment sets are answered, and a put may not set them`() =
        withProxy(env = { name -> "spool.txt".takeIf { name == "PEASHOOT_DUMP_FRAMES" } }) {
            val config = json("/config")
            assertEquals("spool.txt", config["dumpFrames"]?.text())
            assertTrue("cassetteFile" in config, "$config")

            val refusal = call(HttpMethod.Put, "/config", """{"dumpFrames":"other"}""")
            assertContains(assertProblem(refusal, 400), "PEASHOOT_DUMP_FRAMES")
            assertEquals("spool.txt", json("/config")["dumpFrames"]?.text())
        }

    @Test
    fun `what a put applies now, and what it can only write down`() = withProxy {
        val live =
            mapOf(
                "surfaces" to """{"anthropic":{"upstream":"http://127.0.0.1:1"}}""",
                "routes" to """{"default":{"mode":"passthrough"}}""",
                // Nothing in this process reads it, so there is nothing here a restart could
                // change: the next `GET /config` is the next thing that reads it at all.
                "idleSessionMinutes" to "45",
            )
        val restart =
            mapOf(
                "port" to "9001",
                "host" to "\"localhost\"",
                "secretHeaders" to """["authorization"]""",
                "replay" to """{"cadence":"recorded"}""",
                "resume" to """{"pingIntervalSeconds":30}""",
                "gource" to """{"enabled":true}""",
                "pricing" to """{"claude-sonnet-4-5":$PRICE}""",
            )
        assertEquals(RESTART_REQUIRED, restart.keys, "every key that takes a restart is tried here")
        assertEquals(
            json("/config").keys,
            live.keys + restart.keys + ENV_ONLY.keys,
            "and every key the config answers with is one or the other",
        )
        (live + restart).forEach { (key, value) ->
            val before = json("/config")[key]
            val put = okJson(call(HttpMethod.Put, "/config", """{"$key":$value}"""))
            assertEquals(
                if (key in restart) listOf(key) else emptyList(),
                put.getValue("restartRequired").jsonArray.map { it.text() },
                key,
            )
            val after = json("/config")[key]
            if (key in restart) assertEquals(before, after, "$key is what the process still runs")
            else assertNotEquals(before, after, "$key reaches the next request")
        }
    }

    @Test
    fun `puts that arrive at once leave one config and one rule set, both loadable`() = withProxy {
        val answers = coroutineScope {
            (1..CONCURRENT)
                .map { n ->
                    async {
                        if (n % 2 == 0) call(HttpMethod.Put, "/config", GOURCE)
                        else call(HttpMethod.Put, "/rules", """{"ignorePointers":["/n$n"]}""")
                    }
                }
                .map { it.await() }
        }
        answers.forEach { assertEquals(200, it.status.value, it.bodyAsText()) }

        val file = loadConfig(home) { null }
        assertTrue(file.gourceEnabled, "every put landed in one loadable file")
        assertEquals(file.rules.toJson(), json("/rules"), "and the live rules are the file's")
    }

    @Test
    fun `putting the config writes the file, applies the route live, and flags the port`() =
        withProxy {
            val running = json("/config")
            assertFalse(token in running.toString(), "no secret is in the config")
            assertEquals(
                listOf("authorization", "x-api-key"),
                running.getValue("secretHeaders").jsonArray.map { it.text() },
            )

            val put = okJson(call(HttpMethod.Put, "/config", """{"port":9999}"""))
            assertEquals(
                listOf("port"),
                put.getValue("restartRequired").jsonArray.map { it.text() },
            )
            assertContains(home.resolve(CONFIG_FILE).readText(), "port = 9999")
            assertEquals(running["port"], json("/config")["port"], "the running port is unchanged")

            val live =
                okJson(
                    call(
                        HttpMethod.Put,
                        "/config",
                        """{"routes":{"default":{"mode":"replay","strict":true}}}""",
                    )
                )
            assertEquals(emptyList(), live.getValue("restartRequired").jsonArray.toList())
            assertEquals("replay", json("/routes").getValue("default").jsonObject["mode"]?.text())
            assertEquals(
                409,
                relay("""{"model":"m","messages":[]}""").first,
                "strict, with no restart",
            )
            assertEquals(0, upstream.received.size)
            assertContains(home.resolve(CONFIG_FILE).readText(), "mode = \"replay\"")
        }

    @Test
    fun `a config value the loader refuses is a 400 naming the key, and the file is untouched`() =
        withProxy {
            val before = home.resolve(CONFIG_FILE).readText()
            listOf(
                    """{"port":"nine"}""" to "port",
                    """{"host":"192.0.2.1"}""" to "192.0.2.1",
                    """{"replay":{"cadence":"soon"}}""" to "cadence",
                    """{"resume":{"pingIntervalSeconds":0}}""" to "pingIntervalSeconds",
                    """{"idleSessionMinutes":0}""" to "idleSessionMinutes",
                    """{"idleSessionMinutes":100000}""" to "idleSessionMinutes",
                    """{"idleSessionMinutes":"ten"}""" to "idleSessionMinutes",
                    """{"routes":{"other":{"mode":"record"}}}""" to "other",
                    """{"secretHeaders":[7]}""" to "secretHeaders",
                    """{"nope":1}""" to "nope",
                    "not json" to "JSON",
                )
                .forEach { (body, named) ->
                    assertContains(assertProblem(call(HttpMethod.Put, "/config", body), 400), named)
                }
            assertEquals(before, home.resolve(CONFIG_FILE).readText())
        }

    private companion object {
        const val REQUEST = """{"model":"claude-sonnet-4-5","messages":[]}"""
        const val CREDENTIAL = "sk-ant-api03-ABCDEFGHIJKLMNOPQRSTUVWX"
        const val PRICE = """{"input":1.0,"output":2.0,"cacheRead":3.0,"cacheWrite":4.0}"""
        const val GOURCE = """{"gource":{"enabled":true}}"""
        const val CONCURRENT = 8
    }
}
