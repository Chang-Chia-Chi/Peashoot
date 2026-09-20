package dev.peashoot.app.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import dev.peashoot.app.farm.Villager

/**
 * Distinct label strings kept measured. Text layout is the one cost `docs/research/canvas-frame-
 * rate.md` §9 says the spike never measured, so no string is laid out twice: [TextMeasurer] keeps
 * its own cache across frames, which is what this size is for. A farm has one label per crop, per
 * field and per villager, so 512 covers a farm several times over.
 */
internal const val LABEL_CACHE = 512

/**
 * Two inks, not one recoloured on the fly: the weather's shade goes over the world and the text
 * goes over the shade, so near-black on a darkened farm is the one thing that stops being readable.
 * Which one is used is [Sky.pale]'s to say and not this file's — the thing that decides it is how
 * dark the ground has been made, which is the sky's business. Kept as constants because
 * [TextMeasurer] caches against the style, and a `copy` made per label per frame would be a new
 * object 165 times a second for no gain.
 */
private val DARK_INK = Color(0xFF1B1B1F)
private val PALE_INK = Color(0xFFF2F0EA)

private val LABEL_STYLE = TextStyle(color = DARK_INK, fontSize = 11.sp)
private val PALE_LABEL_STYLE = TextStyle(color = PALE_INK, fontSize = 11.sp)

private val NAME_STYLE = TextStyle(color = DARK_INK, fontSize = 10.sp)
private val PALE_NAME_STYLE = TextStyle(color = PALE_INK, fontSize = 10.sp)

private val BADGE_STYLE =
    TextStyle(color = Color(0xFF1B1B1F), fontSize = 14.sp, fontWeight = FontWeight.Bold)

/** The badge is on the canvas itself, so that a screenshot of the farm carries it. */
private const val BADGE_TEXT = "PATHS VISIBLE"
private val BADGE_BACKGROUND = Color(0xFFFFC400)
private const val BADGE_PADDING = 8f

/**
 * A label sits just under the sprite it names. A field's sits in the gap row *above* its plot
 * instead: the row under a plot is where the last furrow's crop labels fall and where the villagers
 * along the bottom stand, while the row above one holds nothing but the row before's crop labels.
 */
private const val UNDER_A_TILE = 0.82f
private const val ABOVE_A_PLOT = -0.6f

/** Kept clear at the end of a label's box, so an elided one does not touch the next. */
private const val LABEL_GUTTER = 4f

/** A name is wider than the villager it belongs to; nothing in the name list needs more. */
private const val NAME_TILES = 1.5f
private const val NAME_LEFT = -0.25f

/** A mood sits over the head rather than under the feet, where the name already is. */
private const val OVER_A_HEAD = -0.55f

/**
 * Waiting and resting, in the two characters each needs.
 *
 * Neither Kenney pack has a thought bubble, a bucket in hand or a sleeping villager — they have two
 * people and no poses at all — so the pose is text over the head through the same cached measurer
 * every other label goes through, rather than a sprite invented for it or an atlas rebuilt.
 */
private const val WAITING_TEXT = "..."
private const val RESTING_TEXT = "zzz"

private val MARKER_SPOT = Spot(0.2f, 0.4f)
private const val MARKER_TILES = 5f

/**
 * A field's directory under its plot and each crop's file name under the crop — the file name
 * alone, because the field above already says the directory. Drawn only when the farm's
 * `labelsHidden` is off, which is what the show-paths toggle flips.
 *
 * ponytail: a label is elided to the tile it belongs to, so `ControlClient.kt` reads as `Contr…`
 * rather than covering its two neighbours. At a 48 px pitch nothing else fits. Upgrade: the whole
 * path belongs in #22's click-a-crop pane, which is where someone who needs to read it will look.
 */
internal fun DrawScope.drawPathLabels(measurer: TextMeasurer, layout: FarmLayout, pale: Boolean) {
    for (plot in layout.plots) {
        val label = if (plot.overflow > 0) "${plot.label} +${plot.overflow}" else plot.label
        drawLabel(
            measurer,
            label,
            Spot(plot.spot.x, plot.spot.y + ABOVE_A_PLOT),
            labelStyle(pale),
            PLOT_COLUMNS.toFloat(),
        )
        for (crop in plot.crops) {
            drawLabel(
                measurer,
                crop.crop.label.substringAfterLast('/'),
                Spot(crop.spot.x, crop.spot.y + UNDER_A_TILE),
                nameStyle(pale),
                1f,
            )
        }
    }
}

