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
    fun `a farm that arrives all at once pops nothing rather than popping everything`() {
        val before = syntheticFarm(fields = 2, crops = MOST_AT_ONCE + 1)
        val after = syntheticFarm(fields = 3, crops = MOST_AT_ONCE + 1)
        assertTrue(
            cropEffects(before, after).isEmpty(),
            "a backfill folded into one frame is not a farm at work",
        )
    }

    @Test
    fun `an effect ages out of the frame it was raised in`() {
        val raised = cropEffects(oneCrop(Growth.SEED, 0), oneCrop(Growth.SPROUT, 0))
        val halfway = aged(raised, EFFECT_SECONDS / 2)
        assertEquals(EFFECT_SECONDS / 2, halfway.getValue(A_CROP).age)
        assertTrue(aged(halfway, EFFECT_SECONDS).isEmpty(), "an effect that never ends is a bug")
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
