# Compose Canvas frame rate with 200 sprites (spike, issue #3)

Measured 2026-09-19 on the author's laptop. Every number in this note comes from a results file the spike wrote during that session; the raw files are quoted in full in the appendix, along with the complete spike source, so the measurement can be repeated without the scratch directory it ran from. Nothing here is estimated. Where a figure is arithmetic over measured phases rather than a measurement, it says so.

## 1. The question, and why it matters

`docs/design.md` §10 states the rendering target in one line: "Compose Canvas, one sprite atlas from Kenney CC0 sets, target 60 fps with 200 entities. Degrade under 30 fps: villagers teleport instead of walking." §13 lists the spike that has to check it before any of that is built: "(2) Compose Canvas frame rate with 200 sprites on the author's laptop, GPU and `SKIKO_RENDER_API=SOFTWARE`".

The next ticket, #19, builds the real renderer, and one of its acceptance criteria is that "Frame rate at 200 entities on the development machine matches the spike's numbers". So this note is the number #19 is held to, and the reason a mismatch there is a regression rather than a surprise.

Three things had to come out of it: does 200 entities hold 60 fps on both backends, how far above 200 can the machine go before it falls under 30, and is the "villagers teleport instead of walking" degrade path something this machine will ever reach.

## 2. The machine, and its state while measuring

Collected with PowerShell immediately before the runs, and not changed for them.

| | |
|---|---|
| Model | HP OMEN by HP Gaming Laptop 16-xf0xxx |
| CPU | AMD Ryzen 7 7840HS w/ Radeon 780M Graphics, 8 cores / 16 threads, 3801 MHz max |
| GPU 0 | AMD Radeon(TM) Graphics (780M iGPU), driver 32.0.11020.1002, 1920x1080 @ 165 Hz |
| GPU 1 | NVIDIA GeForce RTX 4060 Laptop GPU, driver 32.0.15.9282, 1920x1080 @ 60 Hz |
| RAM | 16,415,322,112 bytes (~15.3 GiB) |
| OS | Microsoft Windows 11 Home, 10.0.26200 build 26200, 64-bit |
| Power | `Win32_Battery.BatteryStatus = 2` (on AC), charge 100% |
| Power plan | Balanced, `381b4222-f694-41f0-9685-ff5bb260df2e` |

Two points of honesty about that state. The machine was on AC, so these are not battery-throttled numbers. But the active plan is **Balanced, not High performance**, so they are still a floor rather than the machine's best; a high-performance plan would plausibly do better and certainly not worse.

Which of the two adapters Skiko actually rendered on is **not recorded**. The spike reads the render *API* from the window, not the adapter, and Java reported the default screen device at 165 Hz, which matches the 780M's mode rather than the RTX 4060's. Taking the D3D device as the iGPU is therefore plausible but **unverified**; #19 should not lean on it.

Nothing else heavy ran during the measurements — no repo build in parallel — and the windows were left alone.

## 3. The stack

Read at runtime by the spike and written into every results file, except the Compose version, which the build script takes from the repo's own `gradle/libs.versions.toml` and passes in as a system property, so it is by construction the version the app ships.

| | |
|---|---|
| Compose Multiplatform | 1.12.0 (from the product version catalog) |
| Skiko | 0.150.1 (`org.jetbrains.skiko.Version.skiko`, read reflectively) |
| Kotlin | 2.4.20 (`KotlinVersion.CURRENT`) |
| JDK | 22.0.1, Oracle Corporation — the JVM Gradle runs on here; the build targets JVM 21 |
| Render API, GPU mode | `DIRECT3D` (`ComposeWindow.renderApi`) |
| Render API, software mode | `SOFTWARE_FAST`, with `SKIKO_RENDER_API=SOFTWARE` confirmed present in the forked JVM's environment |
| Display refresh | 165 Hz (`GraphicsEnvironment…defaultScreenDevice.displayMode.refreshRate`) |
| Window / canvas | requested 1280x800 dp; density 1.25 (Windows 125% scaling); **measured canvas 1583x954 px** |

The software runs are not void: each software results file records both `"renderApi":"SOFTWARE_FAST"` from the window and `"skikoRenderApiEnv":"SOFTWARE"` from `System.getenv`, so the environment variable reached the JVM Gradle forked and the window really used a software backend.

The canvas came out at 1583x954 px rather than 1280x800, because `DpSize(1280.dp, 800.dp)` is a window size in dp and the display scale is 1.25. That is a larger surface than the ticket assumed, which makes the numbers more conservative, not less — but #19 must compare like for like, at the same canvas pixel size and the same 1.25 density.

## 4. Method

**What is drawn.** One `Window` (fixed size, not resizable), one full-size `Canvas`, one atlas.

The atlas is **generated in code at startup, not loaded**: 192x176 px of 16x16 tiles, 12 columns by 11 rows, each tile a distinct colour with a simple shape so frame cycling is visible on screen. Those are the dimensions of Kenney's Tiny Town `tilemap_packed.png`, which #19 will load for real. No asset was downloaded: licensing, NOTICE and asset loading are #19's job, and what is being measured is draw calls from one image, not pixel content.

Each frame draws, in order:

1. A tiled ground layer covering the window with 48x48 tiles from the atlas — the static world the real renderer will have, so the entity number is not flattered by an empty background. At the measured canvas size this is 33 x 20 = **660 draw calls**, before any entity.
2. N entities, each `drawImage(atlas, srcOffset, srcSize, dstOffset, dstSize, filterQuality = FilterQuality.None)` at 3x scale (16x16 source, 48x48 destination), which is how pixel art will really be drawn.

So the 200-entity frame is 860 draw calls from one image.

Every entity moves each frame (bouncing off the window edges) and cycles through 4 consecutive atlas tiles at 6 Hz. The clock is `withFrameNanos` inside a `LaunchedEffect` — the per-frame clock `docs/design.md` §10 names for the `Animator` — and the same callback both steps the world and records the frame delta, so the recorded time is the whole frame: application work, composition, draw, and present.

**Phases.** For each entity count: warm up 3 s, then measure 10 s, recording every frame's delta. A phase also requires at least 5 measured frames, so a phase that is slow enough to fit fewer than 5 frames in 10 s runs longer rather than reporting a percentile over two samples.

**Statistics.** Average fps is frames divided by the summed measured seconds. Median, p99 and worst are order statistics over the sorted frame deltas of that phase, in milliseconds. "Above 30 fps" means the phase's average fps is strictly greater than 30.

**Finding the ceiling.** First phase is exactly 200. Then the count ramps 500, 1000, 2000, 5000, 10000, 20000, 50000, 100000, 200000, stopping at the first count whose average is below 30 fps, then bisects between the last passing and first failing count until the bracket is within ~10% of the passing end. The ramp has a hard ceiling of 200000 and the bisect a cap of 8 steps, so it always terminates.

