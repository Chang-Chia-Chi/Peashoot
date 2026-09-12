# Charm-layer prior art: sonification, instrument UIs, agent visualizers, run-diff, mapping rules, Compose perf

Researched 2026-09-12 against primary sources only (repos, READMEs, source files, official docs, GitHub/YouTrack issues, HN Algolia API). Star counts and dates are as returned by `api.github.com` on that day. Scope excludes the simulated-town / Gource-CodeCity / VS Code Pets / rendering-engine / sprite-licensing / privacy threads (covered elsewhere).

---

## 1. Sonification of live developer/system events

### 1.1 Catalogue

| Project | Stars / activity | Tech | Mapping rules (verbatim or from source) |
|---|---|---|---|
| **Listen to Wikipedia** — [hatnote/listen-to-wikipedia](https://github.com/hatnote/listen-to-wikipedia) | 870 stars, 100 forks, pushed 2026-02-28, JavaScript, not archived | D3 + Howler.js (site footer; `static/js/howler.min.js`, `d3.min.js` in tree). 54 pre-rendered samples: `celesta/c001..c027`, `clav/c001..c027`, `swells/swell1..3`, each as .mp3 + .ogg ([tree](https://api.github.com/repos/hatnote/listen-to-wikipedia/git/trees/master?recursive=1)) | Site text: "Bells indicate additions and string plucks indicate subtractions. Pitch changes according to the size of the edit; the larger the edit, the deeper the note. Green circles show edits from unregistered contributors, and purple circles mark edits performed by automated bots ... announcements for new users ... punctuated by a string swell." ([listen.hatnote.com](http://listen.hatnote.com/)). Source ([static/js/app.js](https://raw.githubusercontent.com/hatnote/listen-to-wikipedia/master/static/js/app.js)): `var max_pitch = 100.0; var log_used = 1.0715307808111486871978099;` → `pitch = 100 - Math.min(max_pitch, Math.log(size + log_used) / Math.log(log_used)); index = Math.floor(pitch / 100.0 * Object.keys(celesta).length);` → `if (type == 'add') celesta[index].play(); else clav[index].play();` Circle radius: `size = Math.max(Math.sqrt(abs_size) * scale_factor, 3)`. New user: `play_random_swell()` picks one of 3 swells. |
| **BitListen** ("Listen to Bitcoin") — [MaxLaumeister/BitListen](https://github.com/MaxLaumeister/BitListen) | 306 stars, 116 forks, pushed 2026-01-10, JavaScript | Howler.js + Reconnecting-WebSocket (README); WebSocket feeds from Blockchain.info / Bitstamp | Site option: "Scale pitch with transaction amount (bigger transaction = deeper sound)" ([bitlisten.com](https://www.bitlisten.com/)). README does not document the bubble-size rule (unverified). Cited by LTW as its inspiration. |
| **GitHub Audio** — [debugger22/github-audio](https://github.com/debugger22/github-audio) | 1,753 stars, 97 forks, pushed 2025-05-25, TypeScript | React 18 + Howler.js + D3 (2D) + Three.js (3D), WebSocket feed ([README](https://raw.githubusercontent.com/debugger22/github-audio/master/README.md)) | Instrument by event type: celesta for `PushEvent, CreateEvent, WatchEvent, ForkEvent, ReleaseEvent, DeleteEvent`; clav for `IssuesEvent, PullRequestEvent, IssueCommentEvent`; random ambient swells. Colour by event: Push purple, Create red, Issues/PR/IssueComment green, Watch/Release orange, Fork blue, Delete red. README does not state a size→pitch rule. |
| **Peep, "the network auralizer"** — [sourceforge.net/projects/peep](https://sourceforge.net/projects/peep/) | Last update 2013-06-04 | C/Perl (era 2000) | "Peep is a network monitoring tool that represents network information via an audio interface ... diagnostics are made not only based on single network events but whether the network sounds 'normal'." The USENIX LISA 2000 paper returned HTTP 403; its natural-sound mapping (birds/water) is **unverified** here. |
| **esonify** — [oflatt/esonify](https://github.com/oflatt/esonify) | 21 stars, pushed 2019-01-10, Emacs Lisp | — | "An emacs extension that sonifies your code." (GitHub search `sonify`) |
| **dsonify** — woodshop/dsonify | 7 stars, pushed 2013 | D | "SOS: Sonify Your Operating System" |
| **log-sonification-playground** — gurghet/log-sonification-playground | 2 stars, pushed 2025-06-30 | p5.js | "transforms server logs and system metrics into immersive soundscapes" |
| **Network-Traffic-Sonification** — yabets143/… | 0 stars, pushed 2025-11-29 | Python | "converting real-time network traffic patterns into intuitive soundscape for ambient threat detection" |
| **bco.eveson** — openbase/bco.eveson | 2 stars, pushed 2021-10-04 | Java | "monitoring smart environments through pleasant ambient soundscapes based on data streams" |

GitHub search evidence (2026-09-12):
- `sonification` sorted by stars: supercollider (6,726), listen-to-wikipedia (870), ideoforms/isobar (434, "algorithmic composition, generative music and sonification"), kineteklabs/twotone (89), spacetelescope/astronify (84), james-trayford/strauss (68). Nothing about logs/CI/git in the top 15.
- `sonification logs OR sonify git OR sonify traffic OR sonification monitoring`: 8 results total, max 2 stars (table above).
- `agent sonification OR "llm sonification" OR "sonify agent"`: **0 results**.
- `claude code notification sound hook`: 48 results; top is [wyattjoh/claude-code-notification](https://github.com/wyattjoh/claude-code-notification) (96 stars, "native notifications with customizable system sounds"); the rest ≤6 stars (claude-bell, claude-code-sounds, Claude-Warcraft3-Notifier, cat-ccnotify-hook, session-alarm). These are one-shot chimes on `Stop`/`PermissionRequest`, not continuous sonification.
- The only agent visualizer found that synthesizes audio is [Ryder-MHumble/Realm](https://github.com/Ryder-MHumble/Realm) (26 stars; "Three.js ... with Tone.js for spatial audio synthesis", see §3).

**Conclusion:** there is no maintained "sonify LLM/agent traffic" project. The closest maintained ancestors are LTW (870) and GitHub Audio (1,753), both using the same recipe: two instruments (add vs remove / event-class A vs B), ~27 pre-rendered pitch steps, log-scaled size → pitch, rare "swell" for special events.

### 1.2 JVM feasibility

- **javax.sound.sampled** (Oracle tutorial, [Overview of the Sampled Package](https://docs.oracle.com/javase/tutorial/sound/sampled-overview.html); note the tutorial states it targets JDK 8): `Clip` = "in-memory, unbuffered" — "Because all the sound data is loaded in advance, playback can start immediately". `SourceDataLine` = streaming, "Operations on the bytes commence before all the data has arrived". JDK 21 [Clip Javadoc](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/javax/sound/sampled/Clip.html): "a special kind of data line whose audio data can be loaded prior to playback ... you can set a clip to start playing at any position ... `start` causes playback to continue from the position where playback was last stopped"; rewind with `setFramePosition(0)`.
- **javax.sound.midi** (Oracle tutorial, [Synthesizing Sound](https://docs.oracle.com/javase/tutorial/sound/MIDI-synth.html)): `Synthesizer synth = MidiSystem.getSynthesizer(); synth.getChannels()[n].noteOn(60, 93)`. Latency: "The latency measures the worst-case delay between the time a MIDI message is delivered to the synthesizer and the time that the synthesizer actually produces the corresponding result ... it might take a synthesizer a few milliseconds"; query via `getLatency()`.
- **What the default synth actually does** — OpenJDK's Gervill [SoftSynthesizer.java](https://raw.githubusercontent.com/openjdk/jdk/master/src/java.desktop/share/classes/com/sun/media/sound/SoftSynthesizer.java): `private long latency = 200000; // 200 msec` (microseconds), property info default `latency` = `120000L`; `bufferSize = frameSize * (int)(frameRate * (latency/1000000f))` sizes the `SourceDataLine` buffer; default format `AudioFormat(44100, 16, 2, true, false)`, `interpolation = "linear"`, `control rate = 147f`; comment: "Tell mixer not fill read buffers fully. This lowers latency". So the stock MIDI route has 120–200 ms of buffering unless you open the synth with a lower `latency` property (how low it goes reliably on Windows/macOS/Linux: **unverified**).
- **Compose for Desktop usage:** `gh api search/code` for Kotlin files importing `javax.sound.sampled` together with `androidx.compose.desktop` returned **5 files**: mahozad/cutcon (66 stars, "Media cutter, converter, viewer"), mrsrmn/soundboard (0 stars), marcomorosi06/WiFiAudioStreaming-Desktop, Al-del/Eco_sorter_desktop, SimPL-UBC/Stethoscope_Project. The soundboard's whole pattern is `val clip = AudioSystem.getClip(); clip.open(AudioSystem.getAudioInputStream(file)); clip.start(); clip.addLineListener { … clip.close() }`. No widely-used Compose Desktop app with sound was found; no Compose-specific obstacle is reported (Java Sound has no AWT/EDT dependency in its docs).
- **Simpler route: yes, and it is what the prior art does.** LTW and GitHub Audio ship pre-rendered sample banks (27 pitch steps × 2 instruments) rather than synthesizing. On the JVM the equivalent is one preloaded `Clip` per sample; the Oracle tutorial's "playback can start immediately" applies. For polyphony (overlapping events) you need one `Clip` per simultaneous voice or a mixer such as [philfrei/AudioCue-maven](https://github.com/philfrei/AudioCue-maven) ("modeled on javax.sound.sampled.Clip, enhanced with concurrent playback and dynamic handling of volume, pan and frequency"; stars unverified).

---

## 2. Skeuomorphic / retro instrument UIs in developer tools

| Tool | Stars (2026-09-12) | HN front-page evidence (Algolia API, points / comments / date) |
|---|---|---|
| [captbaritone/webamp](https://github.com/captbaritone/webamp) "Winamp 2 reimplemented for the browser" | 11,250, pushed 2026-08-28 | 281/104 (2021-04), 139/41 (2018-07), 135/62 (2022-09), 124/57 "Webamp IPFS media player" (2021-11) |
| [aristocratos/btop](https://github.com/aristocratos/btop) | 34,535, pushed 2026-09-09 | 316/65 (2021-09), **190/118 titled "Btop: A better modern alternative of htop with a gamified interface" (2025-11-08)**, 111/12 (2023-06) |
| [ClementTsang/bottom](https://github.com/ClementTsang/bottom) (same job, plainer look) | 14,014, pushed 2026-09-12 | best HN submission 9 points |
| htop (reference point) | — | 818, 716, 613, 604, 514 (explainers/releases); **Doom-htop "The classic DOOM game over htop" 288/43 (2024-04)** |
| [sqshq/sampler](https://github.com/sqshq/sampler) "shell commands execution, visualization and alerting. YAML" (gauges/sparklines/run-charts) | 14,795, pushed 2024-02-22 | no >50-pt hit for query "sampler terminal dashboard" (unverified under other titles) |
| [pipeseroni/pipes.sh](https://github.com/pipeseroni/pipes.sh) | 3,022, pushed 2024-08-12 | 1 point |
| [abishekvashok/cmatrix](https://github.com/abishekvashok/cmatrix) | 5,239, pushed 2024-08-21 | no cmatrix story >20 (query matched Citrix) |
| [karlstav/cava](https://github.com/karlstav/cava) "Cross-platform Audio Visualizer" | 6,411, pushed 2026-08-18 | not queried |
| [alemidev/scope-tui](https://github.com/alemidev/scope-tui) "oscilloscope/vectorscope/spectroscope for your terminal" | 715, pushed 2026-03-01 | not queried |
| [acaudwell/Gource](https://github.com/acaudwell/Gource) | 13,138, pushed 2026-03-06 | 256/32 (2019), 213/27 (2023), 173/67 (2024-12), 122, 101, 89 |
| Teenage Engineering-style software UIs | GitHub `teenage engineering ui`: 3 repos, max 1 star | HN TE hits are hardware: TX-6 434/328, NYT profile 253/164, founder interview 163/187, libpo32 148/36 (2026-03) |
| Grafana gauge | — | Docs: "Gauges are single-value visualizations that allow you to quickly visualize where a value falls within a defined or calculated min and max range" ([grafana.com](https://grafana.com/docs/grafana/latest/panels-visualizations/visualizations/gauge/)) |
| VS Code **SynthWave '84** theme | **2,543,847 installs**, 5★/197 ([marketplace](https://marketplace.visualstudio.com/items?itemName=RobbOwen.synthwave-vscode)) | — |
| VS Code **Power Mode** (screen-shake/particles) | **1,458,504 installs**, 5★/85 ([marketplace](https://marketplace.visualstudio.com/items?itemName=hoovercj.vscode-power-mode)) | — |

**Reading of the evidence** (correlational only; no primary source isolates "visuals" as the cause):
- Two functionally similar monitors: btop (34.5K stars, three HN front pages, the 2025 one explicitly framed around its "gamified interface") vs bottom (14K stars, HN max 9 points). A Doom-in-htop joke out-scored most real htop releases (288 pts).
- Webamp gets a fresh 100+ point HN thread every 1–3 years for a 25-year-old UI, with no functional novelty.
- Pure-aesthetic VS Code extensions reach 1.5–2.5M installs. Retro visuals are a proven acquisition channel when the tool is at least as functional as the plain alternative.
- No dev tool with a Teenage-Engineering-style hardware skeuomorph was found; that lane is empty (unverified whether any closed-source tool occupies it).

---

## 3. "Watch AI agents work" visualizers (2025–2026)

GitHub searches `claude-code monitor` (3,555 repos), `claude-code observability` (1,372), `claude code gource OR "codebase map" OR "file heatmap" …` (161), `coding agent activity visualization files live` (1). Ranked, with what each actually renders (from READMEs):

| Repo | Stars | What it shows | Per-tool-call activity mapped onto the codebase? |
|---|---|---|---|
| [ruvnet/ruflo](https://github.com/ruvnet/ruflo) (ex claude-flow) | 72,187 | swarm harness, not a visualizer | no |
| [davila7/claude-code-templates](https://github.com/davila7/claude-code-templates) | 30,630 | "CLI tool for configuring and monitoring Claude Code" (search description; README not checked) | unverified |
| [BloopAI/vibe-kanban](https://github.com/BloopAI/vibe-kanban) | 28,060 | kanban of agent tasks | no |
| [getagentseal/codeburn](https://github.com/getagentseal/codeburn) | 10,973 | token/cost tracking | no |
| [Maciek-roboblog/Claude-Code-Usage-Monitor](https://github.com/Maciek-roboblog/Claude-Code-Usage-Monitor) | 8,700 | usage/quota | no |
| [smtg-ai/claude-squad](https://github.com/smtg-ai/claude-squad) | 8,472 | tmux session manager | no |
| [matt1398/claude-devtools](https://github.com/matt1398/claude-devtools) | 3,917 | Electron; reads `~/.claude/`; tool-call inspector with inline diffs, "Isolated execution trees per agent with tool traces, token metrics, duration, and cost. Nested agents render recursively", per-turn token attribution (7 categories), compaction visualization | inspector, not a map |
| [graykode/abtop](https://github.com/graykode/abtop) | 3,531 | htop-style rows per session: tokens, context %, rate limits, child processes, ports | no |
| [stravu/crystal](https://github.com/stravu/crystal) (now Nimbalyst) | 3,115, pushed 2026-02-26 | parallel worktree sessions, compare approaches | no |
| [disler/claude-code-hooks-multi-agent-observability](https://github.com/disler/claude-code-hooks-multi-agent-observability) | 1,536, pushed 2026-02-08 | Bun+SQLite server, Vue client; event timeline + "Live Pulse Chart" (canvas, 1/3/5-min density, session colours, event-type emoji); captures 12 hooks (`PreToolUse, PostToolUse, PostToolUseFailure, PermissionRequest, Notification, Stop, SubagentStart, SubagentStop, PreCompact, UserPromptSubmit, SessionStart, SessionEnd`) | no ("does not render activity onto a repository map or file tree") |
| [furkankly/zoetrope](https://github.com/furkankly/zoetrope) | 839 | terminal flow graph: nodes = main agent / subagents / workflow groups, edges = spawn relations, tool calls as chips (`⚒ bash ×5`) with live duration; tails JSONL transcripts (live) or replays; "Zero network, provably" | no |
| [simple10/agents-observe](https://github.com/simple10/agents-observe) | 672 | React dashboard via hooks→CLI→SQLite→WebSocket: event stream, agent hierarchy, filters, token stats | no |
| [kibitzsh/kibitz](https://github.com/kibitzsh/kibitz) | 494 | VS Code "live commentary feed" + cross-session prompt dispatch | no |
| [niclasvestlund-YT/vibepulse](https://github.com/niclasvestlund-YT/vibepulse) | 194 | ESP32-S3 AMOLED hardware widget: usage, live agent activity, "NEEDS YOU" alerts | no |
| **[JamsusMaximus/codemap](https://github.com/JamsusMaximus/codemap)** | **132**, created 2025-12-19, pushed 2026-01-15, no license | "pixel-art hotel where folders become rooms and files become desks ... arranged by git activity"; "Watch agents move between rooms as they work on your code"; screens light up on access, yellow glow = read, green = write, walking animation between rooms, bounce when awaiting input; hooks (`file-activity-hook.sh`, `thinking-hook.sh`, `cursor-stop-hook.sh`, `git-post-commit.sh`) → Node server → WebSocket → browser; Claude Code + Cursor | **yes** |
| [Ryder-MHumble/Realm](https://github.com/Ryder-MHumble/Realm) | 26, MIT, pushed 2026-03-09 | Three.js "living workshop": "Every tool call animates a character moving through a living workshop. Each session gets its own hexagonal zone"; stations per tool (Bookshelf = read, Desk = write, Terminal = bash), sub-agents spawn at a Portal, Tone.js spatial audio; hook-driven | tool-typed stations, **not** the repo's file structure |
| Milad-Afdasta/REPO-VISUALIZER | 2, pushed 2026-07-13 | "Defrag-style live visualizer for code repos. Every file is a block — glows green on read, orange on edit, yellow on create, red on delete." | yes (grid, no agents) |
| Conductor ([conductor.build](https://conductor.build/), closed source) | — | "parallel Claude Code, Codex, and Cursor agents in isolated workspaces ... See at a glance what they're working on ... review and merge their changes" | no live code map documented |

**Closest thing to "a live map of your repo with agents moving through it": codemap.** It has the two halves — folders→rooms/files→desks spatial layout, and agent sprites walking to the file being touched, colour-coded read vs write. Distance from the target:
1. Data source is hook shell scripts (file-activity + thinking hooks), so it sees file paths and model name only; it has no view of tokens, cache reads/creations, stop reasons, 429/529, stream drops, or replay — all of which a proxy sees.
2. Layout is "arranged by git activity", browser-rendered, Node server; no desktop app.
3. 132 stars, 19 forks, ~4 weeks of commits (2025-12-19 → 2026-01-15), no license file. Effectively a demo, not a product.
4. Nothing in the space combines the spatial map with a timeline/replay; disler's tool has the timeline but no map; zoetrope has the agent tree but no files.

---

## 4. Run-diff / "ghost race" comparison of two traces

| Tool | Documented comparison UX | Divergence-point handling |
|---|---|---|
| **LangSmith** — [Compare experiment results](https://docs.langchain.com/langsmith/compare-experiment-results) | "Select two or more experiments and then click Compare." Table per dataset example; "Red highlights runs that regressed on any feedback key against your source experiment, while green highlights runs that improved"; per-column tallies of better/worse; filters on input/output/status/latency/metadata; for two experiments, Traces mode "Shows traces for each experiment side by side"; "the diff mode highlights modifications between outputs" (JSON/YAML) | Output-level diff only; no step alignment between the two traces |
| **Langfuse** — [Compare experiments](https://langfuse.com/docs/evaluation/experiments/compare-experiments) | Choose a baseline run; per dataset item show "inputs, outputs, evaluation scores, cost, and latency differences"; "an improvement in average quality can hide a regression on a critical case"; "open the trace for a failing item" to inspect "intermediate retrievals, model calls, or tools"; score thresholds as filters | Item-level; traces opened one at a time |
| **Braintrust** — [Compare experiments](https://www.braintrust.dev/docs/evaluate/compare-experiments) | Baseline "aligns test cases across experiments and adds score deltas to every row, with improvements highlighted in green and regressions in red"; "order by regressions puts the most affected test cases at the top"; Diff toggle → sub-row per experiment; "Show side-by-side diffs"; detail panel gives "character-level diff of that test case" | Output text diff; no trace-step alignment |
| **Jaeger** — release [v1.7.0](https://github.com/jaegertracing/jaeger/releases/tag/v1.7.0): "Compare two traces (jaeger-ui #228, @tiffon)" | Traces condensed to a tree of unique service/operation paths, node colours from [renderNode.css](https://raw.githubusercontent.com/jaegertracing/jaeger-ui/main/packages/jaeger-ui/src/components/TraceDiff/TraceDiffGraph/renderNode.css): `.is-added` `#2a8f04`, `.is-more` `#78d539`, `.is-less` `#ffa39e`, `.is-removed` `#cc1616` | Structural presence/count only. [jaeger-ui #513](https://github.com/jaegertracing/jaeger-ui/issues/513) "Comparing traces by latency" (2020) closed as not planned/stale — duration diff never shipped |
| **Chrome DevTools** | Performance panel reference documents saving traces and annotations but **no two-recording compare** ([reference](https://developer.chrome.com/docs/devtools/performance/reference)). Memory panel: "The Comparison view lets you find leaked objects by comparing multiple snapshots" ([heap snapshots](https://developer.chrome.com/docs/devtools/memory-problems/heap-snapshots)) | Aggregate deltas, not timelines |
| **Firefox Profiler** | `/compare/` route exists (profiler.firefox.com/compare); [issue #470](https://github.com/firefox-devtools/profiler/issues/470) "Ability to compare multiple profiles" (2017) still **open**; `docs-user/` has no compare guide | Unverified beyond route existence |
| **Pernosco** — [overview](https://pernos.co/about/overview/) | Dataflow ("tracking data values back to their sources"), notebook, search for executions of functions/lines, "Multiprocess debugging is first-class", condition/print annotations | **No feature for comparing two recordings** documented |
| **Differential flame graphs** — [Gregg 2014](https://www.brendangregg.com/blog/2014-11-09/differential-flame-graphs.html) | Widths from the "after" profile; "red for growth, and blue for reductions", saturation = magnitude | Caveat: "if code paths vanish completely in the second profile, then there's nothing to color blue" → author recommends a negated (reversed) differential as a second view |
| **git range-diff** — [docs](https://git-scm.com/docs/git-range-diff) | "generate a cost matrix between the commits in both commit ranges, then solve the least-cost assignment" (Jonker-Volgenant); dummy nodes for wholesale add/delete; `--creation-factor` default 60; symbols `=` match, `!` differ, `>` added, `<` removed; "dual-color" keeps inner diff colours and marks outer -/+ with red/green backgrounds, dimmed = only in first range, bold = only in second; output declared human-only, unstable | The `!` row *is* the divergence marker; ordering follows the second range |
| **Game ghost replays borrowed by a dev tool** | Not found. Only hit was a racing-telemetry app requesting a "Comparative Telemetry Overlay (Ghost Lap)" ([f1-race-replay #170](https://github.com/IAmTomShaw/f1-race-replay/issues/170)) — not a developer tool | **unverified** |

**Patterns documented:** (a) baseline + green/red deltas per row (LangSmith, Langfuse, Braintrust); (b) sort-by-regression (Braintrust); (c) structural tree diff with presence/count colours (Jaeger); (d) width-from-after + colour-from-delta, with the vanished-path caveat (flame graphs); (e) assignment-based pairing with explicit `= ! < >` and a creation-cost knob (range-diff); (f) side-by-side trace panes (LangSmith Traces mode).

**Missing for agent runs (no tool found does it):** pairing *tool_use sequences* across two runs the way range-diff pairs commits (same tool + similar args = `=`, same tool different args = `!`, extra call = `>`), a time axis so the two runs can be "raced", latency deltas on matched steps (Jaeger explicitly declined), and a visible first-divergence marker. LangSmith/Langfuse/Braintrust stop at dataset-item outputs; Jaeger ignores order and duration; flame graphs lose vanished paths.

---

## 5. Event → animation mapping rules from prior visualizers

### 5.1 Extracted tables

**Gource** ([README](https://raw.githubusercontent.com/acaudwell/Gource/master/README.md), [src/action.cpp](https://raw.githubusercontent.com/acaudwell/Gource/master/src/action.cpp), [src/file.cpp](https://raw.githubusercontent.com/acaudwell/Gource/master/src/file.cpp))

| Input | Visual |
|---|---|
| Repository | "displayed as a tree where the root of the repository is the centre, directories are branches and files are leaves" |
| User | avatar that "appear[s] and disappear[s] as they contribute"; `--user-scale`, `--highlight-users`, `--user-image-dir` |
| File | leaf node; radius `size = gGourceFileDiameter * 1.05; radius = size * 0.5` (diameter constant 8.0); colour `file_colour = ext.size() ? colourHash(ext) : vec3(1,1,1)` i.e. hue hashed from extension, white if none; optional per-entry `colour` field in custom log |
| Action A (add) | beam from user to file, `vec3(0.0f, 1.0f, 0.0f)` green |
| Action M (modify) | beam `vec3(1.0f, 0.7f, 0.3f)` orange, then `target->setFileColour(modify_colour)` |
| Action D (delete) | beam `vec3(1.0f, 0.0f, 0.0f)` red, then `target->remove(timestamp)` |
| Idle | file fades after `--file-idle-time` seconds then is removed; `--max-files` cap |
| Custom log | `timestamp|username|type|file|colour` — no branch field; git branches have no visual element |
| Toggles | `--hide bloom,date,dirnames,files,filenames,mouse,progress,root,tree,users,usernames` |

**Logstalgia** ([README](https://raw.githubusercontent.com/acaudwell/Logstalgia/master/README), [src/logentry.cpp](https://raw.githubusercontent.com/acaudwell/Logstalgia/master/src/logentry.cpp), [src/requestball.cpp](https://raw.githubusercontent.com/acaudwell/Logstalgia/master/src/requestball.cpp))

| Input | Visual |
|---|---|
| Request | a ball travelling from the left (hostname label) to the paddle (path label) |
| response_size | `size = log((float)bytes) + 1.0f`, minimum 5 px |
| response_code | colour: `<200 → vec3(0,1,0.5)`, `2xx → vec3(1,1,0)` yellow, `3xx → vec3(1,0.5,0)` orange, `≥400 → vec3(1,0,0)` red; code text drawn at the hit point and fades |
| success | `successful = (code<400)`; success → paddle bounces the ball; failure → `no_bounce = !le->successful`, ball passes the paddle |
| grouping | regex groups (default Images/CSS/Scripts + Misc), optional group colour applied to "labels and request balls" |
| paddle | `--paddle-mode single|vhost|pid` — one paddle per virtual host or process |
| custom log | `timestamp|hostname|path|response_code|response_size|[success]|[response_colour]|[referrer]|[user_agent]|[vhost]|[pid]` |

**code_swarm** ([README.markdown](https://raw.githubusercontent.com/rictic/code_swarm/master/README.markdown), [data/sample.config](https://raw.githubusercontent.com/rictic/code_swarm/master/data/sample.config))

| Input | Visual |
|---|---|
| Event | "a file, edited by a person, at a specific time" (ms since epoch) |
| File node | colour by regex `ColorAssign` ("Label", "regex", R,G,B); `FileLife=200` frames, `FileDecrement=-2`/frame, `FileSpeed=7.0` |
| Person node | `PersonLife=255`, `PersonDecrement=-1`, `PersonSpeed=2.0` |
| Edge person→file | `EdgeLife=250`, `EdgeDecrement=-2`; `ShowEdges` "mostly for debug purpose" |
| Layout | force-directed, `PhysicsEngineSelection=PhysicsEngineOrderly` |

**Listen to Wikipedia** and **GitHub Audio** — see §1.1 (add→celesta / remove→clav; `pitch = 100 − min(100, log(size+1.0715)/log(1.0715))` → 27 steps; radius `√|size|·k`, min 3; anon green / bot purple; new user → swell; GitHub Audio: instrument by event class, colour by event type).

**Cross-cutting rules the four share:** one visual noun per event (ball / beam / node), *magnitude* → log-scaled size or pitch (never linear), *outcome class* → hue from a fixed small palette (green good / red bad in three of four), *actor* → separate sprite with its own lifetime, *inactivity* → fade-and-remove on a timer, and a rare high-salience event (new user, swell) that breaks the ambient texture.

### 5.2 Proposed analogue for LLM-proxy events (design proposal, not prior art)

| Proxy event | Visual (Gource/Logstalgia idiom) | Audio (LTW idiom) |
|---|---|---|
| Messages request opened | ball leaves the client node toward the model node; size = log(input_tokens) | short low "send" tick |
| `tool_use` (Read/Grep) | beam to file node, hue = tool (Read blue-ish, Grep cyan), file glows | celesta step, pitch from log(result size) |
| `tool_use` (Edit/Write) | orange beam (Gource M), file recoloured | clav step |
| `tool_use` (Bash) | beam to a "terminal" node; label = first token of command | percussive tick |
| `tool_result` | return ball to agent, size = log(bytes); red if `is_error` | none (avoid doubling) |
| `usage.cache_read_input_tokens` vs `cache_creation_input_tokens` | ball colour cool (cache read) vs warm (cache creation); ring width = ratio | soft vs bright variant of the send tick |
| `stop_reason=end_turn` / `tool_use` / `max_tokens` | paddle hit (bounce) / handoff / ball passes paddle red (Logstalgia miss) | resolve chord / none / dissonant pluck |
| 429 / 529 / `overloaded_error` | red ball, no bounce, response code text fades (Logstalgia) | detuned low pluck |
| stream drop / resume | ball freezes mid-flight then dashed continuation | tape-stop then resume |
| replay (cassette) hit | ball drawn ghosted/dimmed (range-diff "dimmed = first range") | muted sample |
| sub-agent spawn | new actor sprite at a portal (Realm), lifetime decays like code_swarm `PersonLife` | string swell (LTW new user) |

---

## 6. Performance budget: heavy Canvas animation in the same JVM as Ktor

**How Compose Desktop renders.** Skia through Skiko, hosted in AWT/Swing ([Swing interoperability docs](https://kotlinlang.org/docs/multiplatform/compose-desktop-swing-interoperability.html): default `ComposePanel` uses Skia; experimental `compose.swing.render.on.graphics=true` renders via AWT graphics with "a performance penalty that increases with panel size"). AWT is a hard dependency — [compose-multiplatform #2825](https://github.com/JetBrains/compose-multiplatform/issues/2825) "Experimental example application without AWT" (59 reactions, closed). Skiko defaults ([SkikoProperties.kt](https://raw.githubusercontent.com/JetBrains/skiko/master/skiko/src/jvmMain/kotlin/org/jetbrains/skiko/SkikoProperties.kt)): render API METAL (macOS) / OPENGL (Linux) / DIRECT3D (Windows); `skiko.vsync.enabled=true`; "If vsync is enabled, but platform can't support it (Software renderer, Linux with uninstalled drivers), we enable frame limit by the display refresh rate"; `skiko.fps.enabled=false` (FPS counter off by default).

**Issues (number, status, what was learned):**

| Issue | Status | Finding |
|---|---|---|
| [compose-multiplatform #1054](https://github.com/JetBrains/compose-multiplatform/issues/1054) "Animation use very high CPU usage on macOS" (labels bug, performance, desktop) | opened 2021-08-14, closed 2024-09-23 (mirror CMP-5303) | igordmn (2021-10-08): "when we call `animateValue` or `withFrameNanos`, we constantly redraw the whole window content with 1 frame per 16ms, even if the animation should be updated as 1 frame per 100ms". Fix in 1.0.0-alpha4-build396 cut a test animation from 10% to 1% CPU on Windows; later Skiko fixes for "high CPU usage during animation on Linux GPU rendering (and Windows/Linux software rendering)" and "during a hidden state of the window". |
| [#4225](https://github.com/JetBrains/compose-multiplatform/issues/4225) / CMP-4225 "TextField cursor animation uses a lot of CPU" | created 2024-02-03, fixed 2024-02-21 ([compose-multiplatform-core #1113](https://github.com/JetBrains/compose-multiplatform-core/pull/1113)) | m-sasha: "cursor blinking is implemented as a regular animation, which causes `frameClock.hasAwaiters` to be `true` all the time, in turn causing `render` to run all the time" — 2% → 20% CPU from one blinking cursor. Any always-running animation keeps the render loop hot. |
| [CMP-3543](https://youtrack.jetbrains.com/issue/CMP-3543) "Research performance issues on Desktop" | **open** since 2023-08-21 | FPS on Windows fell from 122 (1.0.0) to 69 (1.5.0); "regression originates in Skiko or Skia rather than Compose". Recent comment: "the simplest AnimatedVisibility is really choppy and slow" unless `-Dskiko.renderApi=SOFTWARE`. |
| [CMP-6722](https://youtrack.jetbrains.com/issue/CMP-6722) "Excessive garbage generation from redrawing" | 2024-09-20 → resolved 2024-10-23 (PR #996) | Heap "cycling between 2200M–3800M due to perpetually redrawing spinners"; large byte arrays discarded per size change in `MetalSwingRenderer`/`SwingOffscreenDrawer`; "recomposition without canvas changes caused immediate OOM". Direct evidence that an always-animating Compose Desktop UI is a GC-pressure source in the same JVM. |
| [CMP-6570](https://youtrack.jetbrains.com/issue/CMP-6570) "compose very poor performance on desktop" | 2024-09 → resolved 2025-10 | Process RSS 1.2 GB vs ~500 MB per NMT — off-heap Skia GPU resource cache; `-Dskiko.gpu.resourceCacheLimit` added in 1.10.0-alpha02. |
| [CMP-9269](https://youtrack.jetbrains.com/issue/CMP-9269) "UI not updating after StateFlow update when Compose Desktop window is inactive" | **open**, 2025-11-13 | State updates while the window is unfocused/minimised do not repaint until a window event; user workaround is a dummy `rememberInfiniteTransition` (which by #4225's mechanism burns CPU). A background dashboard must expect this. |
| [CMP-6383](https://youtrack.jetbrains.com/issue/CMP-6383) "Low FPS on Linux with maximized window" | **open** since 2021 | — |
| [CMP-8124](https://youtrack.jetbrains.com/issue/CMP-8124) "ComposePanel: Support vsync with compose.swing.render.on.graphics=true" | **open**, 2025-05 | The Swing-graphics render path has no vsync. |
| [CMP-4199](https://youtrack.jetbrains.com/issue/CMP-4199) / #4199 "Desktop software renderer bad performance" | 2024-01 → resolved 2026 | Software fallback is slow; do not assume GPU. |

**JVM/GC facts relevant to "never stall the proxy":** G1 is the default collector and its pause target defaults to 200 ms — HotSpot [g1Arguments.cpp](https://raw.githubusercontent.com/openjdk/jdk/master/src/hotspot/share/gc/g1/g1Arguments.cpp): `// The default pause time target in G1 is 200ms  FLAG_SET_DEFAULT(MaxGCPauseMillis, 200);`. Generational ZGC ([JEP 439](https://openjdk.org/jeps/439), JDK 21): "Pause times should not exceed 1 millisecond". Ktor engines run on their own thread pools, configurable per engine (Netty `connectionGroupSize`/`workerGroupSize`/`callGroupSize`; CIO coroutine-based) ([ktor.io](https://ktor.io/docs/server-engines.html)) — they do not touch the AWT event thread, so the shared risks are GC pauses, CPU saturation and off-heap GPU memory, not thread contention.

**Is "separate process" the documented recommendation?** No JetBrains document or issue found says so — **unverified**. The primary evidence supports a weaker statement: Compose Desktop has a history of always-on animation pinning the render loop (#1054, #4225), generating heap churn (CMP-6722) and off-heap growth (CMP-6570), with an open desktop-performance umbrella issue (CMP-3543). Isolation is a defensible engineering choice, not a vendor recommendation.

---

## What this means for the design

1. **Ship sound as a sample bank, not a synth.** LTW (870★) and GitHub Audio (1,753★) both use 27 pre-rendered pitch steps × 2 instruments + 3 swells; the stock JVM MIDI synth buffers 120–200 ms by default (SoftSynthesizer source), whereas a preloaded `Clip` "can start immediately" (Oracle tutorial). §1.
2. **Copy LTW's exact size→pitch curve** (`100 − min(100, log(size+1.0715)/log(1.0715))`, 27 bins, big = deep) for tokens/bytes; it is the only sonification mapping in this space with a decade of public use. §1.1.
3. **Nobody has sonified agent traffic** (0 GitHub results; hook-chime repos ≤96★). This is unclaimed ground with a proven recipe — a cheap, memorable differentiator. §1.1.
4. **Retro instrument visuals demonstrably win attention for otherwise-commodity monitors**: btop 34.5K★ and three HN front pages (one explicitly about its "gamified interface") vs bottom 14K★ and 9 HN points; SynthWave '84 at 2.5M installs. Spend on the skin; the lane of a Teenage-Engineering-style *dev* tool is empty. §2.
5. **The "repo map with agents walking through it" exists only as a 132★ four-week demo (codemap)** fed by shell hooks. A proxy sees strictly more (tokens, cache hits, stop reasons, 429/529, stream drops, replay) — the map plus those signals is the gap. §3.
6. **Steal Logstalgia's outcome grammar for HTTP/stop-reason outcomes**: success bounces off the paddle, `≥400` is red and passes the paddle, size = `log(bytes)+1`, response code text fades at the hit point. It maps one-to-one onto 2xx / 429 / 529 / `max_tokens`. §5.1.
7. **For run-diff, use range-diff's pairing, not LangSmith's row table**: cost-matrix assignment over tool_use blocks, `= ! < >` markers, a creation-factor knob, and dual colouring (dimmed = only in run A, bold = only in run B). No agent tool does step-level pairing; Jaeger declined latency diffs (#513). §4.
8. **Show the "vanished path" explicitly** — differential flame graphs document that paths present only in the baseline are invisible in an after-anchored view; render both directions or mark `<` rows loudly. §4.
9. **Any always-on animation keeps Compose's render loop hot and churns the heap** (#1054: whole-window redraw at 16 ms; #4225: one blinking cursor = 20% CPU; CMP-6722: 2.2–3.8 GB heap cycling). Drive animation from events with idle fade-out (Gource `--file-idle-time`), never `rememberInfiniteTransition` at idle, and budget with `skiko.fps.enabled=true` during dev. §6.
10. **Run the proxy on ZGC or in its own process.** G1's default pause target is 200 ms; Generational ZGC targets ≤1 ms (JEP 439). No vendor doc mandates a separate process, but the desktop-perf umbrella issue (CMP-3543) is still open and windows stop repainting when unfocused (CMP-9269) — the dashboard should be able to die without the proxy noticing. §6.

---

## Could not verify

- Peep (2000) natural-sound mapping (birds/water/wind) — USENIX LISA paper returned HTTP 403; only the SourceForge blurb was readable.
- BitListen's bubble-size rule — README and site do not state it; only the pitch option is documented.
- Listen to Wikipedia's exact hex values for anon/bot circles — site text says green/purple; `app.js` assigns `edit_color` from `type` but the constants live elsewhere (not fetched).
- How low Gervill's `latency` property can safely go per OS — no primary source.
- Stars for philfrei/AudioCue-maven (found via search, repo not fetched).
- Whether davila7/claude-code-templates (30,630★) visualizes anything beyond CLI monitoring — README not checked.
- Whether sqshq/sampler ever made the HN front page under another title.
- Any dev tool that borrowed the game "ghost replay" pattern — none found.
- Firefox Profiler `/compare/` behaviour and which PR shipped it — route exists, issue #470 still open, no user doc.
- Jaeger's original design blog (Medium, 403) — colours were taken from the jaeger-ui CSS instead.
- WebPageTest filmstrip-comparison docs — TLS certificate error and 404 on the docs repo.
- A JetBrains statement recommending a separate process for Compose Desktop dashboards next to servers — none found.
- Compose Desktop's use of the AWT EDT / `Dispatchers.Swing` as its main dispatcher — `gh` code search returned 0 hits; inferred only from the Swing-interop docs and issue threads.
