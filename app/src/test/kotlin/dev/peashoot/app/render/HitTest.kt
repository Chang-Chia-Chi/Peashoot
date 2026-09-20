package dev.peashoot.app.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import dev.peashoot.app.farm.Activity
import dev.peashoot.app.farm.Villager
import dev.peashoot.app.farm.nameFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Bigger than the 26 x 16 world on both sides, so the margin the draw phase adds is not zero. */
private val ROOMY = Size(1400f, 900f)

/** Exactly the world, so a click's pixels are its tiles and the margin is nothing. */
private val SNUG = Size((WORLD_COLUMNS * TILE_PX).toFloat(), (WORLD_ROWS * TILE_PX).toFloat())

private const val PARENT = "sess-hit"
private const val HELPER = "sess-hit/agent-one"

/**
 * The click, at the seam #22 needs it at: a pure function from a laid-out frame and a point in
 * canvas pixels to what was clicked. Nothing here loads Skia or opens a window — what a click does
 * to the window is the window's, and where it lands is this.
 */
class HitTest {
    @Test
    fun `a click on a crop names the file under it, margin and all`() {
        val frame = frame()
        val crop = frame.layout.plots.first().crops.first()
        assertEquals(
            Hit.OnCrop("src/field0/File0.kt"),
            hitAt(frame, ROOMY.at(crop.spot.x, crop.spot.y), ROOMY),
        )
        // The same tile clicked on a canvas with no margin at all, which is the transform's other
        // end: the answer may not depend on how much grass is around the farm.
        assertEquals(
            Hit.OnCrop("src/field0/File0.kt"),
            hitAt(frame, SNUG.at(crop.spot.x, crop.spot.y), SNUG),
        )
    }

    @Test
    fun `the transform is the draw phase's, in pixels written down rather than computed`() {
        // Every other case here builds its click from the production `margin`, which would pass
        // just as happily if `margin` were wrong. These are worked out by hand: the world is
        // 26 x 16 tiles of 48 px, so on a 1400 x 900 canvas the farm starts at (76, 66), and the
        // first plot's first crop is the tile at (1, 3) — (76 + 48, 66 + 144).
        assertEquals(Offset(76f, 66f), ROOMY.at(0f, 0f))
        assertEquals(
            Hit.OnCrop("src/field0/File0.kt"),
            hitAt(frame(), Offset(124f, 210f), ROOMY),
        )
    }

    @Test
    fun `the whole tile answers, and the tile past it does not`() {
        val frame = frame()
        val crop = frame.layout.plots.first().crops.first()
        val inside = ROOMY.at(crop.spot.x + 0.99f, crop.spot.y + 0.99f)
        assertEquals(Hit.OnCrop("src/field0/File0.kt"), hitAt(frame, inside, ROOMY))
        // One tile right is the plot's second crop, not the first: the boundary is exclusive.
        val next = ROOMY.at(crop.spot.x + 1f, crop.spot.y)
        assertEquals(Hit.OnCrop("src/field0/File1.kt"), hitAt(frame, next, ROOMY))
    }

    @Test
    fun `a click in the margin, or on a furrow nothing grows in, is nothing`() {
        val frame = frame()
        // The grass left of the world: the margin is 76 px wide on this canvas.
        assertNull(hitAt(frame, Offset(4f, 4f), ROOMY))
        assertNull(hitAt(frame, Offset(ROOMY.width - 4f, ROOMY.height - 4f), ROOMY))
        // Inside the first plot's 4 x 3 footprint, past the two crops the field has: a plot is
        // drawn whole whether or not the field has filled it, and empty soil is not a file.
        val plot = frame.layout.plots.first().spot
        assertNull(hitAt(frame, ROOMY.at(plot.x + 3f, plot.y + 2f), ROOMY))
    }

    @Test
    fun `a helper overlapping its parent loses the click to whoever is drawn in front`() {
        // The helper stands up and to the right of the parent, as the layout puts it, so their
        // tiles overlap. `drawFarm` sorts by y, so the parent is drawn last and is what the eye
        // clicked; the helper's own tile, clear of the parent, still answers for the helper.
        val frame = frame(PARENT to Spot(10f, 12f), HELPER to Spot(10.7f, 11.1f))
        assertEquals(Hit.OnVillager(PARENT), hitAt(frame, ROOMY.at(10.8f, 12.05f), ROOMY))
        assertEquals(Hit.OnVillager(HELPER), hitAt(frame, ROOMY.at(11.5f, 11.5f), ROOMY))
    }

    @Test
    fun `a villager standing on a crop wins it, and one the reducer has retired does not`() {
        val crop = frame().layout.plots.first().crops.first().spot
        val standing = frame(PARENT to crop)
        assertEquals(Hit.OnVillager(PARENT), hitAt(standing, ROOMY.at(crop.x, crop.y), ROOMY))
        // The animator keeps a position for a villager the farm no longer holds; nobody can see it,
        // so nobody clicked it, and the crop underneath answers instead.
        val retired = standing.copy(farm = standing.farm.copy(villagers = emptyMap()))
        assertEquals(
            Hit.OnCrop("src/field0/File0.kt"),
            hitAt(retired, ROOMY.at(crop.x, crop.y), ROOMY),
        )
    }
}

/** The pixel at the top-left of tile ([x], [y]) on a canvas of this size, margin included. */
private fun Size.at(x: Float, y: Float): Offset =
    Offset(
        margin(width, WORLD_COLUMNS) + x * TILE_PX,
        margin(height, WORLD_ROWS) + y * TILE_PX,
    )

/** One laid-out frame: a field of two crops, and whoever the caller stands where. */
private fun frame(vararg people: Pair<String, Spot>): Frame {
    val farm =
        syntheticFarm(fields = 1, crops = 2)
            .copy(
                villagers =
                    people.associate { (id, _) ->
                        id to
                            Villager(
                                id = id,
                                name = nameFor(id),
                                session = PARENT,
                                parent = if (id == PARENT) null else PARENT,
                                activity = Activity.RETURNING,
                            )
                    }
            )
    return Frame(farm, farmLayout(farm), people.toMap())
}
