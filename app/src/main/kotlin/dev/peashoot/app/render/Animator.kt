package dev.peashoot.app.render

import dev.peashoot.app.farm.Activity
import dev.peashoot.app.farm.FarmState
import kotlin.math.hypot

/** How fast a villager walks, in tiles a second: a walk across the farm takes about eight. */
internal const val WALK_TILES_PER_SECOND = 3f

/** One second, in the units `withFrameNanos` speaks. The renderer's only clock, in one place. */
internal const val NANOS_A_SECOND = 1_000_000_000.0

/**
 * Where every villager is now, given where they were and how long the last frame took. The only
 * state the renderer keeps of its own: a villager's position is between two reducer states, which
 * is exactly what the reducer refuses to know.
 *
 * A villager the farm has only just heard of appears at its target rather than walking in from
 * wherever the map's edge is, and one the reducer has retired simply stops being here. [snapped] is
 * the degrade `docs/design.md` §10 names: under 30 fps villagers teleport instead of walking.
 */
internal fun step(
    positions: Map<String, Spot>,
    targets: Map<String, Spot>,
    seconds: Float,
    snapped: Boolean,
): Map<String, Spot> = targets.mapValues { (id, target) ->
    val from = positions[id]
    when {
        from == null || snapped -> target
        else -> toward(from, target, WALK_TILES_PER_SECOND * seconds)
    }
}

/**
 * Where each villager is heading: its spot in the queue at the well while a turn of its own is out
 * there or it is resting off a 429, and its own place on the farm otherwise.
 */
internal fun targets(state: FarmState, layout: FarmLayout): Map<String, Spot> =
    state.villagers.values.associate { villager ->
        villager.id to
            when (villager.activity) {
                Activity.WALKING_TO_WELL,
                Activity.RESTING -> wellSpot(queueIndex(state, villager.id))
                // A home for a villager the layout has not placed cannot happen — both come from
                // the same state — and the well is the harmless answer if it ever does.
                else -> layout.homes[villager.id] ?: WELL
            }
    }

/** A villager at the well the queue does not hold waits behind it rather than on top of someone. */
private fun queueIndex(state: FarmState, id: String): Int =
    state.wellQueue.indexOf(id).takeIf { it >= 0 } ?: state.wellQueue.size

/** Arrives exactly: a step longer than what is left of the walk ends on the target, not past it. */
private fun toward(from: Spot, to: Spot, distance: Float): Spot {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val gap = hypot(dx, dy)
    return if (gap <= distance || gap == 0f) to
    else Spot(from.x + dx / gap * distance, from.y + dy / gap * distance)
}
