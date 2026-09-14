package dev.peashoot.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What a turn costs at the bundled price table, and what has no cost at all. */
class PricingTest {
    private fun cost(model: String?, usage: Usage?, prices: Map<String, Price> = DEFAULT_PRICES) =
        checkNotNull(costUsd(model, usage, prices)) { "$model has no price" }

    @Test
    fun `a turn costs its tokens at the model's price`() {
        // Sonnet 4.5 is $3 in, $15 out per million: 25 in is $0.000075, 42 out is $0.00063.
        assertEquals(0.000705, cost(SONNET, Usage(25, 42, 0, 0)), TOLERANCE)
        // Cache reads are $0.30 and 5-minute cache writes $3.75 per million: the fixture's
        // 1200/95/8000/300 is $0.0036 + $0.001425 + $0.0024 + $0.001125.
        assertEquals(0.00855, cost(SONNET, Usage(1200, 95, 8000, 300)), TOLERANCE)
    }

    @Test
    fun `the longest matching model prefix wins`() {
        val million = Usage(input = 1_000_000, output = 1_000_000, cacheRead = 0, cacheWrite = 0)

        assertEquals(90.0, cost("claude-opus-4-1-20250805", million), TOLERANCE, "$15 in, $75 out")
        assertEquals(30.0, cost("claude-opus-4-5-20251101", million), TOLERANCE, "$5 in, $25 out")
        assertEquals(90.0, cost("claude-opus-4-20250514", million), TOLERANCE, "$15 in, $75 out")
        assertNull(
            costUsd("claude-opus-4-10", million),
            "a prefix ends at a dash, or Opus 4.10 would be priced as Opus 4.1",
        )
    }

    @Test
    fun `a model the table does not know, or a turn with no usage, has no cost`() {
        assertNull(costUsd("gpt-5-codex", Usage(10, 10, 0, 0)))
        assertNull(costUsd(null, Usage(10, 10, 0, 0)), "a request that named no model")
        assertNull(costUsd(SONNET, null), "a response the deriver read no usage from")
    }

    @Test
    fun `an override entry wins over the bundled price`() {
        val prices =
            DEFAULT_PRICES +
                ("claude-sonnet-4-5" to
                    Price(input = 1.0, output = 2.0, cacheRead = 0.0, cacheWrite = 0.0))
        val million = Usage(input = 1_000_000, output = 1_000_000, cacheRead = 0, cacheWrite = 0)

        assertEquals(3.0, cost(SONNET, million, prices), TOLERANCE)
        assertEquals(18.0, cost(SONNET, million), TOLERANCE, "the default is untouched")
    }

    private companion object {
        const val SONNET = "claude-sonnet-4-5-20250929"

        /** Dollars, so anything this close is the same money. */
        const val TOLERANCE = 1e-12
    }
}
