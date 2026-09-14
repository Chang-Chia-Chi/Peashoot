# Peashoot v1 design

Date: 2026-09-12. Status: approved section by section in a design session; this document is the written form. The decision record behind it is `docs/brief.md`; the evidence is `docs/research/`.

## 1. Purpose and scope

Peashoot is a language-agnostic proxy for LLM traffic plus a desktop app. Any agent client (Claude Code first; Codex and OpenCode in v1; any Anthropic- or OpenAI-compatible SDK) points at it. It records every model call into portable cassettes, replays them deterministically for tests and CI, resumes dropped streams so partial output is not re-billed, emits one event line per request that other tools can consume, and renders the traffic as a farm that grows as agents work.

v1 delivers, in this cut order (last item slips first):
1. Faithful capture of streaming traffic, Anthropic Messages first.
2. Record/replay from portable JSONL cassettes with normalized matching.
3. The event line, `events.jsonl`, and a Gource-compatible formatter.
4. The desktop app with the farm as its main view and the control plane.
5. OpenAI Responses and Chat Completions surfaces (Codex, OpenCode, Cline/Roo, Aider).
6. Stream-resume on both stories.

Non-goals for v1: MCP surfaces, fault injection, run diffing, regression assertions, narration, sonification, hook adapters, multi-node deployment, any GPU requirement. These are v1.5 to v3 and are listed in the brief.

## 2. Architecture overview

Two processes:
- `peashoot.jar`: a Ktor server on `localhost:8787` serving the proxy surfaces and, under `/_peashoot/v1/`, the control API. Headless; complete without any UI.
- `peashoot-app`: a Compose for Desktop app that talks only to the control API. If nothing answers on the port it launches the JAR as a child process.

Inside the proxy, every request becomes an Exchange that passes through an ordered interceptor chain (approach C from the design session). Interceptors can respond to a request (Resume, Replay), observe frames (Recorder, Deriver), or, in v2, transform them (Chaos). If no interceptor responds, the upstream provider is the terminal source. Responses flow as `Frame(rawText, offsetMillis)` through one shared Flow with three sinks: the client writer, the recorder, and the event deriver.

Domain vocabulary in the proxy and store is plain: Exchange, Frame, Route, Mode, Rule, Cassette, Event, Surface, Client. Farm vocabulary exists only inside the app's reducer and renderer.

## 3. Runtime topology, data directory, configuration

Data directory `~/.peashoot/` (override with `PEASHOOT_HOME`):
- `peashoot.db`: SQLite, the store of record.
- `bodies/`: content-addressed files (SHA-256 name) for request bodies and frame lists over 64 KB; smaller ones are inline blobs in the database.
- `cassettes/`: exported JSONL cassettes.
- `events.jsonl`: append-only event lines.
- `gource.log`: written only when the Gource formatter is enabled.
- `rules.json`: the active matching rule set, copied from the bundled default on first start.
- `token`: control API bearer token, generated on first start, owner-only file permissions. The app reads it from this file; there is no pairing flow.
- `peashoot.toml`: configuration.

