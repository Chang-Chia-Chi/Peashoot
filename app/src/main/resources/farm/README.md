# The farm atlas

`atlas.png` is 192x352 px: Kenney's two packed tilemaps stacked, unchanged pixel for pixel.

| Source | Where it lands |
|---|---|
| Tiny Town 1.1, `Tilemap/tilemap_packed.png` (192x176) | y = 0, so its rows are atlas rows 0-10 |
| Tiny Farm 1.0, `Tilemap/tilemap_packed.png` (192x176) | y = 176, so its rows are atlas rows 11-21 |

Both are 12 columns x 11 rows of 16x16 tiles with no spacing, so the combined sheet is 12 columns x
22 rows and a frame's source rect is `(column * 16, row * 16, 16, 16)`. `Sprite.kt` is that table.

To rebuild it: download both packs from https://kenney.nl/assets/tiny-town and
https://kenney.nl/assets/tiny-farm, then paste the town sheet at (0, 0) and the farm sheet at
(0, 176) into one 192x352 RGBA image and save it here. Any image editor does; the one used was a
throwaway `javax.imageio` program (`ImageIO.read` both, `Graphics2D.drawImage` at those offsets,
`ImageIO.write`), which is not committed because it runs once.

Credit and licence: `NOTICE` at the repository root. Both packs are CC0.

One image, built offline, loaded once: a texture composed at runtime is a mutable Skia bitmap, and
Skia re-uploads one of those on every draw call — measured at 17x slower with 200 sprites in
`docs/research/canvas-frame-rate.md` §5.