/**
 * Everything that has to be read rather than looked at, drawn after the weather rather than under
 * it: the moods, the names, the stamina bars, the bin's ledger and — when the toggle is on — the
 * paths. A sky that swallowed the names would be a farm that had stopped saying anything, and the
 * ink switches with it for the same reason — with the shade, not with the night, because a daytime
 * storm darkens the ground too and the night is only the deepest way it happens.
 *
 * The spilled bucket's puddle is here too, which costs it something: drawn after the sprites, it
 * sits over the villager's feet rather than under them. That is the lesser loss. A spill only ever
 * happens under lightning, so the shade is always over it — a quarter of the farm's light gone, and
 * seven tenths under a replayed route — and the puddle is the only thing that says *which* villager
 * dropped the bucket, the flash saying only that somebody did.
 */
internal fun DrawScope.drawOverlays(
    measurer: TextMeasurer,
    frame: Frame,
    standing: List<Pair<Villager, Spot>>,
) {
    val pale = frame.sky.pale
    for ((villager, spot) in standing) {
        frame.effects.spills[villager.id]?.let { drawSpill(spot, it) }
        drawMood(measurer, frame.poses[villager.id] ?: Pose.STANDING, spot, pale)
        drawStamina(villager.stamina, spot)
    }
    drawNames(measurer, standing, pale)
    drawLedger(measurer, frame.farm.bin, pale)
    if (!frame.farm.labelsHidden) drawPathLabels(measurer, frame.layout, pale)
    drawHiddenFields(measurer, frame.layout, pale)
}

/** Names are not paths, so they are drawn whether or not the paths are. */
internal fun DrawScope.drawNames(
    measurer: TextMeasurer,
    standing: List<Pair<Villager, Spot>>,
    pale: Boolean,
) {
    for ((villager, spot) in standing) {
        drawLabel(
            measurer,
            villager.name,
            Spot(spot.x + NAME_LEFT, spot.y + UNDER_A_TILE),
            nameStyle(pale),
            NAME_TILES,
        )
    }
}

/** Which ink a name, a mood or a ledger is written in; [Sky.pale] is what decides it. */
internal fun nameStyle(pale: Boolean): TextStyle = if (pale) PALE_NAME_STYLE else NAME_STYLE

private fun labelStyle(pale: Boolean): TextStyle = if (pale) PALE_LABEL_STYLE else LABEL_STYLE

/**
 * What a villager is at, over its head: waiting its turn at the well, or resting off a 429. Walking
 * and standing show nothing — a farm where every sprite carries a caption says less, not more.
 */
internal fun DrawScope.drawMood(measurer: TextMeasurer, pose: Pose, spot: Spot, pale: Boolean) {
    val text =
        when (pose) {
            Pose.WAITING -> WAITING_TEXT
            Pose.RESTING -> RESTING_TEXT
            Pose.WALKING,
            Pose.STANDING -> return
        }
    drawLabel(
        measurer,
        text,
        Spot(spot.x + NAME_LEFT, spot.y + OVER_A_HEAD),
        nameStyle(pale),
        NAME_TILES,
    )
}

/**
 * The fields there was no plot for. A count and not a path, so it does not wait for the toggle: a
 * farm that quietly drops directories is worse than one that says how many it dropped.
 */
internal fun DrawScope.drawHiddenFields(
    measurer: TextMeasurer,
    layout: FarmLayout,
    pale: Boolean,
) {
    if (layout.hiddenFields <= 0) return
    drawLabel(
        measurer,
        "+${layout.hiddenFields} fields not shown",
        MARKER_SPOT,
        labelStyle(pale),
        MARKER_TILES,
    )
}

internal fun DrawScope.drawBadge(measurer: TextMeasurer) {
    val measured = measurer.measure(BADGE_TEXT, BADGE_STYLE)
    val width = measured.size.width + 2 * BADGE_PADDING
    val height = measured.size.height + 2 * BADGE_PADDING
    val left = size.width - width - BADGE_PADDING
    drawRect(
        color = BADGE_BACKGROUND,
        topLeft = Offset(left, BADGE_PADDING),
        size = Size(width, height),
    )
    drawText(
        textMeasurer = measurer,
        text = BADGE_TEXT,
        topLeft = Offset(left + BADGE_PADDING, BADGE_PADDING * 2),
        style = BADGE_STYLE,
    )
}

/**
 * One label, in the box [tiles] wide it is allowed. The box is part of what [TextMeasurer] caches
 * against, so an elided label is still measured once and reused for every later frame.
 */
internal fun DrawScope.drawLabel(
    measurer: TextMeasurer,
    text: String,
    spot: Spot,
    style: TextStyle,
    tiles: Float,
) {
    drawText(
        textMeasurer = measurer,
        text = text,
        topLeft = Offset(spot.x * TILE_PX, spot.y * TILE_PX),
        style = style,
        overflow = TextOverflow.Ellipsis,
        softWrap = false,
        maxLines = 1,
        size = Size(tiles * TILE_PX - LABEL_GUTTER, TILE_PX.toFloat()),
    )
}
