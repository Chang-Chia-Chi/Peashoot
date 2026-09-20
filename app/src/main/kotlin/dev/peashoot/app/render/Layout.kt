package dev.peashoot.app.render

import dev.peashoot.app.farm.Crop
import dev.peashoot.app.farm.FarmState
import dev.peashoot.app.farm.Field
import dev.peashoot.app.farm.Villager
import dev.peashoot.app.farm.parentOf

/**
 * The farm is laid out for a 26 x 16 tile world, which is the 1280x800 dp window at 48 px tiles.
 *
 * ponytail: nothing scrolls and nothing scales. A window narrower than that clips the right-hand
 * plots; a wider one shows more grass. Upgrade: a camera that centres the used tiles, when someone
 * resizes the window and complains.
 */
internal const val WORLD_COLUMNS = 26
internal const val WORLD_ROWS = 16

/** Crops across and down one plot: a field's fixed footprint, which nothing later ever changes. */
internal const val PLOT_COLUMNS = 4
internal const val PLOT_ROWS = 3

/**
 * How many crops a plot draws.
 *
 * ponytail: crop number [PLOT_CAPACITY] + 1 and after are counted into [Plot.overflow] and not
 * drawn, rather than the plot growing or the farm reflowing — a farm that reshuffles on every event
 * cannot be read. Upgrade: a second plot for the same field, keyed by the field's name and its
 * index, once a real repository routinely puts more than twelve files in one directory.
 */
internal const val PLOT_CAPACITY = PLOT_COLUMNS * PLOT_ROWS

private const val PLOTS_ACROSS = 5
private const val PLOTS_DOWN = 3

/**
 * How many fields the farm draws.
 *
 * ponytail: fields past this are counted in [FarmLayout.hiddenFields] and left out, in the order
 * they were first heard, so the plots that are drawn never move. Upgrade: the camera above.
 */
internal const val PLOTS = PLOTS_ACROSS * PLOTS_DOWN

private const val PLOT_X0 = 1

/**
 * The well and its queue have the top two rows, and the row under them carries the first plots'
 * labels and the queue's names, so the fields start on the fourth.
 */
internal const val PLOT_Y0 = 3
private const val PLOT_PITCH_X = 5
private const val PLOT_PITCH_Y = 4

/**
 * Where the well stands: its base, with its roof on the tile above. Fixed, so the eye can learn it.
 */
internal val WELL = Spot(12f, 1f)

/** Eight spots either side of the well on a row, the nearest taken first. */
private const val QUEUE_ACROSS = 16
private const val QUEUE_ROWS = 2

/**
 * How many villagers can wait at the well with a spot of their own: two rows beside it, which is
 * every spot that is above the fields and inside the world.
 *
 * ponytail: past this they share the last spot. Villagers standing on each other still read as a
 * crowd at the well; villagers standing on a field hide the work, which is the farm's whole point.
 * Upgrade: a third rank, if a farm ever really has thirty-odd turns in flight at once.
 */
internal const val QUEUE_CAPACITY = QUEUE_ACROSS * QUEUE_ROWS

private const val HOME_Y = 15f
private const val HOME_X0 = 1f
private const val HOME_PITCH = 1f
private const val HOMES_ACROSS = WORLD_COLUMNS - 2
private const val HOME_ROWS = 2

/**
 * How many villagers have a place of their own along the bottom: two rows, neither of them a field.
 *
 * ponytail: past this they share the last place, for the reason the well queue does. Villagers are
 * the one per-frame cost with no ceiling — plots and crops have theirs — so a farm with hundreds of
 * sessions draws hundreds of sprites and hundreds of names; the spike's numbers say that is fine
 * into the thousands. Upgrade: draw one sprite per session group, if a farm ever gets there.
 */
internal const val HOME_CAPACITY = HOMES_ACROSS * HOME_ROWS

private const val HELPER_STEP = 0.7f
private const val HELPER_LIFT = 0.9f

/**
 * ponytail: a fourth helper of the same parent stands where the third does. Upgrade: a second rank
 * behind the first, if a session ever fans out that far and it matters that they are countable.
 */
private const val HELPERS_ABREAST = 3

/** A place on the farm in tiles, x rightwards and y downwards; pixels are the canvas's business. */
internal data class Spot(val x: Float, val y: Float)

/** One crop where it grows. */
internal data class CropSpot(val crop: Crop, val spot: Spot)

/**
 * One field as a plot of soil: [spot] is its top-left tile, and the plot is the fixed footprint.
 */
internal data class Plot(
    val label: String,
    val spot: Spot,
    val crops: List<CropSpot>,
    /** Crops this field has and this plot has no room for; see [PLOT_CAPACITY]. */
    val overflow: Int,
)

