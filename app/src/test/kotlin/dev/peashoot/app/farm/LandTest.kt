package dev.peashoot.app.farm

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir

/** Buying land: a plot the purse can pay for is bought, and one it cannot is not. */
class LandTest {
    @Test
    fun `each plot costs more than the one before`() {
        assertEquals(listOf(20, 35, 50, 65), (0..3).map(::plotPrice))
    }

    @Test
    fun `a plot the purse can pay for is bought, and spends coins but not the level`() {
        val farm = FarmState(purse = Purse(coins = 25, earned = 40))
        val bought = farm.boughtPlot()
        assertEquals(1, bought.land)
        assertEquals(Purse(coins = 5, earned = 40), bought.purse)
        assertEquals(farm.purse.level, bought.purse.level)
    }

    @Test
    fun `a plot the purse cannot pay for leaves the farm as it was`() {
        val farm = FarmState(purse = Purse(coins = 34, earned = 80), land = 1)
        assertSame(farm, farm.boughtPlot())
    }

    @Test
    fun `the snapshot carries the plots and the next one's price`() {
        val land =
            Json.parseToJsonElement(snapshot(FarmState(land = 2))).jsonObject.getValue("land")
        assertEquals(
            mapOf("plots" to 2, "price" to 50),
            land.jsonObject.mapValues { it.value.jsonPrimitive.int },
        )
    }

    @Test
    fun `the plots bought are saved with the purse`(@TempDir home: Path) {
        val saved = Saved(Purse(coins = 3, earned = 60), land = 2, lastId = 99)
        writeSave(home, saved)
        assertEquals(saved, readSave(home))
    }
}
