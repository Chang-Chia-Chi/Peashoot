package dev.peashoot.app.farm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The line the Godot window is told, at the seam the reducer's own tests use: recorded feeds in,
 * one JSON line per farm out. The window places things by slot, so a slot that changed under a
 * villager or a field would move it, and that is what these hold still.
 */
class SnapshotTest {
    @Test
    fun `a farm is one line of JSON`() {
        for (state in replay("sub-agents.jsonl") + replay("paths.jsonl")) {
            val line = snapshot(state)
            assertFalse('\n' in line, "a snapshot spans lines, and the window reads one at a time")
            Json.parseToJsonElement(line).jsonObject
        }
    }

    @Test
    fun `a home slot, a field's plot and a crop's cell keep their places as lines arrive`() {
        for (file in listOf("one-turn.jsonl", "paths.jsonl", "sub-agents.jsonl")) {
            val homes = mutableMapOf<String, Int>()
            val plots = mutableMapOf<String, Int>()
            val cells = mutableMapOf<String, Int>()
            for (state in replay(file)) {
                val farm = parsed(state)
                for (villager in farm.getValue("villagers").jsonArray.map { it.jsonObject }) {
                    val home = villager.getValue("home")
                    if (home is JsonNull) continue
                    val id = villager.text("id")
                    val slot = home.jsonPrimitive.int
                    assertEquals(homes.put(id, slot) ?: slot, slot, "$id changed home in $file")
                }
                for ((plot, field) in farm.getValue("fields").jsonArray.withIndex()) {
                    val label = field.jsonObject.text("label")
                    assertEquals(plots.put(label, plot) ?: plot, plot, "$label moved in $file")
                    val crops = field.jsonObject.getValue("crops").jsonArray
                    for ((cell, crop) in crops.withIndex()) {
                        val path = crop.jsonObject.text("path")
                        assertEquals(cells.put(path, cell) ?: cell, cell, "$path moved in $file")
                    }
                }
            }
        }
    }

    @Test
    fun `a helper has a parent and no home, and a session has a home and no parent`() {
        val farm = parsed(replay("sub-agents.jsonl").last())
        val villagers = farm.getValue("villagers").jsonArray.map { it.jsonObject }
        val byId = villagers.associateBy { it.text("id") }
        val helper = byId.getValue("sess-charlie/agent-two")
        assertEquals(JsonNull, helper.getValue("home"))
        assertEquals("sess-charlie/agent-one", helper.text("parent"))
        val session = byId.getValue("sess-charlie")
        assertEquals(JsonNull, session.getValue("parent"))
        assertTrue(session.getValue("home").jsonPrimitive.int >= 0)
        val homes = villagers.filter { it.getValue("home") !is JsonNull }
        assertEquals(
            homes.size,
            homes.map { it.getValue("home").jsonPrimitive.int }.distinct().size,
            "two villagers were given one home",
        )
    }

    @Test
    fun `whoever waits at the well has their place in the queue`() {
        val state = replay("two-in-flight.jsonl").first { it.wellQueue.isNotEmpty() }
        val villagers = parsed(state).getValue("villagers").jsonArray.map { it.jsonObject }
        for ((place, id) in state.wellQueue.withIndex()) {
            val villager = villagers.single { it.text("id") == id }
            assertEquals(place, villager.getValue("queue").jsonPrimitive.int)
        }
    }

    @Test
    fun `a farmer carries the crops its latest turn tended, and how many turns it has done`() {
        val farmer =
            parsed(replay("one-turn.jsonl").last())
                .getValue("villagers")
                .jsonArray
                .single()
                .jsonObject
        assertEquals(
            listOf("src/main/App.kt", "src/test/AppTest.kt"),
            farmer.getValue("tended").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(2, farmer.getValue("turns").jsonPrimitive.int)
    }

    @Test
    fun `the sky, the night, the toggle and the bin travel with the farm`() {
        val state = replay("bin.jsonl").last().copy(night = true, labelsHidden = false)
        val farm = parsed(state)
        assertEquals(state.weather.name, farm.text("weather"))
        assertEquals("true", farm.getValue("night").jsonPrimitive.content)
        assertEquals("false", farm.getValue("labelsHidden").jsonPrimitive.content)
        val bin = farm.getValue("bin").jsonObject
        assertEquals(state.bin.produce, bin.getValue("produce").jsonPrimitive.int)
    }

    @Test
    fun `an empty farm is still a whole farm`() {
        val farm = parsed(FarmState())
        assertEquals(JsonArray(emptyList()), farm.getValue("villagers"))
        assertEquals(JsonArray(emptyList()), farm.getValue("fields"))
    }
}

private fun parsed(state: FarmState): JsonObject =
    Json.parseToJsonElement(snapshot(state)).jsonObject

private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