/** Where everything is. Pure, in tiles, and with no idea that Compose exists. */
internal data class FarmLayout(
    val plots: List<Plot>,
    /** Every villager's own spot, which is where it stands when it is not at the well. */
    val homes: Map<String, Spot>,
    val hiddenFields: Int,
)

/**
 * The farm's places from the farm's state. Fields keep the order they were first heard and each one
 * has a fixed footprint, so a plot, once placed, stays there however the rest of the farm grows;
 * that stability is the whole point, and it is what [LayoutTest] asserts line by line over a feed.
 */
internal fun farmLayout(state: FarmState): FarmLayout =
    FarmLayout(
        plots = state.fields.values.take(PLOTS).mapIndexed(::plotOf),
        homes = Homes(state).all(),
        hiddenFields = (state.fields.size - PLOTS).coerceAtLeast(0),
    )

/**
 * Where the villager [queueIndex] deep in the well queue waits: beside the well and never below it,
 * nearest spot first, alternating right and left, and onto the row above only once that row is
 * full. Queued villagers must not stand on the fields — a villager on a plot hides the crops that
 * are the point of drawing the farm at all.
 */
internal fun wellSpot(queueIndex: Int): Spot {
    val spot = queueIndex.coerceIn(0, QUEUE_CAPACITY - 1)
    val beside = spot % QUEUE_ACROSS
    val step = beside / 2 + 1
    return Spot(
        x = WELL.x + if (beside % 2 == 0) step.toFloat() else -step.toFloat(),
        y = WELL.y - spot / QUEUE_ACROSS,
    )
}

private fun plotOf(index: Int, field: Field): Plot {
    val spot =
        Spot(
            x = (PLOT_X0 + (index % PLOTS_ACROSS) * PLOT_PITCH_X).toFloat(),
            y = (PLOT_Y0 + (index / PLOTS_ACROSS) * PLOT_PITCH_Y).toFloat(),
        )
    val crops =
        field.crops.values.take(PLOT_CAPACITY).mapIndexed { i, crop ->
            CropSpot(
                crop,
                Spot(spot.x + (i % PLOT_COLUMNS), spot.y + (i / PLOT_COLUMNS)),
            )
        }
    return Plot(field.label, spot, crops, (field.crops.size - PLOT_CAPACITY).coerceAtLeast(0))
}

private fun mainHome(slot: Int): Spot {
    val place = slot.coerceIn(0, HOME_CAPACITY - 1)
    return Spot(
        x = HOME_X0 + (place % HOMES_ACROSS) * HOME_PITCH,
        y = HOME_Y - (place / HOMES_ACROSS),
    )
}

/**
 * Homes, worked out once per state. A villager with nobody to stand beside gets its own slot in the
 * row along the bottom, in the order it was first heard; a helper stands beside whoever
 * [FarmState.parentOf] says it belongs to, which is the session's villager until the helper it
 * names has been heard. A helper's home therefore moves when that parent arrives — it is the one
 * thing in the layout that does, and it moves because the fan-out it draws is what changed.
 */
private class Homes(private val state: FarmState) {
    /** Everyone with no parent to stand beside, in the order the farm heard them. */
    private val slots =
        state.villagers.values
            .filter { state.parentOf(it) == null }
            .withIndex()
            .associate { (slot, villager) -> villager.id to slot }

    private val abreast = siblings()

    private val known = mutableMapOf<String, Spot>()

    fun all(): Map<String, Spot> {
        state.villagers.values.forEach { homeOf(it, emptySet()) }
        return known
    }

    private fun siblings(): Map<String, Int> {
        val counted = mutableMapOf<String, Int>()
        return state.villagers.values
            .mapNotNull { villager ->
                val parent = state.parentOf(villager) ?: return@mapNotNull null
                val nth = counted.getOrElse(parent.id) { 0 }
                counted[parent.id] = nth + 1
                villager.id to nth
            }
            .toMap()
    }

    /**
     * [seen] is the helpers already being placed further down the stack: a feed that named two
     * agents as each other's parent would otherwise walk in a circle for ever.
     */
    private fun homeOf(villager: Villager, seen: Set<String>): Spot {
        known[villager.id]?.let {
            return it
        }
        val parent = state.parentOf(villager)
        val spot =
            when {
                slots[villager.id] != null -> mainHome(slots.getValue(villager.id))
                parent == null || parent.id in seen -> mainHome(0)
                else -> beside(homeOf(parent, seen + villager.id), abreast[villager.id] ?: 0)
            }
        known[villager.id] = spot
        return spot
    }

    private fun beside(parent: Spot, nth: Int): Spot =
        Spot(
            x = parent.x + HELPER_STEP * (nth.coerceAtMost(HELPERS_ABREAST - 1) + 1),
            y = parent.y - HELPER_LIFT,
        )
}