**vsync.** The main runs are with vsync as the app will ship it: on. That makes the 200-entity figure very likely equal to the display refresh, which is the honest answer to "does it hold 60" but hides the headroom — so the median and p99 frame *times* are recorded too, and one extra 200-entity run per mode was made with `skiko.vsync.enabled=false`.

**One thing worth knowing about the harness.** Gradle-side configuration does not survive the Compose plugin's `run` task: it sets the task's `args` and `jvmArgs` itself, wiping anything the build script adds. The first attempt at the vsync-off runs silently ran *with* vsync for that reason; the results file showed `"vsyncProp":"null"` and the run was discarded rather than reported. Everything is passed as a system property instead, and every results file records the values it actually saw (`skikoRenderApiEnv`, `vsyncProp`) so a void run is visible in its own output.

## 5. The finding that had to be fixed before any number meant anything

The very first 200-entity run in GPU mode measured **9.65 fps, median 103.987 ms**, on a DIRECT3D window drawing 860 sprites. That is not a plausible Compose number, and it was not one. (That launch wrote `results-run.json`, which is the one results file not reproduced in the appendix: it is the uncontrolled first sighting, and the paired probe below is the measurement of the same thing.)

The cause is the atlas. An `ImageBitmap` that Compose still holds a `Canvas` over stays a *mutable* Skia bitmap, and Skia will not cache a mutable bitmap's GPU texture — it re-uploads the image on every single draw call. Snapshotting the finished bitmap to an immutable `Image` (`setImmutable()`, then `Image.makeFromBitmap`, then `toComposeImageBitmap()`) is all it takes.

Measured both ways, same machine, same session, GPU mode, 0 and 200 entities:

| Atlas | Entities | Avg fps | Median ms | p99 ms | Worst ms |
|---|---|---|---|---|---|
| mutable | 0 (ground only, 660 calls) | 26.12 | 38.303 | 57.630 | 59.445 |
| mutable | 200 (860 calls) | 9.54 | 103.217 | 137.496 | 137.496 |
| immutable | 0 (ground only, 660 calls) | 164.77 | 6.069 | 8.841 | 13.252 |
| immutable | 200 (860 calls) | 164.11 | 6.060 | 8.528 | 16.704 |

A factor of 17 at 200 entities, and the ground layer alone was already under 30 fps. **All measurements in this note use the immutable atlas**, which is what #19 gets for free by loading a PNG — `Image.makeFromEncoded` and the Compose resource loaders both produce immutable images. The reason it is written down anyway is that anyone who later generates a texture at runtime (a recoloured sprite, a composed label, a minimap) can reintroduce exactly this, and it will look like "Compose is slow" rather than like a missing `setImmutable()`.

## 6. Results

### GPU mode (`DIRECT3D`), vsync on

Full phase list from `results-gpu.json`. Ramp phases in order, then the bisect.

| Entities | Avg fps | Median ms | p99 ms | Worst ms | >30 fps |
|---:|---:|---:|---:|---:|:--:|
| **200** | **164.50** | **6.054** | **7.669** | **15.479** | yes |
| 500 | 165.01 | 6.055 | 6.931 | 7.656 | yes |
| 1000 | 164.56 | 6.056 | 7.886 | 15.746 | yes |
| 2000 | 160.39 | 6.065 | 10.590 | 16.206 | yes |
| 5000 | 113.45 | 8.388 | 12.916 | 23.510 | yes |
| 10000 | 66.57 | 14.671 | 21.805 | 31.819 | yes |
| 20000 | 33.65 | 29.145 | 40.115 | 44.378 | yes |
| 50000 | 14.10 | 69.808 | 90.962 | 96.758 | no |
| 35000 | 18.54 | 50.959 | 75.255 | 77.331 | no |
| 27500 | 25.06 | 39.300 | 53.572 | 56.044 | no |
| 23750 | 29.26 | 33.707 | 44.790 | 46.241 | no |
| 21875 | 30.23 | 31.884 | 47.281 | 49.758 | yes |

### Software mode (`SOFTWARE_FAST`), vsync on

Full phase list from `results-software.json`.

| Entities | Avg fps | Median ms | p99 ms | Worst ms | >30 fps |
|---:|---:|---:|---:|---:|:--:|
| **200** | **64.41** | **15.536** | **16.555** | **16.699** | yes |
| 500 | 63.75 | 15.575 | 18.554 | 20.820 | yes |
| 1000 | 54.63 | 18.083 | 23.694 | 26.800 | yes |
| 2000 | 32.38 | 29.614 | 44.540 | 46.061 | yes |
| 5000 | 16.47 | 60.460 | 73.149 | 76.319 | no |
| 3500 | 21.84 | 45.362 | 58.307 | 60.588 | no |
| 2750 | 28.27 | 34.005 | 49.249 | 55.492 | no |
| 2375 | 31.32 | 31.443 | 43.253 | 46.242 | yes |
| 2562 | 29.88 | 32.938 | 43.098 | 48.946 | no |

### 200 entities: repeats and vsync off

Each row is a separate fresh launch of the JVM and the window.

| Run | Mode | vsync | Avg fps | Median ms | p99 ms | Worst ms |
|---|---|---|---:|---:|---:|---:|
| `results-gpu.json` | DIRECT3D | on | 164.50 | 6.054 | 7.669 | 15.479 |
| `results-gpu-repeat.json` | DIRECT3D | on | 163.22 | 6.064 | 10.687 | 14.718 |
| `results-gpu-novsync.json` | DIRECT3D | **off** | 163.71 | 6.066 | 10.333 | 13.459 |
| `results-software.json` | SOFTWARE_FAST | on | 64.41 | 15.536 | 16.555 | 16.699 |
| `results-software-repeat.json` | SOFTWARE_FAST | on | 64.53 | 15.512 | 16.483 | 16.912 |
| `results-software-novsync.json` | SOFTWARE_FAST | **off** | 118.19 | 8.204 | 12.260 | 13.887 |

The repeats reproduce to within 0.8% (GPU) and 0.2% (software). The number is stable.

**On vsync.** `skiko.vsync.enabled=false` demonstrably reached both JVMs — every vsync-off results file records `"vsyncProp":"false"` — and it did two different things. On the software backend it is plainly effective: 64.4 → 118.2 fps, 15.5 → 8.2 ms. On the DIRECT3D backend it changed nothing measurable (164.50 / 163.22 with vsync on, 163.71 with it off; all three within run-to-run noise, all pinned to the 165 Hz refresh). So on Skiko 0.150.1 the property does not uncap the Direct3D present path on this machine, and there is **no honest uncapped GPU figure to report**. The GPU headroom argument has to come from the ramp instead, and it does: the frame stays at the refresh cap all the way to 2000 entities.

