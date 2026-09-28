# The Godot farm

The farm as the owner chose it (`docs/adr/0003-godot-farm.md`): three styles mixed. A nanoblock
island of studded bricks on a blue baseplate, standing on a workshop table under one warm window
beam; HD-2D in the light (glow, volumetric beam, dust, depth of field) and in the farmers, who are
Octopath-style pixel sprites built of tiny bricks and turned to face the camera; and a Story of
Seasons farm — a farmhouse with its shipping bin and porch lanterns, raised beds, a well by an apple
tree, a barn and silo, a paddock of cows and hens, a pier. The island follows the farm's season:
blossom and a meadow of flowers in spring, deep green in summer, gold trees, red apples and
straw-flecked grass in autumn, and in winter snow over the ground, the roofs and the crowns, ice
along the shore, a paler, cooler sun, and snow rather than rain when the provider has a bad
day.

Around the farmers the farm keeps living (`life.gd`): the cows wander the paddock and graze, the
hens scurry and peck, gulls circle over the sea, a rowboat rocks by the pier, the weathervane on the
silo swings, and butterflies are out over the meadows but in winter.

The world is built from code: `main.gd` places bricks and sloped bricks in a grid and draws every
visible one as an instance of one brick mesh. The farmers are read from `art/villager_a.png` and
`art/villager_b.png`, the owner's generated sprites, down to a 30-pixel-tall grid, one brick a
pixel.

It is live: `live.gd` reads one JSON farm per line — the app's `FarmState` as `Snapshot.kt` writes
it — and moves the world to match. Each directory gets a raised bed and each file a crop of the kind
its file type grows (carrot for code, tomato for tests, turnip for docs, pumpkin for build files, and
corn, strawberry and sunflower as the level unlocks them), grown a stage per edit; each session is a farmer who walks to the well for every model call, then on its
way home walks the rounds of the crops that turn planted or grew and bends to each one, with `…`
while it waits, `zzz` while it rests off a rate limit and `♪` while it tends; replay is night with the
lanterns lit; rain, storms and lightning follow the provider; the shipping bin shows the ledger.
Clicking a farmer or a crop prints `{"villager": id}` or `{"crop": path}`, which the app turns into
its detail pane. Clicking the "For sale" sign or a line of the shop board by the paddock prints
`{"buy": item}`; the app decides whether the farm can pay, and the animals bought walk into the paddock. The shop
also builds a farmhouse wing (more homes) and a greenhouse over the back row of beds, which keeps
them growing when winter puts the open beds to sleep under snow. From level 2 a dog barks at any
farmer whose turn failed or is resting off a rate limit; from level 4 a cat naps on the porch while
every farmer is idle.

- From the app: set `PEASHOOT_GODOT` to a Godot 4.7 executable. The app finds this directory from
  the repository root or from `app/`; `PEASHOOT_FARM_PROJECT` names it anywhere else. The app starts
  `godot --path farm -- --from-app`, writes farms to its stdin and an empty heartbeat line every five
  seconds; the window closes itself after fifteen seconds of silence.
- From a recording: `godot --path farm -- --feed farms.jsonl [--every 0.3] [--shot]` plays the lines
  and, with `--shot`, writes `farm/shot.png` once they are done. `docs/farm-godot.png` is the README
  demo's sixteen turns, recorded from the real app and played this way, and
  `docs/farm-seasons.jpg` is its last farm in spring, summer, autumn and winter.

## In an installed app

A release carries the farm exported as one executable, `farm/peashoot-farm.exe` (Windows) or
`farm/peashoot-farm.x86_64` (Linux), beside the proxy's jar in the app's resources; with no
`PEASHOOT_GODOT` set, the app starts that. macOS has none yet: an unsigned app inside the DMG is
untried against Gatekeeper.

`farm/export.sh` makes it, in CI's release legs and on a developer's machine alike, with the Godot
pinned as `godot` in `gradle/libs.versions.toml` (it downloads the editor and the one export template
it needs into `build/godot` once). To try the whole thing on Windows, from Git Bash at the
repository root:

```
bash farm/export.sh Windows build/farm            # build/farm/peashoot-farm.exe
./gradlew :app:runDistributable -Ppeashoot.farm=build/farm
```

`runDistributable` runs the app from the same image the installer is made of, so the farm window
should open by itself once the app has a proxy. `./gradlew :app:packageMsi -Ppeashoot.farm=build/farm`
builds the installer itself. Things only Windows can tell: that the window opens, that farms reach it
(the farm fills in as the feed arrives), that a click on a farmer opens its pane in the app, and that
closing the app closes the farm within fifteen seconds.

Gate: `gdformat --check` and `gdlint` (gdtoolkit 4.x), on their defaults.
