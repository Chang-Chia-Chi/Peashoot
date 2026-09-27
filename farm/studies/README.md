# Style studies

Two looks for the farm, each a self-contained Godot 4.7 project built entirely from code — no art
files — so the owner can compare them against the sprite scene in `farm/`:

- `bricks/`: a nanoblock-style island of studded bricks. Still: `docs/farm-study-bricks.png`.
- `toy/`: a smooth toy diorama in a display case — vegetable houses, bumpy trees, big-headed
  farmers, a painted sky. Still: `docs/farm-study-toy.png`.

`godot --path farm/studies/toy -- --shot` writes `shot.png` beside it. Studies, not the farm: not
gated by gdlint and not wired to anything.
