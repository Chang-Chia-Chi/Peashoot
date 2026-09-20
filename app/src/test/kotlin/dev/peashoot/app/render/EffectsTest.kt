package dev.peashoot.app.render

import dev.peashoot.app.farm.FarmState
import dev.peashoot.app.farm.Growth
import dev.peashoot.app.farm.replay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val A_FIELD = "src/field0"
private const val A_CROP = "src/field0/File0.kt"

/** More files than one turn plausibly touches, so that nothing quietly caps the diff again. */
private const val A_BIG_FIELD = 40

/** The one villager of `weather.jsonl`, and the three states its dropped stream sits between. */
private const val A_VILLAGER = "sess-sky"
private const val IN_FLIGHT = 4
private const val SPILLED = 5
private const val AFTER_SPILL = 6

/**
 * What the crops do between two reducer states: the diff and the ageing, both pure, so that a pop
 * can be tested without a window. The reducer is not asked to hold any of this — a crop that has
 * just grown is exactly the sort of thing that is true for half a second and about a frame, which
 * `docs/design.md` §10 keeps out of the state.
 */
class EffectsTest {
    @Test
    fun `the first farm pops nothing, because every crop on it is new to the renderer`() {
        assertTrue(cropEffects(null, oneCrop(Growth.SEED, inspections = 0)).isEmpty())
    }

    @Test
    fun `a crop that advances a stage pops, and one that did not is left alone`() {
        val before = oneCrop(Growth.SEED, inspections = 0)
        val after = oneCrop(Growth.SPROUT, inspections = 0)
        assertEquals(EffectKind.GROW, cropEffects(before, after).getValue(A_CROP).kind)
        assertNull(cropEffects(before, before)[A_CROP])
    }

    @Test
    fun `a crop planted since the last farm pops too`() {
        val planted = cropEffects(FarmState(), oneCrop(Growth.SEED, inspections = 0))
        assertEquals(EffectKind.GROW, planted.getValue(A_CROP).kind)
    }

    @Test
    fun `a crop that was only read is inspected rather than grown`() {
        val before = oneCrop(Growth.GROWING, inspections = 1)
        val after = oneCrop(Growth.GROWING, inspections = 2)
        assertEquals(EffectKind.INSPECT, cropEffects(before, after).getValue(A_CROP).kind)
    }

    @Test
    fun `a crop both edited and read in one turn pops rather than ringing`() {
        val before = oneCrop(Growth.SEED, inspections = 0)
        val after = oneCrop(Growth.SPROUT, inspections = 1)
        assertEquals(
            EffectKind.GROW,
            cropEffects(before, after).getValue(A_CROP).kind,
            "one tile cannot say two things at once, and growth is the news",
        )
    }

    @Test
    fun `a whole field arriving at once pops every crop of it`() {
        val before = syntheticFarm(fields = 2, crops = A_BIG_FIELD)
        val after = syntheticFarm(fields = 3, crops = A_BIG_FIELD)
        assertEquals(
            A_BIG_FIELD,
            cropEffects(before, after).size,
            "a turn that touched a lot of files still shows every one of them growing",
        )
    }

    @Test
    fun `an effect ages out of the frame it was raised in`() {
        val raised = Effects().raised(oneCrop(Growth.SEED, 0), oneCrop(Growth.SPROUT, 0))
        val halfway = aged(raised, EFFECT_SECONDS / 2)
        assertEquals(EFFECT_SECONDS / 2, halfway.crops.getValue(A_CROP).age)
        assertEquals(
            Effects(),
            aged(halfway, EFFECT_SECONDS),
            "an effect that never ends is a bug, and a leak",
        )
    }

    @Test
    fun `a dropped stream puddles at the villager that lost the bucket`() {
        val states = replay("weather.jsonl")
        val spilled = spillEffects(states[IN_FLIGHT], states[SPILLED])
        assertEquals(setOf(A_VILLAGER), spilled.keys)
        assertEquals(EffectKind.SPILL, spilled.getValue(A_VILLAGER).kind)
    }

    @Test
    fun `a spill count that has not moved puddles nothing`() {
        val states = replay("weather.jsonl")
        assertTrue(
            spillEffects(states[SPILLED], states[AFTER_SPILL]).isEmpty(),
            "a villager that spilled a turn ago is not still spilling",
        )
        assertTrue(
            spillEffects(null, states[SPILLED]).isEmpty(),
            "a window opening onto a farm mid-run must not puddle under its whole history",
        )
    }

    @Test
    fun `the sky turning to lightning flashes once, not on every frame after it`() {
        val states = replay("weather.jsonl")
        assertTrue(struck(states[IN_FLIGHT], states[SPILLED]), "a dropped stream did not strike")
        assertTrue(
            !struck(states[SPILLED], states[AFTER_SPILL]),
            "the weather has no decay, so a farm left on lightning would strobe",
        )
        assertTrue(!struck(null, states[SPILLED]), "the first farm a window sees strikes nothing")
    }

    @Test
    fun `a second dropped stream flashes even though the sky was already on lightning`() {
        val once = replay("weather.jsonl")[SPILLED]
        val twice =
            once.copy(
                villagers = once.villagers.mapValues { (_, it) -> it.copy(spills = it.spills + 1) }
            )
        assertTrue(
            !struck(once, twice),
            "the weather has no decay, so there is nothing left for it to turn to",
        )
        assertEquals(
            EffectKind.FLASH,
            Effects().raised(once, twice).flash?.kind,
            "a second drop under a sky already lit is still a second drop",
        )
    }

    @Test
    fun `a second strike restarts a flash rather than being swallowed by it`() {
        val states = replay("weather.jsonl")
        val halfway = Effects(flash = Effect(EffectKind.FLASH, age = EFFECT_SECONDS / 2))
        val again = halfway.raised(states[IN_FLIGHT], states[SPILLED])
        assertEquals(0f, again.flash?.age, "a second dropped stream is news, not a repeat")
        // And a step that struck nothing leaves the one already running exactly where it was.
        assertEquals(
            EFFECT_SECONDS / 2,
            halfway.raised(states[SPILLED], states[AFTER_SPILL]).flash?.age,
        )
    }

    @Test
    fun `a recorded feed's edits and reads each raise one effect`() {
        val states = replay("paths.jsonl")
        val kinds =
            states.zipWithNext().flatMap { (before, after) ->
                cropEffects(before, after).values.map { it.kind }
            }
        assertTrue(EffectKind.GROW in kinds, "no crop grew over a feed that edits files")
        assertTrue(EffectKind.INSPECT in kinds, "no crop was inspected over a feed that reads one")
    }
}

private fun oneCrop(growth: Growth, inspections: Int): FarmState =
    syntheticFarm(fields = 1, crops = 1).let { farm ->
        val field = farm.fields.getValue(A_FIELD)
        val crop = field.crops.getValue(A_CROP).copy(growth = growth, inspections = inspections)
        farm.copy(fields = mapOf(A_FIELD to field.copy(crops = mapOf(A_CROP to crop))))
    }
