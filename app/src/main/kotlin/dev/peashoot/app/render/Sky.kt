package dev.peashoot.app.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import dev.peashoot.app.farm.FarmState
import dev.peashoot.app.farm.Season
import dev.peashoot.app.farm.Weather

/** How much of the farm the night takes: dark enough to read as night, light enough to work in. */
private const val NIGHT_SHADE = 0.45f

/** A storm is a darker sky, not a darker farm, so it takes rather less than the night does. */
private const val STORM_SHADE = 0.25f

/**
 * The darkest the farm is ever drawn. A storm at night is darker than either alone, and this is
 * where that stops: a farm nobody can make anything out on has stopped being a dashboard.
 */
private const val DARKEST = 0.7f

/** How hard it falls, as a share of [MOST_STREAKS]. */
private const val RAIN_FALL = 0.35f
private const val STORM_FALL = 1f

/**
 * The most streaks one frame draws.
 *
 * A fixed count and not one per tile: the rain is one [drawPoints] call whatever its density, and
 * the list of ends it is handed must not grow with the canvas either, or a maximised window would
 * allocate its way through a frame. A bigger window gets the same rain, spread thinner.
 *
 * ponytail: that list is still built fresh each raining frame, so a storm boxes up to 480 `Offset`s
 * a frame — eden garbage beside the 660 `drawImage` calls the ground already makes, and none of it
 * while the sky is clear. Upgrade: a list the scene keeps and writes over, if the allocation ever
 * shows up in a profile.
 */
private const val MOST_STREAKS = 240

/**
 * How long and how slanted a streak is, in tiles, and how many canvas heights it falls a second.
 */
private const val STREAK_TILES = 0.55f
private const val STREAK_SLANT = 0.2f

/**
 * Whole canvas heights a second, which is what lets the rain run off [Frame.clock]: that clock
 * wraps at one second, and a fall of a whole number of heights is in the same place either side of
 * the wrap. A fractional one would jump every second.
 */
private const val FALLS_A_SECOND = 3f

private val RAIN_COLOUR = Color(0xAAB4CCE8)
private const val RAIN_STROKE = 2f

/** A flash is a flash and not a whiteout: the farm stays visible through it. */
private const val FLASH_ALPHA = 0.55f

/** A lantern is a soft pool of light with a brighter eye in it, both in tiles. */
private const val GLOW_TILES = 0.7f
private const val FLAME_TILES = 0.16f
private val GLOW_COLOUR = Color(0x59FFD98A)
private val FLAME_COLOUR = Color(0xFFFFE9A8)

/** The middle of a tile, for the things drawn round a point rather than into a rect. */
private const val TILE_MIDDLE = 0.5f

/**
 * Where a streak lands across the canvas, out of this many buckets: enough that 240 streaks do not
 * visibly line up, small enough that the hash stays cheap.
 */
private const val SCATTER = 1024

// The spatial hash `groundSprite` uses, for the same reason: rain that picked at random would
// jitter, since the whole sky is redrawn every frame and nothing remembers the last one.
private const val DROP_PRIME = 73856093
private const val DROP_MIX = 0x45D9F3B
private const val DROP_SHIFT = 13

/**
 * What the sky is doing this frame, as three numbers the draw phase can use without asking the farm
 * anything else: how dark the world is drawn under, how hard it is raining, and how much of a
 * lightning flash is still live. Pure, and the seam issue #21's first and fourth criteria are
 * asserted at — the reducer's half of the weather is already tested in `dev.peashoot.app.farm`.
 */
internal data class Sky(val shade: Float, val rain: Float, val flash: Float)

/**
 * The sky from the farm and whatever flash is part-way through. Night and a storm stack, because
 * they are two different reasons for the same darkness and a replayed route under a storm is both.
 */
internal fun skyOf(state: FarmState, flash: Effect?): Sky =
    Sky(
        shade =
            ((if (state.night) NIGHT_SHADE else 0f) + shadeOf(state.weather)).coerceAtMost(DARKEST),
        rain = fallOf(state.weather),
        flash = flash?.let { 1f - (it.age / EFFECT_SECONDS).coerceIn(0f, 1f) } ?: 0f,
    )

/**
 * The palette the month puts on the ground.
 *
 * A tint on the existing sprites and not four sets of ground tiles: neither Kenney pack has an
 * autumn or a snow tile, and [ColorFilter] with [BlendMode.Modulate] is the GPU's business, where a
 * recoloured sheet would be a texture built at runtime — the one thing
 * `docs/research/canvas-frame-rate.md` §5 forbids. Modulate multiplies, so every season can only
 * darken; spring is the palette the sprites were drawn in and is left alone, which is also what
 * keeps every still taken before #21 looking as it did.
 *
 * ponytail: winter is a cold cast rather than snow, because multiplying cannot brighten. Upgrade: a
 * white overlay over the ground rows, if a winter farm ever has to actually look snowed on.
 */
