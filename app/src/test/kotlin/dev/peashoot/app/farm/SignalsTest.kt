package dev.peashoot.app.farm

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

private const val SKY = "sess-sky"
private const val WELL = "sess-well"
private const val ODD = "sess-odd"

/**
 * The signals #18 maps, asserted after every line of their fixtures because each of them is a
 * per-event mapping: the sky, the spilled bucket, the rest at the well and the stamina bar, the
 * shipping bin, and night.
 */
class SignalsTest {
    @Test
    fun `the latest line that says anything about the sky is the weather`() {
        assertEquals(
            listOf(
                // A started line says nothing about the sky.
                Weather.CLEAR,
                // A cache read is rain, and a completion without one is clear again.
                Weather.RAIN,
                Weather.RAIN,
                Weather.CLEAR,
                Weather.CLEAR,
                // The departure is lightning, and the completion that follows it keeps it, though
                // that turn read from the cache and would have been rain: a client that left
                // outranks everything else the line says.
                Weather.LIGHTNING,
                Weather.LIGHTNING,
                Weather.LIGHTNING,
                Weather.STORM,
            ),
            replay("weather.jsonl").map { it.weather },
        )
    }

    @Test
    fun `a client that leaves spills the bucket that turn was filling`() {
        val states = replay("weather.jsonl")
        assertEquals(listOf(0, 0, 0, 0, 0, 1, 1, 1, 1), states.map { it.villager(SKY).spills })
        // 340 and 120 are carried home; the interrupted turn's 500 output tokens were billed to
        // the day but never reached the farm.
        assertEquals(
            listOf(0, 340, 340, 460, 460, 460, 460, 460, 460),
            states.map { it.villager(SKY).water },
        )
        // The turn that reported usage and no cost is unpriced; the 529 that ends the file
        // reported nothing, so it is a failure and not a price the ledger is missing.
        assertEquals(listOf(0, 0, 0, 1, 1, 1, 1, 1, 1), states.map { it.bin.unpriced })
    }

    @Test
    fun `a departure nobody here was waiting for still turns the sky`() {
        // The line carries no `agent`, so an exchange id nothing holds is a departure the farm can
        // place nowhere: a window that connected mid-stream sees exactly this.
        val gone =
            Json.parseToJsonElement(
                    """{"ts":"2026-09-19T15:30:00Z","event":"exchange.client_gone",""" +
                        """"exchangeId":"01EX9999","session":"sess-nobody","bytesSoFar":12}"""
                )
                .jsonObject
        assertEquals(FarmState(weather = Weather.LIGHTNING), reduce(FarmState(), gone))
    }

