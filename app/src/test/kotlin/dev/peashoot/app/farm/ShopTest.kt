package dev.peashoot.app.farm

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir

/** The animal shop: what it sells, at which level, while there is room, and the upgrades. */
class ShopTest {
    /** A farm at [level] with [coins] to spend. */
    private fun farm(level: Int, coins: Int, herd: Herd = Herd()) =
        FarmState(purse = Purse(coins = coins, earned = floorOf(level)), herd = herd)

    @Test
    fun `a farm starts with three cows and three hens, and room for three more of each`() {
        val herd = FarmState().herd
        assertEquals(listOf(3, 3, 0, 0), Animal.entries.map(herd::count))
        assertEquals(6, herd.coopRoom)
        assertEquals(6, herd.barnRoom)
    }

    @Test
    fun `an animal the level has reached and the purse can pay for is bought`() {
        val bought = farm(level = 3, coins = 45).bought("cow")
        assertEquals(4, bought.herd.cows)
        assertEquals(5, bought.purse.coins)
    }

    @Test
    fun `an animal above the level, or dearer than the purse, is not sold`() {
        val farm = farm(level = 3, coins = 100)
        assertSame(farm, farm.bought("sheep"))
        val poor = farm(level = 5, coins = 29)
        assertSame(poor, poor.bought("pig"))
    }

    @Test
    fun `a full barn sells no more animals until it is upgraded`() {
        val full = farm(level = 5, coins = 200, herd = Herd(cows = 4, sheep = 1, pigs = 1))
        assertSame(full, full.bought("pig"))
        val upgraded = full.bought("barn")
        assertEquals(1, upgraded.herd.barn)
        assertEquals(10, upgraded.herd.barnRoom)
        assertEquals(150, upgraded.purse.coins)
        assertEquals(1, upgraded.bought("pig").herd.pigs - upgraded.herd.pigs)
    }

    @Test
    fun `the coop is for hens alone, and each upgrade costs more than the last`() {
        val full = farm(level = 3, coins = 100, herd = Herd(hens = 6))
        assertSame(full, full.bought("hen"))
        assertEquals(4, full.bought("cow").herd.cows)
        val once = full.bought("coop")
        assertEquals(70, once.purse.coins)
        assertEquals(10, once.bought("coop").purse.coins)
    }

    @Test
    fun `something the shop does not sell leaves the farm as it was`() {
        val farm = farm(level = 9, coins = 999)
        assertSame(farm, farm.bought("dragon"))
    }

    @Test
    fun `the snapshot carries the herd and the board, each line saying why it cannot be bought`() {
        val herd =
            Json.parseToJsonElement(snapshot(farm(level = 3, coins = 35)))
                .jsonObject
                .getValue("herd")
                .jsonObject
        assertEquals(3, herd.getValue("cow").jsonPrimitive.int)
        val why =
            herd.getValue("shop").jsonArray.associate {
                val line = it.jsonObject
                line.getValue("item").jsonPrimitive.content to
                    line.getValue("why").let { w ->
                        if (w is JsonNull) null else w.jsonPrimitive.content
                    }
            }
        assertEquals(
            mapOf(
                "hen" to null,
                "cow" to "coins",
                "sheep" to "level",
                "pig" to "level",
                "coop" to null,
                "barn" to "coins",
            ),
            why,
        )
    }

    @Test
    fun `the herd is saved with the purse, and an older save gets the starting herd`(
        @TempDir home: Path
    ) {
        val saved = Saved(Purse(3, 60), land = 1, herd = Herd(hens = 5, sheep = 2, coop = 1))
        writeSave(home, saved)
        assertEquals(saved, readSave(home))
        writeSave(home, Saved())
        home.resolve("farm.json").toFile().writeText("""{"coins":4,"earned":9,"land":0}""")
        assertEquals(Herd(), readSave(home).herd)
    }
}