internal fun seasonTint(season: Season): ColorFilter? =
    when (season) {
        Season.SPRING -> null
        Season.SUMMER -> SUMMER_TINT
        Season.AUTUMN -> AUTUMN_TINT
        Season.WINTER -> WINTER_TINT
    }

/**
 * The weather over everything the farm has drawn: the rain first, then the shade over it, so a
 * storm's dark sits on the rain rather than under it. Drawn in canvas space and not in the world's,
 * because weather is over the whole window and not only over the 26 x 16 tiles in the middle of it.
 */
internal fun DrawScope.drawSky(sky: Sky, clock: Float) {
    drawRain(sky.rain, clock)
    if (sky.shade > 0f) drawRect(color = Color.Black.copy(alpha = sky.shade))
}

/**
 * The flash a dropped stream left, over everything the farm draws, labels included: it is the one
 * thing on the canvas meant to interrupt, and it is gone in half a second. The show-paths badge
 * sits above even this, because a screenshot must carry it whatever the weather was doing.
 */
internal fun DrawScope.drawFlash(sky: Sky) {
    if (sky.flash > 0f) drawRect(color = Color.White.copy(alpha = sky.flash * FLASH_ALPHA))
}

/**
 * The lanterns, at the fixed spots [LANTERNS] keeps clear. Drawn *after* the night's shade, so they
 * read as lit rather than as holes in it, and two circles rather than a sprite because neither pack
 * has a lamp in it — the same reason a waiting villager gets a `...` and not a thought bubble.
 */
internal fun DrawScope.drawLanterns() {
    for (spot in LANTERNS) {
        val centre = Offset((spot.x + TILE_MIDDLE) * TILE_PX, (spot.y + TILE_MIDDLE) * TILE_PX)
        drawCircle(color = GLOW_COLOUR, radius = GLOW_TILES * TILE_PX, center = centre)
        drawCircle(color = FLAME_COLOUR, radius = FLAME_TILES * TILE_PX, center = centre)
    }
}

private val SUMMER_TINT = ColorFilter.tint(Color(0xFFFFF0C8), BlendMode.Modulate)
private val AUTUMN_TINT = ColorFilter.tint(Color(0xFFE8B478), BlendMode.Modulate)
private val WINTER_TINT = ColorFilter.tint(Color(0xFFBCCCE4), BlendMode.Modulate)

private fun shadeOf(weather: Weather): Float =
    when (weather) {
        Weather.CLEAR,
        Weather.RAIN -> 0f
        Weather.STORM,
        Weather.LIGHTNING -> STORM_SHADE
    }

private fun fallOf(weather: Weather): Float =
    when (weather) {
        Weather.CLEAR -> 0f
        Weather.RAIN -> RAIN_FALL
        Weather.STORM,
        Weather.LIGHTNING -> STORM_FALL
    }

/**
 * Every streak in one [drawPoints]: the rain costs one draw call at any density, which is the whole
 * reason it is lines and not sprites. Where a streak sits comes from its own number, so the sky
 * does not shimmer, and how far it has fallen comes from the scene clock, so it does.
 */
private fun DrawScope.drawRain(fall: Float, clock: Float) {
    if (fall <= 0f) return
    val streaks = (MOST_STREAKS * fall).toInt()
    val length = STREAK_TILES * TILE_PX
    // The canvas, read out here: inside the builder `size` is the list's own.
    val canvas = size
    val ends =
        buildList(streaks * 2) {
            for (streak in 0 until streaks) {
                val x = scatter(streak) * canvas.width
                val top =
                    (scatter(streak + MOST_STREAKS) + clock * FALLS_A_SECOND).mod(1f) *
                        canvas.height
                add(Offset(x, top))
                add(Offset(x + STREAK_SLANT * TILE_PX, top + length))
            }
        }
    drawPoints(ends, PointMode.Lines, RAIN_COLOUR, RAIN_STROKE)
}

/** A number in 0..1 from a streak's own index, spread the way `groundSprite` spreads its grass. */
private fun scatter(seed: Int): Float {
    val hashed = seed * DROP_PRIME
    val mixed = (hashed xor (hashed ushr DROP_SHIFT)) * DROP_MIX
    return (mixed ushr DROP_SHIFT).mod(SCATTER) / SCATTER.toFloat()
}
