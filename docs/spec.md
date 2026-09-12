# Peashoot v1 spec

Status: ready-for-agent (to be published to the issue tracker once one is configured). Technical design: `docs/design.md`. Decision record: `docs/brief.md`. Evidence: `docs/research/`.

## Problem Statement

Developers who build or run LLM agents have no way to make an agent run repeatable. Every test, every eval, and every debugging session pays for real model calls, and the same run can never be reproduced because the model answers differently each time. The SDK vendors have declined to build record and replay themselves. When a stream drops mid-response (laptop sleep, Wi-Fi blip, an idle-timeout abort), the client re-issues the request and the partial output that was already billed is thrown away and paid for again. And when several agents run at once, nothing shows what they are doing, what it costs, whether the cache is helping, or why the provider is pushing back, unless the client happens to be Claude Code with hooks installed. Python and TypeScript agents are invisible.

## Solution

Peashoot is a local proxy that any agent client points at, plus a desktop app. The proxy records every model call into a portable cassette, replays cassettes deterministically so harness logic and tool wiring can be tested in CI with zero model calls, and keeps consuming an upstream stream when the client disconnects so the re-issued request is served from the buffer instead of re-billed. It emits one event line per request that any tool can consume, including a Gource-compatible output on day one. The desktop app is a farm: each agent session is a villager, the repository is fields and crops, requests are trips to the well, cache reads are rain, rate-limit pressure is stamina, provider trouble is weather, and the cost ledger is the shipping bin. Clicking anything opens the plain timeline underneath. The app is also the control plane: record or replay per route, editable matching rules with a test-before-save view, cassette export with a redaction preview.

It works for Claude Code, Codex, OpenCode, and any Anthropic- or OpenAI-compatible SDK, because it speaks the providers' wire protocols rather than any client's hooks.

## User Stories

