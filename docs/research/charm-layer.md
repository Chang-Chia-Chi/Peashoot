# Charm layer for an LLM proxy dashboard: research notes

Date: 2026-09-12. Star counts and dates are from the GitHub API on that day unless noted. "unverified" marks claims with no primary source found.

## 1. Prior art: simulated towns and agent worlds

### The headline finding

The "agents as inhabitants of a pixel world, driven by real Claude Code activity" idea is **not novel in 2026**. There is a crowded cluster of hook/transcript-driven projects, several with strong traction. None found is driven from the API proxy layer (`ANTHROPIC_BASE_URL`); all read Claude Code hooks or `~/.claude/projects` JSONL transcripts. That is the open gap.

| Project | Stars | Data source | Live? | Rendering | Notes |
|---|---|---|---|---|---|
| [pablodelucca/pixel-agents](https://github.com/pablodelucca/pixel-agents) | 9.3k (README badge) | "Hooks mode (default) — a hook script receives Claude events such as `SessionStart`, `PreToolUse`, `PermissionRequest`, and `Stop`"; fallback "infers agent status by scanning Claude's JSONL session transcripts under `~/.claude/projects/`" | Live | "Canvas 2D with pathfinding and character state machines" | VS Code ext + `npx pixel-agents`. Marketplace: [84,332 installs](https://marketplace.visualstudio.com/items?itemName=pablodelucca.pixel-agents). MIT. |
| [rullerzhou-afk/clawd-on-desk](https://github.com/rullerzhou-afk/clawd-on-desk) | 6,205 | Command hooks, log-file polling (`~/.codex/sessions/`), HTTP `/state` endpoint, process monitoring | Live | Electron + SVG animation | Desktop pet, not a town. AGPL-3.0 code; bundled art "All rights reserved". Created 2026-03-18. |
| [crafter-station/petdex](https://github.com/crafter-station/petdex) | 4,081 | Gallery of pets for CLIs | n/a | Next.js | Shows the pet/mascot category has demand. |
| [paulrobello/claude-office](https://github.com/paulrobello/claude-office) | 513 | Claude Code hooks → FastAPI backend (`CLAUDE_OFFICE_API_URL`); also OpenCode plugin events | Live | PixiJS + Next.js, "60fps with A* pathfinding" | Maps `session.compacted` → boss stomps trashcan, task completion → printer. Payloads "carry tool I/O and file paths". MIT. |
| [IvanWng97/pixtuoid](https://github.com/IvanWng97/pixtuoid) | 475 | Terminal (Rust/ratatui) office | Live | TUI | |
| [agentsmill/age-of-agents](https://github.com/agentsmill/age-of-agents) | 260 | Tails JSONL transcripts in `~/.claude`, `~/.codex`, `~/.opencode`, `~/.koda` "locally and read-only"; "a small logging proxy" only for local LLMs | Live | PixiJS v8 | **Closest to a "town"**: session → settler leaving a keep, tools → workshops (forge=edits, mage tower=research, mine=terminal), subagents → workers, tokens → harvest in a storehouse. Deterministic, no LLM narration. MIT. Created 2026-06-14. |
| [Michaelliv/claude-quest](https://github.com/Michaelliv/claude-quest) | 196 | Claude Code sessions | Live | Go | RPG metaphor. |
| [W17ant/Claude-Office](https://github.com/W17ant/Claude-Office) | 150 | Claude Code hooks, WebSocket | Live | Isometric pixel art | |
| [JamsusMaximus/codemap](https://github.com/JamsusMaximus/codemap) | 132 | Cursor & Claude Code activity | Live | TS | Created 2025-12-19, earliest in the cluster. |
| [KbWen/agent-virtual-office](https://github.com/KbWen/agent-virtual-office) | 20 | Hooks (`PreToolUse`, `PostToolUse`, `SubagentStart/Stop`, `UserPromptSubmit`, `Stop`) + HTTP POST | Live | React + SVG + `requestAnimationFrame`, "no backend" | Has explicit sanitization scripts for fixtures. |
| [rafapetter/agent-town](https://github.com/rafapetter/agent-town) | 5 | Library API (`addAgent()`, `updateAgent()`) + webhook bridge | Live | "HTML5 Canvas 2D with procedural rendering — no sprite sheets" | Externally driven by design. |
| [gukosowa/agents-in-the-office](https://github.com/gukosowa/agents-in-the-office) | 2 | Hooks → JSON files → Rust watcher (Tauri) + JSONL poller for subagents | Live | Vue + Tauri, A* | Read → bookshelf, Edit → computer, permission wait → red vignette. |

Implication: a JVM/Compose town is a new *platform* and a new *data source* (proxy sees tokens, cache, 429s, stream drops, stop reasons; hooks do not — see §3), not a new idea. Differentiate on that or the project reads as a port of pixel-agents.

### Simulated (LLM-driven) worlds

| Project | Stars | Stack | Driven by | External events? |
|---|---|---|---|---|
| [a16z-infra/ai-town](https://github.com/a16z-infra/ai-town) | 10,480; last commit 2026-08-26 | Convex backend, PixiJS via `pixi-react`; default `llama3` via Ollama | LLM loop + memory/vector search | Yes, at the engine level: "Inputs are submitted by players and agents and processed by the game engine" via `insertInput` (`moveTo`, `startConversation`, `acceptInvite`, `leaveConversation`, ...), ticks at 60/s batched into 1/s steps ([ARCHITECTURE.md](https://github.com/a16z-infra/ai-town/blob/main/ARCHITECTURE.md)). So the engine *could* be fed externally, but it is a Convex/TS stack, MIT. |
| [joonspk-research/generative_agents](https://github.com/joonspk-research/generative_agents) (Smallville) | 22,092; last push 2024-08-05 | Django env server + browser frontend | OpenAI API per step ("One game step represents 10 seconds in the game") | Replay only: `http://localhost:8000/replay/<simulation-name>/<step>`; "primarily intended for debugging". Apache-2.0. Frontend renderer not named in README (Phaser: unverified). |
| [altera-al/project-sid](https://github.com/altera-al/project-sid) | 1,380 | Minecraft, PIANO architecture ([arXiv 2411.00114](https://arxiv.org/abs/2411.00114)) | LLM agents, 10–1000+ | Repo "contains our technical report"; no simulation code or visualizer found. |
| AI Village ([theaidigest.org/village](https://theaidigest.org/village)) | n/a | Live UI; dataset on [Hugging Face](https://huggingface.co/datasets/aidigestorg/ai-village) | "a group of AI agents — built on frontier models from Anthropic, OpenAI, and Google — live together in a long-running virtual environment"; 31 agents | Viewer is transcript/chat + computer-use screenshots, not a spatial map. Visualizes real agent activity, but as a feed, not a town. |

### Gource-style activity visualizers (deterministic, no LLM)

| Project | Stars | Data | Live? | Rendering |
|---|---|---|---|---|
| [acaudwell/Gource](https://github.com/acaudwell/Gource) | 13,138; release gource-0.56 published 2026-03-06 | Git/Bazaar/Hg/SVN logs, or custom pipe format `timestamp\|username\|type\|file\|colour` | Yes: "`my-custom-log-script.pl \| gource --realtime --log-format custom -`", "`--realtime` tells Gource to increment the clock in real time" ([wiki](https://github.com/acaudwell/Gource/wiki/Custom-Log-Format)) | OpenGL, "requires a 3D accelerated video card". GPL-3.0. |
| [acaudwell/Logstalgia](https://github.com/acaudwell/Logstalgia) | 1,805 | Apache/Nginx access logs | Yes: "`tail -f /var/log/apache2/access.log \| logstalgia -`", `--sync` | OpenGL, "retro arcade-style" (Pong). GPL-3.0. |
| [rictic/code_swarm](https://github.com/rictic/code_swarm) | 358; last push 2014 | VCS logs → XML events | Offline | Java/Processing, particle physics |
| CodeCity ([wettel.github.io](https://wettel.github.io/codecity.html)) | n/a | Software metrics from source (classes → buildings, packages → districts) | Offline | VisualWorks Smalltalk on Moose + OpenGL; last dated reference 2009 |
| [hatnote/listen-to-wikipedia](https://github.com/hatnote/listen-to-wikipedia) | 870 | Wikipedia recent-changes feed via wikimon websocket | Live | D3 + Web Audio/Howler (sonification) |

Takeaway: the two best-known long-lived activity visualizers (Gource, Logstalgia) are deterministic stdin-fed renderers with a tiny "grammar" (who/what/when/type). That grammar, not an LLM, is what made them screenshot-able.

## 2. Prior art: charm as an adoption driver

| Tool | Evidence | Maintained? |
|---|---|---|
| VS Code Pets | Marketplace: [2,679,182 installs](https://marketplace.visualstudio.com/items?itemName=tonybaloney.vscode-pets), 5/5 (123 reviews), v1.36.0; GitHub [tonybaloney/vscode-pets](https://github.com/tonybaloney/vscode-pets) 4,171 stars, MIT | Yes, last commit 2026-08-07 (dependabot), pushed 2026-09-10 |
| pixel-agents (see §1) | 9.3k stars, 84,332 installs in <1 year | Yes |
| clawd-on-desk | 6,205 stars in ~6 months (created 2026-03-18) | Yes |
| [ohmyzsh/ohmyzsh](https://github.com/ohmyzsh/ohmyzsh) | 189,681 stars; README leads with "A delightful community-driven ... 140+ themes to spice up your morning" | Yes |
| [dylanaraps/neofetch](https://github.com/dylanaraps/neofetch) | 23,687 stars | **Archived** (last push 2024-07-19) |
| [carloscuesta/gitmoji](https://github.com/carloscuesta/gitmoji) | 16,791 stars | Yes, pushed 2026-08-31 |
| [sindresorhus/ora](https://github.com/sindresorhus/ora) / [cli-spinners](https://github.com/sindresorhus/cli-spinners) | 9,747 / 2,931 stars | Yes |
| [LazoVelko/Pokemon-Terminal](https://github.com/LazoVelko/Pokemon-Terminal) | 4,798 stars | Updated 2026-09 (stars only; commit cadence not checked) |
| [github/gh-skyline](https://github.com/github/gh-skyline) | 1,337 stars, MIT | Pushed 2026-08-13 |
| [githubocto/repo-visualizer](https://github.com/githubocto/repo-visualizer) | 1,307 stars | **Archived** |
| WakaTime | Public [leaderboards](https://wakatime.com/leaders) with tabs "Hours Coded", "Manual Coding", "AI Coding", "AI Spend"; [wakatime-cli](https://github.com/wakatime/wakatime-cli) 451 stars | Yes |
| Code::Stats | [code-stats/code-stats](https://github.com/code-stats/code-stats) 45 stars, moved to GitLab | Low signal |
| Terminal tamagotchis | [ezeoleaf/termagotchi](https://github.com/ezeoleaf/termagotchi) 117, [C-GBL/sshb](https://github.com/C-GBL/sshb) 106 | Small |
| "octocat", "termi-pets" | unverified (no canonical repo found) | |

Reading: charm alone gets 100–5k stars; charm **attached to a tool people already run daily** (VS Code, Claude Code) gets 10k–2.6M. The proxy is the daily-run tool here, so the town rides on it rather than the reverse.

## 3. What the proxy can see (Messages API + Claude Code)

### Request body

- `system`: "optional string or array of TextBlockParam"; `tools`: "optional array of ToolUnion"; `messages`; `metadata.user_id` ([Messages API](https://platform.claude.com/docs/en/api/messages)). Claude Code sends `system` as an array with an attribution block first and `cache_control` markers "to `system` blocks and to `messages` entries" ([gateway guide](https://code.claude.com/docs/en/llm-gateway-protocol)). So yes: full system prompt and full tool definitions (`name`, `description`, `input_schema`) are in every request.
- Tool definition shape: `{"name", "description", "input_schema": {...}}`; Claude replies with `stop_reason: "tool_use"` and a block `{"type":"tool_use","id":"toolu_...","name":...,"input":{...}}`; the next request carries `{"type":"tool_result","tool_use_id":...,"content":...}` ([tool use overview](https://platform.claude.com/docs/en/agents-and-tools/tool-use/overview)).
- Claude Code built-in tool names and whether they prompt (from [tools reference](https://code.claude.com/docs/en/tools-reference)): Agent (no), Bash (yes), Edit (yes), Glob (no), Grep (no), Read (no), Write (yes), WebFetch (yes), WebSearch (yes), NotebookEdit (yes), PowerShell (yes), Monitor (yes), Skill (yes), Workflow (yes), TodoWrite/TaskCreate/TaskUpdate (no), SendMessage (no), plus ~30 others. Documented inputs: Read "takes a file path"; Edit "takes an `old_string` and a `new_string`"; Bash has `timeout`, `run_in_background`; Grep has `output_mode` (`files_with_matches`/`content`/`count`) and `glob`; Glob "`**` for recursive". The exact `input` JSON for Bash is shown in the [hooks doc](https://code.claude.com/docs/en/hooks): `{"command": "npm test", "description": "Run test suite", "timeout": 120000, "run_in_background": false}`.
- Thinking: blocks are `{"type":"thinking","thinking":"...","signature":"..."}`; `display` controls text: `"summarized"` returns a summary, `"omitted"` (default on Opus 5 / Sonnet 5 / Fable / Mythos) "returns thinking blocks with an empty `thinking` field"; "No `display` setting returns the raw chain of thought"; `redacted_thinking` is a separate block type ([thinking](https://platform.claude.com/docs/en/build-with-claude/thinking)). So a town can show *that* an agent is thinking (block open, `signature_delta`), rarely *what*.

### Response / streaming

- `usage`: `input_tokens` = "tokens which were not read from or used to create a cache (that is, tokens after the last cache breakpoint)"; `cache_creation_input_tokens`; `cache_read_input_tokens`; `output_tokens`; optional `cache_creation.ephemeral_5m_input_tokens` / `ephemeral_1h_input_tokens`; total input = sum of the three ([prompt caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching)). `usage.output_tokens_details.thinking_tokens` "appears only on the final `message_delta` event" when streaming ([extended thinking](https://platform.claude.com/docs/en/build-with-claude/extended-thinking)).
- Stream events: `message_start` (Message with `usage: {"input_tokens": 25, "output_tokens": 1}`), `content_block_start/delta/stop`, `message_delta`, `message_stop`, `ping`, `error`. "The token counts shown in the `usage` field of the `message_delta` event are *cumulative*." Example final delta: `"usage":{"input_tokens":10682,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"output_tokens":510,...}` ([streaming](https://platform.claude.com/docs/en/build-with-claude/streaming)).
- `stop_reason` values: `end_turn`, `max_tokens`, `stop_sequence`, `tool_use`, `pause_turn`, `refusal`, `model_context_window_exceeded` ([handling stop reasons](https://platform.claude.com/docs/en/api/handling-stop-reasons)).

### Headers and endpoints Claude Code sends to an `ANTHROPIC_BASE_URL` gateway

From the [gateway compatibility guide](https://code.claude.com/docs/en/llm-gateway-protocol):

- Endpoints: `POST /v1/messages?beta=true`, optional `/v1/messages/count_tokens`, best-effort `HEAD /api/hello`, optional `GET /v1/models?limit=1000` when discovery is enabled.
- `x-claude-code-session-id`: "Use it to aggregate all requests from one session without parsing request bodies". `x-claude-code-agent-id`: "present only on requests from an agent Claude Code spawned inside the session". `x-claude-code-parent-agent-id`: "present only for nested agents". "Subagent IDs are generated fresh for each spawn. Teammate agents ... reuse a stable name-based ID". "don't treat the agent ID header as a user identifier."
- Must forward unchanged: `anthropic-version`, `anthropic-beta` ("don't allowlist individual values"). Everything else the gateway "may consume".

### What a proxy must NOT do

- "A gateway that rewrites or redacts request bodies for content inspection breaks the pairing the same way stripping does, so **inspect without modifying**."
- "Forward the `system` array exactly as received, keeping the block first: prepending another system block, reordering the array, or converting it to a single string defeats the strip" (attribution block).
- "if your gateway buffers complete responses before relaying them, Claude Code stalls." Forward SSE `ping` events; Claude Code "aborts a stream that goes silent for 300 seconds by default".
- "forward error response bodies unmodified" (retry logic matches on wording).
- Forward `cache_control` unchanged or every turn bills uncached.

### Proxy vs hooks (why the proxy data source is different)

Hooks receive `session_id`, `tool_name`, `tool_input`, `tool_use_id`, `tool_response`, `transcript_path`, `cwd`, `agent_id`; "The documentation does not indicate that hooks receive token usage or API-level data" ([hooks](https://code.claude.com/docs/en/hooks)). The proxy additionally sees per-request `usage` (cache hit/miss), `stop_reason`, HTTP status (429/529/overloaded_error), stream timing and drops, thinking-block presence, the full tool list, and the model id. None of the §1 projects render those.

## 4. Rendering feasibility on the JVM

### Compose for Desktop (recommended for v1)

- Current: [Compose Multiplatform 1.12.0](https://github.com/JetBrains/compose-multiplatform/releases/tag/v1.12.0), published 2026-08-25; 19,352 stars. Skiko [v0.152.0](https://github.com/JetBrains/skiko/releases/tag/v0.152.0), 2026-09-10; 2,187 stars, Apache-2.0. Skiko = "the graphical library exposing significant part of Skia library APIs to Kotlin".
- Drawing API: `Canvas` "is a convenient wrapper around `Modifier.drawBehind`"; `DrawScope` exposes `drawImage(ImageBitmap)`, `drawRect`, etc.; "Use `drawWithCache` to cache created objects until the size of the drawing area changes" ([Compose graphics](https://developer.android.com/develop/ui/compose/graphics/draw/overview)).
- Game loop: `withFrameNanos` "Suspends until a new frame is requested, immediately invokes [onFrame] with the frame time in nanoseconds in the calling context of frame dispatch"; times are "strictly monotonically increasing" ([MonotonicFrameClock.kt](https://github.com/androidx/androidx/blob/androidx-main/compose/runtime/runtime/src/commonMain/kotlin/androidx/compose/runtime/MonotonicFrameClock.kt)). JetBrains' Sebastian Aigner built Asteroids this way: "In Compose for Desktop, we use `withFrameMillis` and `withFrameNanos`", and notes that a retro game "comes with the luxury of not having to think too hard about performance optimizations, allocations, entity-component systems" ([dev.to/kotlin](https://dev.to/kotlin/tips-tricks-for-building-a-game-using-jetpack-compose-for-desktop-266o)).
- Backends and fallback: DirectX 12 on Windows; "Execution environments not supporting DirectX 12 will gracefully fall back to an OpenGL-based renderer – and, if even that fails, to an all-new software renderer"; the software renderer is "significantly slower ... (up to 4 times slower)"; force with `SKIKO_RENDER_API="SOFTWARE"` ([JetBrains blog, M3](https://blog.jetbrains.com/kotlin/2021/02/jetpack-compose-for-desktop-milestone-3-released/)). Relevant because "must work without a GPU".
- Known issues (all closed, no fix version visible in page): [#4042](https://github.com/JetBrains/compose-multiplatform/issues/4042) Canvas at 2–5 fps on a 4K Windows 11 display (Dec 2023); [#4199](https://github.com/JetBrains/compose-multiplatform/issues/4199) SOFTWARE_FAST at 10–20 fps with 35 buttons at 1080p (Jan 2024); [#3543](https://github.com/JetBrains/compose-multiplatform/issues/3543) desktop perf research (Aug 2023, high priority). JetBrains ships a `CanvasDrawing` benchmark with VSYNC_EMULATION and REAL modes but [publishes no numbers](https://github.com/JetBrains/compose-multiplatform/blob/master/benchmarks/multiplatform/README.md).
- Verdict: 50–200 sprites at 60 fps on a GPU is plausible (one `Canvas`, one `withFrameNanos` loop, `drawImage` from a cached atlas, no per-entity composables). **No primary-source benchmark proves it**; the closed issues show 4K + software rendering is the risk. Budget an afternoon to measure with `SKIKO_RENDER_API=SOFTWARE` before committing to entity counts; degrade to 30 fps / fewer sprites there.

### KorGE

- [korlibs/korge](https://github.com/korlibs/korge): 3,046 stars; latest tagged release [v6.0.0](https://github.com/korlibs/korge/releases/tag/v6.0.0) 2025-05-16; commits continue (2026-08-26, "korlibs-7.0.0-SNAPSHOT"); README says namespace moved `com.soywiz.korge` → `org.korge` (2026-05-02).
- Maintenance history: author soywiz posted ["Leaving after 6.0 and Looking for Maintainers"](https://blog.korge.org/leaving-and-looking-for-maintainers/) (2025-04-30), then ["Transferring ownership to Jobe"](https://blog.korge.org/transferring-ownership-to-jobe/) (2025-12-31). ["Korge is alive"](https://blog.korge.org/korge-roadmap-2026/) (2026-06-05) is by Marko Koschak: "I want to do my best to maintain the Korge and Korlibs projects"; roadmap for 7.0 is Gradle/AGP upgrades, module consolidation, migration guide. Compose interop: not mentioned in any of these posts (unverified/none).
- Verdict: alive but one volunteer, 7.0 unreleased, mid-migration. Adds a second UI toolkit to a Compose app. Not for v1.

### libGDX (+ KTX)

- [libgdx/libgdx](https://github.com/libgdx/libgdx): 25,384 stars, Apache-2.0, [1.14.2](https://github.com/libgdx/libgdx/releases/tag/1.14.2) 2026-06-05. [libktx/ktx](https://github.com/libktx/ktx) 1,466 stars.
- Mature sprite batching, but LWJGL window + Compose window = two windows/contexts. Only worth it if a standalone "screensaver mode" app is wanted later.

### Skiko direct

- Same Skia as Compose, no Compose overhead; but Compose's `Canvas` already reaches Skia via `DrawScope`, so direct Skiko buys little unless profiling shows Compose overhead.

Recommendation: **Compose `Canvas` + `withFrameNanos` + one sprite atlas** (already in the stack; no new dependency). Revisit KorGE/libGDX only if measurement fails.

## 5. Data-driven vs LLM-narrated

- Smallville's believability came from the LLM architecture: "the components of our agent architecture--observation, planning, and reflection--each contribute critically to the believability of agent behavior" ([arXiv 2304.03442](https://arxiv.org/abs/2304.03442)). ai-town likewise needs an LLM (default `llama3` via Ollama) and vector memory. Those towns are interesting *because* the inhabitants improvise.
- But every real-activity visualizer with traction is deterministic: Gource (13k), Logstalgia, pixel-agents (9.3k, state machine), age-of-agents ("state machines drive settler behavior based on session events, not generative text"), agent-town (7 fixed statuses). Their interest comes from the data, not from generated dialogue.
- So a deterministic mapping (session → inhabitant, tool_use → action at a building, tokens → coins, cache_read → shortcut, 429 → traffic jam, stream drop → trip, `max_tokens`/`model_context_window_exceeded` → overloaded cart, `refusal` → shakes head, subagent header → child follows parent) is enough for v1 and is what shipped in every comparable project. The proxy's unique fields (cache, 429, stop_reason) are exactly the ones no competitor renders.
- Optional narration (v2), local, no GPU required:
  - Ollama exposes OpenAI-compatible `http://localhost:11434/v1/chat/completions` ("The api_key is required but ignored") ([docs](https://docs.ollama.com/api/openai-compatibility)); RTX 4060 is listed as supported (compute 8.9; needs driver 550+) ([GPU docs](https://docs.ollama.com/gpu)).
  - llama.cpp: `./llama-server -m model.gguf -c 2048`, listens on `127.0.0.1:8080`, serves `/v1/chat/completions`, `-ngl` / `--gpu-layers` (default `auto`) ([server README](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md)).
  - Models that fit 8 GB with room for context: [qwen3:4b](https://ollama.com/library/qwen3) 2.5 GB, [gemma3:4b](https://ollama.com/library/gemma3) 3.3 GB, [llama3.2:3b](https://ollama.com/library/llama3.2) 2.0 GB, qwen3:8b 5.2 GB (tight). Same endpoints run CPU-only, just slower, so the feature degrades rather than breaks.
  - JVM side: the proxy already has a Ktor HTTP client; one POST to `/v1/chat/completions` with a redacted event summary. No SDK needed.

## 6. Licensing and assets

- Kenney: "all game assets on the asset pages are public domain licensed (CC0). You're free to use them, even in commercial projects." "Attribution is not required" ([kenney.nl/support](https://kenney.nl/support)). [Tiny Town](https://kenney.nl/assets/tiny-town): 16×16, 130 tiles, "License: Creative Commons CC0" — a town tileset ready to use. Safe in Apache-2.0.
- Liberated Pixel Cup (LPC) character generator ([repo](https://github.com/LiberatedPixelCup/Universal-LPC-Spritesheet-Character-Generator), 1,735 stars): each piece is "licensed under one or more of" CC0, CC-BY-SA 4.0, CC-BY 4.0, OGA-BY 3.0, GPL 3.0; "Users must credit all authors (except CC0 works)"; generator exports a credits CSV. Usable, but you inherit per-asset attribution and, for CC-BY-SA pieces, ShareAlike on *derived art*.
- OpenGameArt FAQ ([link](https://opengameart.org/content/faq)): "the game code and game media are separate entities and do not need to be released under the same license"; for GPL art in non-GPL games "guidance specific to video game art scenarios remains limited". Creative Commons FAQ: CC "recommends against applying their licenses to software"; a *collection* keeps per-item licenses, an *adaptation* triggers ShareAlike ([CC FAQ](https://creativecommons.org/faq/)).
- Not compatible / avoid: GPL-licensed sprites (ambiguous in an Apache project; avoid), CC-BY-NC or NC/ND variants (OGA does not accept them, but they exist elsewhere), and any "all rights reserved" mascot art (clawd-on-desk ships AGPL code with proprietary characters — a model of what *not* to copy).
- Practical rule: Kenney CC0 for tiles/buildings; if characters come from LPC, pick CC0/CC-BY/OGA-BY pieces only and ship `CREDITS.txt`.

## 7. Leak risk in screenshots

- The town's inputs are exactly the sensitive parts: `tool_input.command`, `tool_input.file_path`, `old_string`/`new_string`, `tool_result` contents, and prompts. Comparable tools acknowledge this: claude-office event payloads "carry tool I/O and file paths" and gate non-localhost backends behind `CLAUDE_OFFICE_ALLOW_REMOTE=1`; agent-virtual-office keeps "raw capture ... local and is never committed" with sanitization scripts. Neither redacts in the visual layer.
- Anthropic's own default is redaction-by-default: OTel `tool_result` events carry `tool_name`, `tool_use_id`, `success`, `duration_ms`, `error_type`, `tool_input_size_bytes` only; `tool_input`/`tool_parameters`/Bash commands appear only with `OTEL_LOG_TOOL_DETAILS=1`; prompts only with `OTEL_LOG_USER_PROMPTS=1`; full bodies only with `OTEL_LOG_RAW_API_BODIES` ([monitoring](https://code.claude.com/docs/en/monitoring-usage)). Metrics "never include your code, prompts, or file paths"; error reports redact "secrets, file paths, email addresses" ([data usage](https://code.claude.com/docs/en/data-usage)).
- Gource: `--hide filenames,dirnames,usernames,...` exists for exactly this reason ([controls](https://github.com/acaudwell/Gource/wiki/Controls)).
- Design rule that follows: the town renders **tool names, counts, sizes, timings, stop reasons, statuses** by default (the same tier Anthropic logs by default) and never renders `tool_input` strings, prompts, or results. If the developer wants labels, show the file *extension* or basename and require an explicit `showPaths` toggle that is off by default and visibly badged so screenshots taken with it on are self-evidently unsafe. Narration prompts to a local model must be built from the same redacted tier.

## Recommendation

- Metaphor: a **town fed by the proxy's own grammar** — session → inhabitant, subagent (agent-id / parent-agent-id headers) → child following parent, tool_use → walk to a building (Read=library, Edit/Write=workshop, Bash=forge, Grep/Glob=lookout tower, Agent=town hall, WebFetch=harbor), `cache_read_input_tokens` → takes the shortcut, `cache_creation` → paves a new road, 429/529 → traffic jam at the gate, stream drop → trips and gets back up, `stop_reason=end_turn` → goes home, `max_tokens`/`model_context_window_exceeded` → cart overflows, `refusal` → shakes head, thinking block open → thought cloud. Every one of these is a field competitors cannot see (§3) and is zero-cost to compute (§5). age-of-agents already proves the settlers/workshops half works visually; the proxy-only half is the differentiator and the screenshot.
- Rendering: Compose `Canvas` + `withFrameNanos` + one CC0 Kenney Tiny Town atlas. Measure with `SKIKO_RENDER_API=SOFTWARE` first; cap sprites if needed. No new dependency.
- Redaction: names, counts, statuses only, by default (§7).
- Narration: v2, optional, Ollama `/v1/chat/completions` from Ktor, 4B model, prompt built from the redacted tier.
- v1 or v2 for 10 h/week: the deterministic town is **v1-adjacent but not v1**. Ship cassettes/stream-resume first, log the event grammar (one JSON line per request with the ~12 fields above) from day one so the town is a pure consumer. The town itself is a 2–4 week side project once the event log exists; if it takes longer, cut buildings, not the event log.

## Could not verify

- Whether any hook/transcript-based visualizer *also* taps `ANTHROPIC_BASE_URL`: none found in GitHub search; absence, not proof.
- Smallville frontend renderer (Phaser is commonly stated; README does not say).
- Project Sid: whether any simulation code is public beyond the report.
- Any published fps/entity-count benchmark for Compose Desktop `Canvas` (JetBrains benchmark exists, no numbers published). Fix versions for issues #4042/#4199/#3543 (closed, resolution not shown).
- KorGE Compose interop (no mention anywhere primary). Discrepancy between "ownership to Jobe" (Dec 2025) and "Marko Koschak took over in January 2026" (Jun 2026) is as stated by the blog; not reconciled.
- pixel-agents GitHub star count taken from the README page (9.3k), not the API.
- "octocat", "termi-pets", "tamagotchi dev tools" beyond termagotchi/sshb; Code::Stats current stars (moved to GitLab).
- Exact `input_schema` JSON for Claude Code's Read/Edit/Write/Grep/Glob/Agent tools (docs describe parameters in prose; Bash is the only full example). The proxy will see them verbatim in `tools[]` on the first request, so log one.
- Kenney's per-asset license file text (site 404s on `/data/license`; support page and asset page both say CC0).
- AI Village live-UI specifics (page is JS-rendered; description taken from the Hugging Face dataset card).
