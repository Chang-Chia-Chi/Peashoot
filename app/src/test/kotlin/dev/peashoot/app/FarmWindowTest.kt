package dev.peashoot.app

import dev.peashoot.app.farm.FarmState
import dev.peashoot.app.farm.Villager
import dev.peashoot.app.render.Hit
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
        withTimeout(TIMEOUT_MILLIS) { runFarmWindow(fakeWindow(), flowOf(farm), hits::add) }
        assertEquals(listOf<Hit>(Hit.OnVillager("sess-a")), hits)
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
    }

    @Test
    fun `no godot configured is no farm window, and one configured is told who its parent is`() {
        assertNull(farmWindowCommand { null })
        val command = farmWindowCommand { if (it == "PEASHOOT_GODOT") "/opt/godot" else null }
        assertEquals(listOf("/opt/godot", "--path", "farm", "--", "--parent"), command?.take(5))
        assertEquals(ProcessHandle.current().pid().toString(), command?.last())
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
 * The stand-in window: reads one farm, clicks its first villager, and closes, which is the window
 * being closed by hand.
 */
fun main() {
    val farm = Json.parseToJsonElement(readln()).jsonObject
    val first = farm.getValue("villagers").jsonArray.first().jsonObject
    println("Godot Engine v4.7 - a banner line the app must ignore")
    println("""{"villager":"${first.getValue("id").jsonPrimitive.content}"}""")
}
