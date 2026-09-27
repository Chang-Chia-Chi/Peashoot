# The farm moves to Godot; the farm's logic stays in Kotlin

Status: Accepted — decided by the owner on 2026-09-26; the style revised by the owner on 2026-09-27 (below)

The Compose farm renderer never adopted the village the design canvas proposed (the "Peashoot Village" canvas, 2026-09-20), and what it draws — a flat top-down field of Kenney Tiny Town and Tiny Farm tiles at 3x — is neither charming nor legible: `docs/farm.gif` shows a dark field with no crops, lanterns cut in half by the window edge, and nothing that says what a villager, a field or the well stands for. The owner's references are HD-2D pixel art in the manner of Octopath Traveler and Story of Seasons: a tilted, looking-down camera, detailed sprites, warm grading, sunbeams, depth of field.

A composition study (Kenney's atlas re-laid out after the references, with the lighting approximated) showed that lighting alone does not close the gap. Two things do: **art** with several times the detail of a 16 px Kenney tile, and a **camera** that tilts the ground away, stands sprites upright and blurs with depth.

Decided:

- **Art comes from the owner's image tool**, generated one object per image on a flat `#FF00FF` background, in one prompt style ("HD-2D pixel art game sprite … 3/4 top-down view from about 45 degrees, sunlight from the top right …"), then cut out and fitted to a pixel grid here. A test batch of five comes first — farmhouse, stone well, apple tree, pumpkin in four growth stages, cow — so the pipeline is proven before the full set is asked for.
- **The farm is drawn by Godot 4** with a perspective camera, upright sprites, depth of field, glow and real lights. It runs as its own window, a child process of the app.
- **The reducer stays in Kotlin.** `dev.peashoot.app.farm` and its tests are the one place that decides what a line means. The app streams each new farm to the Godot process as one JSON line on its stdin, and reads clicks (a villager id, a crop path) back as JSON lines on its stdout, which open the same detail panes as today. No port, no token, no second reducer.
- **Meaning and geometry split at slots.** Kotlin says *which* slot each thing has, in the order it was first heard: a field's plot, a crop's cell in it, a villager's home, a place in the well queue, a helper's parent. Godot says *where* each slot is, from markers placed in its scene. So the rule that nothing already placed ever moves stays a property of stable indices, and the look can be re-laid out without touching Kotlin.
- **The world, as the study laid it out:** a farmhouse where idle sessions wait, the well under an apple tree on the lane (model calls), villagers queueing on the lane, eight fenced plots (directories; crops are files; the rest counted as "+N fields"), the barn and its crate (the cost ledger), animals and trees for life, and a one-line key on screen.
- **GDScript is gated by `gdformat` and `gdlint`** (gdtoolkit, from PyPI) on their defaults, locally and in CI, the way ktfmt and detekt gate Kotlin.
- **The Compose farm is retired** once the Godot farm is live: one farm to maintain.

## Revised: nanoblock bricks, built from code

After seeing the sprite scene, and photos of a nanoblock island and a museum display case, the owner
chose **nanoblock bricks enhanced with the original HD-2D look and a Story of Seasons farm**. So the
first two decisions above change: the farm is no longer drawn from generated sprites, and it is
built entirely from code — every building, crop, animal and farmer is a set of studded bricks in a
grid, drawn as instances of one brick mesh. The HD-2D part is the light and the camera (a low warm
sun, volumetric light, glow, dust, depth of field); the Story of Seasons part is what stands on the
island. People and animals are therefore real 3D, and a new crop or animal is code, not a new image
to ask for. Everything else here — Godot as a child process, the reducer in Kotlin, slots, the
GDScript gate, retiring the Compose farm — stands. The sprite scene and a toy-diorama study are kept
in `farm/studies/` for comparison.

## Considered options

- **Kenney plus a lighting pass in Compose's Skia canvas.** Buildable today and much warmer than what ships, but the owner judged the study still far from the references. The art is the limit, not the engine.
- **A tilted camera in Compose.** Skia can draw through a perspective matrix, so Godot is not strictly required; it was chosen because it gives the camera, depth of field, glow and lights without hand-rolled projection and hit-testing.
- **A second reducer in GDScript reading the proxy's feed directly.** Rejected: two readings of the same line would drift, and the Kotlin one is the tested one.

## Consequences

- Building and seeing this needs network access this project's cloud environment does not have today: `dl.google.com` (Compose's AndroidX dependencies) and GitHub release downloads (Godot itself and its export templates).
- Releases carry a Godot export per platform beside the app, and CI needs Godot's export templates.
- The farm's placement invariants now in `LayoutTest` (nothing on a field, nobody in the well, nothing already placed moves) split: stable slots stay tested in Kotlin, and the marker geometry is checked by a headless Godot script.
- Releases carry the farm, exported by `farm/export.sh` into one executable per OS, on the Windows
  and Linux legs; the app starts it when no `PEASHOOT_GODOT` is set. The Compose farm stays until
  the Windows installer has been tried by hand and macOS carries one too — retiring it first would
  leave an installed app with no farm at all. Then the farm tab and `dev.peashoot.app.render` go.
