package dev.peashoot.app.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.peashoot.app.farm.FarmState

/**
 * How long a crop pops for: long enough to catch out of the corner of an eye, short enough to be
 * over before the next line lands.
 */
internal const val EFFECT_SECONDS = 0.5f

/**
 * How many crops may change in one step and still be a farm at work.
 *
 * ponytail: a feed backfilled into one frame — a window opening onto a proxy that has been running
 * all day — would otherwise pop every crop in the repository at once, which reads as a glitch and
 * not as growth, so past this the whole diff is dropped rather than shown. The cost is that a
 * single turn editing more than [MOST_AT_ONCE] files shows none of them growing. Upgrade: pop them
 * in sequence, a few frames apart, if a turn that large ever turns out to be worth watching.
 */
internal const val MOST_AT_ONCE = 8

/** How much bigger a crop is at the top of its pop: half a tile again, and back down from there. */
private const val POP_SCALE = 0.5f

private val INSPECT_COLOUR = Color(0xFF2F6FE0)
private const val INSPECT_STROKE = 3f

/** What a crop is doing that the state itself cannot say, because it is over in half a second. */
internal enum class EffectKind {
    /** Planted, or advanced a growth stage: the crop swells and settles back. */
    GROW,
    /** Read: a ring round the tile, which fades. */
    INSPECT,
}

/** One effect part-way through, [age] in seconds since the farm that raised it arrived. */
internal data class Effect(val kind: EffectKind, val age: Float = 0f)

/**
 * What changed between two farms, keyed by the crop's path — which is the field and the crop both,
 * since a crop's field is its path's directory. Pure, and the whole of the renderer's knowledge of
 * what a turn did: the reducer keeps the crop's stage, not the fact that it moved.
 *
 * ponytail: an inspection plays at the crop and not at the villager that read it. The event line
 * names the tools a turn used but not which villager is standing where by the time the window folds
 * it in — a completed turn's villager is walking home within milliseconds — so sending someone to
 * the tile would be a guess. Upgrade: the reducer records the inspector on the crop, and the
 * renderer walks that villager over.
 */
internal fun cropEffects(before: FarmState?, after: FarmState): Map<String, Effect> {
    // The first farm a window sees is not something that happened: it is everything that already
    // had, which is the backfill case below by another name.
    if (before == null) return emptyMap()
    val changed = buildMap {
        for ((directory, field) in after.fields) {
            val had = before.fields[directory]?.crops.orEmpty()
            for ((path, crop) in field.crops) {
                val was = had[path]
                when {
                    was == null || crop.growth != was.growth -> put(path, Effect(EffectKind.GROW))
                    crop.inspections > was.inspections -> put(path, Effect(EffectKind.INSPECT))
                }
            }
        }
    }
    return if (changed.size > MOST_AT_ONCE) emptyMap() else changed
}

/** Every effect one frame older, and the ones that have run their course gone. */
internal fun aged(effects: Map<String, Effect>, seconds: Float): Map<String, Effect> =
    if (effects.isEmpty()) effects
    else
        effects
            .mapValues { (_, effect) -> effect.copy(age = effect.age + seconds) }
            .filterValues { it.age < EFFECT_SECONDS }

/**
 * One crop, with whatever it is doing. Still one [drawImage] from the one atlas, plus at most one
 * [drawRect]: nothing here builds a texture, for the reason `docs/research/canvas-frame-rate.md` §5
 * measured.
 */
internal fun DrawScope.drawCrop(
    atlas: ImageBitmap,
    sprite: Sprite,
    spot: Spot,
    effect: Effect?,
) {
    val left = effect?.let { 1f - (it.age / EFFECT_SECONDS).coerceIn(0f, 1f) } ?: 0f
    val grown = if (effect?.kind == EffectKind.GROW) 1f + POP_SCALE * left else 1f
    drawSprite(atlas, sprite, spot, grown)
    if (effect?.kind == EffectKind.INSPECT) {
        drawRect(
            color = INSPECT_COLOUR.copy(alpha = left),
            topLeft = Offset(spot.x * TILE_PX, spot.y * TILE_PX),
            size = Size(TILE_PX.toFloat(), TILE_PX.toFloat()),
            style = Stroke(width = INSPECT_STROKE),
        )
    }
}
