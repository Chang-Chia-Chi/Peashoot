package dev.peashoot.app.render

import dev.peashoot.app.farm.Activity
import dev.peashoot.app.farm.FarmState
import dev.peashoot.app.farm.Villager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SECOND = 1f

/** The render-side positions: a pure step, so a walk can be tested without a frame clock. */
class AnimatorTest {
    @Test
    fun `a villager arrives exactly rather than overshooting`() {
        val target = Spot(4f, 0f)
        val far = step(mapOf("a" to Spot(0f, 0f)), mapOf("a" to target), SECOND, snapped = false)
        assertNotEquals(
            target,
            far.getValue("a"),
            "a tile a second does not cross four tiles in one",
        )
        val arrived = step(far, mapOf("a" to target), SECOND * 10, snapped = false)
        assertEquals(target, arrived.getValue("a"))
    }

    @Test
    fun `a step covers the walking speed and no more`() {
        val moved =
            step(mapOf("a" to Spot(0f, 0f)), mapOf("a" to Spot(10f, 0f)), SECOND, snapped = false)
        assertEquals(WALK_TILES_PER_SECOND, moved.getValue("a").x)
        assertEquals(0f, moved.getValue("a").y)
    }

    @Test
    fun `a villager seen for the first time appears at its target`() {
        val target = Spot(7f, 2f)
        assertEquals(
            target,
            step(emptyMap(), mapOf("a" to target), SECOND, snapped = false).getValue("a"),
        )
    }

    @Test
    fun `a retired villager leaves the farm rather than standing there`() {
        val still = step(mapOf("a" to Spot(0f, 0f)), emptyMap(), SECOND, snapped = false)
        assertNull(still["a"])
    }

    @Test
    fun `degraded means snapped to the target`() {
        val snapped =
            step(mapOf("a" to Spot(0f, 0f)), mapOf("a" to Spot(9f, 9f)), SECOND, snapped = true)
        assertEquals(Spot(9f, 9f), snapped.getValue("a"))
    }

    @Test
    fun `a walking or resting villager heads for its own spot at the well`() {
        val state =
            FarmState(
                villagers =
                    mapOf(
                        "one" to villager("one", Activity.WALKING_TO_WELL),
                        "two" to villager("two", Activity.RESTING),
                        "three" to villager("three", Activity.RETURNING),
                    ),
                wellQueue = listOf("one", "two"),
            )
        val layout = farmLayout(state)
        val targets = targets(state, layout)
        assertEquals(wellSpot(0), targets.getValue("one"))
        assertEquals(wellSpot(1), targets.getValue("two"))
        assertNotEquals(
            targets.getValue("one"),
            targets.getValue("two"),
            "two queued villagers stack",
        )
        assertEquals(layout.homes.getValue("three"), targets.getValue("three"))
        assertTrue(
            targets.getValue("three").y > targets.getValue("one").y,
            "home is away from the well",
        )
    }
}

private fun villager(id: String, activity: Activity): Villager =
    Villager(id = id, name = id, session = id, parent = null, activity = activity)
