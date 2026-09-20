package dev.peashoot.app.render

import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import org.jetbrains.skia.Image

private const val ATLAS = "/farm/atlas.png"

/** Pixel art is drawn at 3x, which is 48 px a tile — the size the spike measured. */
internal const val SCALE = 3
internal const val TILE_PX = TILE * SCALE

/**
 * The one image the farm is drawn from, decoded once for the life of the process.
 *
 * It matters that this is a decoded, immutable image and not a bitmap built here: Skia will not
 * cache a mutable bitmap's texture and re-uploads it on every draw call, which measured 17x slower
 * at 200 sprites (`docs/research/canvas-frame-rate.md` §5). Nothing in the renderer may compose a
 * texture at runtime for the same reason — no pre-rendered ground, no stitched atlas.
 *
 * Held here rather than `remember`ed in the canvas, because #20 put the farm behind a tab: a
 * remembered atlas would be read off the disk and decoded again on every switch back, and an
 * immutable image is exactly the thing there is no reason to hold twice.
 */
internal val farmAtlas: ImageBitmap by lazy {
    val bytes =
        checkNotNull(Sprite::class.java.getResourceAsStream(ATLAS)) { "no $ATLAS on the classpath" }
            .use { it.readBytes() }
    Image.makeFromEncoded(bytes).toComposeImageBitmap()
}

/**
 * One sprite at a tile position. The destination is rounded to whole pixels, because pixel art
 * landing on a half pixel smears even with [FilterQuality.None].
 *
 * [scale] draws it bigger or smaller about the middle of its tile, which is how a crop pops when it
 * grows: still one `drawImage` from the same immutable atlas, because scaling a destination rect is
 * the GPU's business and composing a bigger bitmap would be ours.
 */
internal fun DrawScope.drawSprite(
    atlas: ImageBitmap,
    sprite: Sprite,
    spot: Spot,
    scale: Float = 1f,
) {
    val side = (TILE_PX * scale).roundToInt()
    val inset = (TILE_PX - side) / 2
    drawImage(
        image = atlas,
        srcOffset = IntOffset(sprite.column * TILE, sprite.row * TILE),
        srcSize = IntSize(TILE, TILE),
        dstOffset =
            IntOffset(
                (spot.x * TILE_PX).roundToInt() + inset,
                (spot.y * TILE_PX).roundToInt() + inset,
            ),
        dstSize = IntSize(side, side),
        filterQuality = FilterQuality.None,
    )
}
