package dev.peashoot.app.farm

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir

/** The farmhouse extensions, the greenhouse against winter, the pets, and the failed turn. */
class BuildingsTest {
    /** A turn of one session that ended with [status] and edited [paths]. */
    private fun turn(status: Int, vararg paths: String): JsonObject =
        Json.parseToJsonElement(
                """{"event":"exchange.completed","exchangeId":"x$status${paths.size}",""" +
                    """"session":"sess-a","agent":null,"mode":"record","status":$status,""" +
                    """"stopReason":"tool_use","usage":{"input":1,"output":1},"tools":[""" +
                    paths.joinToString(",") { """{"name":"Edit","path":"$it"}""" } +
                    "]}"
            )
            .jsonObject

    /** [state] after [times] turns that each edit [paths]. */
    private fun edited(state: FarmState, times: Int, vararg paths: String): FarmState =
        (1..times).fold(state) { farm, _ -> reduce(farm, turn(200, *paths)) }

    private val fourFields = (0..4).map { "dir$it/File.kt" }.toTypedArray()

    @Test
    fun `the farmhouse takes two extensions, the second dearer, from level 2`() {
        val farm = FarmState(purse = Purse(coins = 200, earned = floorOf(2)))
        val once = farm.bought("house")
        assertEquals(1, once.buildings.house)
        assertEquals(160, once.purse.coins)
        val twice = once.bought("house")
        assertEquals(2, twice.buildings.house)
        assertEquals(80, twice.purse.coins)
        assertSame(twice, twice.bought("house"))
        val early = FarmState(purse = Purse(coins = 200, earned = floorOf(1)))
        assertSame(early, early.bought("house"))
    }

    @Test
    fun `the greenhouse is built once, from level 4`() {
        val farm = FarmState(purse = Purse(coins = 250, earned = floorOf(4)))
        val built = farm.bought("greenhouse")
        assertTrue(built.buildings.greenhouse)
        assertEquals(150, built.purse.coins)
        assertSame(built, built.bought("greenhouse"))
        val early = FarmState(purse = Purse(coins = 250, earned = floorOf(3)))
        assertSame(early, early.bought("greenhouse"))
    }

    @Test
    fun `winter stops open beds growing, and edits there leave the crops as they were`() {
        val planted = edited(FarmState(season = Season.WINTER), 1, *fourFields)
        val later = edited(planted, 3, *fourFields)
        assertEquals(
            List(fourFields.size) { Growth.SEED },
            fourFields.map { later.crop(it)?.growth },
        )
    }

    @Test
    fun `the greenhouse keeps its four beds growing through winter, and only those`() {
        val winter = FarmState(season = Season.WINTER, buildings = Buildings(greenhouse = true))
        val later = edited(edited(winter, 1, *fourFields), 3, *fourFields)
        assertEquals(
            List(4) { Growth.RIPE } + Growth.SEED,
            fourFields.map { later.crop(it)?.growth },
        )
    }

    @Test
    fun `outside winter every bed grows, greenhouse or not`() {
        val later =
            edited(edited(FarmState(season = Season.SPRING), 1, *fourFields), 3, *fourFields)
        assertTrue(fourFields.all { later.crop(it)?.growth == Growth.RIPE })
    }

    @Test
    fun `the dog comes at level 2 and the cat at level 4`() {
        fun pets(level: Int) = FarmState(purse = Purse(earned = floorOf(level))).pets()
        assertEquals(
            listOf(emptyList(), listOf("dog"), listOf("dog"), listOf("dog", "cat")),
            (1..4).map(::pets),
        )
    }

    @Test
    fun `a turn that ends in an error marks its farmer failed until a turn goes through`() {
        val failed = reduce(FarmState(), turn(529))
        assertTrue(failed.villager("sess-a").failed)
        assertFalse(reduce(FarmState(), turn(429)).villager("sess-a").failed, "a 429 is resting")
        assertFalse(reduce(failed, turn(200)).villager("sess-a").failed)
    }

    @Test
    fun `the snapshot carries the buildings, the pets, a dormant crop and a failed farmer`() {
        val winter =
            reduce(
                FarmState(season = Season.WINTER, purse = Purse(earned = 60)),
                turn(500, "a/B.kt"),
            )
        val json = Json.parseToJsonElement(snapshot(winter)).jsonObject
        assertEquals(
            listOf("dog", "cat"),
            json.getValue("pets").jsonArray.map { it.jsonPrimitive.content },
        )
        assertFalse(
            json.getValue("buildings").jsonObject.getValue("greenhouse").jsonPrimitive.boolean
        )
        val crop = json.getValue("fields").jsonArray[0].jsonObject.getValue("crops").jsonArray[0]
        assertTrue(crop.jsonObject.getValue("dormant").jsonPrimitive.boolean)
        val farmer = json.getValue("villagers").jsonArray[0].jsonObject
        assertTrue(farmer.getValue("failed").jsonPrimitive.boolean)
    }

    @Test
    fun `the buildings are saved with the purse`(@TempDir home: Path) {
        val saved = Saved(Purse(1, 70), buildings = Buildings(house = 1, greenhouse = true))
        writeSave(home, saved)
        assertEquals(saved, readSave(home))
    }
}