Note also that the software backend with vsync on sits at ~64 fps / 15.5 ms, not at the 165 Hz the display runs at. Its present path appears to cap near 60–64 Hz regardless of the display mode. That is what the app will actually see in the fallback, so it is the number that counts.

### Derived, not measured

Two linear fits over measured phases, given because they are the only way to state the 200-entity headroom in GPU mode, where vsync hides it:

- GPU: the 10000-entity (14.671 ms) and 20000-entity (29.145 ms) medians give **~1.45 µs per entity** and a ~0.20 ms fixed cost. At 200 entities that is ~0.49 ms of frame work against a 6.06 ms budget — roughly 8% of one frame at 165 Hz, about 3% of a 60 fps frame.
- Software: the 1000-entity (18.083 ms) and 2000-entity (29.614 ms) medians give **~11.5 µs per entity** and a ~6.55 ms fixed cost, most of which is the 660-call ground layer plus the present. At 200 entities that predicts 8.86 ms, against the 8.204 ms actually measured with vsync off — a 7% overshoot, which is the cross-check that the fit is sane. Solving the same fit for 33.33 ms gives 2323 entities, against the 2375 measured by the bisect.

Software is therefore about **8x the per-entity cost of the GPU path** on this machine.

## 7. Largest entity count above 30 fps

| Mode | Largest count measured above 30 fps | Bisect bracket |
|---|---:|---|
| GPU (`DIRECT3D`) | **21,875** (30.23 fps) | 21,875 passes / 23,750 fails at 29.26 fps; bracket width 1,875, within 10% of the passing end |
| Software (`SOFTWARE_FAST`) | **2,375** (31.32 fps) | 2,375 passes / 2,562 fails at 29.88 fps; bracket width 187, within 10% of the passing end |

Both brackets are honest but tight at the passing end: 21,875 passed by 0.23 fps and 2,375 by 1.32 fps, which is inside plausible run-to-run variation. Read them as "about 22,000" and "about 2,400", not as thresholds to the entity.

The GPU ramp and bisect ran back to back for about 2.6 minutes of continuous drawing. There is no sign of thermal throttling across it: the 21,875-entity phase, measured last, is 9% more entities than the 20,000-entity phase measured seven phases earlier and 9% slower (31.884 ms vs 29.145 ms median), which is exactly linear. If the machine had been sagging, the late phase would have been worse than linear.

## 8. Go / no-go on the 200-entity target

**GPU mode: go.** 164.5 fps at 200 entities, reproducing at 163.2 on a second launch — the display refresh, which is the ceiling, and the ramp shows the frame does not leave that ceiling until somewhere past 2,000 entities. The per-entity fit puts 200 entities at about half a millisecond of frame work. 200 entities is not close to a limit here; it is 1% of what the backend does at 30 fps.

**Software mode: go.** 64.4 fps at 200 entities, reproducing at 64.5, with the vsync-off run showing 8.2 ms of real work behind that — a 60 fps frame has 16.7 ms, so the fallback is at roughly half its budget at the target. The design's "target 60 fps with 200 entities" is met on the fallback backend as well, with the caveat that the software present path caps near 64 fps, so 60 fps is what it delivers and 165 fps is not available there.

**Overall: go.** The 200-entity target in `docs/design.md` §10 stands on both backends on this machine, by a margin of about 100x in GPU mode and about 12x in software mode measured in entities. No design change is needed.

The one condition attached to that go is section 5: it holds **only** if the atlas is an immutable image. With a mutable one the same 200 entities ran at 9.5 fps and the empty ground layer alone was under 30 fps.

## 9. What this does not measure

#19 inherits all of this.

- **Real Kenney art.** The atlas was generated. The real `tilemap_packed.png` has the same dimensions and the same tile grid, so the draw-call count and the texture size are the same, but it is a different image and it arrives through a real decode path. Licensing and NOTICE are untouched here.
- **Text.** No labels, no fonts, no text layout. Farm labels are a design feature (`docs/design.md` §14: off by default) and text rendering on Skia is a different cost class from `drawImage`. Nothing in this note bounds it.
- **The `Animator`'s interpolation.** The spike moves entities with a trivial per-frame integration on flat `FloatArray`s. The real animator interpolates between reducer states, which is more work per entity and may allocate. The per-entity slope measured here is the *drawing* cost, not the drawing plus animating cost.
- **The reducer and the feed.** No `ControlClient`, no SSE, no `FarmReducer` running on the same process. These frames are drawn by a JVM doing nothing else.
- **A resized or larger farm window.** Fixed, non-resizable, 1583x954 px. The ground layer scales with area, and in software mode the ground plus present was ~6.5 ms of the ~8.2 ms frame — so a full-screen farm window is the case most likely to move the software number, and it is untested.
- **Any HiDPI scale other than 1.25.** Density was 1.25 throughout. A 2.0 display doubles the canvas pixels for the same dp layout.
- **Any other machine.** One laptop, one session, Windows 11, Balanced power plan, on AC. Nothing here says anything about CI runners, about macOS (`METAL`) or Linux (`OPENGL`), or about a machine without a discrete GPU.
- **Which adapter D3D chose**, as section 2 says.

## 10. What it means for the "under 30 fps, villagers teleport" degrade

`docs/design.md` §10 specifies a degrade: below 30 fps the renderer stops walking villagers and teleports them instead. The question this spike can answer is whether that path is ever reached on this machine at realistic counts.

It is not. At the design's own target of 200 entities the fallback backend runs at 64 fps, and it takes about 2,400 entities — twelve times the target — before software mode drops under 30. In GPU mode it takes about 22,000. A farm would have to be two orders of magnitude busier than designed, on the slower backend, before the degrade fires.

Two consequences for #19. First, the degrade is worth building anyway — it is cheap, it is the documented behaviour, and it protects machines weaker than this one, which this spike says nothing about. Second, and more practically: **it will never be exercised in normal use here, so it needs a way to be triggered deliberately** — a forced flag, or a test that drives the renderer with a synthetic entity count — or it will ship untested. Measuring the frame rate and hoping to see it dip is not a test on this hardware.

One further caution for #19. The threshold is a frame-rate reading, and in GPU mode the frame rate is pinned to the display refresh whatever the load, up to about 2,000 entities. A degrade that watches average fps therefore sees 165, 165, 165 and then falls off a cliff; it has no early warning. Watching the median frame *time* against the frame budget gives a signal that moves before the cliff. That is a suggestion, not a measurement.

## 11. Where the spike lived, and that it is not merged

The spike is a standalone Gradle project outside the repository, built and run with the repo's own wrapper and taking every version from the repo's catalog so that the stack measured is the stack the app ships. It lived in a session's temporary scratch directory, `canvas-fps/`, which is not a place to go back to: the appendix is the copy that lasts.

Run as, from that directory:

