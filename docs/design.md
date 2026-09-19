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
- `redact.json`: the redaction rules cassette export applies, written from the bundled default on first start (section 6).
- `token`: control API bearer token, generated on first start, owner-only file permissions. The app reads it from this file; there is no pairing flow.
- `peashoot.toml`: configuration.

Configuration keys (TOML, with `PEASHOOT_`-prefixed environment overrides for the ones CI needs):
- `port` (default 8787), refused unless it is a port a socket takes (0 to 65535, where 0 asks for a free one), so a number that would only fail at bind fails at load instead; `host` (`PEASHOOT_HOST`, default 127.0.0.1). Anything that does not resolve to loopback addresses only is refused at startup, by the loader and by the server for a config built in code.
- `surfaces.anthropic.upstream` (default `https://api.anthropic.com`), `surfaces.openai.upstream` (default `https://api.openai.com`). Pointing the OpenAI surfaces at Ollama's OpenAI-compatible endpoint is the free local test setup.
- `routes.<name>.mode` in `record | replay | passthrough`, `routes.<name>.strict` (bool), `routes.<name>.cassette` (name). Default route per surface, mode `record`. The environment sets the default route: `PEASHOOT_MODE`, `PEASHOOT_STRICT` (`true | false`), and `PEASHOOT_CASSETTE` (a cassette file, imported on start under its base name, which becomes the route's cassette). `PEASHOOT_PORT` and `PEASHOOT_ANTHROPIC_UPSTREAM` set the port and upstream. An invalid value stops the proxy with a message naming the variable.
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
5. Client gone. If the client disconnects mid-stream in `record` or `passthrough` mode, the client sink detaches; the recorder and deriver keep consuming to completion. The exchange is flagged `clientDisconnected`, `exchange.client_gone` is emitted, and the exchange stays eligible for Resume until the window expires. A departure no failing write can report — the client leaving before the first byte, or a response written in one burst the engine discards silently — is heard by asking the engine's own channel instead (#51, #54). The client sink asks once before every wait, which costs a volatile read, so the answer never depends on a write failing or on a silence falling; reaching the channel is why the relay unwraps the routing call to the engine's. Hearing it only marks the exchange: the consuming never stops, so the recording stays whole for Resume. Two edges are accepted in exchange. A call that never starts a stream, an unreachable upstream or a refusal, has no sink to ask and drops the departure. And a client that closes on the response's last frame, while the drive still waits for the upstream body to end, is flagged as gone though it received everything: mid-stream is the drive's end, not the last frame's, and a closed channel cannot say which of the two it was.
6. Complete. `onComplete` runs down the chain with the outcome (status, usage, stop reason, timings), exactly once and last for every exchange that heard `onRequest` (step 3), from a source (step 4), with a proxy failure (step 7), or with no answer at all, where the status is null because the proxy stopped before one existed and 500 because the relay failed; whether or not the client stayed to hear it.
7. Upstream errors. Status and body are forwarded verbatim and recorded like any exchange, so a cassette can replay a 429. Proxy-side failures (upstream unreachable, TLS error) return 502 with a JSON body whose `type` is `peashoot_error`, never imitating a provider's error shape, because Claude Code's retry logic matches on provider wording. An upstream body the frame path cannot carry is refused the same way, before the response starts: a non-text body (anything but `text/*`, `application/json`, or `application/*+json`, in UTF-8 or with no charset declared) returns 502 `unsupported_content_type` from the declared content-type alone, never truncated into a 200. See ADR 0001, `docs/adr/0001-text-frames.md`. A request whose own `Content-Type` does not parse is refused the same way before it is forwarded, with 400 `bad_content_type`: the request still became an exchange, so it still ends like one.

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

`Exchange` carries: id (ULID), receivedAt, surface, client (type, sessionId, agentId, parentAgentId), route, mode, request (method, path, headers, rawBody, json, normalizedJson, fingerprint), response (status, headers, set by the source), flags (replayHit, resumed, clientDisconnected), clientBytes (the response bytes the client had taken when it left, keep-alive comments excluded), and timings (firstByteAt, completedAt).

v1 chain order: Resume, Replay, Recorder, Deriver. v2's Chaos goes between Replay and Recorder so injected faults are recorded like real ones. No fifth hook until v2 needs one.

## 6. Store, cassettes, matching

Store. Table `exchange` with: id, fingerprint, surface, client fields, route, mode, request headers (JSON, secrets already absent), request body (inline or body ref), response status, response headers (JSON), frames (inline or ref), usage (JSON), stop reason, timings, the three flags, cassette name (null for live recordings). Table `event` mirrors the event line for queries. Table `session` aggregates counts, tokens, cost, last seen. Indexes on fingerprint, session, receivedAt.

Cassette file. JSONL, one record per exchange:

```json
{"v":1,"fingerprint":"...",
 "request":{"method":"POST","path":"/v1/messages","headers":{"content-type":["application/json"]},"body":{}},
 "response":{"status":200,"headers":{"content-type":["text/event-stream"]},"frames":[{"t":0,"raw":"event: message_start\ndata: {}\n\n"}]},
 "meta":{"client":"claude-code","model":"...","usage":{},"stopReason":"end_turn","recordedAt":"2026-09-12T10:00:00Z"}}
```

Non-streaming responses carry `"body"` instead of `"frames"`; a response is a stream when its content type is `text/event-stream`. Header values are lists, as the store keeps them (`{"content-type":["application/json"]}`), since a response may repeat a header. Request headers are the ones the rule set keeps; response headers are all of them. Secret headers were dropped at capture, and export drops them again by name, so even a rule set that keeps one cannot export it. A request body that is a JSON object is written as that object; any other body as a string. Frames keep their text and offsets, so a replayed response is byte-equal to the recording, redactions aside. `meta` is read from the frames the way the event line reads them; `recordedAt` is when the request arrived. A record whose `v` is not 1 fails the whole import, naming its line.

Export is `proxy export <name> [--dry-run] [--session <id>]`, which writes `cassettes/<name>.jsonl` with every live recording (no cassette tag) oldest first, or only that session's; an imported cassette is never copied into a new one. Import is `proxy import <file>`: the records are tagged with the file's base name, which must be a usable cassette name (letters, digits, `.`, `_`, `-`), their secret headers are dropped as capture drops them, and replace every row that name already had, in one transaction, so importing on every start is idempotent. Imported rows keep the recorded fingerprint and take the recorded time as their arrival. A route with a cassette replays only that cassette's rows; a route without one replays any row, as before. The control API (#15) calls the same functions.

Redaction. `redact.json` is a list of `{pointer, pattern, replacement}` rules, the shape of a `replace` rule in `rules.json`, parsed and validated by the same code. A pointer names strings in the request body, with `*` for any key or index. The empty pointer, RFC 6901's whole document, names every string in the request body at any depth and also the response text: each frame's raw text, or the whole body. A pointer cannot reach into the response because a stream is SSE text, not one JSON document. An empty-pointer rule therefore runs over the raw SSE or body text, field names and JSON escapes included, and a pattern that matches across them can break a frame; nothing validates this, and the dry run shows what each rule would change. The default is one whole-document rule replacing API-key-shaped strings (`sk-` and 20 or more key characters, which covers `sk-ant-…` and `sk-proj-…`) with `[REDACTED]`. A dry run lists each hit, the exchange id, where it was (`request /messages/0/content/0/text`, `response frame 3`), a masked form of the text matched (its first characters and its length, since a preview lands in CI logs), and what it becomes, and writes nothing. The stored fingerprint is exported verbatim, so a redacted request still replays. A key a provider streams split across two deltas is whole in neither frame, and no rule matches it.

Schema. Before v1 the tables are created if absent and never altered, so the `cassette` column means a fresh database: a store opened on a database without it refuses to start and says so, rather than failing every insert.

Matching. Normalized request = rules applied to (method, path, kept headers, JSON body). Fingerprint = SHA-256 over canonical JSON (sorted keys, no insignificant whitespace, numbers as written). Three rule types, stored in `rules.json`:
- `keepHeaders`: allowlist. Default: `content-type`, `anthropic-version`, `anthropic-beta`, `openai-beta`. All attribution headers are therefore ignored.
- `ignorePointers`: RFC 6901 pointers with `*` matching any array index. Default: `/metadata`, `/stream`.
- `replace`: `{pointer, pattern, replacement}` applied to string values, patterns visible and editable. Three defaults, each checked against a request captured from Claude Code 2.1.274 rather than assumed. One removes the machine-context block (working directory, git repo, platform, shell, OS version). It travels in a message, not in `system` where this design first put it, so the rule is on `/messages/*/content/*/text`; a second copy on `/system/*/text` costs nothing where the block is absent and still covers a client that moves it back. The third removes the `x-anthropic-billing-header` line that Claude Code sends as its first `system` block, because it names the client version, sits in the body where `keepHeaders` cannot reach it, and would otherwise invalidate every cassette on every client upgrade.

Exact mode is the empty rule set. `POST /rules/test` fingerprints the last N exchanges under a candidate rule set and reports collisions and splits before saving.

Repeats. Several recordings sharing a fingerprint are served in recorded order via a per-fingerprint cursor that resets per proxy start (`inOrder`), so retry sequences replay faithfully. `latest` serves the newest.

Strict miss. Replay on a strict route with no hit returns 409 with body `{"type":"peashoot_error","error":"replay_miss","fingerprint":"...","route":"..."}`; lenient routes fall back to upstream and append the recording. The 409 goes out on the same path as the relay's 502s, so it has no stream and is never recorded; a replay hit is never recorded again either.

Responses API state. Recorded response ids are served verbatim on replay, and `previous_response_id` stays in the fingerprint untouched, so chained Responses calls replay without id mapping.

## 7. The event line

JSONL, one object per event, written to `events.jsonl`, the `event` table, and the control API SSE feed.

- `exchange.started`: `ts, event, exchangeId, session, agent, parentAgent, client, surface, model, route, mode, toolResults:[{name, bytes}]`.
- `exchange.completed`: all of the above plus `tools:[{name, path?, command?}]` (from tool-use blocks in the response), `usage:{input, output, cacheRead, cacheWrite}`, `costUsd` (null for OAuth subscription traffic, 0 for a replay hit, which was billed nothing), `stopReason, status, firstByteMs, latencyMs, replayHit, resumed, clientDisconnected, rateLimit:{remainingTokens?, remainingRequests?, resetAt?}` from provider headers when present. `resumed` appears with resume (#26); `model` on completed is the response's when it named one, resolving an alias in the request.
- `exchange.client_gone`: `ts, event, exchangeId, session, bytesSoFar`.

Cost comes from a bundled per-model price table (input, output, cache read, cache write per million tokens), overridable in config.

Gource formatter (`gource.enabled`): one line per tool in `exchange.completed`, `timestamp|user|type|file|colour`, user = session short id, Read/Grep/Glob as `A`, Edit/Write as `M`, colour by tool name.

Paths appear in the event line and Gource output; both are local data. The labels-off rule applies to what the app draws.

## 8. Control API

Base `/_peashoot/v1/`, loopback only, bearer token from the `token` file on every call except `GET /health` under exactly that path. The token is 32 random bytes, base64url, written on first start to a staged file restricted to its owner (`rw-------`, or on Windows an ACL naming the owner alone, which Java writes protected so inherited entries neither stay nor return) and then hard-linked into place, which fails rather than replaces, so two starts at once agree on one token. An existing file is kept, tightened with a warning if others could read it, and refused if it is too short to be a token. It is compared in constant time and never logged.

A request whose path names the prefix once percent-decoded and case-folded (`/_Peashoot/v1/events`, `/_peashoot%2Fv1%2Fevents`) is the control API's however it is spelled, and is answered before routing: the token first, then 404 for every spelling but the canonical one. Relaying such a request would have carried the control token upstream. Nothing under the prefix is relayed, recorded, or derived.

- `GET /health`: version, uptime, routes and their modes.
- `GET /events`: SSE feed of event lines, each with its `event` table id as the SSE `id`; `?since=<eventId>`, or the standard `Last-Event-ID` header, backfills every line after that id, then the feed continues live; with neither, only new lines. The subscription is taken before the backfill is read and ids already sent are skipped, and the deriver stores and publishes under one lock, so ids arrive in order with no gap and no duplicate. A comment line opens the feed and repeats while it is idle. Each subscriber has a bounded buffer and publishing never waits: a subscriber that falls that far behind gets what it had buffered, then its response ends, and it reconnects with its last id.
- `GET /exchanges?session=&client=&cursor=&limit=`: newest first, summary rows; `cursor` is the last id of the previous page and one naming no exchange is refused, `nextCursor` is set when the page is full, `limit` is 1 to 500 (default 50). Session and client come from the exchange's event lines, so an imported cassette's rows match neither filter. `GET /exchanges/{id}` adds request headers and body and response headers; `?frames=true` adds `frames: [{t, raw}]`.
- `GET /sessions`: per session and agent aggregates.
- `GET /routes`; `PUT /routes/{name}` body `{mode, strict?, cassette?}` replaces that route for the next request, and an unknown name is a 404 rather than a route invented here. The relay reads the table once per request and the exchange carries that snapshot, so replay reads the strictness and cassette the request arrived under and a change mid-request cannot mix two routes. The table lives in memory, so a restart goes back to the config file and the environment. Only `default` exists until routing arrives.
- `GET /rules` serves the rule set in `rules.json`'s own shape; `PUT /rules` replaces it whole, validated by the parser the start uses, so an invalid pointer, a pattern that is not a regular expression, a replacement naming a group its pattern lacks, or a key nothing reads is a 400 naming the rule. The file is written before the live set changes, so what a restart reads and what the next request is fingerprinted under cannot differ.
- `POST /rules/test` body `{rules, lastN}` (`lastN` 1 to 500, default 50) fingerprints that many of the newest live recordings under the candidate set and saves nothing. An imported cassette's rows are left out, as they are left out of an export: each carries the fingerprint the machine that recorded it computed, over a body redaction may have changed, so re-fingerprinting it here would invent a difference. It answers `{tested, collisions: [{fingerprint, exchangeIds}], splits: [{fingerprint, groups: [[exchangeId]]}]}`, against the fingerprints those exchanges were stored under: a collision is exchanges that would share a fingerprint and do not now, which is a replay answering the wrong request; a split is exchanges that share one now and would not, which is a replay that stops hitting.
- `GET /cassettes` merges the files in `cassettes/` with the names the store's rows are tagged with, one `{name, exchanges, path, bytes, modified}` each, null where that half is missing: a name with no file was imported from elsewhere, a file with no rows was written here and never imported back.
- `POST /cassettes/export` body `{name, sessionIds?, exchangeIds?}` runs the export the CLI runs, each id list narrowing it and both narrowing by both, and answers `{name, dryRun, path, exchanges, redactions, preview: [{exchangeId, where, matched, becomes}]}`, where `matched` is masked as the CLI masks it. `?dryRun=true` is the preview alone: no file, and `path` null. `POST /cassettes/import` body `{path}` runs the same import and answers `{name, exchanges}`; a path that is not a readable cassette is a 400 saying which line or which rule refused it.
- `GET /config` is the config as the proxy runs it, with no secret in it: the token is not a config value, and `secretHeaders` is names only. The routes are the table's, which is what a request reads, and `dumpFrames` and `cassetteFile` are answered too, though `PEASHOOT_DUMP_FRAMES` and `PEASHOOT_CASSETTE` are the only things that set them and a `PUT` naming either is refused. `PUT /config` replaces the keys it names, leaf by leaf, so a table keeps the keys the body says nothing about, rewrites `peashoot.toml` from the result, and applies what it can. One writer at a time: the rule and config puts take one lock, since each reads a file, writes it, and loads it back. It is validated by loading the file it just wrote, so a value the next start would refuse is refused here in the loader's own words and the file that runs goes back. The answer is `{config, restartRequired: [key], environmentWins: [variable]}`: `routes` and `surfaces` reach the next request, because the route table and the relay read them per request, while `port`, `host`, `secretHeaders`, `replay`, `resume`, `pricing`, and `gource` are read once at start. A key in `environmentWins` will not take effect on a restart either, because the variable named there is read over the file. The route table owns the routes while the proxy runs, so a `PUT` that does not name `routes` leaves whatever `PUT /routes/{name}` last set. `GET /config` keeps answering what the process does while the file holds what it will do, and the merge is onto the file rather than onto what runs, so a restart-only key stays as the `PUT` that set it left it. Environment overrides still win at start, so a key one of them covers reads back as the environment's.
- `POST /shutdown` answers `{"stopping": true}` and then the server stops: the answer is written first, and whoever started the server closes it and the store, which is what ends the process.

Errors are JSON problem objects `{type, title, detail, status}` served as `application/problem+json`: 400 for a parameter or body that cannot be used, 401 for a missing or wrong token, which is answered before an unknown path or method is, 404 for an unknown path or exchange, 500 for anything of ours that failed, whose detail says only where to look while the log keeps the exception. The app never opens the database file.

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

As built (#15, the skeleton). `ControlClient` reconnects with `Last-Event-ID` rather than `?since`, which the proxy honours over the query parameter and which is what an SSE client resends anyway; the backoff waits 250 ms and then doubles to 5 s, and a connection that got as far as streaming starts it over. It never throws at the window: a connection is a `Feed.State` on the same flow as the lines, so the window shows losing the proxy instead of a collector catching it. The one failure it does not retry is a token the proxy refused, which another attempt cannot mend. The token is read per connection, not per app, so a proxy the app has only just started is read from the token file it has only just written.

Where the feed resumes from is a cursor held across attempts, not a value returned by one. The connection that most needs resuming is the one that threw on the way out — a killed proxy resets the socket rather than closing the stream — and a returned id would be the id that connection *began* with, which replays everything it had already delivered. The cursor moves as each line reaches the collector, so the reset path and the orderly-end path resume from the same place. `AppModel` keeps the last id as well, so a feed started a second time does not skip what happened in between.

Deciding whether to start a proxy turns on three answers, not two: Peashoot, something else, or nothing. `ControlClient.probe` never throws, because a port answering a 500 and a port answering nothing are opposite decisions — only silence means the port is free, and starting a proxy against a port something else holds would leave a child that cannot bind and is never reached. The same probe fills the health display, so there is one call and not two. The port comes from one place and is passed to the child as `PEASHOOT_PORT` along with `PEASHOOT_HOME`, because a child told to bind a different port than the app looks on is unreachable by construction.

Starting a proxy prefers `peashoot.jar` over the `installDist` script, so the process started is the JVM itself with nothing between. Where only the script exists, the JVM is its child, and the JVMs it spawned are remembered while the script is still there to name them: a script that has exited or `exec`'d away lists no children, and asking only at the end would leave a proxy running with nothing pointing at it. Only a proxy the app started is ever stopped. `homeDir` and `TOKEN_FILE` moved to `core` for this: the app has to find the same directory, and two copies of that rule would be one restart away from disagreeing.

As built (#17, the reducer). `reduce(FarmState, JsonObject)` is a top-level function in `dev.peashoot.app.farm`, not a `FarmReducer` type: there is one implementation and nothing to swap. It reads the event line's own fields and nothing else — no clock, no randomness — so a recorded feed replays to the same farm every time, and a line it cannot use gives back the state it was handed rather than throwing out of the feed collector, which is the duty the window already had. `exchange.client_gone` is one of those lines until #18 makes it lightning. There is no `waitingAtWell`: the feed says nothing between `started` and `completed`, so the wait is the tail of the walk, and the renderer (#20) shows waiting once the sprite has arrived. A session can have several exchanges out at once, which Claude Code does routinely, so the villager holds the ids of its own in-flight turns and leaves the well only when the last of them completes; and a `completed` line for a villager never seen still makes one, because the app can connect in the middle of a turn. That is also why a `completed` line heard twice would count twice: a duplicate and a late join look the same, so what keeps a line from arriving twice is the feed's cursor, not the reducer. A helper keeps the parent id its lines named, and `FarmState.parentOf` answers who it stands beside each time the farm is read — the named helper once that one has been heard, the session's villager until then — because a window connecting mid-run hears a nested helper before its parent, and a link settled at the first line would flatten the fan-out for good. Two things are left to #18's clock: nothing in the feed ends a walk home, so no villager returns to `idle`; and a `started` whose proxy was killed before its `completed` leaves that villager at the well, since only time can say the turn is not coming back. Which tools edit a file is one set in `core`, read by the farm and the Gource log both. Crops are keyed by the path with separators normalised to `/`, not by a path hash: a hash buys nothing a string key lacks here, and the label has to be kept anyway, while normalising is what makes a file written on Windows and read on a POSIX box one crop. An edit to a path that was never planted plants it — the file existed before the app was watching, and a crop has to exist to grow — and that edit is the planting rather than a stage on top of it. Labels are one `labelsHidden` flag on the state, true by default, because the badged toggle it will answer to is one switch and not one per crop.

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

Toolchain: JDK 21 LTS, Kotlin 2.4.x, Ktor 3.x, kotlinx.serialization, sqlite-jdbc, Compose Multiplatform 1.12 stable line. 1.12.0 is the stable release of that line, and the Compose compiler plugin is versioned with the Kotlin it plugs into (2.4.20), never with Compose Multiplatform. 1.12 is aligned with Jetpack, so its runtime, lifecycle, and saved-state artifacts are Google's and are published nowhere else: `settings.gradle.kts` adds `google()` narrowed to those groups, so everything else still resolves from Maven Central and only from there. The app takes no dependency Compose Desktop does not bring, apart from the Ktor client it shares with the proxy; Material 2 ships with the desktop artifact, so Material 3 is not pulled in for a window with four text styles. Composables are named in camelCase, against the Compose convention, because detekt's `FunctionNaming` is one of this build's fixed gates and does not exempt `@Composable`.

Packaging: `nativeDistributions` as `Peashoot` 1.0.0, MSI, DMG, and DEB, and `createDistributable` writes a runnable image to `app/build/compose/binaries/main/app/`. jpackage refuses a 0 major on Windows, and nothing is released or tagged until it is. The image does not yet carry a proxy launcher of its own.

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

- Secret headers are never stored, not even locally. `secretHeaders` therefore takes a restart: `PUT /config` writes a new list into the file, but a running proxy keeps dropping the headers it started with, so no call can turn capture of a credential on mid-session.
- Bodies and error bodies are forwarded unmodified; the proxy inspects, never rewrites. A body the proxy cannot carry is refused, never truncated.
- Whole responses are never buffered before relaying; pings are forwarded; silent upstreams get SSE comment pings so Claude Code's 300-second watchdog is not tripped by the proxy.
- Body redaction runs at cassette export with a preview; the stored recording is never rewritten.
- Farm labels are off by default; the "show paths" toggle draws a visible badge.
- The control API binds to loopback only in v1.
