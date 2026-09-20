package dev.peashoot.app.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/**
 * What a click on the farm landed on. Public because the window acts on it and the renderer does
 * not: a click is news the canvas reports, never something it handles.
 */
sealed interface Hit {
    /** A villager, by the id the reducer keys it under: a session, or a session and its agent. */
    data class OnVillager(val id: String) : Hit

    /** A crop, by the normalised path that is both its key and its label. */
    data class OnCrop(val path: String) : Hit
}

/**
 * What is under [at], in canvas pixels. The inverse of what the draw phase does and nothing else:
 * `drawFarm` centres a fixed [WORLD_COLUMNS] x [WORLD_ROWS] world in whatever canvas it is given
 * with the same [margin], and every sprite goes down at [TILE_PX] per tile, so undoing exactly
 * those two is what makes the thing clicked the thing drawn.
 *
 * Villagers are tested before crops, and the one furthest down the screen wins, because that is the
 * order `drawFarm` draws them in: the eye clicked whatever is in front. A click on the grass
 * outside the world, or on a plot tile no crop has grown in yet, is nothing.
 */
internal fun hitAt(frame: Frame, at: Offset, canvas: Size): Hit? {
    val x = (at.x - margin(canvas.width, WORLD_COLUMNS)) / TILE_PX
    val y = (at.y - margin(canvas.height, WORLD_ROWS)) / TILE_PX
    return villagerAt(frame, x, y) ?: cropAt(frame.layout, x, y)
}

/**
 * The frontmost villager on that tile. Only villagers the farm still holds are candidates, for the
 * reason `drawFarm`'s own list is built that way: the animator keeps a position for a villager the
 * reducer has since retired, and one nobody can see is not one anybody clicked.
 */
private fun villagerAt(frame: Frame, x: Float, y: Float): Hit? =
    frame.farm.villagers.keys
        .mapNotNull { id -> frame.positions[id]?.let { id to it } }
        .filter { (_, spot) -> covers(spot, x, y) }
        .maxByOrNull { (_, spot) -> spot.y }
        ?.let { (id, _) -> Hit.OnVillager(id) }

/**
 * The crop on that tile. A plot's footprint is drawn whole whether or not the field has filled it,
 * so only the tiles a crop actually sits on answer: an empty furrow is soil and not a file.
 */
private fun cropAt(layout: FarmLayout, x: Float, y: Float): Hit? =
    layout.plots
        .asSequence()
        .flatMap { it.crops }
        .firstOrNull { covers(it.spot, x, y) }
        ?.let { Hit.OnCrop(it.crop.label) }

/** A sprite fills the tile its spot names, which is where `drawSprite` puts it at scale 1. */
private fun covers(spot: Spot, x: Float, y: Float): Boolean =
    x >= spot.x && x < spot.x + 1 && y >= spot.y && y < spot.y + 1
