# Peashoot — project brief

Status: shared understanding, confirmed through a grilling session on 2026-09-12. This is the decision record the design spec builds on. Research behind every claim lives in `../../research/` (six files; move into this repo when it is initialized).

Name origin: the author's daughter's nickname. The garden metaphor follows from the name.

## What it is

A language-agnostic proxy for LLM traffic, written in Kotlin with Ktor, shipped as one JAR with embedded SQLite, plus a separate Compose for Desktop app that is both dashboard and control plane. Developers building or running agents point any client at it (Claude Code first, then Codex, OpenCode, and any OpenAI- or Anthropic-compatible SDK). It records every model call into portable cassettes, replays them deterministically for tests and CI, resumes dropped streams so partial output is never re-billed, and shows the whole thing as a garden that grows as agents work.

## Who it is for, and the evidence

Developers building agents, in any language. Validation gate: public primary-source evidence only (research files), then the author runs Claude Code through it daily for two weeks, then three strangers file issues.

Key evidence (details and citations in `research/`):
- Record/replay: OpenAI Agents maintainers declined to build it (2026-09-05); Claude's plugin evals cost real model calls; six prior attempts at 0–30 stars, none records Anthropic streaming faithfully.
- Stream-resume: claude-code issue with 168 upvotes / 184 comments, open; SDK issues confirm retries re-bill partial output; no proxy solves it.
- Fault injection (v2): claude-code issue with 116 upvotes; Toxiproxy is TCP-only.
- Charm layer: pixel worlds fed by Claude Code hooks already have 9.3k and 6.2k stars, so the category is proven; none is fed from the proxy layer, none sees cost/cache/429/stream drops, none works for clients without hooks; sonification of agent traffic has zero prior work.

## Decisions

Product
- Audience: developers building agents, language-agnostic via existing protocols. Not a JVM-only library.
- Novelty bar: not a commodity category; niche prior art is fine.
- Adoption over novelty when they conflict.
- v1 sits on stable HTTP surfaces (Anthropic Messages, OpenAI). MCP waits for v2.
- Vendor-neutral core; Claude Code is the first-class demo integration.
- Multi-client: Codex and OpenCode support is in v1. Exact wire APIs per client come from `research/client-compat-matrix.md` (pending at time of writing; the decision that they are in v1 is not pending).

Scope, v1 (target about five months at ~10 h/week; public milestone at three months)
1. Faithful capture of streaming traffic, Anthropic Messages first.
2. Record/replay from portable JSONL cassettes. Matching normalized by default with a visible, editable rule set; exact matching opt-in. Miss behavior: pass through and append by default; strict fail behind a flag for CI.
3. One-line JSONL event per request emitted from day one (session, agent, parent agent, client, model, tool name, path, tokens in/out, cache read/write, stop reason, status, latency, replay hit). Gource-compatible output as one formatter flag.
4. The garden as the desktop app's main view, a pure consumer of the event stream. Timeline is a detail pane reached by clicking a gardener. Control plane: mode switch per route, match-rule editor, cassette export.
5. OpenAI surfaces needed for Codex and OpenCode.
6. Stream-resume: proxy finishes the upstream read on client disconnect and serves the buffered completion to the re-issued request. Moves to v1.5 if v1 is behind.

Three-month public milestone: items 1–3 working end to end with Claude Code, plus the Gource GIF in the README.

Scope, v1.5
- Sonification: sample bank (pre-rendered clips, not the MIDI synth), mapping table, off by default.
- Optional hook adapter (Claude Code hooks first) feeding the same event stream for signals the proxy cannot see. The proxy stays the only required source.
- Stream-resume if it slipped from v1.

Scope, v2
- Fault injection on the same chassis (provider-faithful 429/529/mid-stream errors, truncated streams, stalls, malformed tool JSON), scenarios recorded and replayable.
- Optional local-model narration (speech bubbles) via Ollama's OpenAI-compatible endpoint. Local only, never a second cloud provider.
- MCP surfaces.

Scope, v3
- Diff two runs and fork from step N (borrow git range-diff's cost-matrix pairing for tool-call sequences).
- Regression assertions with CI thresholds and JUnit XML output.

Later, optional
- Token-aware fair scheduler and budgets (first research pass, gap 1).
- JVM runtime cockpit MCP server (JFR, heap, GC) as a side module.

Tech
- Kotlin, Ktor client and server (a stream is one Flow: upstream → store → client).
- Embedded SQLite via JDBC; large bodies spilled to files.
- Desktop app is a separate process talking to the headless JAR over a localhost control API with a bearer token; it can launch the JAR if not running.
- Compose for Desktop; UI code in a common module so Android can be added later. Garden rendering: Compose Canvas + withFrameNanos + one sprite atlas; measure with SKIKO_RENDER_API=SOFTWARE before fixing entity counts. Art: Kenney CC0 tilesets; no GPL art.
- Local models on the RTX 4060 (8 GB) are a free test backend only; the product never assumes a GPU.

Rules with no exceptions
- The auth header is never stored, not even locally.
- Bodies and error bodies are forwarded unmodified (Claude Code's gateway docs require it); the proxy inspects, never rewrites.
- Do not buffer whole responses before relaying; forward pings; Claude Code aborts silent streams at 300 s.
- Body redaction runs at cassette export with a preview.
- Garden labels (file paths, commands) are off by default; a badged "show paths" toggle marks screenshots.

Posture
- Apache-2.0, CI from the first commit, solo until v1.
- API spend under 50 USD/month during development.

## The garden mapping (v1 draft)

| Traffic event | In the garden |
|---|---|
| Session / sub-agent | Gardener / helper |
| Directory / file (from tool-call paths) | Bed / plant |
| Request to the API | Trip to the well |
| Tokens spent (cost) | Water carried (water bill) |
| Cache read | Rain |
| 429 / 529 | Queue at the well |
| Dropped stream | Spilled bucket |
| Replay | Same seeds, same shoots |
| Fault injection (v2) | Weather: frost, hail |
| Stop reason / result size | How tall the shoot grew |

## First spikes (before UI work)
1. Is Claude Code's re-issued request after a stream drop byte-identical? Stream-resume matching depends on it. Unverified by any research pass.
2. Compose Canvas frame rate with ~200 sprites on this laptop, GPU and SOFTWARE fallback.

## Next steps
1. Fold in `client-compat-matrix.md` when it lands (wire APIs, base-URL overrides, timeouts per client).
2. Design pass: capture pipeline, cassette format and matching rules, event-line schema, control API, garden renderer boundaries. Written spec in `docs/superpowers/specs/`.
3. Implementation plan from the spec.
