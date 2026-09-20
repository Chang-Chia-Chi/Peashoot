package dev.peashoot.app.render

import dev.peashoot.app.farm.FarmState
import dev.peashoot.app.farm.replay
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How far apart two villagers may stand and still read as standing beside each other, in tiles. */
private const val BESIDE = 2f

/**
 * The layout at the seam the reducer's tests use: recorded event lines in, tile coordinates out.
 * Pure — no Compose, no window, no atlas — which is what lets it run on a headless CI box.
 */
class LayoutTest {
    @Test
    fun `a plot, a crop and a home keep their places as later lines arrive`() {
        assertStable(replay("one-turn.jsonl"))
        assertStable(replay("paths.jsonl"))
        assertStable(replay("sub-agents.jsonl"))
    }

    @Test
    fun `plots never overlap each other or the well`() {
        val plots = farmLayout(syntheticFarm(fields = PLOTS, crops = PLOT_CAPACITY)).plots
        assertEquals(PLOTS, plots.size)
        for ((i, plot) in plots.withIndex()) {
            for (other in plots.drop(i + 1)) {
                assertTrue(!overlaps(plot, other), "${plot.label} overlaps ${other.label}")
            }
            for (tile in wellTiles()) {
                assertTrue(!covers(plot, tile), "${plot.label} covers the well at $tile")
            }
        }
    }

    @Test
    fun `a helper stands beside its parent, even one heard before it`() {
        val states = replay("sub-agents.jsonl")
        val homes = farmLayout(states.last()).homes
        assertTrue(
            distance(
                homes.getValue("sess-charlie/agent-two"),
                homes.getValue("sess-charlie/agent-one"),
            ) <= BESIDE,
            "a helper stands beside the helper it names",
        )
        assertTrue(
            distance(
                homes.getValue("sess-charlie/agent-three"),
                homes.getValue("sess-charlie/agent-late"),
            ) <= BESIDE,
            "a helper stands beside a parent heard after it",
        )
        // Before that parent was heard, the same helper stood beside its session's villager.
        val meanwhile =
            farmLayout(
                    states.first {
                        "sess-charlie/agent-three" in it.villagers &&
                            "sess-charlie/agent-late" !in it.villagers
                    }
                )
                .homes
        assertTrue(
            distance(
                meanwhile.getValue("sess-charlie/agent-three"),
                meanwhile.getValue("sess-charlie"),
            ) <= BESIDE,
            "a helper whose parent has not been heard stands beside its session",
        )
    }

    @Test
    fun `a field that outgrows its plot keeps the crops it was planted first`() {
        val plot = farmLayout(syntheticFarm(fields = 1, crops = PLOT_CAPACITY + 3)).plots.single()
        assertEquals(PLOT_CAPACITY, plot.crops.size)
        assertEquals(3, plot.overflow)
        assertEquals("src/field0/File0.kt", plot.crops.first().crop.label)
        assertEquals("src/field0/File${PLOT_CAPACITY - 1}.kt", plot.crops.last().crop.label)
    }

    @Test
    fun `a farm with more fields than plots draws the first ones and counts the rest`() {
        val layout = farmLayout(syntheticFarm(fields = PLOTS + 2, crops = 1))
        assertEquals(PLOTS, layout.plots.size)
        assertEquals(2, layout.hiddenFields)
    }

    @Test
    fun `nobody waiting at the well stands on a field`() {
        val plots = farmLayout(syntheticFarm(fields = PLOTS, crops = PLOT_CAPACITY)).plots
        val queue = (0 until QUEUE_CAPACITY).map(::wellSpot)
        assertEquals(QUEUE_CAPACITY, queue.distinct().size, "two villagers were sent to one spot")
        for (spot in queue) {
            for (plot in plots) {
                assertTrue(!standsOn(plot, spot), "a villager at the well is on ${plot.label}")
            }
            for (tile in wellTiles()) {
                assertTrue(spot != tile, "a villager is standing in the well")
            }
        }
        // Past the last spot they share it: crowding each other is fine, covering a field is not.
        assertEquals(wellSpot(QUEUE_CAPACITY - 1), wellSpot(QUEUE_CAPACITY + 5))
    }

    @Test
    fun `nobody's home is on a field either`() {
        val state =
            syntheticFarm(
                fields = PLOTS,
                crops = PLOT_CAPACITY,
                villagers = HOME_CAPACITY + 5,
                atWell = false,
            )
        val layout = farmLayout(state)
        for ((id, home) in layout.homes) {
            for (plot in layout.plots) {
                assertTrue(!standsOn(plot, home), "$id is standing on ${plot.label}")
            }
        }
        assertEquals(
            HOME_CAPACITY,
            layout.homes.values.distinct().size,
            "villagers past the last home share it rather than wandering into the fields",
        )
    }
}

/** Every villager, plot and crop that has a place keeps it, state after state after state. */
private fun assertStable(states: List<FarmState>) {
    val plots = mutableMapOf<String, Spot>()
    val crops = mutableMapOf<String, Spot>()
    val homes = mutableMapOf<String, Spot>()
    for (state in states) {
        val layout = farmLayout(state)
        for (plot in layout.plots) {
            assertEquals(
                plots.put(plot.label, plot.spot) ?: plot.spot,
                plot.spot,
                "plot ${plot.label} moved",
            )
            for (crop in plot.crops) {
                val label = crop.crop.label
                assertEquals(
                    crops.put(label, crop.spot) ?: crop.spot,
                    crop.spot,
                    "crop $label moved",
                )
            }
        }
        for ((id, home) in layout.homes) {
            // A helper's home is its parent's, so it moves when a parent heard later takes over.
            if (state.villagers.getValue(id).parent != null) continue
            assertEquals(homes.put(id, home) ?: home, home, "$id's home moved")
        }
    }
}

private fun distance(one: Spot, other: Spot): Float = hypot(one.x - other.x, one.y - other.y)

private fun overlaps(one: Plot, other: Plot): Boolean =
    one.spot.x < other.spot.x + PLOT_COLUMNS &&
        other.spot.x < one.spot.x + PLOT_COLUMNS &&
        one.spot.y < other.spot.y + PLOT_ROWS &&
        other.spot.y < one.spot.y + PLOT_ROWS

private fun covers(plot: Plot, tile: Spot): Boolean =
    tile.x >= plot.spot.x &&
        tile.x < plot.spot.x + PLOT_COLUMNS &&
        tile.y >= plot.spot.y &&
        tile.y < plot.spot.y + PLOT_ROWS

/** A villager is a tile wide and a tile tall, so standing anywhere it laps the plot counts. */
private fun standsOn(plot: Plot, spot: Spot): Boolean =
    spot.x < plot.spot.x + PLOT_COLUMNS &&
        plot.spot.x < spot.x + 1 &&
        spot.y < plot.spot.y + PLOT_ROWS &&
        plot.spot.y < spot.y + 1

private fun wellTiles(): List<Spot> = listOf(WELL, Spot(WELL.x, WELL.y - 1))
