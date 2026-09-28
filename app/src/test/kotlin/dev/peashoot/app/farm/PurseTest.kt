package dev.peashoot.app.farm

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir

/**
 * What the agents' work earns, at the reducer's seam: recorded lines in, the purse after each out.
 * A coin a turn, a harvest bonus once a crop ripens, and nothing for a replayed line.
 */
class PurseTest {
    @Test
    fun `each turn that ended earns a coin`() {
        val states = replay("one-turn.jsonl")
        assertEquals(listOf(0, 1, 1, 2), states.map { it.purse.coins })
        assertEquals(states.map { it.bin.produce }, states.map { it.purse.earned })
    }

    @Test
    fun `a crop's ripening earns a harvest on top of its turn, once`() {
        // The second turn again: its Edit takes App.kt from growing to ripe, and a fourth leaves
        // it ripe, so only the third turn harvests.
        val again = events("one-turn.jsonl")[3]
        val lines = events("one-turn.jsonl") + again + again
        val purses = lines.runningFold(FarmState()) { state, line -> reduce(state, line) }
        val gains = purses.zipWithNext { a, b -> b.purse.earned - a.purse.earned }
        assertEquals(listOf(0, 1, 0, 1, 1 + COINS_A_HARVEST, 1), gains)
        assertEquals(Growth.RIPE, purses.last().crop("src/main/App.kt")?.growth)
    }

    @Test
    fun `a replayed turn earns nothing`() {
        val lines = events("one-turn.jsonl").map { it.with("mode", "replay") }
        val farm = lines.fold(FarmState()) { state, line -> reduce(state, line) }
        assertEquals(2, farm.bin.produce, "a replay is still shipped, and still night")
        assertEquals(Purse(), farm.purse)
    }

    @Test
    fun `the level is read from lifetime coins, so spending keeps it`() {
        assertEquals(
            listOf(1, 1, 2, 2, 3, 4, 5),
            listOf(0, 9, 10, 29, 30, 60, 100).map { Purse(earned = it).level },
        )
        assertEquals(3, Purse(coins = 0, earned = 30).level)
    }

    @Test
    fun `the snapshot carries the purse and the level's bounds`() {
        val farm = FarmState(purse = Purse(coins = 4, earned = 34))
        val purse = Json.parseToJsonElement(snapshot(farm)).jsonObject.getValue("purse").jsonObject
        assertEquals(
            mapOf("coins" to 4, "earned" to 34, "level" to 3, "floor" to 30, "next" to 60),
            purse.mapValues { it.value.jsonPrimitive.int },
        )
    }

    @Test
    fun `a save round-trips, and a missing or broken one is a new farm`(@TempDir home: Path) {
        assertEquals(Saved(), readSave(home))
        val saved = Saved(Purse(coins = 7, earned = 42), lastId = 1234)
        writeSave(home, saved)
        assertEquals(saved, readSave(home))
        Files.writeString(home.resolve("farm.json"), "{not json")
        assertEquals(Saved(), readSave(home))
    }

    private fun JsonObject.with(key: String, value: String) =
        JsonObject(this + (key to JsonPrimitive(value)))
}
