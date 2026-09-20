package dev.peashoot.app.render

import dev.peashoot.app.farm.Growth
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ATLAS_WIDTH = 192
private const val ATLAS_HEIGHT = 352

/**
 * The frame table against the picture it indexes. `ImageIO` rather than Skia on purpose: this has
 * to pass on a headless CI box, where nothing may load a graphics backend.
 */
class SpriteTest {
    @Test
    fun `the atlas is both packed tilemaps, stacked`() {
        val atlas =
            checkNotNull(Sprite::class.java.getResourceAsStream("/farm/atlas.png")) { "no atlas" }
                .use { ImageIO.read(it) }
        assertEquals(ATLAS_WIDTH, atlas.width)
        assertEquals(ATLAS_HEIGHT, atlas.height)
    }

    @Test
    fun `every frame lies inside the atlas`() {
        for (sprite in Sprite.entries) {
            assertTrue(sprite.column >= 0 && sprite.row >= 0, "$sprite is off the top left")
            assertTrue((sprite.column + 1) * TILE <= ATLAS_WIDTH, "$sprite runs off the right")
            assertTrue((sprite.row + 1) * TILE <= ATLAS_HEIGHT, "$sprite runs off the bottom")
        }
    }

    @Test
    fun `a crop keeps its plant and changes its stage as it grows`() {
        val stages = Growth.entries.map { cropSprite("src/main/App.kt", it) }
        assertEquals(stages.size, stages.distinct().size, "every stage is a different frame")
        assertEquals(1, stages.map { it.row }.distinct().size, "a crop stays the same plant")
        assertEquals(stages, Growth.entries.map { cropSprite("src/main/App.kt", it) })
    }

    @Test
    fun `villagers are not all the same person`() {
        val ids = (0 until 20).map { "sess-$it" }
        assertTrue(ids.map(::villagerSprite).distinct().size > 1)
        assertEquals(villagerSprite("sess-alpha"), villagerSprite("sess-alpha"))
    }

    @Test
    fun `the ground picks its grass from where the tile is`() {
        val patch = (0 until 8).flatMap { x -> (0 until 8).map { y -> groundSprite(x, y) } }
        assertTrue(patch.distinct().size > 1, "a ground of one tile has no texture")
    }

    @Test
    fun `the ground picks the same tiles it has always picked`() {
        // Written-down tiles and not a property, for the reason `FarmTest` writes down a villager's
        // name: a hash gives the same answer twice in one run whatever it does, so only literals
        // catch the rule changing between runs. It matters more since #21, because the rain now
        // shares this hash, and a change to it would reshuffle every tuft and flower on every still
        // ever taken of the farm. These were worked out away from the Kotlin, from the same two
        // primes, mix and shift.
        assertEquals(
            listOf(Sprite.GRASS_TUFTS, Sprite.GRASS, Sprite.GRASS, Sprite.GRASS),
            (0 until 4).map { groundSprite(it, 0) },
        )
        assertEquals(
            listOf(Sprite.GRASS_FLOWERS, Sprite.GRASS, Sprite.GRASS_FLOWERS, Sprite.GRASS),
            (0 until 4).map { groundSprite(it, 1) },
        )
        assertEquals(
            14,
            (0 until 8).sumOf { column ->
                (0 until 8).count { groundSprite(column, it) != Sprite.GRASS }
            },
            "the spread of tufts and flowers over a patch changed",
        )
    }
}