```
/c/dev/Peashoot/gradlew -p . run --no-configuration-cache -Pspike.label=gpu
/c/dev/Peashoot/gradlew -p . run --no-configuration-cache -Pspike.label=software -Pspike.renderapi=SOFTWARE
/c/dev/Peashoot/gradlew -p . run --no-configuration-cache -Pspike.label=gpu-repeat        -Pspike.phases=200
/c/dev/Peashoot/gradlew -p . run --no-configuration-cache -Pspike.label=gpu-novsync       -Pspike.phases=200 -Pspike.vsync=false
/c/dev/Peashoot/gradlew -p . run --no-configuration-cache -Pspike.label=software-repeat   -Pspike.phases=200 -Pspike.renderapi=SOFTWARE
/c/dev/Peashoot/gradlew -p . run --no-configuration-cache -Pspike.label=software-novsync  -Pspike.phases=200 -Pspike.renderapi=SOFTWARE -Pspike.vsync=false
/c/dev/Peashoot/gradlew -p . run --no-configuration-cache -Pspike.label=probe-mutable     -Pspike.phases=0,200 -Pspike.atlas=mutable
/c/dev/Peashoot/gradlew -p . run --no-configuration-cache -Pspike.label=probe-immutable   -Pspike.phases=0,200 -Pspike.atlas=immutable
```

No spike source is in this repository. The only file this ticket adds to it is this note. That satisfies the ticket's last acceptance criterion, "Spike code is not merged into the product modules" — nothing was added to `core`, `proxy` or `app`, and the whole of the spike is reproduced below instead.

## 12. #19: the renderer against these numbers

Measured 2026-09-19 on the same machine in the same state as sections 2 and 3, with the real
renderer — `dev.peashoot.app.render`, the real atlas, the real layout, the real animator — hosted by
`app/src/test/kotlin/dev/peashoot/app/render/FarmBench.kt` and run as `./gradlew :app:benchFarm`.
Same phases as the spike: 1280x800 dp non-resizable window, warm up 3 s, measure 10 s of
`withFrameNanos` deltas, average fps over the summed measured seconds, order statistics over the
sorted deltas. Same 200 entities, counted the same way: 40 villagers and 160 crops. Both figures
below come from a results file the bench wrote; nothing here is estimated.

| | Spike, 200 entities | #19 renderer, 200 entities |
|---|---:|---:|
| GPU (`DIRECT3D`) avg fps | 164.50 | **164.91** |
| GPU median / p99 / worst ms | 6.054 / 7.669 / 15.479 | **6.060 / 7.162 / 13.214** |
| Software (`SOFTWARE_FAST`) avg fps | 64.41 | **64.14** |
| Software median / p99 / worst ms | 15.536 / 16.555 / 16.699 | **15.581 / 19.410 / 21.356** |
| Draw calls a frame | 860 | **1070** |
| Canvas, density | 1583x954 px, 1.25 | 1583x954 px, 1.25 |

**It matches.** What "matches" was taken to mean, decided before the runs: on the GPU, still pinned
at the display's 165 Hz refresh with a median frame time in the same band — 6.060 ms against 6.054,
a difference of 6 µs, well inside the 0.8% the spike's own repeats varied by; in software, within
run-to-run noise of 64 fps — 64.14 against 64.41 and 64.53, a spread of 0.6%. The render API each
run reported is in its results file: `"renderApi":"DIRECT3D"` for the GPU run and
`"renderApi":"SOFTWARE_FAST"` with `"skikoRenderApiProp":"SOFTWARE"` for the software one, so
neither run is void. Note that the property, not the environment variable, is what reaches the JVM
here: the bench is its own `JavaExec`, so `-Pbench.renderapi=SOFTWARE` becomes `skiko.renderApi`.

The draw-call figure is **computed by the bench, not by the renderer**: the count is a function of
the layout, the canvas size in tiles, whether labels are on and how many villagers there are, and
the bench works it out from the same `farmLayout` the canvas draws from. An earlier version of this
bench read a counter the renderer kept for it; the counter was a product bent around its
measurement and has been removed. The formula was checked against that counter before it went: both
say 1070 for the measured scene and 1246 for the same scene with paths on.

Three things differ in kind from the spike, and all three are visible in the tails rather than the
medians. The frame draws **1070 calls, not 860**: the ground is the same 660, but the spike's 200
sprites became 160 crops plus 40 villagers plus 180 tiles of soil plot (fifteen plots at their full
4x3 footprint, drawn whether or not the field has filled them) plus 2 for the well and 40 name
labels. The **animator** is real: every frame steps every villager's position toward a target and
allocates a new position map, where the spike integrated flat `FloatArray`s; the bench also swaps
the whole `FarmState` every 4 s, which re-lays the farm and recomposes the canvas, as a live feed
would. And there is **text**: 40 villager names every frame, measured once per distinct string and
box and cached by `TextMeasurer` across frames. In software p99 moved from the spike's 16.555 ms to
19.410 ms, which is where that text and those allocations show up; on the GPU it did not move
(7.162 against 7.669). Neither median moved, which is what says the extra work fits inside the
frame rather than lengthening it. The labels-on case draws 1246 calls — 160 crop labels and 14
field labels and the badge on top of the 1070 — and is not part of the 10 s measurement, because
labels are off by default.

The degrade this note asked for in §10 exists and is tested. `FrameMeter` watches a rolling second
of frame deltas, degrades under 30 fps, recovers only above 35, and snaps villagers to their targets
while degraded. As §10 predicted, this machine never reaches it: the bench's worst single frame in
either mode is 21.356 ms. So the trigger is `FrameMeterTest`, which feeds synthetic frame times — a
run of 40 ms frames degrades, a run of 16 ms frames recovers, one 400 ms stall among smooth frames
does neither, and a 3 s gap — a dragged window, a resumed laptop — is dropped as a pause rather than
read as a frame rate, which is a bug this note's numbers could never have caught. No flag, no system
property, no hidden UI.

§10's other suggestion, watching the median frame *time* because average fps is pinned at the
display's refresh, was weighed and declined: that caution is about seeing load grow while the frame
rate is still at the cap, and the degrade asks a different question. Its threshold is 30 fps, far
below any refresh rate, where a one-second average is pinned to nothing and is the design's own
wording.

### The raw results files

```json
{"label":"gpu","env":{"renderApi":"DIRECT3D","skiko":"0.150.1","compose":"1.12.0","kotlin":"2.4.20","java":"22.0.1","javaVendor":"Oracle Corporation","skikoRenderApiProp":"null","skikoRenderApiEnv":"null","vsyncProp":"null","refreshRateHz":"165","canvasPx":"1583x954","density":1.25,"drawCallsPerFrame":1070,"villagers":40,"crops":160},"frames":1650,"seconds":10.005,"avgFps":164.91,"medianMs":6.060,"p99Ms":7.162,"worstMs":13.214}
```

