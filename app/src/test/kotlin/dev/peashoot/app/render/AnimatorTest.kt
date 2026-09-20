package dev.peashoot.app.render

import dev.peashoot.app.farm.Activity
import dev.peashoot.app.farm.FarmState
import dev.peashoot.app.farm.Villager
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SECOND = 1f

/** How far apart two villagers may stand and still read as standing beside each other, in tiles. */
private const val BESIDE = 2f

/**
 * Two sums of the same offset in `Float` differ in the last bit; a pixel is a fiftieth of a tile.
 */
private const val A_WHISKER = 0.001f

/** The render-side positions: a pure step, so a walk can be tested without a frame clock. */
class AnimatorTest {
    @Test
    fun `a villager arrives exactly rather than overshooting`() {
        val target = Spot(4f, 0f)
        val far = step(mapOf("a" to Spot(0f, 0f)), heading("a", target), SECOND, snapped = false)
        assertNotEquals(
            target,
            far.getValue("a"),
            "a tile a second does not cross four tiles in one",
        )
        val arrived = step(far, heading("a", target), SECOND * 10, snapped = false)
        assertEquals(target, arrived.getValue("a"))
    }

    @Test
    fun `a step covers the walking speed and no more`() {
        val moved =
            step(mapOf("a" to Spot(0f, 0f)), heading("a", Spot(10f, 0f)), SECOND, snapped = false)
        assertEquals(WALK_TILES_PER_SECOND, moved.getValue("a").x)
        assertEquals(0f, moved.getValue("a").y)
    }

    @Test
    fun `a villager seen for the first time appears at its target`() {
        val target = Spot(7f, 2f)
        assertEquals(target, step(emptyMap(), heading("a", target), SECOND, false).getValue("a"))
    }

    @Test
    fun `a villager seen for the first time walks in from the way it came`() {
        val walked =
            step(
                emptyMap(),
                mapOf("a" to Heading(target = Spot(9f, 0f), entrance = Spot(0f, 0f))),
                SECOND,
                snapped = false,
            )
        assertEquals(Spot(WALK_TILES_PER_SECOND, 0f), walked.getValue("a"))
    }

    @Test
    fun `a retired villager leaves the farm rather than standing there`() {
        val still = step(mapOf("a" to Spot(0f, 0f)), emptyMap(), SECOND, snapped = false)
        assertNull(still["a"])
    }

    @Test
    fun `degraded means snapped to the target`() {
        val snapped =
            step(mapOf("a" to Spot(0f, 0f)), heading("a", Spot(9f, 9f)), SECOND, snapped = true)
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
        val targets = headings(state, layout, emptyMap()).mapValues { (_, it) -> it.target }
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

    @Test
    fun `a helper trails its parent about rather than standing on its own spot`() {
        val state = helped(Activity.RETURNING)
        val layout = farmLayout(state)
        val parentIsOut = mapOf("s" to Spot(WELL.x, WELL.y + 1))
        val trailing = headings(state, layout, parentIsOut).getValue("s/help").target
        assertTrue(
            distance(trailing, parentIsOut.getValue("s")) <= BESIDE,
            "a helper follows its parent, and $trailing is not beside the well",
        )
        // It keeps the offset the layout gave it, so two helpers of one parent stay countable.
        val home = layout.homes.getValue("s/help")
        val parentHome = layout.homes.getValue("s")
        assertEquals(home.x - parentHome.x, trailing.x - parentIsOut.getValue("s").x, A_WHISKER)
        assertEquals(home.y - parentHome.y, trailing.y - parentIsOut.getValue("s").y, A_WHISKER)
    }

    @Test
    fun `a helper of its own goes to the well and walks in from beside its parent`() {
        val state = helped(Activity.WALKING_TO_WELL)
        val layout = farmLayout(state)
        val parentIsHome = mapOf("s" to layout.homes.getValue("s"))
        val heading = headings(state, layout, parentIsHome).getValue("s/help")
        assertEquals(wellSpot(0), heading.target, "a helper with a turn out waits at the well")
        assertTrue(
            distance(heading.entrance, parentIsHome.getValue("s")) <= BESIDE,
            "a helper first seen walks out from beside its parent, not from the well",
        )
    }

    @Test
    fun `two villagers naming each other as parent neither loop nor drift off the farm`() {
        val state =
            FarmState(
                villagers =
                    mapOf(
                        "s/a" to helper("s/a", "s/b", Activity.RETURNING),
                        "s/b" to helper("s/b", "s/a", Activity.RETURNING),
                    )
            )
        val layout = farmLayout(state)
        var positions = emptyMap<String, Spot>()
        repeat(FRAMES) {
            positions = step(positions, headings(state, layout, positions), SECOND, false)
        }
        for ((id, spot) in positions) {
            assertTrue(spot.x.isFinite() && spot.y.isFinite(), "$id ran off to $spot")
        }
    }

    @Test
    fun `the pose is the walk, the wait, the rest, and standing about`() {
        assertEquals(Pose.WALKING, poseOf(Activity.WALKING_TO_WELL, arrived = false))
        assertEquals(Pose.WALKING, poseOf(Activity.RETURNING, arrived = false))
        assertEquals(Pose.WAITING, poseOf(Activity.WALKING_TO_WELL, arrived = true))
        assertEquals(Pose.RESTING, poseOf(Activity.RESTING, arrived = true))
        assertEquals(Pose.STANDING, poseOf(Activity.RETURNING, arrived = true))
        assertEquals(Pose.STANDING, poseOf(Activity.IDLE, arrived = true))
    }

    @Test
    fun `only a walking villager bobs, and never off its own tile`() {
        val spot = Spot(3f, 4f)
        assertEquals(spot, bobbed(spot, Pose.STANDING))
        assertEquals(spot, bobbed(spot, Pose.WAITING))
        val walked = (0 until FRAMES).map { bobbed(Spot(it * 0.1f, 0f), Pose.WALKING).y }
        assertTrue(walked.any { it < 0f }, "a walking villager never left the ground")
        assertTrue(walked.all { it > -1f }, "a bob lifted a villager clean off its tile")
    }
}

/** Enough frames for a cycle to show, and for a pair of followers to run away if they would. */
private const val FRAMES = 20

private fun heading(id: String, target: Spot): Map<String, Heading> = mapOf(id to Heading(target))

private fun distance(one: Spot, other: Spot): Float = hypot(one.x - other.x, one.y - other.y)

/** A session with one helper of its own, both doing the same thing. */
private fun helped(activity: Activity): FarmState =
    FarmState(
        villagers =
            mapOf("s" to villager("s", activity), "s/help" to helper("s/help", "s", activity)),
        wellQueue =
            if (activity == Activity.WALKING_TO_WELL) listOf("s/help", "s") else emptyList(),
    )

private fun villager(id: String, activity: Activity): Villager =
    Villager(id = id, name = id, session = id, parent = null, activity = activity)

private fun helper(id: String, parent: String, activity: Activity): Villager =
    Villager(id = id, name = id, session = "s", parent = parent, activity = activity)
