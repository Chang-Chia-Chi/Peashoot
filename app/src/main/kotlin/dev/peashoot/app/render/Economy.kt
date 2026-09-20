package dev.peashoot.app.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import dev.peashoot.app.farm.ShippingBin
import dev.peashoot.app.farm.Stamina
import java.util.Locale

/** A bar is a thin thing over a head: wide as the sprite, and a few pixels of it. */
private const val BAR_TILES = 0.9f
private const val BAR_HEIGHT = 5f
private const val BAR_LEFT = -0.2f

/** Over the head and above the mood, which already has the row right under it. */
private const val BAR_TOP = -0.78f

private val BAR_EMPTY = Color(0xFF4A3B2E)
private val BAR_FULL = Color(0xFF4FA84F)

/** Under a quarter left is the point at which a villager is about to be sent to rest. */
private const val BAR_LOW = 0.25f
private val BAR_SPENT = Color(0xFFD06A2A)

/**
 * The ledger sits under the crate, in the corner of the world the queue never reaches.
 *
 * ponytail: its box ends a twentieth of a tile short of where the sixteenth villager in the well
 * queue has its name written, so a farm with a queue that deep puts the two within a couple of
 * pixels of each other. A queue sixteen deep is already sharing spots — see [QUEUE_CAPACITY] — and
 * money elided to `$12.3…` would be worse than money nearly touching a name. Upgrade: give the bin
 * a row of its own, if a farm ever routinely runs a queue that long.
 */
private const val LEDGER_DROP = 0.85f
private const val LEDGER_LEFT = -0.9f
private const val LEDGER_TILES = 3.6f

/**
 * How full a villager's stamina bar is, or null for one with no honest bar to draw.
 *
 * The provider's headers say what is left and never what the whole was, so the whole is the most
 * this villager has ever been told it had — see [Stamina]. A villager that has heard neither, or
 * whose peak is nothing, gets no bar rather than a made-up one: an empty bar and no bar say
 * different things, and only one of them is true of a villager nothing has reported on yet.
 */
internal fun staminaFraction(stamina: Stamina): Float? {
    val peak = stamina.peakTokens?.takeIf { it > 0L } ?: return null
    return stamina.remainingTokens?.let { (it.toFloat() / peak).coerceIn(0f, 1f) }
}

/**
 * What the shipping bin has taken in, in one line. Money is not a path, so this is drawn whether or
 * not the show-paths toggle is on: a ledger nobody can see is a farm that hides its own point.
 *
 * The unpriced count only appears when there is one, because "0 unpriced" is noise on every farm
 * that has ever had a price — and when it does appear it is the ledger explaining why it is quieter
 * than the day was, which is the whole reason the reducer counts them.
 */
internal fun binLine(bin: ShippingBin): String {
    val money = String.format(Locale.ROOT, "%.2f", bin.ledger)
    val unpriced = if (bin.unpriced > 0) " · ${bin.unpriced} unpriced" else ""
    return "${bin.produce} shipped · \$$money$unpriced"
}

/**
 * One villager's stamina over its head: the whole in the dark colour, what is left over the top of
 * it. Two [drawRect]s and no sprite, and none at all for a villager [staminaFraction] has nothing
 * honest to say about.
 */
internal fun DrawScope.drawStamina(stamina: Stamina, spot: Spot) {
    val left = staminaFraction(stamina) ?: return
    val width = BAR_TILES * TILE_PX
    val corner = Offset((spot.x + BAR_LEFT) * TILE_PX, (spot.y + BAR_TOP) * TILE_PX)
    drawRect(color = BAR_EMPTY, topLeft = corner, size = Size(width, BAR_HEIGHT))
    drawRect(
        color = if (left <= BAR_LOW) BAR_SPENT else BAR_FULL,
        topLeft = corner,
        size = Size(width * left, BAR_HEIGHT),
    )
}

/** The bin's own line, under the crate at [BIN]. */
internal fun DrawScope.drawLedger(measurer: TextMeasurer, bin: ShippingBin, night: Boolean) {
    drawLabel(
        measurer,
        binLine(bin),
        Spot(BIN.x + LEDGER_LEFT, BIN.y + LEDGER_DROP),
        nameStyle(night),
        LEDGER_TILES,
    )
}