```json
{"label":"software","env":{"renderApi":"SOFTWARE_FAST","skiko":"0.150.1","compose":"1.12.0","kotlin":"2.4.20","java":"22.0.1","javaVendor":"Oracle Corporation","skikoRenderApiProp":"SOFTWARE","skikoRenderApiEnv":"null","vsyncProp":"null","refreshRateHz":"165","canvasPx":"1583x954","density":1.25,"drawCallsPerFrame":1070,"villagers":40,"crops":160},"frames":642,"seconds":10.010,"avgFps":64.14,"medianMs":15.581,"p99Ms":19.410,"worstMs":21.356}
```

Run as, from the repository root, each on a fresh launch with nothing else heavy running:

```
./gradlew :app:benchFarm -Pbench.label=gpu      -Pbench.out=<dir>
./gradlew :app:benchFarm -Pbench.label=software -Pbench.renderapi=SOFTWARE -Pbench.out=<dir>
```

Each run also writes four PNGs into the same directory — the bench farm, the same farm with paths
on, a small farm replayed from the reducer's fixtures, and a farm with more directories than there
are plots — rendered through `ImageComposeScene` at the same 1583x954 and density 1.25, so the farm
can be looked at without a window. Four things were wrong in those pictures and were fixed before
these numbers were taken: the ground's grass variants fell on diagonals (two small primes; now a
proper spatial hash); the fixed 26x16 tile world sat in the top-left corner of a 33x20 tile canvas
(now centred); villagers waiting at the well stood on top of three whole fields (the queue is now
beside the well, on the rows above the first plots, and a test asserts no queue spot or home
overlaps a plot); and crop labels at a 48 px pitch ran over their neighbours (now elided to their
cell). None of it changed the frame time: the run before those fixes was 164.51 fps / 6.063 ms on
the GPU and 64.28 / 15.514 in software, which is the same 0.5% band as everything else here.

---

## Appendix A — the spike

### `settings.gradle.kts`

```kotlin
rootProject.name = "canvas-fps"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google {
            content {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android\\..*")
            }
        }
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // Same narrowing as the repo's own settings.gradle.kts: Compose Multiplatform 1.12 is
        // aligned with Jetpack, so runtime/lifecycle/saved-state are Google's and only those.
        google {
            content {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android\\..*")
            }
        }
    }
    // The point of the spike is to measure the stack the app ships, so take the versions from
    // the product's catalog rather than pinning them here.
    versionCatalogs { create("libs") { from(files("C:/dev/Peashoot/gradle/libs.versions.toml")) } }
}
```

### `build.gradle.kts`

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
}

dependencies { implementation(compose.desktop.currentOs) }

// Mirrors the product build: JVM 21 target, Java 21 source/target compatibility.
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

compose.desktop { application { mainClass = "spike.SpikeKt" } }

// Gradle properties rather than the ambient environment: the Gradle daemon is long-lived, so an
// env var exported in the shell is not guaranteed to reach the JVM the `run` task forks.
//   -Pspike.label=gpu -Pspike.phases=200 -Pspike.renderapi=SOFTWARE -Pspike.vsync=false
// Catalog accessors resolve against the project, not a task's scope, so capture this first.
// `compose` is a prefix of `compose-rules` in the catalog, so the leaf needs `asProvider()`.
val composeVersion = libs.versions.compose.asProvider().get()

// The Compose plugin's `run` task sets `args` itself, so everything goes in as a system property.
tasks.withType<JavaExec>().configureEach {
    val renderApi = providers.gradleProperty("spike.renderapi").orNull
    // Skiko reads this at class-init time, so it must be on the forked JVM from the start.
    // `jvmArgs` is no good here: the Compose plugin's run task resets it, as it does `args`.
    providers.gradleProperty("spike.vsync").orNull?.let { systemProperty("skiko.vsync.enabled", it) }
    for (key in listOf("spike.label", "spike.phases", "spike.ground", "spike.atlas")) {
        providers.gradleProperty(key).orNull?.let { systemProperty(key, it) }
    }
    systemProperty("spike.out", providers.gradleProperty("spike.out").getOrElse(project.projectDir.absolutePath))
    systemProperty("spike.compose.version", composeVersion)
    if (renderApi != null) environment("SKIKO_RENDER_API", renderApi)
}
```

### `src/main/kotlin/spike/Spike.kt`

```kotlin
package spike

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.GraphicsEnvironment
import java.io.File
import kotlin.math.roundToInt
import kotlin.random.Random
import androidx.compose.ui.graphics.Canvas as GfxCanvas
import org.jetbrains.skia.Image as SkiaImage

// Spike for issue #3: how fast does Compose Canvas draw N animated sprites from one atlas?
// Throwaway: it is never merged into core/proxy/app. See docs/research/canvas-frame-rate.md.

private const val TILE = 16
private const val COLS = 12
private const val ROWS = 11
private const val SCALE = 3
private const val DST = TILE * SCALE // 48 px, the size pixel art is really drawn at
private const val ANIM_FPS = 6f
private const val ANIM_FRAMES = 4
private const val WARMUP_NANOS = 3_000_000_000L
private const val MEASURE_NANOS = 10_000_000_000L
private const val MIN_MEASURED_FRAMES = 5
private const val PASS_FPS = 30.0
private const val CEILING = 200_000

/**
 * The atlas is generated, not loaded: 192x176 px of 16x16 tiles, the dimensions of Kenney's Tiny
 * Town `tilemap_packed.png` that #19 will load for real. What is measured is draw calls from one
 * image, not pixel content, so licensing, NOTICE and asset loading stay #19's job.
 */
private fun buildAtlas(immutable: Boolean): ImageBitmap {
    val bmp = ImageBitmap(COLS * TILE, ROWS * TILE)
    val canvas = GfxCanvas(bmp)
    val paint = Paint()
    for (row in 0 until ROWS) {
        for (col in 0 until COLS) {
            val i = row * COLS + col
            val x = (col * TILE).toFloat()
            val y = (row * TILE).toFloat()
            paint.color = Color.hsv((i * 360f / (COLS * ROWS)), 0.65f, 0.85f)
            canvas.drawRect(Rect(x, y, x + TILE, y + TILE), paint)
            paint.color = Color.hsv((i * 360f / (COLS * ROWS)), 0.9f, 0.35f)
            // A shape that moves with the tile index, so frame cycling is visible on screen.
            canvas.drawCircle(
                Offset(x + 4f + (i % 3) * 4f, y + 4f + (i % 4) * 3f),
                3f,
                paint,
            )
            canvas.drawRect(Rect(x, y, x + TILE, y + 1f), paint)
        }
    }
    if (!immutable) return bmp
    // A bitmap Compose still holds a Canvas over stays mutable, and Skia will not cache a mutable
    // bitmap's GPU texture: it re-uploads on every draw call. Snapshotting it to an immutable
    // Image is what loading a PNG would give #19 for free. Measured both ways; see the note.
    val sk = bmp.asSkiaBitmap()
    sk.setImmutable()
    return SkiaImage.makeFromBitmap(sk).toComposeImageBitmap()
}