1. As a developer using Claude Code, I want to set one environment variable and have all my traffic pass through Peashoot, so that adoption costs me nothing and my claude.ai login keeps working.
2. As a developer using Codex, I want to point its provider config at Peashoot and have it fall back cleanly from WebSocket to HTTP, so that Codex works through the proxy without special flags.
3. As a developer using OpenCode, I want to set the provider base URL and optionally add a three-line plugin that tags my sessions, so that my sessions are grouped correctly in the farm.
4. As a developer using the Anthropic or OpenAI SDK directly in Python or TypeScript, I want to set the SDK base URL and get the same recording, replay, and farm as Claude Code users, so that my agent is not a second-class citizen because it has no hooks.
5. As an agent developer, I want every model call recorded automatically in record mode, so that I never have to remember to capture a run before it becomes interesting.
6. As an agent developer, I want to flip a route to replay mode and re-run my agent with zero upstream calls, so that I can iterate on harness logic and tool wiring for free.
7. As an agent developer, I want replay to be byte-faithful per streamed frame, so that my client cannot tell the difference between a replay and a live response.
8. As an agent developer, I want replay to be instant by default and "as recorded" on request, so that tests are fast unless they are specifically about timing.
9. As an agent developer, I want a replay miss on a strict route to fail with an error that names the fingerprint and route, so that CI fails loudly instead of silently calling the real API.
10. As an agent developer, I want a replay miss on a lenient route to fall back to upstream and append the recording, so that I can grow a cassette by simply running the agent.
11. As an agent developer, I want matching to ignore my machine's working directory, platform, and OS version, session and agent attribution headers, and other volatile fields by default, so that a cassette recorded on my laptop replays on CI.
12. As an agent developer, I want to see and edit the matching rules as data, so that I can add an ignore rule for a field my own client varies.
13. As an agent developer, I want to test a candidate rule set against my last N recorded exchanges and see which would collide or split, so that I understand a rule's effect before saving it.
14. As an agent developer, I want an exact-match mode with no normalization, so that I can prove two requests are literally identical when that is the question.
15. As an agent developer, I want repeated identical requests to replay in recorded order, so that a retry sequence replays exactly as it happened.
16. As an agent developer, I want chained Responses API calls to replay without any ID remapping, so that Codex sessions replay cleanly.
17. As a CI engineer, I want cassettes to be plain JSONL files I can commit to the repository, so that anyone's CI can replay them without an API key.
18. As a CI engineer, I want to start the proxy in replay mode with a cassette path, strict flag, and port from environment variables, so that a CI job needs no config file.
19. As a security-conscious developer, I want the proxy to never store authorization headers or API keys, not even locally, so that a cassette or a database copy can never leak a credential.
20. As a security-conscious developer, I want cassette export to run redaction rules and show me a preview of what will be stripped, so that I can share a cassette without leaking prompt content.
21. As a developer on flaky Wi-Fi, I want the proxy to finish reading an upstream stream after my client disconnects and serve the buffered completion when the client re-issues the request, so that I stop paying twice for the same answer.
22. As a developer with a long-thinking model, I want the proxy to keep sending keep-alive comments while the upstream is silent, so that my client's byte-counting watchdog does not abort the stream.
23. As a Codex user, I want the proxy to support the Responses API's own sequence numbers and resume cursor, so that resumption uses the mechanism the API already has.
24. As an agent developer, I want the proxy to forward request and response headers verbatim, including sticky state headers and beta headers, so that clients that depend on them keep working.
25. As an agent developer, I want upstream errors forwarded unmodified with their status and body, so that my client's retry logic sees exactly what the provider sent.
26. As an agent developer, I want proxy-side failures to be clearly marked as coming from Peashoot, so that I never mistake a proxy bug for a provider outage.
27. As an agent developer, I want one JSONL event per request start and completion, with session, agent, client, model, tool calls with paths or commands, tokens, cache reads and writes, cost, stop reason, status, latency, and rate-limit remaining, so that I can pipe my agent traffic into any tool I like.
28. As an agent developer, I want a Gource-compatible log written by a flag, so that on day one I can watch my agents move through my repository with a tool I already have.
29. As an agent developer, I want cost computed from a bundled price table I can override, so that the shipping bin ledger is real money.
30. As a subscription user with OAuth traffic, I want cost shown as tokens rather than a fabricated dollar figure, so that the numbers stay honest.
31. As an agent developer, I want to open the desktop app and see the farm immediately with my live sessions as villagers, so that I know at a glance who is working and who is waiting.
32. As an agent developer, I want each villager's walk to the well and wait to reflect a real in-flight request and its latency, so that slow requests are visible without reading logs.
33. As an agent developer, I want cache reads shown as rain and rate-limit pressure as a stamina bar, so that I can see prompt-cache health and quota pressure without opening provider dashboards.
34. As an agent developer, I want a 429 to show a villager resting at the well and a 529 or dropped stream to show a storm or lightning, so that provider trouble is obvious in a glance.
35. As an agent developer, I want sub-agents shown as helpers beside their parent villager, so that I can see fan-out.
36. As an agent developer, I want fields and crops built from the paths my agents touch, with reads as inspections, edits as growth, and first writes as planting, so that I can see where in the repository the work is happening.
37. As an agent developer, I want an end-of-day card when a session goes idle, with tokens, cost, files touched, and cache-hit rate, so that I get a summary I can screenshot.
38. As an agent developer, I want replay mode shown as night with lanterns, so that I always know whether I am looking at live or replayed traffic.
39. As an agent developer, I want to click a villager to see the plain timeline of their exchanges with usage, cost, latency, and replay flags, so that the farm never hides the data.
40. As an agent developer, I want to click a crop to see its touch history, so that I can trace which agents changed a file and when.
41. As someone who shares screenshots, I want file paths and commands hidden by default in the farm and a visible badge when I turn them on, so that I never leak a path by accident.
42. As an agent developer, I want to switch a route between record, replay, and passthrough from the app, so that I do not have to edit config files mid-session.
43. As an agent developer, I want the app to launch the proxy for me if it is not running, so that the 30-second demo is actually 30 seconds.
44. As an operator, I want the proxy to run headless as one JAR with an embedded database and no external services, so that I can run it anywhere Java runs.
45. As an operator, I want the control API bound to localhost with a bearer token, so that nothing on my network can read my traffic.
46. As a Windows, macOS, or Linux user, I want native installers for the app and a single JAR for the proxy, so that installation is one download.
47. As a contributor, I want a fake upstream in the test suite that replays fixture streams with controllable timing, drops, and error statuses, so that I can test proxy behavior without an API key.
48. As a contributor, I want the farm logic to be a pure function from events to state, testable with recorded event files, so that I can change the farm without a display.
49. As a maintainer, I want a manual live-smoke workflow against the real providers, so that grammar drift is caught before users hit it.
50. As a reader of the README, I want a GIF of agents moving through a repository within the first screen, so that I understand what Peashoot is before reading a word.

