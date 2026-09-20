package dev.peashoot.app.render

import dev.peashoot.app.farm.FarmState
import dev.peashoot.app.farm.ShippingBin
import dev.peashoot.app.farm.Stamina
import dev.peashoot.app.farm.replay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which state of `rate-limit.jsonl` is which; see the fixture README for the line-by-line. */
private const val FULL = 1
private const val REFUSED = 3

/**
 * The two numbers the farm draws rather than acts on: how much stamina is left over a villager's
 * head, and what the shipping bin has taken in. Both pure, because a bar's fraction and a ledger's
 * wording are exactly the parts of drawing that can be wrong without a window to see it in.
 */
class EconomyTest {
    @Test
    fun `a villager told what it has left is a bar full of it`() {
        assertEquals(1f, staminaFraction(replay("rate-limit.jsonl")[FULL].stamina()))
    }

    @Test
    fun `a rate-limited villager's bar is empty rather than missing`() {
        assertEquals(0f, staminaFraction(replay("rate-limit.jsonl")[REFUSED].stamina()))
    }

    @Test
    fun `a bar is the share of the most this villager was ever told it had`() {
        val half = Stamina(remainingTokens = 6_000, peakTokens = 12_000)
        assertEquals(0.5f, staminaFraction(half))
    }

    @Test
    fun `a villager nothing has told anything about has no bar at all`() {
        assertNull(staminaFraction(Stamina()), "a bar of an unknown whole is a made-up bar")
        assertNull(
            staminaFraction(Stamina(remainingTokens = 500)),
            "the line carries no limit, so remaining alone is not a fraction of anything",
        )
        assertNull(
            staminaFraction(Stamina(peakTokens = 500)),
            "a peak with nothing left against it says nothing either",
        )
    }

    @Test
    fun `a peak of nothing is no bar rather than a division by zero`() {
        assertNull(staminaFraction(Stamina(remainingTokens = 0, peakTokens = 0)))
    }

    @Test
    fun `a villager handed more than its peak fills the bar and no further`() {
        val over = Stamina(remainingTokens = 20_000, peakTokens = 12_000)
        assertEquals(1f, staminaFraction(over), "a bar cannot be more than full")
    }

    @Test
    fun `an empty bin says so in money, because a ledger that says nothing is worse`() {
        assertEquals("0 shipped · \$0.00", binLine(ShippingBin()))
    }

    @Test
    fun `a bin with turns nobody priced says how many, and only then`() {
        assertEquals("1 shipped · \$0.25", binLine(ShippingBin(produce = 1, ledger = 0.25)))
        assertEquals("3 shipped · \$0.38 · 1 unpriced", binLine(replay("bin.jsonl").last().bin))
    }
}

/** The one villager these fixtures have. */
private fun FarmState.stamina(): Stamina = villagers.values.single().stamina