private class Stats(val frames: Int, val seconds: Double, val median: Double, val p99: Double, val worst: Double) {
    val fps: Double = if (seconds > 0) frames / seconds else 0.0
}

private fun stats(samples: List<Long>): Stats {
    val sorted = samples.sorted()
    val ms = { n: Long -> n / 1_000_000.0 }
    return Stats(
        frames = sorted.size,
        seconds = sorted.sum() / 1_000_000_000.0,
        median = ms(sorted[sorted.size / 2]),
        p99 = ms(sorted[((sorted.size * 99) / 100).coerceAtMost(sorted.size - 1)]),
        worst = ms(sorted.last()),
    )
}

private class PhaseResult(val entities: Int, val s: Stats) {
    val pass: Boolean = s.fps > PASS_FPS

    fun json(): String =
        """{"entities":$entities,"frames":${s.frames},"seconds":${"%.3f".format(s.seconds)},""" +
            """"avgFps":${"%.2f".format(s.fps)},"medianMs":${"%.3f".format(s.median)},""" +
            """"p99Ms":${"%.3f".format(s.p99)},"worstMs":${"%.3f".format(s.worst)},"pass30":$pass}"""
}

/** Entities as parallel arrays; rebuilt whenever the phase's count changes. */
private class World(val n: Int, w: Float, h: Float) {
    val x = FloatArray(n)
    val y = FloatArray(n)
    val vx = FloatArray(n)
    val vy = FloatArray(n)
    val base = IntArray(n)
    val phase = IntArray(n)

    init {
        val rnd = Random(42)
        for (i in 0 until n) {
            x[i] = rnd.nextFloat() * (w - DST)
            y[i] = rnd.nextFloat() * (h - DST)
            vx[i] = (rnd.nextFloat() - 0.5f) * 160f
            vy[i] = (rnd.nextFloat() - 0.5f) * 160f
            base[i] = rnd.nextInt(COLS * ROWS - ANIM_FRAMES)
            phase[i] = rnd.nextInt(ANIM_FRAMES)
        }
    }

    fun step(dt: Float, w: Float, h: Float) {
        for (i in 0 until n) {
            var nx = x[i] + vx[i] * dt
            var ny = y[i] + vy[i] * dt
            if (nx < 0f) { nx = 0f; vx[i] = -vx[i] }
            if (ny < 0f) { ny = 0f; vy[i] = -vy[i] }
            if (nx > w - DST) { nx = w - DST; vx[i] = -vx[i] }
            if (ny > h - DST) { ny = h - DST; vy[i] = -vy[i] }
            x[i] = nx
            y[i] = ny
        }
    }
}

/** Phase scheduler: 200 first, then ramp until something drops below 30 fps, then bisect. */
private class Runner(private val explicit: List<Int>?) {
    private val ramp = listOf(500, 1000, 2000, 5000, 10000, 20000, 50000, 100000, CEILING)
    private var rampIdx = 0
    private var explicitIdx = 0
    private var bisecting = false
    private var bisectSteps = 0
    private var lastPass = 0
    private var firstFail = -1

    val results = mutableListOf<PhaseResult>()
    var entities = explicit?.firstOrNull() ?: 200
        private set
    var finished = false
        private set
    var worldDirty = true
        private set

    private var measuring = false
    private var phaseStart = 0L
    private val samples = mutableListOf<Long>()

    fun tick(now: Long, dt: Long) {
        if (phaseStart == 0L) phaseStart = now
        if (!measuring) {
            if (now - phaseStart >= WARMUP_NANOS) {
                measuring = true
                phaseStart = now
                samples.clear()
            }
            return
        }
        if (dt > 0) samples.add(dt)
        if (now - phaseStart >= MEASURE_NANOS && samples.size >= MIN_MEASURED_FRAMES) finishPhase()
    }

    private fun finishPhase() {
        val r = PhaseResult(entities, stats(samples))
        results.add(r)
        println("phase ${r.entities}: ${"%.1f".format(r.s.fps)} fps, median ${"%.2f".format(r.s.median)} ms")
        val next = nextCount(r)
        if (next == null) {
            finished = true
        } else {
            entities = next
            worldDirty = true
            measuring = false
            phaseStart = 0L
            samples.clear()
        }
    }

    private fun nextCount(r: PhaseResult): Int? {
        if (explicit != null) {
            if (r.pass) lastPass = r.entities else if (firstFail < 0) firstFail = r.entities
            explicitIdx++
            return explicit.getOrNull(explicitIdx)
        }
        if (r.pass) lastPass = r.entities else if (firstFail < 0 || r.entities < firstFail) firstFail = r.entities
        if (!r.pass && !bisecting) bisecting = true
        if (!bisecting) {
            if (rampIdx >= ramp.size) return null // ceiling reached, everything passed
            return ramp[rampIdx++]
        }
        // Bisect the bracket until it is within ~10%, with a hard step cap so it always ends.
        if (lastPass == 0) return null // even 200 failed: nothing to bracket
        if (bisectSteps >= 8) return null
        if (firstFail - lastPass <= (lastPass / 10).coerceAtLeast(1)) return null
        bisectSteps++
        return (lastPass + firstFail) / 2
    }

    fun worldBuilt() {
        worldDirty = false
    }

    fun bracket(): String = """"largestAbove30":$lastPass,"firstBelow30":$firstFail"""
}

/** Plain holder: the draw phase must not write snapshot state, and only `tick` needs to be state. */
private class Shared {
    var world: World? = null
    var w = 0f
    var h = 0f
    var ground = 0
}

@Composable
private fun FrameWindowScope.spike(cfg: Config, onDone: () -> Unit) {
    val atlas = remember { buildAtlas(cfg.immutableAtlas) }
    val runner = remember { Runner(cfg.phases) }
    val shared = remember { Shared() }
    val density = LocalDensity.current.density
    var tick by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        var last = 0L
        while (!runner.finished) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0L else now - last
                last = now
                if (shared.w > 0f) {
                    if (runner.worldDirty || shared.world == null) {
                        shared.world = World(runner.entities, shared.w, shared.h)
                        runner.worldBuilt()
                    }
                    shared.world?.step((dt / 1_000_000_000.0f).coerceAtMost(0.1f), shared.w, shared.h)
                    runner.tick(now, dt)
                }
                tick = now
            }
        }
        val report = report(cfg, runner, density, shared.w, shared.h, shared.ground)
        println(report)
        File(cfg.outDir, "results-${cfg.label}.json").writeText(report)
        onDone()
    }

    Canvas(Modifier.fillMaxSize()) {
        val now = tick // read the per-frame state so the draw is invalidated every frame
        shared.w = size.width
        shared.h = size.height
        shared.ground = if (cfg.ground) drawGround(atlas) else 0
        val w = shared.world ?: return@Canvas
        val f = (now / 1_000_000_000.0f * ANIM_FPS).toInt()
        for (i in 0 until w.n) {
            val t = w.base[i] + (f + w.phase[i]) % ANIM_FRAMES
            drawImage(
                image = atlas,
                srcOffset = IntOffset((t % COLS) * TILE, (t / COLS) * TILE),
                srcSize = IntSize(TILE, TILE),
                dstOffset = IntOffset(w.x[i].roundToInt(), w.y[i].roundToInt()),
                dstSize = IntSize(DST, DST),
                filterQuality = FilterQuality.None,
            )
        }
    }
}