Configuration keys (TOML, with `PEASHOOT_`-prefixed environment overrides for the ones CI needs):
- `port` (default 8787), `bind` (default 127.0.0.1, not changeable to a non-loopback address in v1).
- `surfaces.anthropic.upstream` (default `https://api.anthropic.com`), `surfaces.openai.upstream` (default `https://api.openai.com`). Pointing the OpenAI surfaces at Ollama's OpenAI-compatible endpoint is the free local test setup.
- `routes.<name>.mode` in `record | replay | passthrough`, `routes.<name>.strict` (bool), `routes.<name>.cassette` (name). Default route per surface, mode `record`.
- `resume.windowSeconds` (default 300), `resume.maxBufferedExchanges` (default 100), `resume.pingIntervalSeconds` (default 15; SSE comment lines emitted while an upstream is silent, so byte-counting watchdogs stay alive).
- `replay.cadence` in `instant | recorded` (default `instant`), `replay.repeatPolicy` in `inOrder | latest` (default `inOrder`).
- `secretHeaders`: list, default `authorization`, `x-api-key`. Never stored.
- `pricing`: overrides for the bundled per-model price table.
- `gource.enabled` (default false).
- `idleSessionMinutes` (default 10; used by the app's end-of-day card).

Claude Code needs exactly `ANTHROPIC_BASE_URL=http://localhost:8787`. Its saved claude.ai login keeps working because `anthropic-beta` and all other headers are forwarded verbatim.

## 4. The Exchange lifecycle

1. Receive. Any request with an HTTP upgrade header is refused with 426 and a plain body, so Codex falls back to HTTP. Otherwise the full request body is read (requests are small; only responses stream). Headers are captured minus the secret list, which is retained in memory only for the upstream call. The body is parsed as JSON once.
2. Classify. The surface is chosen by path. The client is detected from headers (section 9). The route and its mode are resolved. The rule set is applied to produce the normalized request; its fingerprint is computed.
3. Decide the source, by running `onRequest` down the chain in order: Resume, Replay, Recorder, Deriver. The first non-null source wins. Resume responds when an in-flight or recently completed exchange with the same fingerprint sits inside the resume window. Replay responds on a cassette hit; on a miss it returns the strict error (section 6) when the route is strict, otherwise nothing. Recorder and Deriver never answer. If nothing responded, the upstream client opens the provider call with headers forwarded verbatim except hop-by-hop headers, `host`, `accept-encoding` (stripped so responses are never compressed), and the secret headers, which are sent but never logged.
4. Stream. The source yields frames. Non-streaming responses are a single frame holding the whole body. `onFrames` wraps the Flow in chain order. Three sinks consume the shared Flow: the client writer (writes each frame's bytes as it arrives, never buffers a whole response, forwards ping and error events unchanged, forwards response headers verbatim), the recorder (appends frames to the exchange buffer, persists on completion), and the deriver (parses frames incrementally and emits `exchange.completed` when the terminal frame arrives).
5. Client gone. If the client disconnects mid-stream in `record` or `passthrough` mode, the client sink detaches; the recorder and deriver keep consuming to completion. The exchange is flagged `clientDisconnected`, `exchange.client_gone` is emitted, and the exchange stays eligible for Resume until the window expires.
6. Complete. `onComplete` runs down the chain with the outcome (status, usage, stop reason, timings), exactly once for every exchange the proxy answered, from a source (step 4) or with a proxy failure (step 7), whether or not the client stayed to hear it.
7. Upstream errors. Status and body are forwarded verbatim and recorded like any exchange, so a cassette can replay a 429. Proxy-side failures (upstream unreachable, TLS error) return 502 with a JSON body whose `type` is `peashoot_error`, never imitating a provider's error shape, because Claude Code's retry logic matches on provider wording. An upstream body the frame path cannot carry is refused the same way, before the response starts: a non-text body (anything but `text/*`, `application/json`, or `application/*+json`, in UTF-8 or with no charset declared) returns 502 `unsupported_content_type` from the declared content-type alone, never truncated into a 200. See ADR 0001, `docs/adr/0001-text-frames.md`.

## 5. Interceptor contract

```kotlin
interface Interceptor {
    suspend fun onRequest(ctx: Exchange): FrameSource?             // null means nothing to offer
    fun onFrames(ctx: Exchange, frames: Flow<Frame>): Flow<Frame>  // observe or transform
    suspend fun onComplete(ctx: Exchange, outcome: Outcome)
    suspend fun onClientGone(ctx: Exchange)
}
interface FrameSource { val status: Int; val headers: Headers; fun frames(): Flow<Frame> }
data class Frame(val raw: String, val offsetMillis: Long)
```

`Exchange` carries: id (ULID), receivedAt, surface, client (type, sessionId, agentId, parentAgentId), route, mode, request (method, path, headers, rawBody, json, normalizedJson, fingerprint), response (status, headers, set by the source), flags (replayHit, resumed, clientDisconnected), and timings (firstByteAt, completedAt).

v1 chain order: Resume, Replay, Recorder, Deriver. v2's Chaos goes between Replay and Recorder so injected faults are recorded like real ones. No fifth hook until v2 needs one.

## 6. Store, cassettes, matching

Store. Table `exchange` with: id, fingerprint, surface, client fields, route, mode, request headers (JSON, secrets already absent), request body (inline or body ref), response status, response headers (JSON), frames (inline or ref), usage (JSON), stop reason, timings, the three flags, cassette name (null for live recordings). Table `event` mirrors the event line for queries. Table `session` aggregates counts, tokens, cost, last seen. Indexes on fingerprint, session, receivedAt.

Cassette file. JSONL, one record per exchange:

```json
{"v":1,"fingerprint":"...",
 "request":{"method":"POST","path":"/v1/messages","headers":{"content-type":"application/json"},"body":{}},
 "response":{"status":200,"headers":{},"frames":[{"t":0,"raw":"event: message_start\ndata: {}\n\n"}]},
 "meta":{"client":"claude-code","model":"...","usage":{},"stopReason":"end_turn","recordedAt":"2026-09-12T10:00:00Z"}}
```

Non-streaming responses carry `"body"` instead of `"frames"`. Export applies the redaction rules and returns a preview first (`dryRun`). Import inserts records tagged with the cassette name. Secret headers were dropped at capture, so no export can contain them.

Matching. Normalized request = rules applied to (method, path, kept headers, JSON body). Fingerprint = SHA-256 over canonical JSON (sorted keys, no insignificant whitespace, numbers as written). Three rule types, stored in `rules.json`:
- `keepHeaders`: allowlist. Default: `content-type`, `anthropic-version`, `anthropic-beta`, `openai-beta`. All attribution headers are therefore ignored.
- `ignorePointers`: RFC 6901 pointers with `*` matching any array index. Default: `/metadata`, `/stream`.
- `replace`: `{pointer, pattern, replacement}` applied to string values. Default: one rule on `/system/*/text` that removes Claude Code's machine-context block (working directory, platform, shell, OS version), with the pattern visible and editable.

Exact mode is the empty rule set. `POST /rules/test` fingerprints the last N exchanges under a candidate rule set and reports collisions and splits before saving.

Repeats. Several recordings sharing a fingerprint are served in recorded order via a per-fingerprint cursor that resets per proxy start (`inOrder`), so retry sequences replay faithfully. `latest` serves the newest.

Strict miss. Replay on a strict route with no hit returns 409 with body `{"type":"peashoot_error","error":"replay_miss","fingerprint":"...","route":"..."}`; lenient routes fall back to upstream and append the recording.

Responses API state. Recorded response ids are served verbatim on replay, and `previous_response_id` stays in the fingerprint untouched, so chained Responses calls replay without id mapping.

## 7. The event line

JSONL, one object per event, written to `events.jsonl`, the `event` table, and the control API SSE feed.

- `exchange.started`: `ts, event, exchangeId, session, agent, parentAgent, client, surface, model, route, mode, toolResults:[{name, bytes}]`.
- `exchange.completed`: all of the above plus `tools:[{name, path?, command?}]` (from tool-use blocks in the response), `usage:{input, output, cacheRead, cacheWrite}`, `costUsd` (null for OAuth subscription traffic), `stopReason, status, firstByteMs, latencyMs, replayHit, resumed, clientDisconnected, rateLimit:{remainingTokens?, remainingRequests?, resetAt?}` from provider headers when present. `replayHit` and `resumed` appear with replay (#12) and resume (#26); `model` on completed is the response's when it named one, resolving an alias in the request.
- `exchange.client_gone`: `ts, event, exchangeId, session, bytesSoFar`.

Cost comes from a bundled per-model price table (input, output, cache read, cache write per million tokens), overridable in config.

Gource formatter (`gource.enabled`): one line per tool in `exchange.completed`, `timestamp|user|type|file|colour`, user = session short id, Read/Grep/Glob as `A`, Edit/Write as `M`, colour by tool name.

Paths appear in the event line and Gource output; both are local data. The labels-off rule applies to what the app draws.

## 8. Control API

Base `/_peashoot/v1/`, loopback only, bearer token from the `token` file on every call except `GET /health`.

- `GET /health`: version, uptime, routes and their modes.
- `GET /events`: SSE feed of event lines; `?since=<eventId>` backfills.
- `GET /exchanges?session=&client=&cursor=&limit=`; `GET /exchanges/{id}` (`?frames=true` includes frames).
- `GET /sessions`: per session and agent aggregates.
- `GET /routes`; `PUT /routes/{name}` body `{mode, strict, cassette}`.
- `GET /rules`; `PUT /rules` (validated, whole replacement); `POST /rules/test` body `{rules, lastN}`.
- `GET /cassettes`; `POST /cassettes/export` body `{name, sessionIds?, exchangeIds?}` with `?dryRun=true` returning the redaction preview; `POST /cassettes/import` body `{path}`.
- `GET /config`; `PUT /config` (fields needing restart are flagged in the response); `POST /shutdown`.

Errors are JSON problem objects `{type, title, detail, status}`. The app never opens the database file.

## 9. Surfaces, headers, client detection

Surfaces (each a `Surface` adapter: request shape, frame grammar, extraction into the event line, resume story):
- Anthropic Messages: `POST /v1/messages`; passthrough for `POST /v1/messages/count_tokens`, `GET /v1/models`, and `HEAD /api/hello` (answered 200 locally). Frame grammar: SSE events `message_start, content_block_start, content_block_delta, content_block_stop, message_delta, message_stop, ping, error`. Resume: buffered completion (section 4, step 5), matched by fingerprint within the window. The Files API is not a v1 surface: its content download is a non-text body, so `GET /v1/files/{id}/content` is refused with 502 `unsupported_content_type` (ADR 0001, `docs/adr/0001-text-frames.md`).
- OpenAI Responses: `POST /v1/responses`; `GET /v1/responses/{id}` including `?stream=true&starting_after=N`; `POST /v1/responses/{id}/cancel`. Frame grammar: SSE events carrying `sequence_number`. Resume: the API's own sequence numbers; background mode passes through; the proxy also serves `starting_after` from its own buffer when the upstream response was not created in background mode.
- OpenAI Chat Completions: `POST /v1/chat/completions`; `data:` chunks terminated by `data: [DONE]`. Resume: buffered completion.
- `GET /v1/models` on the OpenAI surfaces passes through.

Headers. Request: forward verbatim minus hop-by-hop, `host`, `accept-encoding`, plus secret headers used but never stored. Response: forward verbatim minus hop-by-hop, which is what makes Codex's sticky `x-codex-turn-state` round-trip.

Client detection, in order: an `x-peashoot-session` header wins outright and is honored for any client; then `x-claude-code-session-id` present means Claude Code (agent and parent from the sibling headers); `originator: codex_cli_rs` means Codex (session from `session-id`, thread from `thread-id`); `x-stainless-*` headers mean an official SDK (language from `x-stainless-lang`); otherwise the user-agent prefix, else `unknown`. Session identity is the client's header when present; otherwise `client type + SHA-256 of the first user message` groups a conversation's turns. The docs ship a three-line OpenCode plugin using its `chat.headers` hook to inject `x-peashoot-session`, which the proxy honors for any client.

## 10. The desktop app and the farm

Structure. `ControlClient` (SSE plus REST, reconnects with `since`) feeds a pure `FarmReducer: (FarmState, Event) -> FarmState`. Compose renders `FarmState`. An `Animator` interpolates entity positions between states each frame with `withFrameNanos`. The reducer has no Compose dependency and is tested with recorded event files.

FarmState: villagers (id, name, parent, position target, stamina, activity: idle | walkingToWell | waitingAtWell | resting | returning), fields and crops (path hash, growth stage, last touched, label hidden unless toggled), well queue, weather (clear | rain | storm | lightning), time (real season by month; day/night by mode: replay is night), shipping bin (produce count, cost ledger), pending end-of-day cards.

Mapping (from the approved design): session to villager, sub-agent to helper walking beside the parent; directory to field, file to crop; first Write to an unseen path plants, each Edit advances a growth stage, a Read sends the villager to inspect; request in flight is a walk to the well and a wait; output tokens are water carried back; a cache read is rain that shortens the wait; rate-limit remaining is the stamina bar; a 429 is the villager resting at the well; 529 or overloaded is a storm; a dropped stream is lightning and a spilled bucket; a completed turn drops produce in the shipping bin whose ledger is cost; a session idle for `idleSessionMinutes` ends its day with a card (tokens, cost, files touched, cache-hit rate); replay mode is night with lanterns; the calendar month picks the season palette. Villager names come from a fixed name list indexed by session id hash. Cut for v1: animals, relationships, festivals, shop.

Panes. Click a villager for their exchange timeline (usage, cost, latency, replay flags). Click a crop for its touch history. Labels off until the badged "show paths" toggle, which draws a visible badge on the canvas so screenshots are marked.

Control plane. Route mode switch, rules editor with the test-before-save view, cassette export dialog with redaction preview, health and config.

Rendering. Compose Canvas, one sprite atlas from Kenney CC0 sets, target 60 fps with 200 entities. Degrade under 30 fps: villagers teleport instead of walking.

## 11. Testing

- Unit (`core`): rules and fingerprint golden cases (attribution headers and machine-context block ignored; prompt change changes the hash); frame parsers for all three grammars against redacted fixture streams captured from the real providers; the farm reducer against recorded event files with expected states.
- Integration (`proxy`): a fake upstream (Ktor test server) replaying fixture SSE with controllable chunk timing, injected drops, and error statuses. Asserts: byte-equal frames on the client side; the recorder persisted the exchange; a replay hit serves recorded frames with zero upstream calls; a strict miss returns the 409 shape; resume serves the buffered completion after a client disconnect and re-issue, with exactly one upstream call. This fake upstream is the v2 chaos test bed.
- Live smoke (manual workflow): tiny prompts against the real Anthropic and OpenAI APIs to catch grammar drift, under the monthly budget. Checklist for Claude Code and Codex through the proxy: streaming, tool calls, no watchdog trips, retry paths.
- Performance: only spike 2's frame-rate measurement.

## 12. Modules, build, CI, packaging

Build tool: Gradle with the Kotlin DSL and a version catalog. The author's default is Maven, and Maven was checked on 2026-09-12: the Compose Desktop artifacts and Skiko runtimes are plain Maven artifacts and the compiler ships as `kotlin-compose-compiler-plugin-embeddable`, but there is no official `kotlin-maven-compose` plugin, so the wiring would be an undocumented `-Xplugin` argument and packaging a third-party plugin. Gradle is the only path JetBrains supports and tests: the Compose Gradle plugin applies the compiler, selects the native Skiko runtime per OS, and provides the jpackage tasks. Decision: Gradle for all modules, so one build tool.

Modules: `core` (models, rules, fingerprint, event line, surface grammars; no server dependency), `proxy` (Ktor server, interceptors, SQLite store, control API; produces the fat `peashoot.jar` via the Shadow plugin), `app` (Compose Desktop, control client, reducer, renderer; the reducer lives in a non-UI package whose tests need no Compose runtime). Every version pinned in the catalog. Gradle wrapper committed, since nothing is on the author's PATH.

Toolchain: JDK 21 LTS, Kotlin 2.4.x, Ktor 3.x, kotlinx.serialization, sqlite-jdbc, Compose Multiplatform 1.12 stable line.

CI: GitHub Actions on every push, build and tests on Ubuntu and Windows, fat JAR as an artifact on every run, jpackage installers on tags only, live smoke manual.

Repo layout: `docs/` (brief, specs, research), `core/`, `proxy/`, `app/`, `.github/workflows/`, `LICENSE` (Apache-2.0), `NOTICE` crediting Kenney.

## 13. Milestones and spikes

| Milestone | Weeks | Delivers |
|---|---|---|
| M0 spikes | 1 | (1) Is Claude Code's re-issued request after a stream drop byte-identical? (2) Compose Canvas frame rate with 200 sprites on the author's laptop, GPU and `SKIKO_RENDER_API=SOFTWARE` |
| M1 capture | 2 to 6 | Messages surface, record and passthrough, store, event line, `events.jsonl`, Gource formatter, Claude Code smoke, first README GIF |
| M2 replay | 7 to 10 | Replay, rules, cassettes with export and import, control API, strict mode, CI replay demo. Three-month public milestone |
| M3 farm | 11 to 16 | The app: control client, reducer, renderer, panes, control plane |
| M4 clients | 17 to 20 | Responses and Chat surfaces, Codex and OpenCode smoke, stream-resume on both stories |
| M5 release | 21 to 22 | jpackage installers, README, docs, v1 tag |

Resume always matches on the normalized fingerprint. Spike 1 decides whether a re-issued request differs in any field the default rules do not already ignore; if it does, the default rule set gains a rule for that field. The design does not change.

## 14. Rules with no exceptions

- Secret headers are never stored, not even locally.
- Bodies and error bodies are forwarded unmodified; the proxy inspects, never rewrites. A body the proxy cannot carry is refused, never truncated.
- Whole responses are never buffered before relaying; pings are forwarded; silent upstreams get SSE comment pings so Claude Code's 300-second watchdog is not tripped by the proxy.
- Body redaction runs at cassette export with a preview.
- Farm labels are off by default; the "show paths" toggle draws a visible badge.
- The control API binds to loopback only in v1.
