package dev.peashoot.app.render

import dev.peashoot.app.farm.Activity
import dev.peashoot.app.farm.FarmState
import dev.peashoot.app.farm.Villager
import dev.peashoot.app.farm.parentOf
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

/** How fast a villager walks, in tiles a second: a walk across the farm takes about eight. */
internal const val WALK_TILES_PER_SECOND = 3f

/** One second, in the units `withFrameNanos` speaks. The renderer's only clock, in one place. */
internal const val NANOS_A_SECOND = 1_000_000_000.0

/**
 * How high a walking villager bobs, in tiles: a couple of pixels at a 48 px tile, which is as small
 * as a bob can be and still survive the rounding to whole pixels that drawing pixel art needs. The
 * atlas has two people in it and no walk frames, so the walk is the sprite going up and down.
 */
private const val BOB_TILES = 0.05f

/** Six steps a second, which is about what [WALK_TILES_PER_SECOND] would take. */
private const val BOBS_A_SECOND = 6

/**
 * How fast the bob goes, in radians a second: `|sin|` repeats every half turn, so six bobs is six
 * half turns. On a clock rather than on the distance walked, because any phase read off a position
 * is constant along one direction of travel — a villager walking that way would slide along dead
 * still. Where the villager is goes into the phase all the same, so a farm full of walkers is not a
 * chorus line. Six whole bobs a second is also what lets [FarmScene] wrap its clock at one second
 * without the step jumping.
 */
private val BOB_RADIANS_A_SECOND = (BOBS_A_SECOND * PI).toFloat()

/** What a villager is doing where it stands, which is the renderer's idea and not the reducer's. */
internal enum class Pose {
    WALKING,
    /** Arrived at the well with a turn still out there: the wait that is the tail of the walk. */
    WAITING,
    /** Rate-limited, sat at the well until the retry comes. */
    RESTING,
    STANDING,
}

/**
 * Where a villager is going, and where it walks in from the first time the farm hears of it. The
 * two are the same spot for everyone but a helper: a helper with a turn of its own heads for the
 * well, and walking in from the well is exactly the arrival that would hide the fan-out — it has to
 * come out from beside its parent, which is [entrance].
 */
internal data class Heading(val target: Spot, val entrance: Spot = target)

/**
 * Where every villager is now, given where they were and how long the last frame took. The only
 * state the renderer keeps of its own: a villager's position is between two reducer states, which
 * is exactly what the reducer refuses to know.
 *
 * A villager the farm has only just heard of appears at its [Heading.entrance] rather than walking
 * in from wherever the map's edge is, and one the reducer has retired simply stops being here.
 * [snapped] is the degrade `docs/design.md` §10 names: under 30 fps villagers teleport instead of
 * walking.
 */
internal fun step(
    positions: Map<String, Spot>,
    headings: Map<String, Heading>,
    seconds: Float,
    snapped: Boolean,
): Map<String, Spot> = headings.mapValues { (id, heading) ->
    val from = positions[id] ?: heading.entrance
    if (snapped) heading.target else toward(from, heading.target, WALK_TILES_PER_SECOND * seconds)
}

/**
 * Where each villager is heading: its spot in the queue at the well while a turn of its own is out
 * there or it is resting off a 429, and its own place on the farm otherwise — except that a
 * helper's own place follows its parent about, so that the fan-out a session made stays readable
 * while that session walks.
 *
 * [positions] is the frame before's, which is a frame of lag nobody can see and which is what keeps
 * this free of any order: a helper reads where its parent *was*, so two agents naming each other as
 * parent trail each other instead of looping. Worked out every frame and not once per farm, because
 * a parent's position changes between two frames of the same state, which is the whole point of it.
 */
internal fun headings(
    state: FarmState,
    layout: FarmLayout,
    positions: Map<String, Spot>,
): Map<String, Heading> {
    // The queue as a lookup: `indexOf` per villager is the same walk down the same list every time.
    val queue = state.wellQueue.withIndex().associate { (place, id) -> id to place }
    return state.villagers.values.associate { villager ->
        // A home for a villager the layout has not placed cannot happen — both come from the same
        // state — and the well is the harmless answer if it ever does.
        val home = layout.homes[villager.id] ?: WELL
        val beside = beside(state, layout, positions, villager, home) ?: home
        val target =
            when (villager.activity) {
                Activity.WALKING_TO_WELL,
                Activity.RESTING -> wellSpot(queue[villager.id] ?: state.wellQueue.size)
                else -> beside
            }
        villager.id to Heading(target, beside)
    }
}

/** Everyone's pose this frame: walking until they arrive, and then whatever the reducer says. */
internal fun poses(
    state: FarmState,
    positions: Map<String, Spot>,
    headings: Map<String, Heading>,
): Map<String, Pose> =
    state.villagers.mapValues { (id, villager) ->
        // `toward` ends exactly on the target rather than near it, so this is an honest equality
        // and not a distance under some epsilon nobody would know how to pick.
        poseOf(villager.activity, arrived = positions[id] == headings[id]?.target)
    }

/**
 * What a villager does where it stands, from what the reducer says and whether it has got there.
 */
internal fun poseOf(activity: Activity, arrived: Boolean): Pose =
    when {
        !arrived -> Pose.WALKING
        activity == Activity.WALKING_TO_WELL -> Pose.WAITING
        activity == Activity.RESTING -> Pose.RESTING
        else -> Pose.STANDING
    }

/**
 * A walking villager off the ground by a pixel or two; everyone else stands exactly where it is.
 * [clock] is the scene's seconds, wrapped: see [BOB_RADIANS_A_SECOND] for why it is not the walk.
 */
internal fun bobbed(spot: Spot, pose: Pose, clock: Float): Spot =
    if (pose != Pose.WALKING) spot
    else
        Spot(
            spot.x,
            spot.y - abs(sin(clock * BOB_RADIANS_A_SECOND + spot.x + spot.y)) * BOB_TILES,
        )

/**
 * Where a helper stands beside its parent: the parent's own spot, offset by the gap the layout put
 * between their two homes, so a parent with three helpers keeps all three countable wherever it
 * goes. Null for a villager with nobody to follow — and for one whose lines named itself as its own
 * parent, which no feed of ours writes and which would otherwise be a villager chasing its own
 * tail.
 *
 * Brought back inside the world before it is answered: this is the one spot in the renderer worked
 * out from a live position rather than from a place the layout chose, so a helper of a parent on
 * the queue's back row would otherwise sit a tile above the canvas and not be drawn at all.
 */
private fun beside(
    state: FarmState,
    layout: FarmLayout,
    positions: Map<String, Spot>,
    villager: Villager,
    home: Spot,
): Spot? {
    val parent = state.parentOf(villager)?.takeIf { it.id != villager.id } ?: return null
    // A parent the layout has not placed cannot happen — [home] and this come from the one layout —
    // and the well is the same harmless answer [headings] gives when the villager's own is missing.
    val parentHome = layout.homes[parent.id] ?: WELL
    val at = positions[parent.id] ?: parentHome
    return inWorld(Spot(at.x + home.x - parentHome.x, at.y + home.y - parentHome.y))
}

/** Arrives exactly: a step longer than what is left of the walk ends on the target, not past it. */
private fun toward(from: Spot, to: Spot, distance: Float): Spot {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val gap = hypot(dx, dy)
    return if (gap <= distance || gap == 0f) to
    else Spot(from.x + dx / gap * distance, from.y + dy / gap * distance)
}