/** The static world the real renderer will have, so the entity number is not flattered. */
private fun DrawScope.drawGround(atlas: ImageBitmap): Int {
    val cols = (size.width / DST).toInt() + 1
    val rows = (size.height / DST).toInt() + 1
    for (ty in 0 until rows) {
        for (tx in 0 until cols) {
            val t = (tx * 7 + ty * 3) % COLS
            drawImage(
                image = atlas,
                srcOffset = IntOffset(t * TILE, 0),
                srcSize = IntSize(TILE, TILE),
                dstOffset = IntOffset(tx * DST, ty * DST),
                dstSize = IntSize(DST, DST),
                filterQuality = FilterQuality.None,
            )
        }
    }
    return cols * rows
}

private fun FrameWindowScope.report(cfg: Config, runner: Runner, density: Float, w: Float, h: Float, ground: Int): String {
    val skiko =
        runCatching {
            val c = Class.forName("org.jetbrains.skiko.Version")
            c.getMethod("getSkiko").invoke(c.getField("INSTANCE").get(null)) as String
        }.getOrElse { "unknown (${it.javaClass.simpleName})" }
    val refresh =
        runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.displayMode.refreshRate.toString()
        }.getOrElse { "unknown" }
    val env =
        """"renderApi":"${window.renderApi}","skiko":"$skiko",""" +
            """"compose":"${System.getProperty("spike.compose.version")}","kotlin":"${KotlinVersion.CURRENT}",""" +
            """"java":"${System.getProperty("java.version")}","javaVendor":"${System.getProperty("java.vendor")}",""" +
            """"skikoRenderApiEnv":"${System.getenv("SKIKO_RENDER_API")}",""" +
            """"vsyncProp":"${System.getProperty("skiko.vsync.enabled")}",""" +
            """"refreshRateHz":"$refresh","canvasPx":"${w.toInt()}x${h.toInt()}","density":$density,""" +
            """"atlas":"${if (cfg.immutableAtlas) "immutable" else "mutable"}","groundDrawCalls":$ground"""
    return "{\"label\":\"${cfg.label}\",\"env\":{$env},${runner.bracket()},\"phases\":[\n" +
        runner.results.joinToString(",\n") { "  " + it.json() } +
        "\n]}\n"
}

/**
 * System properties, not program arguments: the Compose plugin's `run` task sets `args` itself and
 * wipes anything the build script adds.
 */
private class Config {
    val label: String = System.getProperty("spike.label", "run")
    val outDir: String = System.getProperty("spike.out", ".")
    val ground: Boolean = System.getProperty("spike.ground", "true").toBoolean()
    val immutableAtlas: Boolean = System.getProperty("spike.atlas", "immutable") != "mutable"

    /** null means the adaptive plan: 200, then ramp, then bisect. */
    val phases: List<Int>? =
        System.getProperty("spike.phases", "").takeIf { it.isNotBlank() }?.split(",")?.map { it.trim().toInt() }
}

