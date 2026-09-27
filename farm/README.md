# The Godot farm

The farm as the owner chose it (`docs/adr/0003-godot-farm.md`): a nanoblock-style island of studded
bricks, lit the HD-2D way — a low warm sun with light shafts, glow, soft shadows, drifting dust,
chimney smoke and a tilt-shift depth of field — with a Story of Seasons farm on it: a farmhouse with
its shipping bin at the door, fenced fields, a well by an apple tree, a barn and silo, a paddock of
animals, and a forest round the shore.

Everything is built from code: `main.gd` places bricks in a grid and draws every brick that can be
seen as one instance of one studded-brick mesh. No art files.

Run it with Godot 4.7 (Forward+): `godot --path farm`. A still: `godot --path farm -- --shot`
writes `farm/shot.png` after 40 frames; `docs/farm-godot.png` is one.

Not wired to the proxy yet: the fields, crops and farmers are placed by hand.

Gate: `gdformat --check` and `gdlint` (gdtoolkit 4.x), on their defaults.