    @Test
    fun `a 429 rests the villager at the well, and the retry walks it out again`() {
        val states = replay("rate-limit.jsonl")
        assertEquals(
            listOf(
                Activity.WALKING_TO_WELL,
                Activity.RETURNING,
                Activity.WALKING_TO_WELL,
                Activity.RESTING,
                Activity.WALKING_TO_WELL,
                Activity.RETURNING,
                Activity.WALKING_TO_WELL,
                Activity.RETURNING,
            ),
            states.map { it.villager(WELL).activity },
        )
        // Resting is resting *at the well*: the villager stays in the queue until a turn of its
        // own actually ends.
        assertEquals(
            listOf(
                listOf(WELL),
                emptyList(),
                listOf(WELL),
                listOf(WELL),
                listOf(WELL),
                emptyList(),
                listOf(WELL),
                emptyList(),
            ),
            states.map { it.wellQueue },
        )
        // A rest drops no produce and carries no water: the turn is the retry's to finish.
        assertEquals(
            listOf(0, 200, 200, 200, 200, 350, 350, 400),
            states.map { it.villager(WELL).water },
        )
        assertEquals(listOf(0, 1, 1, 1, 1, 2, 2, 3), states.map { it.bin.produce })
        // Nor is it unpriced: a refusal reported no usage, so there was nothing to put a price
        // on. The last turn did report usage and named no cost, and that one is.
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 0, 1), states.map { it.bin.unpriced })
    }

    @Test
    fun `a 429 heard before anything else still rests the villager at the well`() {
        // A window that connects while a request is out hears the refusal and never the start,
        // so nothing had put the villager in the queue for the rest to keep it in.
        val refused =
            Json.parseToJsonElement(
                    """{"ts":"2026-09-19T16:00:00Z","event":"exchange.completed",""" +
                        """"exchangeId":"01EX9000","session":"sess-late-rest","agent":null,""" +
                        """"parentAgent":null,"client":"claude-code",""" +
                        """"surface":"anthropic-messages","model":"claude-sonnet-4-5",""" +
                        """"route":"default","mode":"record","toolResults":[],"tools":[],""" +
                        """"usage":null,"costUsd":null,"stopReason":null,"status":429,""" +
                        """"firstByteMs":null,"latencyMs":40,"replayHit":false,""" +
                        """"clientDisconnected":false,"rateLimit":null}"""
                )
                .jsonObject
        val farm = reduce(FarmState(), refused)
        assertEquals(Activity.RESTING, farm.villager("sess-late-rest").activity)
        assertEquals(listOf("sess-late-rest"), farm.wellQueue)
    }

    @Test
    fun `stamina is what the rate-limit headers last said, and the most they ever said`() {
        assertEquals(
            listOf(
                Stamina(),
                Stamina(12_000L, 20L, 12_000L),
                Stamina(12_000L, 20L, 12_000L),
                // Spent: the peak is what the bar is still a fraction of.
                Stamina(0L, 0L, 12_000L),
                Stamina(0L, 0L, 12_000L),
                // Null fields say nothing, and leave what the villager had.
                Stamina(0L, 33L, 12_000L),
                Stamina(0L, 33L, 12_000L),
                // A `rateLimit` of null says nothing at all.
                Stamina(0L, 33L, 12_000L),
            ),
            replay("rate-limit.jsonl").map { it.villager(WELL).stamina },
        )
    }

    @Test
    fun `produce is one per completed turn, and the ledger sums only the costs it knows`() {
        val states = replay("bin.jsonl")
        // The `tool_use` completion is the middle of a turn, so it drops nothing.
        assertEquals(listOf(0, 1, 1, 1, 1, 2, 2, 3, 3), states.map { it.bin.produce })
        // A replay hit was billed nothing and adds nothing, which is not the same as unknown.
        assertEquals(
            listOf(0.0, 0.25, 0.25, 0.375, 0.375, 0.375, 0.375, 0.375, 0.375),
            states.map { it.bin.ledger },
        )
        // A null cost is not a free turn: it is one the ledger cannot speak for, counted so the
        // silence can be explained.
        assertEquals(listOf(0, 0, 0, 0, 0, 1, 1, 1, 1), states.map { it.bin.unpriced })
    }

    @Test
    fun `replay is night, and the next recorded line is day again`() {
        assertEquals(
            listOf(false, false, false, false, false, false, true, true, false),
            replay("bin.jsonl").map { it.night },
        )
    }

    @Test
    fun `signal fields of the wrong shape leave the farm what it had`() {
        val states = replay("odd-signals.jsonl")
        assertEquals(3, states.size)
        // A `rateLimit` that is a string says nothing about stamina.
        assertEquals(Stamina(), states[1].villager(ODD).stamina)
        // A `costUsd` that is a string is a cost the ledger does not know, not a zero; and with no
        // `status` at all there is no 2xx, so nothing is shipped.
        assertEquals(ShippingBin(produce = 0, ledger = 0.0, unpriced = 1), states[1].bin)
        assertEquals(Weather.CLEAR, states[1].weather)
        // A `mode` no proxy of ours writes leaves night where the last readable one left it.
        assertEquals(listOf(true, true, false), states.map { it.night })
        // A `ts` that is not a time leaves the session's last-heard time alone, and everything
        // else on the line still counts.
        val day = states[1].days.getValue(ODD)
        assertEquals(Instant.parse("2026-09-19T14:00:00Z"), day.lastHeard)
        assertEquals(Tokens(input = 1, output = 2, cacheRead = 0, cacheWrite = 4), day.tokens)
    }
}