## Implementation Decisions

Runtime shape
- Two processes: a headless proxy JAR and a separate Compose for Desktop app. The app talks only to the proxy's control API and never opens the database. The app launches the proxy as a child process when nothing answers on the port.
- Kotlin throughout. Ktor for both the server and the upstream client. SQLite via JDBC as the embedded store with large bodies spilled to content-addressed files. Compose for Desktop with Canvas rendering for the farm. Gradle with a version catalog and a committed wrapper; the Compose Gradle plugin is the only officially supported build path.
- A data directory holds the database, spilled bodies, exported cassettes, the event log, the Gource log, the rule set, the control token, and the config file. Config is TOML with environment overrides for the keys CI needs.

The exchange and the interceptor chain
- Every request becomes an Exchange: identity, timestamps, surface, detected client and session, route and mode, the request in raw and parsed and normalized form with its fingerprint, the response status and headers, timing, and three flags (replay hit, resumed, client disconnected).
- Responses flow as Frames (raw text plus arrival offset) through one shared flow with three sinks: the client writer, the recorder, and the event deriver. Non-streaming responses are one frame.
- An ordered interceptor chain decides the response source and observes frames. The contract has four hooks: on request (continue, or respond with a source), on frames (observe or transform), on complete, on client gone. v1 order is Resume, Replay, Recorder, Deriver; the upstream provider is the terminal source when no interceptor responds. Future fault injection sits between Replay and Recorder so injected faults are recorded like real ones.
- Upgrade requests are refused with a plain 426 so Codex falls back to HTTP.
- Request headers forward verbatim minus hop-by-hop headers, host, accept-encoding (stripped so upstream never compresses a stream), and the configured secret headers, which are used for the upstream call and never persisted. Response headers forward verbatim minus hop-by-hop.
- Upstream errors are forwarded verbatim and recorded. Proxy-side failures return 502 with a body typed as a Peashoot error and never imitate a provider error shape.
- On client disconnect in record or passthrough mode, the client sink detaches and the recorder and deriver consume to completion; the exchange stays eligible for resume for a configurable window. While an upstream is silent, the proxy emits SSE comment lines at a configurable interval.

