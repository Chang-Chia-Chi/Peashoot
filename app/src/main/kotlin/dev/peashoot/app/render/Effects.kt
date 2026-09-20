package dev.peashoot.app.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.peashoot.app.farm.FarmState
import dev.peashoot.app.farm.Weather

/**
 * How long a crop pops for: long enough to catch out of the corner of an eye, short enough to be
 * over before the next line lands.
 */
internal const val EFFECT_SECONDS = 0.5f

/** How much bigger a crop is at the top of its pop: half a tile again, and back down from there. */
private const val POP_SCALE = 0.5f

private val INSPECT_COLOUR = Color(0xFF2F6FE0)
private const val INSPECT_STROKE = 3f

/** How big a spilled bucket's puddle is, in tiles, and where it sits under the villager's feet. */
private const val PUDDLE_TILES = 0.55f
private const val PUDDLE_DROP = 0.45f
private val PUDDLE_COLOUR = Color(0xFF3E7BC8)

/**
 * What something is doing that the state itself cannot say, because it is over in half a second.
 */
internal enum class EffectKind {
    /** Planted, or advanced a growth stage: the crop swells and settles back. */
    GROW,
    /** Read: a ring round the tile, which fades. */
    INSPECT,
    /** A turn whose client left: a puddle at the villager's feet, where the bucket went down. */
    SPILL,
    /** The sky turning to lightning: one white flash over the whole canvas. */
    FLASH,
}

/** One effect part-way through, [age] in seconds since the farm that raised it arrived. */
internal data class Effect(val kind: EffectKind, val age: Float = 0f)

/**
 * Everything part-way through this frame, in the three shapes the draw phase reads them in: by crop
 * path, by villager id, and the one flash that belongs to no thing at all. One holder rather than
 * three fields on [Frame], and rather than one map with both kinds of key in it: a crop's path and
 * a villager's id are both strings and there is no rule saying they can never be the same string.
 */
internal data class Effects(
    val crops: Map<String, Effect> = emptyMap(),
    val spills: Map<String, Effect> = emptyMap(),
    val flash: Effect? = null,
)

/**
 * What changed between two farms, keyed by the crop's path — which is the field and the crop both,
 * since a crop's field is its path's directory. Pure, and the whole of the renderer's knowledge of
 * what a turn did: the reducer keeps the crop's stage, not the fact that it moved.
 *
 * A crop both edited and read in one turn pops and does not ring: one tile cannot say two things at
 * once, and growth is the news.
 *
 * ponytail: an inspection plays at the crop and not at the villager that read it. Recording who
 * read a file is one field and not a guess — `completed` has the villager in hand when it grows
 * that turn's crops — so what stops it is the timing: the same line sets that villager RETURNING
 * and its next `started` walks it back to the well within milliseconds, and a detour to the tile
 * would be fighting both. Upgrade: the reducer records the inspector, and the renderer walks that
 * villager over when there is time in the turn for the walk.
 */
internal fun cropEffects(before: FarmState?, after: FarmState): Map<String, Effect> {
    // The first farm a window sees is not something that happened but everything that already had,
    // which is also every crop a window opening onto a running proxy would pop at once.
    if (before == null) return emptyMap()
    return buildMap {
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
}

/**
 * A bucket spilled since the last farm, by the villager that dropped it. The reducer counts spills
 * and never forgets one, so it is the count *rising* that is the news — a villager that spilled two
 * turns ago is not still spilling, and a window that connects to a farm mid-run must not open onto
 * a puddle under everyone who ever lost a stream.
 */
internal fun spillEffects(before: FarmState?, after: FarmState): Map<String, Effect> {
    if (before == null) return emptyMap()
    return buildMap {
        for ((id, villager) in after.villagers) {
            val had = before.villagers[id]?.spills ?: 0
            if (villager.spills > had) put(id, Effect(EffectKind.SPILL))
        }
    }
}

/**
 * Whether the step from one farm to the next is the moment the sky turned. Only the turning: the
 * weather has no decay, so a farm left on lightning would otherwise flash on every frame for as
 * long as nothing else happened.
 */
internal fun struck(before: FarmState?, after: FarmState): Boolean =
    before != null && before.weather != Weather.LIGHTNING && after.weather == Weather.LIGHTNING

/**
 * Everything the step from [before] to [after] sets off, laid over whatever is still running. A
 * second strike restarts a flash that is part-way through rather than being swallowed by it: it is
 * a second dropped stream, and a farm that hides the second one is hiding news. It cannot strobe —
 * only the *turning* to lightning strikes at all, and a farm already under it turns nowhere.
 */
internal fun Effects.raised(before: FarmState?, after: FarmState): Effects =
    Effects(
        crops = crops + cropEffects(before, after),
        spills = spills + spillEffects(before, after),
        flash = if (struck(before, after)) Effect(EffectKind.FLASH) else flash,
    )

/** Every effect one frame older, and the ones that have run their course gone. */
internal fun aged(effects: Effects, seconds: Float): Effects =
    Effects(
        crops = aged(effects.crops, seconds),
        spills = aged(effects.spills, seconds),
        flash =
            effects.flash
                ?.let { it.copy(age = it.age + seconds) }
                ?.takeIf { it.age < EFFECT_SECONDS },
    )

private fun aged(effects: Map<String, Effect>, seconds: Float): Map<String, Effect> =
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

/**
 * The puddle a spilled bucket left, at the feet of whoever dropped it, fading as it soaks in. One
 * [drawOval] and no sprite: neither Kenney pack has a bucket in a villager's hands, let alone a
 * tipped one, and an oval of water says it without an atlas rebuilt for one half second.
 */
internal fun DrawScope.drawSpill(spot: Spot, effect: Effect) {
    val left = 1f - (effect.age / EFFECT_SECONDS).coerceIn(0f, 1f)
    val width = PUDDLE_TILES * TILE_PX
    val height = width / 2
    drawOval(
        color = PUDDLE_COLOUR.copy(alpha = left),
        topLeft =
            Offset(
                (spot.x + (1f - PUDDLE_TILES) / 2) * TILE_PX,
                (spot.y + PUDDLE_DROP) * TILE_PX,
            ),
        size = Size(width, height),
    )
}
