package dev.peashoot.app.render

import dev.peashoot.app.farm.Growth

/** One tile of the atlas, in pixels: Kenney's packed tilemaps are 16x16 with no spacing. */
internal const val TILE = 16

/** The atlas is the two packed tilemaps stacked, so Tiny Farm's row 0 is row 11 here. */
private const val FARM_ROW_0 = 11

/**
 * A frame rectangle in `farm/atlas.png`, as a column and a row of 16 px tiles: the source rect is
 * `(column * TILE, row * TILE, TILE, TILE)`. A table and not a JSON manifest, because a manifest
 * would need a parser and a second file to keep in step with the picture.
 *
 * Rows 0-10 are Kenney's Tiny Town, rows 11-21 Tiny Farm; `app/src/main/resources/farm/README.md`
 * says how the one image is built. Neither pack has a plant with four stages in the ground, so a
 * crop's last stage is the pack's own produce sprite, which is the only unambiguous "pick me".
 */
internal enum class Sprite(val column: Int, val row: Int) {
    GRASS(column = 0, row = 0),
    GRASS_TUFTS(column = 1, row = 0),
    GRASS_FLOWERS(column = 2, row = 0),

    /**
     * A row of tilled soil, drawn as a left cap, as many middles as the plot is wide, a right cap.
     */
    FURROW_LEFT(column = 0, row = FARM_ROW_0 + 5),
    FURROW_MIDDLE(column = 1, row = FARM_ROW_0 + 5),
    FURROW_RIGHT(column = 2, row = FARM_ROW_0 + 5),

    /** The well is two tiles tall: a wooden roof over a stone base with water in it. */
    WELL_ROOF(column = 8, row = 7),
    WELL_BASE(column = 8, row = 8),

    /** The only two people in either pack; Tiny Town's own villagers are in Tiny Dungeon. */
    VILLAGER_OVERALLS(column = 0, row = FARM_ROW_0 + 9),
    VILLAGER_HAT(column = 1, row = FARM_ROW_0 + 9),
    CARROT_SEED(column = 4, row = FARM_ROW_0),
    CARROT_SPROUT(column = 5, row = FARM_ROW_0),
    CARROT_GROWING(column = 6, row = FARM_ROW_0),
    CARROT_RIPE(column = 8, row = FARM_ROW_0),
    TURNIP_SEED(column = 4, row = FARM_ROW_0 + 1),
    TURNIP_SPROUT(column = 5, row = FARM_ROW_0 + 1),
    TURNIP_GROWING(column = 6, row = FARM_ROW_0 + 1),
    TURNIP_RIPE(column = 8, row = FARM_ROW_0 + 1),
    TOMATO_SEED(column = 4, row = FARM_ROW_0 + 3),
    TOMATO_SPROUT(column = 5, row = FARM_ROW_0 + 3),
    TOMATO_GROWING(column = 6, row = FARM_ROW_0 + 3),
    TOMATO_RIPE(column = 8, row = FARM_ROW_0 + 3),
    CABBAGE_SEED(column = 4, row = FARM_ROW_0 + 4),
    CABBAGE_SPROUT(column = 5, row = FARM_ROW_0 + 4),
    CABBAGE_GROWING(column = 6, row = FARM_ROW_0 + 4),
    CABBAGE_RIPE(column = 8, row = FARM_ROW_0 + 4),
}

/** The four stages of each plant, in [Growth] order. A field of one plant would read as a field. */
private val PLANTS =
    listOf(
        listOf(Sprite.CARROT_SEED, Sprite.CARROT_SPROUT, Sprite.CARROT_GROWING, Sprite.CARROT_RIPE),
        listOf(Sprite.TURNIP_SEED, Sprite.TURNIP_SPROUT, Sprite.TURNIP_GROWING, Sprite.TURNIP_RIPE),
        listOf(Sprite.TOMATO_SEED, Sprite.TOMATO_SPROUT, Sprite.TOMATO_GROWING, Sprite.TOMATO_RIPE),
        listOf(
            Sprite.CABBAGE_SEED,
            Sprite.CABBAGE_SPROUT,
            Sprite.CABBAGE_GROWING,
            Sprite.CABBAGE_RIPE,
        ),
    )

private val VILLAGERS = listOf(Sprite.VILLAGER_OVERALLS, Sprite.VILLAGER_HAT)

/** One tile in twelve is a tufted one and one in twelve has flowers; the rest is plain grass. */
private const val GROUND_SPREAD = 12
private const val GROUND_TUFTS = 0
private const val GROUND_FLOWERS = 7

// The usual spatial hash: two large primes and a shift-mix. Small primes alone leave the variants
// on diagonals — which is exactly what the first rendering of the farm showed.
private const val GROUND_COLUMN_PRIME = 73856093
private const val GROUND_ROW_PRIME = 19349663
private const val GROUND_MIX = 0x45D9F3B
private const val GROUND_SHIFT = 13

/**
 * Which grass a ground tile gets, from where it is and nothing else: a ground that picked at random
 * would shimmer, since the whole ground is redrawn every frame.
 */
internal fun groundSprite(column: Int, row: Int): Sprite {
    val seed = (column * GROUND_COLUMN_PRIME) xor (row * GROUND_ROW_PRIME)
    val mixed = (seed xor (seed ushr GROUND_SHIFT)) * GROUND_MIX
    return when ((mixed ushr GROUND_SHIFT).mod(GROUND_SPREAD)) {
        GROUND_TUFTS -> Sprite.GRASS_TUFTS
        GROUND_FLOWERS -> Sprite.GRASS_FLOWERS
        else -> Sprite.GRASS
    }
}

/**
 * Which plant a crop is, from its path: a field of one plant looks like a monoculture, and `String`
 * hashing is specified, so a path is the same plant in every run and every replay of a feed.
 */
internal fun cropSprite(path: String, growth: Growth): Sprite =
    PLANTS[path.hashCode().mod(PLANTS.size)][growth.ordinal]

/** Which of the two people a villager is, by its id, the way `nameFor` picks its name. */
internal fun villagerSprite(id: String): Sprite = VILLAGERS[id.hashCode().mod(VILLAGERS.size)]
