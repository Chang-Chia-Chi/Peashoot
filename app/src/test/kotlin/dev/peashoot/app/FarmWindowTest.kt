package dev.peashoot.app

import dev.peashoot.app.farm.FarmState
import dev.peashoot.app.farm.Villager
import dev.peashoot.app.render.Hit
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val TIMEOUT_MILLIS = 30_000L

/**
 * The farm window's process boundary, against a real child process: a stand-in window
 * ([fakeFarmWindow]) started from this test's own classpath, which answers each farm it is sent
 * with a click on that farm's first villager, the way the Godot farm answers a click.
 */
class FarmWindowTest {
    @Test
    fun `each farm goes out as a line and each click comes back as a hit`() = runBlocking {
        val farm =
            FarmState(
                villagers =
                    mapOf(
                        "sess-a" to
                            Villager(id = "sess-a", name = "Ada", session = "sess-a", parent = null)
                    )
            )
        val hits = mutableListOf<Hit>()
        var buys = 0
        withTimeout(TIMEOUT_MILLIS) {
            runFarmWindow(fakeWindow(), flowOf(farm), hits::add) { buys++ }
        }
        assertEquals(listOf<Hit>(Hit.OnVillager("sess-a")), hits)
        assertEquals(1, buys, "the sign's click is a purchase, and not a hit")
    }

    @Test
    fun `a window that cannot start is no farm window, not a crash`() = runBlocking {
        val hits = mutableListOf<Hit>()
        runFarmWindow(listOf("peashoot-no-such-godot"), flowOf(FarmState()), hits::add)
        assertEquals(emptyList(), hits)
    }

    @Test
    fun `a click line names a villager or a crop, and anything else is not a click`() {
        assertEquals(
            Hit.OnVillager("sess-a/agent-one"),
            hitOf("""{"villager":"sess-a/agent-one"}"""),
        )
        assertEquals(Hit.OnCrop("src/App.kt"), hitOf("""{"crop":"src/App.kt"}"""))
        assertNull(hitOf("Godot Engine v4.7.stable.official - https://godotengine.org"))
        assertNull(hitOf("""{"villager":7}"""))
        assertNull(hitOf("""{"weather":"CLEAR"}"""))
        assertNull(hitOf("{not json"))
        assertNull(hitOf("""{"buy":"plot"}"""))
        assertTrue(buysPlot("""{"buy":"plot"}"""))
        assertFalse(buysPlot("""{"buy":"cow"}"""))
        assertFalse(buysPlot("""{"villager":"sess-a"}"""))
    }

    @Test
    fun `no godot configured is no farm window, and one configured is told it is fed by the app`() {
        assertNull(farmWindowCommand({ null }, { true }, packaged = null))
        val godot = { name: String -> if (name == "PEASHOOT_GODOT") "/opt/godot" else null }
        val command = farmWindowCommand(godot, isProject = { false })
        assertEquals(listOf("/opt/godot", "--path", "farm", "--", "--from-app"), command)
    }

    @Test
    fun `an installed app with no godot configured starts the farm it carries`() {
        val resources = File("resources")
        val exe = File(resources, "farm/peashoot-farm.exe")
        val command = farmWindowCommand({ null }, { false }, resources.path) { it == exe }
        assertEquals(listOf(exe.path, "--", "--from-app"), command)
        assertNull(farmWindowCommand({ null }, { false }, resources.path) { false })
        // A Godot configured outright wins over the carried farm: it is how the farm is developed.
        val godot = { name: String -> if (name == "PEASHOOT_GODOT") "/opt/godot" else null }
        assertEquals(
            "/opt/godot",
            farmWindowCommand(godot, { false }, resources.path) { true }?.first(),
        )
    }

    @Test
    fun `the farm project is found from the repository root or from app, or named outright`() {
        val godot = { name: String -> if (name == "PEASHOOT_GODOT") "/opt/godot" else null }
        assertEquals("farm", farmWindowCommand(godot, isProject = { it == "farm" })?.get(2))
        assertEquals("../farm", farmWindowCommand(godot, isProject = { it == "../farm" })?.get(2))
        val named = { name: String ->
            if (name == "PEASHOOT_FARM_PROJECT") "/srv/farm" else godot(name)
        }
        assertEquals("/srv/farm", farmWindowCommand(named, isProject = { false })?.get(2))
    }
}

private fun fakeWindow(): List<String> =
    listOf(
        File(System.getProperty("java.home"), "bin/java").path,
        "-cp",
        System.getProperty("java.class.path"),
        "dev.peashoot.app.FarmWindowTestKt",
    )

/**
 * The stand-in window: reads one farm, clicks its first villager and then the "For sale" sign, and
 * closes, which is the window being closed by hand.
 */
fun main() {
    val farm = Json.parseToJsonElement(readln()).jsonObject
    val first = farm.getValue("villagers").jsonArray.first().jsonObject
    println("Godot Engine v4.7 - a banner line the app must ignore")
    println("""{"villager":"${first.getValue("id").jsonPrimitive.content}"}""")
    println("""{"buy":"plot"}""")
}
