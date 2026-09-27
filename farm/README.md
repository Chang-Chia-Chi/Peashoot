# The Godot farm (look prototype)

What the farm will look like, per `docs/adr/0003-godot-farm.md`: a tilted camera, the owner's
generated sprites standing upright, a low warm sun with shadows, volumetric light, depth of field,
glow, dust and chimney smoke. Not wired to the proxy yet — the world is placed by hand in `main.gd`.

Run it with Godot 4.7 (Forward+): `godot --path farm`. A still: `godot --path farm -- --shot`
writes `farm/shot.png` after 40 frames.

Art in `art/`: `farmhouse`, `barn`, `well`, `apple_tree`, `chicken`, `cow`, and `pumpkin`, `turnip`,
`carrot`, `tomato` at stages `_0`..`_3` are the owner's generated sprites, cut from a magenta backdrop and brought down to their own pixel grid.
`tree_green` and `tree_autumn` are the apple tree with its apples painted out, the second with its
leaves turned amber. `villager_a` and `villager_b` are the owner's two farmers, and `cow` is theirs too.
`ground.png` is painted by `tools/ground.py`, which holds the lane, the road and the eight plots.

Gate: `gdformat --check` and `gdlint` (gdtoolkit 4.x), on their defaults.
