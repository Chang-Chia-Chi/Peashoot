# Growing farm: agreed spec

Agreed with the owner on 2026-09-27. Numbers marked *tunable* are my defaults, not decisions.

## Today
The farm is fixed: a 96×64 island (`farm/main.gd:5-6`), 8 beds (`farm/main.gd:74`) of 9 crops each (`farm/live.gd:57`),
4 crop kinds rotating by bed (`farm/live.gd:55`), 3 cows and 3 hens (`farm/life.gd:43-47`). A 9th directory gets only a
"+N fields not shown" sign (`farm/live.gd:268`). The farm starts empty every time the app opens (`app/.../Main.kt:124`).

## Decisions
| # | Question | Decision |
|---|----------|----------|
| 1 | Does growth survive a restart? | **Remember.** Coins, lifetime coins, owned plots and bought animals are saved to a small file beside the app's settings. |
| 2 | What earns coins? | **Turns and harvests.** 1 coin per completed turn, a bonus when a crop first ripens (once per crop). Tokens and cost earn nothing. |
| 3 | How is land bought? | **You click** a "For sale" sign on the next plot. |
| 4 | Where does new land come from? | **The island grows**: each plot rises out of the sea at the edge and the camera pulls back. No hard ceiling. |
| 5 | What decides a crop's kind? | **File type**, not bed. |
| 6 | How do animals arrive? | **Bought** with coins at a shop sign, each unlocked at a level. |
| 7 | What do buildings do? | **Something useful**: each upgrade opens room the farm lacks. Plus **pets**. |
| 8 | What do pets do? | **Watch the agents.** |

## Rules that follow from them
- **Level comes from lifetime coins, not the balance**, so buying never lowers it.
- **Kotlin counts, Godot draws** (ADR 0003). Coins, level, plots and animals live in the farm reducer and go out in the snapshot.
  A click in Godot sends one line back to the app ("buy plot", "buy hen"). The app checks the price and applies it as a pure step,
  like dismissing the end-of-day card today. Godot never decides what you can afford.
- **Existing placement never moves.** A new plot takes the next slot, the same way fields and villagers do today.

## Buildings
- **Farmhouse extension**: a bigger yard with more home spots, so more sessions get their own place.
  Today there are about 13, and farmers after that share (`farm/live.gd:19-21`).
- **Barn and coop upgrade**: raises how many animals you can own.
- **Greenhouse**: its beds stay green and keep growing through winter.

## Pets (adopted once at a level, free; *tunable*)
- **Dog** (level 2): runs to a farmer whose turn failed or hit a rate limit and barks at it.
- **Cat** (level 4): naps on the porch while every agent is idle and wakes when work starts.

## Numbers (*tunable*)
- Coins: 1 per turn, 3 for each crop's first ripening. A replayed turn (proxy in replay mode) earns nothing.
- Level n needs 10 × n(n+1)/2 lifetime coins: level 2 at 10, level 3 at 30, level 4 at 60, level 5 at 100.
- Plots: 4 beds each. The starting island keeps its 8 beds. The first plot costs 20, and each later one costs 15 more.
- Crops by file type: code → carrot, tests → tomato, docs → turnip, config and build → pumpkin.
  Unlocks: corn at level 3 (scripts), strawberry at level 5 (styles and markup), sunflower at level 7 (everything else).
  A locked kind grows as a carrot until it unlocks, and a planted crop changes kind the moment it does.
- Animals: hen 10 (level 2), cow 40 (level 3), sheep 30 (level 4), pig 30 (level 5). Today's 3 cows and 3 hens stay as the starting herd.
- Coop and barn: each holds 6 at first (the coop hens, the barn cows, sheep and pigs together), and each upgrade adds 4.
  Coop upgrades cost 30, 60, 90…; barn upgrades 50, 100, 150…. The henhouse widens and the barn's lean-to lengthens with them.
  Each barn upgrade also stretches the paddock 10 bricks east over new pasture that rises out of the sea, up to three stretches, so a bigger herd has room to roam; the camera pulls back to take it in.
- Buildings, bought at the same shop board: a farmhouse wing 40 then 80 (level 2, two at most), each adding six homes; the greenhouse 100 (level 4, once).
- Winter pauses growth in open beds: an edit there leaves the crop where it was, and the bed sleeps under snow until spring.
  The greenhouse stands over the back row of four beds (the first four directories heard), which keep growing all year.
- A failed turn is a status outside 2xx other than 429 (which is resting); the dog barks at either until the next turn goes through.

## Phases (one PR each)
1. **Coins and level.** The reducer counts coins and level, a save file keeps them, and the snapshot carries them.
   Godot shows a level badge with a progress bar. Tests cover the reducer and the save round trip.
2. **Buying land.** The Godot-to-app click channel, the "For sale" sign, the island growing at its edge, the camera pulling back,
   and the 8-bed limit gone.
3. **Crops by file type.** The kind comes from the path, plus the new crop art and the unlocks.
4. **Animal shop.** A shop sign, the new animals' art and behaviour, barn and coop upgrades, and buying over the phase 2 channel.
5. **Buildings and pets.** Farmhouse extension, greenhouse, pets.

## Known limits
- Turns made while the app is closed count: the save keeps the id of the last line counted, and the next window resumes the feed from it, so the proxy's store hands over every line in between. Those lines also rebuild the farm (villagers, crops, end-of-day cards) from where the last window stopped.
- One farm per machine. Separate farms per project would need a key to save under, and that's not planned.
