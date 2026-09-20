package dev.peashoot.app.render

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import dev.peashoot.app.farm.FarmState

/** A frame longer than this is a window that was dragged or a laptop that slept, not a walk. */
private const val LONGEST_STEP = 0.1f

/**
 * The farm, drawn from one atlas. Everything here is a `drawImage` from a single immutable image
 * and a `drawText` from a cached layout; nothing builds a texture at runtime, for the reason
 * `docs/research/canvas-frame-rate.md` §5 measured.
 */
@Composable
fun FarmCanvas(farm: FarmState, modifier: Modifier = Modifier) {
    val atlas = remember { farmAtlas() }
    val measurer = rememberTextMeasurer(cacheSize = LABEL_CACHE)
    val scene = remember { FarmScene() }
    // The effect runs once and the farm changes under it, so the latest state has to be read inside
    // the frame callback rather than captured when the effect started.
    val latest by rememberUpdatedState(farm)
    LaunchedEffect(scene) {
        var previous = 0L
        while (true) {
            withFrameNanos { now ->
                scene.advance(latest, if (previous == 0L) 0L else now - previous)
                previous = now
            }
        }
    }
    Canvas(modifier) {
        // `scene.frame` is read here, in the draw phase: a new frame then redraws this canvas and
        // nothing around it recomposes — the dashboard's text and event list are untouched 165
        // times a second.
        drawFarm(atlas, measurer, scene.frame)
    }
}

/** One frame as the draw phase needs it: the farm, where everything is, and what everyone is at. */
internal data class Frame(
    val farm: FarmState,
    val layout: FarmLayout,
    val positions: Map<String, Spot>,
    val poses: Map<String, Pose> = emptyMap(),
    val effects: Map<String, Effect> = emptyMap(),
)

/**
 * The renderer's own state: where everyone is, what they are at, and which crops are part-way
 * through moving. A plain holder and not a set of remembered values: only [frame] is snapshot
 * state, because only it is read while drawing.
 */
internal class FarmScene {
    var frame by mutableStateOf(Frame(FarmState(), farmLayout(FarmState()), emptyMap()))
        private set

    private val meter = FrameMeter()
    private var layout = frame.layout
    private var effects = emptyMap<String, Effect>()
    private var last: FarmState? = null

    /**
     * One frame: age whatever the last farm set off, re-lay the farm and diff its crops if the
     * reducer has given us a new one, then walk everyone on. The layout is worked out only when the
     * farm itself changes — it cannot change between two frames of the same state, and the feed is
     * slower than the screen by three or four orders of magnitude. Where everyone is *heading* is
     * worked out every frame all the same, because a helper heads for wherever its parent has got
     * to, which is a thing that changes without the farm changing at all.
     */
    fun advance(farm: FarmState, deltaNanos: Long) {
        val seconds = (deltaNanos / NANOS_A_SECOND).toFloat().coerceAtMost(LONGEST_STEP)
        var popping = aged(effects, seconds)
        if (farm !== last) {
            popping = popping + cropEffects(last, farm)
            last = farm
            layout = farmLayout(farm)
        }
        effects = popping
        val aim = headings(farm, layout, frame.positions)
        val snapped = meter.frame(deltaNanos)
        val positions = step(frame.positions, aim, seconds, snapped)
        frame = Frame(farm, layout, positions, poses(farm, positions, aim), popping)
    }
}

private fun DrawScope.drawFarm(atlas: ImageBitmap, measurer: TextMeasurer, frame: Frame) {
    // The ground covers the canvas whatever size it is; the farm is a fixed 26 x 16 tiles, so it
    // sits in the middle of whatever is left over. Whole pixels, or the pixel art would smear.
    drawGround(atlas)
    translate(left = margin(size.width, WORLD_COLUMNS), top = margin(size.height, WORLD_ROWS)) {
        drawPlots(atlas, frame.layout)
        drawCrops(atlas, frame.layout, frame.effects)
        drawWell(atlas)
        drawVillagers(atlas, measurer, frame)
        if (!frame.farm.labelsHidden) drawPathLabels(measurer, frame.layout)
        drawHiddenFields(measurer, frame.layout)
    }
    // The badge belongs to the canvas and not to the farm: it must be in the same corner of every
    // screenshot, whatever the window is doing.
    if (!frame.farm.labelsHidden) drawBadge(measurer)
}

private fun margin(canvas: Float, tiles: Int): Float =
    ((canvas - tiles * TILE_PX) / 2).toInt().coerceAtLeast(0).toFloat()

/** The ground under everything, one tile at a time: 660 draw calls at the spike's canvas size. */
private fun DrawScope.drawGround(atlas: ImageBitmap) {
    val columns = (size.width / TILE_PX).toInt() + 1
    val rows = (size.height / TILE_PX).toInt() + 1
    for (row in 0 until rows) {
        for (column in 0 until columns) {
            drawSprite(atlas, groundSprite(column, row), Spot(column.toFloat(), row.toFloat()))
        }
    }
}

/** A plot is drawn at its full footprint whether or not the field has filled it: it never moves. */
private fun DrawScope.drawPlots(atlas: ImageBitmap, layout: FarmLayout) {
    for (plot in layout.plots) {
        for (row in 0 until PLOT_ROWS) {
            for (column in 0 until PLOT_COLUMNS) {
                drawSprite(atlas, furrow(column), Spot(plot.spot.x + column, plot.spot.y + row))
            }
        }
    }
}

private fun furrow(column: Int): Sprite =
    when (column) {
        0 -> Sprite.FURROW_LEFT
        PLOT_COLUMNS - 1 -> Sprite.FURROW_RIGHT
        else -> Sprite.FURROW_MIDDLE
    }

private fun DrawScope.drawCrops(
    atlas: ImageBitmap,
    layout: FarmLayout,
    effects: Map<String, Effect>,
) {
    for (plot in layout.plots) {
        for (crop in plot.crops) {
            val sprite = cropSprite(crop.crop.label, crop.crop.growth)
            drawCrop(atlas, sprite, crop.spot, effects[crop.crop.label])
        }
    }
}

private fun DrawScope.drawWell(atlas: ImageBitmap) {
    drawSprite(atlas, Sprite.WELL_ROOF, Spot(WELL.x, WELL.y - 1))
    drawSprite(atlas, Sprite.WELL_BASE, WELL)
}

/** Sorted by y, so a villager standing nearer the bottom overlaps one standing behind it. */
private fun DrawScope.drawVillagers(atlas: ImageBitmap, measurer: TextMeasurer, frame: Frame) {
    val standing =
        frame.farm.villagers.values
            .mapNotNull { villager -> frame.positions[villager.id]?.let { villager to it } }
            .sortedBy { (_, spot) -> spot.y }
    for ((villager, spot) in standing) {
        val pose = frame.poses[villager.id] ?: Pose.STANDING
        drawSprite(atlas, villagerSprite(villager.id), bobbed(spot, pose))
        drawMood(measurer, pose, spot)
    }
    drawNames(measurer, standing)
}