Surfaces and clients
- Three surfaces in v1, each an adapter that knows its request shape, frame grammar, extraction into the event line, and resume story: Anthropic Messages (plus passthrough for token counting, model listing, and Claude Code's probe), OpenAI Responses (including the resume cursor and cancel), and OpenAI Chat Completions. Upstream base URL is configured per surface, so the OpenAI surfaces can point at a local OpenAI-compatible server for free testing.
- Resume on Messages and Chat Completions is the buffered completion matched by normalized fingerprint within the window. Resume on Responses uses the API's sequence numbers, with the proxy also serving the cursor from its own buffer when the response was not created in background mode.
- Client detection order: a Peashoot session header wins for any client; then Claude Code's session and agent headers; then Codex's originator, session, and thread headers; then official SDK headers; then user-agent; else unknown. Clients with no session header get a fallback session from client type plus a hash of the conversation's first user message.

Store, cassettes, matching
- The store of record is an exchange table plus an event table and a session aggregate table. Cassette files are versioned JSONL with one record per exchange holding the fingerprint, the request with kept headers and body, the response with status and headers and either frames with offsets or a single body, and metadata.
- The fingerprint is a SHA-256 over canonical JSON of method, path, kept headers, and the normalized body. Three rule types: a header allowlist, ignored JSON pointers with an array wildcard, and regex replacements at a pointer. Defaults ship in a rule file copied on first start; the default replacement removes Claude Code's machine-context block from the system prompt. Exact mode is the empty rule set.
- Repeated fingerprints replay in recorded order via a per-fingerprint cursor, with "latest" as the alternative. A strict miss returns 409 with a Peashoot error naming the fingerprint and route. Recorded Responses ids are served verbatim on replay.
- Export applies redaction rules and supports a dry run that returns the preview; import tags records with the cassette name.

Event line
- Three event types: exchange started, exchange completed, client gone. Completed carries tool calls with name and path or command, usage including cache reads and writes, cost from the price table or null for OAuth traffic, stop reason, status, timings, the three flags, and rate-limit remaining from provider headers when present.
- Sinks: the event log file, the event table, the control API's live feed, and an optional Gource formatter emitting timestamp, user, type, file, colour with reads as added and edits as modified.

Control API
- Under a reserved prefix on the proxy port, loopback only, bearer token from the token file on every call except health. Resources: health, live events with backfill, exchanges with filters and optional frames, sessions, routes, rules with a test endpoint, cassettes with export dry run and import, config, and shutdown. Errors are JSON problem objects.

Desktop app and farm
- Unidirectional: a control client feeds a pure reducer from events to farm state; Compose renders the state; an animator interpolates positions per frame.
- Farm state holds villagers with activity and stamina, fields and crops with growth stage and hidden labels, the well queue, weather, time (season by calendar month, night in replay mode), the shipping bin with its ledger, and pending end-of-day cards. Villager names come from a fixed list indexed by session hash.
- The mapping is fixed for v1 as described in the design; animals, relationships, festivals, and a shop are cut because no signal drives them.
- Labels are off by default; the show-paths toggle draws a visible badge on the canvas.
- Rendering targets 60 fps with 200 entities from one CC0 sprite atlas; under 30 fps villagers teleport instead of walking.

Milestones
- Spikes first: whether Claude Code's re-issued request after a drop differs in any field the default rules do not already ignore, and the Canvas frame rate on the author's laptop with and without GPU.
- Then capture, replay, farm, additional clients, release, in that order, with a public milestone at three months (capture, replay, event stream, Gource GIF) and stream-resume as the first item to slip.

## Testing Decisions

What makes a good test here: it drives Peashoot the way a client does and asserts what a client or a downstream tool can observe. Nothing asserts on interceptor internals, database rows, or Compose widgets.

Seams, from highest to lowest; two in total.
1. The proxy's HTTP boundary, with a fake upstream on the other side. Tests start the proxy against a fake provider (a test server that replays fixture streams with controllable chunk timing, injected disconnects, and chosen error statuses), send requests with a real HTTP client, and assert on what comes back and what the proxy emitted: frames byte-equal to the fixture, replay hits served with zero upstream calls, strict misses returning the documented error, resume serving the buffered completion after a client disconnect with exactly one upstream call, event lines written with the expected fields, and control API responses for routes, rules, rule tests, and cassette export previews. This one seam covers everything in the proxy and the shared core, including matching rules, because a rule's only observable effect is whether a replay hits.
2. The farm reducer: recorded event files in, expected farm state out. This is the highest testable point below the pixels; the renderer itself is verified by the frame-rate spike and by eye.

Fixtures are streams captured from the real providers, redacted, and committed. A manual live-smoke workflow runs tiny prompts against the real APIs to catch grammar drift; it is the only test that spends money.

Prior art: none in this repository yet. The pattern is the same as the author's earlier projects that test a server through its wire protocol with a real client.

## Out of Scope

- MCP surfaces and anything MCP-specific.
- Fault injection, run diffing and forking, regression assertions with CI thresholds.
- Sonification, local-model narration, hook adapters for signals the proxy cannot see.
- Multi-node deployment, a non-loopback control API, authentication beyond the local bearer token.
- Gemini CLI and any fourth wire format.
- Any GPU requirement; local models are a free test backend only.
- Farm elements with no driving signal: animals, relationships, festivals, a shop.
- Rewriting, redacting, or compacting traffic in flight; the proxy inspects and never modifies.

## Further Notes

- The name comes from the author's daughter's nickname; the farm and the pea-shoot imagery follow from it. Story of Seasons is the inspiration for the farm mechanics and is a third-party trademark, so it is never used in branding.
- The pixel-world category already has projects with thousands of stars, all fed from Claude Code hooks or transcripts. Peashoot's farm is deliberately fed from the proxy, so it shows what hooks cannot (cost, cache, rate limits, provider errors, dropped streams) and works for clients that have no hooks. The README leads with replay, resume, and cost; the farm is how they are shown.
- Gradle was chosen over the author's usual Maven because Compose Desktop has no official Maven support.
- The full technical design with configuration keys, endpoint list, frame grammars, and the interceptor contract is in the design document; the decision history is in the brief; every external claim is cited in the research folder.