fun main() {
    val cfg = Config()
    application {
        Window(
            onCloseRequest = ::exitApplication,
            state = rememberWindowState(size = DpSize(1280.dp, 800.dp)),
            resizable = false,
            title = "canvas-fps ${cfg.label}",
        ) {
            spike(cfg) { exitApplication() }
        }
    }
}
```

## Appendix B — the raw results files

Exactly as written by the spike, one file per run.

### `results-gpu.json`

```json
{"label":"gpu","env":{"renderApi":"DIRECT3D","skiko":"0.150.1","compose":"1.12.0","kotlin":"2.4.20","java":"22.0.1","javaVendor":"Oracle Corporation","skikoRenderApiEnv":"null","vsyncProp":"null","refreshRateHz":"165","canvasPx":"1583x954","density":1.25,"atlas":"immutable","groundDrawCalls":660},"largestAbove30":21875,"firstBelow30":23750,"phases":[
  {"entities":200,"frames":1645,"seconds":10.000,"avgFps":164.50,"medianMs":6.054,"p99Ms":7.669,"worstMs":15.479,"pass30":true},
  {"entities":500,"frames":1651,"seconds":10.006,"avgFps":165.01,"medianMs":6.055,"p99Ms":6.931,"worstMs":7.656,"pass30":true},
  {"entities":1000,"frames":1646,"seconds":10.002,"avgFps":164.56,"medianMs":6.056,"p99Ms":7.886,"worstMs":15.746,"pass30":true},
  {"entities":2000,"frames":1604,"seconds":10.000,"avgFps":160.39,"medianMs":6.065,"p99Ms":10.590,"worstMs":16.206,"pass30":true},
  {"entities":5000,"frames":1135,"seconds":10.004,"avgFps":113.45,"medianMs":8.388,"p99Ms":12.916,"worstMs":23.510,"pass30":true},
  {"entities":10000,"frames":666,"seconds":10.004,"avgFps":66.57,"medianMs":14.671,"p99Ms":21.805,"worstMs":31.819,"pass30":true},
  {"entities":20000,"frames":337,"seconds":10.015,"avgFps":33.65,"medianMs":29.145,"p99Ms":40.115,"worstMs":44.378,"pass30":true},
  {"entities":50000,"frames":142,"seconds":10.068,"avgFps":14.10,"medianMs":69.808,"p99Ms":90.962,"worstMs":96.758,"pass30":false},
  {"entities":35000,"frames":186,"seconds":10.034,"avgFps":18.54,"medianMs":50.959,"p99Ms":75.255,"worstMs":77.331,"pass30":false},
  {"entities":27500,"frames":251,"seconds":10.017,"avgFps":25.06,"medianMs":39.300,"p99Ms":53.572,"worstMs":56.044,"pass30":false},
  {"entities":23750,"frames":293,"seconds":10.015,"avgFps":29.26,"medianMs":33.707,"p99Ms":44.790,"worstMs":46.241,"pass30":false},
  {"entities":21875,"frames":303,"seconds":10.023,"avgFps":30.23,"medianMs":31.884,"p99Ms":47.281,"worstMs":49.758,"pass30":true}
]}
```

### `results-software.json`

```json
{"label":"software","env":{"renderApi":"SOFTWARE_FAST","skiko":"0.150.1","compose":"1.12.0","kotlin":"2.4.20","java":"22.0.1","javaVendor":"Oracle Corporation","skikoRenderApiEnv":"SOFTWARE","vsyncProp":"null","refreshRateHz":"165","canvasPx":"1583x954","density":1.25,"atlas":"immutable","groundDrawCalls":660},"largestAbove30":2375,"firstBelow30":2562,"phases":[
  {"entities":200,"frames":645,"seconds":10.015,"avgFps":64.41,"medianMs":15.536,"p99Ms":16.555,"worstMs":16.699,"pass30":true},
  {"entities":500,"frames":638,"seconds":10.009,"avgFps":63.75,"medianMs":15.575,"p99Ms":18.554,"worstMs":20.820,"pass30":true},
  {"entities":1000,"frames":547,"seconds":10.013,"avgFps":54.63,"medianMs":18.083,"p99Ms":23.694,"worstMs":26.800,"pass30":true},
  {"entities":2000,"frames":324,"seconds":10.007,"avgFps":32.38,"medianMs":29.614,"p99Ms":44.540,"worstMs":46.061,"pass30":true},
  {"entities":5000,"frames":165,"seconds":10.021,"avgFps":16.47,"medianMs":60.460,"p99Ms":73.149,"worstMs":76.319,"pass30":false},
  {"entities":3500,"frames":219,"seconds":10.027,"avgFps":21.84,"medianMs":45.362,"p99Ms":58.307,"worstMs":60.588,"pass30":false},
  {"entities":2750,"frames":283,"seconds":10.012,"avgFps":28.27,"medianMs":34.005,"p99Ms":49.249,"worstMs":55.492,"pass30":false},
  {"entities":2375,"frames":314,"seconds":10.024,"avgFps":31.32,"medianMs":31.443,"p99Ms":43.253,"worstMs":46.242,"pass30":true},
  {"entities":2562,"frames":299,"seconds":10.008,"avgFps":29.88,"medianMs":32.938,"p99Ms":43.098,"worstMs":48.946,"pass30":false}
]}
```

### `results-gpu-repeat.json`

```json
{"label":"gpu-repeat","env":{"renderApi":"DIRECT3D","skiko":"0.150.1","compose":"1.12.0","kotlin":"2.4.20","java":"22.0.1","javaVendor":"Oracle Corporation","skikoRenderApiEnv":"null","vsyncProp":"null","refreshRateHz":"165","canvasPx":"1583x954","density":1.25,"atlas":"immutable","groundDrawCalls":660},"largestAbove30":200,"firstBelow30":-1,"phases":[
  {"entities":200,"frames":1633,"seconds":10.005,"avgFps":163.22,"medianMs":6.064,"p99Ms":10.687,"worstMs":14.718,"pass30":true}
]}
```

### `results-software-repeat.json`

```json
{"label":"software-repeat","env":{"renderApi":"SOFTWARE_FAST","skiko":"0.150.1","compose":"1.12.0","kotlin":"2.4.20","java":"22.0.1","javaVendor":"Oracle Corporation","skikoRenderApiEnv":"SOFTWARE","vsyncProp":"null","refreshRateHz":"165","canvasPx":"1583x954","density":1.25,"atlas":"immutable","groundDrawCalls":660},"largestAbove30":200,"firstBelow30":-1,"phases":[
  {"entities":200,"frames":646,"seconds":10.011,"avgFps":64.53,"medianMs":15.512,"p99Ms":16.483,"worstMs":16.912,"pass30":true}
]}
```

### `results-gpu-novsync.json`

```json
{"label":"gpu-novsync","env":{"renderApi":"DIRECT3D","skiko":"0.150.1","compose":"1.12.0","kotlin":"2.4.20","java":"22.0.1","javaVendor":"Oracle Corporation","skikoRenderApiEnv":"null","vsyncProp":"false","refreshRateHz":"165","canvasPx":"1583x954","density":1.25,"atlas":"immutable","groundDrawCalls":660},"largestAbove30":200,"firstBelow30":-1,"phases":[
  {"entities":200,"frames":1638,"seconds":10.005,"avgFps":163.71,"medianMs":6.066,"p99Ms":10.333,"worstMs":13.459,"pass30":true}
]}
```

### `results-software-novsync.json`

```json
{"label":"software-novsync","env":{"renderApi":"SOFTWARE_FAST","skiko":"0.150.1","compose":"1.12.0","kotlin":"2.4.20","java":"22.0.1","javaVendor":"Oracle Corporation","skikoRenderApiEnv":"SOFTWARE","vsyncProp":"false","refreshRateHz":"165","canvasPx":"1583x954","density":1.25,"atlas":"immutable","groundDrawCalls":660},"largestAbove30":200,"firstBelow30":-1,"phases":[
  {"entities":200,"frames":1182,"seconds":10.000,"avgFps":118.19,"medianMs":8.204,"p99Ms":12.260,"worstMs":13.887,"pass30":true}
]}
```

### `results-probe-immutable.json`

```json
{"label":"probe-immutable","env":{"renderApi":"DIRECT3D","skiko":"0.150.1","compose":"1.12.0","kotlin":"2.4.20","java":"22.0.1","javaVendor":"Oracle Corporation","skikoRenderApiEnv":"null","vsyncProp":"null","refreshRateHz":"165","canvasPx":"1583x954","density":1.25,"atlas":"immutable","groundDrawCalls":660},"largestAbove30":200,"firstBelow30":-1,"phases":[
  {"entities":0,"frames":1648,"seconds":10.002,"avgFps":164.77,"medianMs":6.069,"p99Ms":8.841,"worstMs":13.252,"pass30":true},
  {"entities":200,"frames":1642,"seconds":10.006,"avgFps":164.11,"medianMs":6.060,"p99Ms":8.528,"worstMs":16.704,"pass30":true}
]}
```

### `results-probe-mutable.json`

```json
{"label":"probe-mutable","env":{"renderApi":"DIRECT3D","skiko":"0.150.1","compose":"1.12.0","kotlin":"2.4.20","java":"22.0.1","javaVendor":"Oracle Corporation","skikoRenderApiEnv":"null","vsyncProp":"null","refreshRateHz":"165","canvasPx":"1583x954","density":1.25,"atlas":"mutable","groundDrawCalls":660},"largestAbove30":0,"firstBelow30":0,"phases":[
  {"entities":0,"frames":262,"seconds":10.031,"avgFps":26.12,"medianMs":38.303,"p99Ms":57.630,"worstMs":59.445,"pass30":false},
  {"entities":200,"frames":96,"seconds":10.066,"avgFps":9.54,"medianMs":103.217,"p99Ms":137.496,"worstMs":137.496,"pass30":false}
]}
```
