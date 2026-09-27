# The Godot farm

The farm as the owner chose it (`docs/adr/0003-godot-farm.md`): three styles mixed. A nanoblock
island of studded bricks on a blue baseplate, standing on a workshop table under one warm window
beam; HD-2D in the light (glow, volumetric beam, dust, depth of field) and in the farmers, who are
Octopath-style pixel sprites built of tiny bricks and turned to face the camera; and a Story of
Seasons farm — a farmhouse with its shipping bin and porch lanterns, raised beds, a well by an apple
tree, a barn and silo, a paddock of cows and hens, a pier.

The world is built from code: `main.gd` places bricks and sloped bricks in a grid and draws every
visible one as an instance of one brick mesh. The farmers are read from `art/villager_a.png` and
`art/villager_b.png`, the owner's generated sprites, down to a 30-pixel-tall grid, one brick a
pixel.

Run it with Godot 4.7 (Forward+): `godot --path farm`. A still: `godot --path farm -- --shot`
writes `farm/shot.png` after 40 frames; `docs/farm-godot.png` is one.

Not wired to the proxy yet: the fields, crops and farmers are placed by hand.

Gate: `gdformat --check` and `gdlint` (gdtoolkit 4.x), on their defaults.
