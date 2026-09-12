# Devex tooling for LLM-agent builders: direction survey

Date: 2026-09-12. Primary sources only (official docs, specs, repos, issues, arXiv). Star counts and reaction counts pulled from the GitHub API on 2026-09-12. Anything not backed by a primary source is marked **unverified**.

Constraints applied to every candidate: language-agnostic proxy/server in Kotlin/JVM speaking OpenAI-compatible HTTP, Anthropic Messages, or MCP (2026-07-28); single JAR with embedded state; Compose for Desktop dashboard + control plane; vendor-neutral with Claude Code as first demo; solo dev at ~10 h/week, useful v1 in 3 months.

## Ranked result

| # | Candidate | Verdict vs. baseline scheduler proxy | Strongest evidence |
|---|-----------|--------------------------------------|--------------------|
| 1 | Fault-injection ("chaos") proxy for LLM + MCP | Complementary, same chassis; strongest pain evidence in the cluster | claude-code #63875: 116 upvotes, 75 comments, "The model's tool call could not be parsed (retry also failed)"; anthropic-sdk-python #1258: production fallback "never fired" because mid-stream `overloaded_error` surfaced as status 200 |
| 2 | Record/replay cassette proxy (LLM + MCP, streaming-faithful) | Complementary; the SDK vendors have explicitly declined to build it | openai-agents-python #4795 closed by maintainer 2026-09-05: "we are not planning to pursue this in the near term"; `claude plugin eval` docs: "Every eval run and every judge grader is a real model call on your account" |
| 3 | Desktop time-travel viewer: diff two runs, fork from step N (on proxy captures) | Complementary; it is the dashboard for #2 | claude-code #32631 (43 upvotes, open): "Compare branches -- No diff between session states"; all four popular JSONL viewers (2.2k/1.3k/1.2k/364 stars) are read-only, none diff/fork/replay |
| 4 | Proxy-based regression assertions + CI gate | Weaker standalone; phase 2 of #2 | pydantic-ai #3138 (open, needs-maintainer-action) asks for `expect_tool_called`, snapshot flows; `claude plugin eval` has `tool_used`/`tool_order` graders but no cost grader, "no custom-code graders", plugins only, live model only |
| 5 | Cache-break forensics module | Weaker; a 2-3 week add-on to the baseline, not a product | Three claude-code issues (#81967, #84253, #49038) each hand-rolled mitmproxy captures + diff scripts; Anthropic and OpenAI now both ship server-side diagnostics, but "Claude API only: Not available on Amazon Bedrock or Google Cloud", "Limited retention" |
| 6 | MCP toxics (tool-list drift, hung calls, malformed frames) | Extension of #1 | mcpsnoop (347 stars) already detects "hung calls, stream errors, malformed JSON-RPC frames" and "tool definition drift after approval" but injects none of them |

Dropped: context-window budget middleware (#6 in the brief), standalone prompt-cache advisor, Claude Code JSONL viewer, hosted-trace-platform clone, MCP inspector clone. Details at the end.

---

## Facts that shape every candidate (Claude Code as a proxy client)

- Base URL override: "`ANTHROPIC_BASE_URL` is the variable that points Claude Code at the gateway. Setting only that variable, without a gateway credential, doesn't replace the subscription. Requests still route through the gateway, but a saved claude.ai login remains the active credential" -- https://code.claude.com/docs/en/llm-gateway. The proxy must forward `anthropic-beta` verbatim: "this header also carries an OAuth capability that the upstream requires, and stripping it fails those requests with `401`" -- https://code.claude.com/docs/en/llm-gateway-protocol.
- Endpoints hit: "Inference requests post to `/v1/messages?beta=true`"; `/v1/messages/count_tokens` optional; `HEAD /api/hello` probe; `GET /v1/models?limit=1000` only if `CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY=1` (same page).
- Per-session attribution headers exist: `x-claude-code-session-id`, `x-claude-code-agent-id`, `x-claude-code-parent-agent-id` -- "Use it to aggregate all requests from one session without parsing request bodies" (same page). This is what a dashboard groups on.
- Streaming constraints: "if your gateway buffers complete responses before relaying them, Claude Code stalls"; "aborts a stream that goes silent for 300 seconds by default" (same page).
- Do not rewrite bodies: "A gateway that rewrites or redacts request bodies for content inspection breaks the pairing the same way stripping does, so inspect without modifying" (same page). Error bodies too: "The retry logic matches on the upstream's error wording, so forward error response bodies unmodified."
- Claude Code retries: "Up to 10 times with exponential backoff" for failures "that arrive before any of Claude's response has streamed"; mid-stream failures get "Smaller retry budget or none" -- https://code.claude.com/docs/en/errors.
- MCP attach: `claude mcp add --transport http <name> <url>` or `claude mcp add <name> -- <command> [args...]`; `.mcp.json` with `{"mcpServers": {...}}` -- https://code.claude.com/docs/en/mcp.
- MCP spec revision 2026-07-28 exists; transports are JSON-RPC over stdio and Streamable HTTP; Tasks/Apps are opt-in extensions -- https://modelcontextprotocol.io/specification/2026-07-28.
- Baseline-relevant rate-limit facts (verified): Anthropic "only uncached input tokens count toward your ITPM rate limits", headers `anthropic-ratelimit-{requests,tokens,input-tokens,output-tokens}-{limit,remaining,reset}` and `retry-after`; "A tier spend-cap 429 has no `retry-after` header" -- https://platform.claude.com/docs/en/api/rate-limits. OpenAI: usage "calculated as the maximum of `max_tokens` and the estimated number of tokens based on the character count of your request"; headers `x-ratelimit-*`, `Retry-After` -- https://developers.openai.com/api/docs/guides/rate-limits.

---

## 1. Fault-injection proxy for LLM and MCP traffic ("Toxiproxy for LLMs")

**Description.** A pass-through proxy for Anthropic Messages and OpenAI chat/responses (plus MCP Streamable HTTP) that can inject provider-faithful faults on demand or by schedule: 429 with and without `retry-after`, 529 `overloaded_error`, 500, spend-cap 429 (no `retry-after`, `enforced_spend_limit_reached`), SSE `error` events after a 200, streams truncated mid-`input_json_delta`, silent stalls (no `ping`) longer than N seconds, slow chunking, dropped connections, malformed or truncated tool-call JSON, `max_tokens` stops, context-length 400s. Scenarios are recorded and replayable so a resilience test is reproducible. Toxiproxy works at TCP level and cannot produce any of these HTTP/SSE-level shapes.

**Evidence the problem is real.**
- claude-code #63875 (116 upvotes, 75 comments, opened 2026-05-30, closed as duplicate 2026-08-19): "During normal sessions, Claude Code intermittently stops mid-task and shows: The model's tool call could not be parsed (retry also failed)... the turn is effectively lost" -- https://github.com/anthropics/claude-code/issues/63875
- claude-code #75953 (closed not planned): "322 logged error events across 13+ days" of 529 bursts and "23+ distinct session transcripts begin with the user reporting a restart after a crash" from malformed tool calls -- https://github.com/anthropics/claude-code/issues/75953
- claude-code #60577 (closed not planned, 5 comments): "When the Anthropic API returns a transient `529 Overloaded` response mid-session, Claude Code surfaces the error and halts the in-flight task. There is no automatic retry, backoff, or resume behavior" -- https://github.com/anthropics/claude-code/issues/60577
- anthropic-sdk-python #1258 (fixed in 0.87.0, closed 2026-03-31): "When the API returns HTTP 200 (streaming started fine) but then sends an SSE error event like `overloaded_error`, the SDK creates an `APIStatusError` with `status_code=200` instead of 529... We hit this in production with pydantic-ai's `FallbackModel`... fallback never fired" -- https://github.com/anthropics/anthropic-sdk-python/issues/1258. This is exactly the class of bug a mid-stream error toxic surfaces before production.
- anthropic-sdk-typescript #842 (3 upvotes, 13 comments): "The stream ends abruptly without sending a message_stop event, leaving the response incomplete. This happens specifically when using tool_use with large JSON payloads" -- https://github.com/anthropics/anthropic-sdk-typescript/issues/842
- openai-agents-python #2061 (fixed by PR #2337): "When a tool call contains invalid JSON arguments (e.g., missing closing brace), the session stores the invalid JSON string. This breaks the session" -- https://github.com/openai/openai-agents-python/issues/2061; #325 "Retry mechanism for ModelBehaviorError" (6 upvotes, 16 comments) -- https://github.com/openai/openai-agents-python/issues/325; #782 "Rate Limit Support" labelled wontfix -- https://github.com/openai/openai-agents-python/issues/782
- Stated in docs: "When receiving a streaming response over server-sent events (SSE), an error can occur after the API returns a 200 response. In that case, error handling doesn't follow these standard mechanisms" -- https://platform.claude.com/docs/en/api/errors. Example event: `event: error` / `data: {"type": "error", "error": {"type": "overloaded_error", "message": "Overloaded"}}` -- https://platform.claude.com/docs/en/build-with-claude/streaming. "The official SDKs automatically retry transient failures... twice by default, honoring the `retry-after` header when present" (errors page) -- so agent authors inherit a 2-retry default and rarely test beyond it.

**Closest existing attempts.**
- Shopify/toxiproxy -- 12,333 stars, Go. Toxics are `latency, down, bandwidth, slow_close, timeout, reset_peer, slicer, limit_data`; "a TCP proxy to simulate network and system conditions" -- https://github.com/Shopify/toxiproxy. Cannot emit a 429, an SSE error event, or a malformed tool-call delta; TLS to api.anthropic.com is opaque to it.
- theblixguy/llm-mock-server -- 3 stars, TypeScript, last push 2026-07-25. "A mock LLM server for testing. It handles OpenAI `/chat/completions`, Anthropic `/messages`, and OpenAI `/responses`"; scripted replies and error injection, but no upstream passthrough (pure mock), no stream truncation, no GUI -- https://github.com/theblixguy/llm-mock-server
- LiteLLM `mock_response` -- Python SDK feature ("mock calling the completion endpoint"), streaming supported; the docs page shows no error/429 simulation and no proxy-server mode -- https://docs.litellm.ai/docs/completion/mock_requests. The claim that `mock_testing_fallbacks` is stripped from proxy requests since v1.85.0 comes from a search summary: **unverified**.
- Rhugved-Kale/ChaosLLM (0 stars, Python, 2026-08-15, "a fault-injection proxy, YAML experiments, and resilience reports"), goshipra/ai-agent-chaos (0 stars, Python, 2026-08-20), navyabijoy/chaosline (1 star, TS, 2026-08-15) -- three independent attempts in the last month, none with traction, none with a control plane. Bifrost (7,990 stars, Go) has fallbacks/retries; no fault-injection feature found in its repo description -- **unverified** beyond that.
- Why none satisfy the constraints: TCP-only (Toxiproxy), mock-only with no passthrough (llm-mock-server), Python-library-only (LiteLLM), or zero-star prototypes without provider-faithful shapes or a UI.

**Demo in 30 seconds.** `java -jar agentproxy.jar` -> `export ANTHROPIC_BASE_URL=http://localhost:8787` -> `claude`. In the desktop app click "Stall stream 320 s" on the running session: Claude Code aborts with its documented 300-second watchdog and you watch which retry path it takes. Click "Corrupt next tool-call JSON": see the exact "tool call could not be parsed" failure from #63875 on demand instead of once a week. Point a Python agent at the same port and click "Mid-stream overloaded_error": the agent's fallback either fires or you have found the #1258 bug in your own code.

**Compose dashboard / control plane.** Live request table grouped by `x-claude-code-session-id` and agent id; per-request timeline (TTFB, chunk cadence, pings); toxic toolbar with per-route/per-model/per-session scope, probability and count; scenario recorder (sequence of toxics) with save/replay; a "what did the client do" panel (retry count, backoff timing, whether `retry-after` was honored). Control plane changes: enable/disable toxics live, set schedule ("every 7th request returns 429 without retry-after"), pin a scenario to a session id, kill switch.

**Solo MVP.** ~12 weeks: 3 (streaming pass-through for Anthropic + OpenAI, embedded SQLite/H2) + 3 (HTTP/SSE toxics) + 2 (MCP Streamable-HTTP toxics) + 3 (Compose UI + scenario store) + 1 (packaging). Main risk: error shapes must be byte-faithful to each provider's current API or the test is misleading, and they drift with API versions; secondary risk is that value only accrues when developers actually run resilience tests.

**Vs. baseline.** Complementary and the same chassis: the scheduler manages real 429s in production, the chaos module manufactures synthetic ones in test; both parse the same rate-limit headers and usage blocks. Evidence of pain is stronger here (a 116-upvote issue) but the always-on value is lower (test-time only). Best play: ship the scheduler as the daily-driver and chaos as its second tab.

---

## 2. Record/replay cassette proxy for LLM and MCP traffic

**Description.** The same proxy records every request/response pair (including the exact SSE chunk sequence and inter-chunk timing, usage blocks, rate-limit headers) into a cassette keyed on a normalized request fingerprint (model, system, tools, messages; volatile headers and the Claude Code attribution block stripped). Replay mode serves cassettes with zero upstream calls, so an agent's harness logic, tool wiring, retry paths and MCP plumbing run deterministically in CI. Covers Anthropic Messages, OpenAI chat/responses, and MCP over Streamable HTTP (stdio via a shim command).

**Evidence the problem is real.**
- arXiv 2607.16200 (submitted 2026-04-30) abstract: "Existing observability platforms capture execution logs but cannot reproduce a run in isolation" -- https://arxiv.org/abs/2607.16200
- openai-agents-python #4795 "Feature proposal: record live agent runs as deterministic replay fixtures" (opened 2026-08-31, closed 2026-09-05). Maintainer seratch: "implementing recording and replay safely requires careful handling of sensitive data... That is not a trivial implementation or maintenance commitment. Given our current priorities, we are not planning to pursue this in the near term" -- https://github.com/openai/openai-agents-python/issues/4795
- pydantic-ai #3138 (open, `needs-maintainer-action`, assigned): "Snapshot testing -- Not available"; proposes "First run creates snapshot, subsequent runs compare against it" -- https://github.com/pydantic/pydantic-ai/issues/3138
- Claude Code's own eval harness cannot run offline: "Every eval run and every judge grader is a real model call on your account, counted against your plan's usage or your API bill" -- https://code.claude.com/docs/en/plugin-evals. It does mock MCP servers ("A run never starts your plugin's real MCP servers unless you ask") but not the model.
- Inspect's cache is in-process only: cache key is "Model name and base URL... Model prompt... Epoch number... Generate configuration... Active `tools` and `tool_choice`", invoked via `--cache` or `cache=True` -- https://inspect.aisi.org.uk/caching.html. promptfoo caches "the results of API calls to LLM providers" inside its own evaluation runs; no proxy mode -- https://www.promptfoo.dev/docs/configuration/caching/

**Closest existing attempts.**
- Taiwrash/agrepl -- 30 stars, Go, MIT, last push 2026-09-07; the arXiv tool. "Transport-level MITM proxy" with "Automatic Root CA trust injection" on the roadmap; README does not mention Anthropic, OpenAI, MCP, or SSE; CLI only -- https://github.com/Taiwrash/agrepl. Generic HTTP MITM, so it needs CA trust and is protocol-blind (no usage/cost/tool-call awareness, no streaming semantics).
- leynos/spycatcher-harness -- 3 stars, Rust, ISC. "a record/replay HTTP proxy for OpenAI-compatible chat completions"; no Anthropic, no MCP, no GUI -- https://github.com/leynos/spycatcher-harness
- devhelmhq/mcp-recorder -- 9 stars, Python, last push 2026-03-24. "records and replays MCP server interactions"; MCP only, CLI only -- https://github.com/devhelmhq/mcp-recorder
- amosjyng/vcr-langchain -- 82 stars, Python, last push 2024-06-26 (dormant); LangChain decorator only -- https://github.com/amosjyng/vcr-langchain. sixty-north/langchain-replay -- 1 star. YuCPbit/loop-replay -- 0 stars (2026-08-22). kevin1024/vcrpy -- 3,008 stars but Python-only and protocol-blind.
- Why none satisfy: language-specific (vcrpy, vcr-langchain, langchain-replay, mcp-recorder), single-protocol (spycatcher, mcp-recorder), protocol-blind MITM requiring CA install (agrepl), none has a control plane, none records Anthropic streaming faithfully.

**Demo in 30 seconds.** Run the JAR, set `ANTHROPIC_BASE_URL`, run `claude -p "list the files in src and summarize"` once with the dashboard in Record. Flip to Replay, revoke the API key (or `unset` it -- Claude Code still sends requests to the base URL with its saved login header), run the same prompt: identical output in ~0 seconds, zero tokens billed, and the dashboard shows the cassette hits. Run it a third time with a changed prompt: the dashboard highlights the first divergent step and offers "pass-through and append".

**Compose dashboard / control plane.** Cassette browser (per session, per step, with usage and cost); mode switch per route (record / replay / replay-then-passthrough / strict); match-key editor (which fields are normalized, e.g. ignore timestamps in system, ignore tool order); redaction rules before write (the exact concern OpenAI cited); export a session as a portable cassette; diff view when a replay misses.

**Solo MVP.** ~13 weeks: 3 (proxy chassis, shared with #1) + 3 (cassette format + normalized matching + streaming replay with timing) + 2 (MCP Streamable HTTP + stdio shim) + 3 (UI) + 2 (redaction + export). Main risk: matching. Once a live run diverges from the cassette at step k, every later request differs; strict replay is only useful for harness/tool-wiring tests, not model-behavior tests. Claude Code adds machine-specific context ("Each conversation carries the working directory, platform, shell, and OS version" -- https://code.claude.com/docs/en/prompt-caching), so cassettes are per-machine unless normalized; the attribution block is stable only "From Claude Code v2.1.181".

**Vs. baseline.** Complementary. Different job (test-time determinism vs. production fairness), same wire position. Standalone it is weaker than the scheduler because its value is realized only when developers write tests, and six tiny prior attempts show the market has not rewarded a CLI-only version. Stronger if bundled: "the proxy you already run for scheduling also records, and replay is one click".

---

## 3. Desktop time-travel viewer: step through, diff two runs, fork from step N

**Description.** The Compose front-end over #2's captures: a per-session timeline of model calls and tool calls (from proxy traffic, not from any framework's logs), request-body diff between any two steps or two runs, and "fork here": edit the captured request N (prompt, a tool result, the system prompt), send it live upstream, and record the divergent branch as a sibling. Works for any client that speaks the protocol, so it covers Python/TypeScript agents that have no session log at all.

**Evidence the problem is real.**
- claude-code #32631 (43 upvotes, 10 comments, open since 2026-03-09): the gap table lists "Compare branches | (no) | No diff between session states" and "List/navigate branches | (no)"; it consolidates #10370 (19 upvotes), #12629 (29), #1417 (27) -- https://github.com/anthropics/claude-code/issues/32631. #39484 "[BUG] 'Fork Conversation from here' doesn't work" (14 upvotes, open) -- https://github.com/anthropics/claude-code/issues/39484
- Claude Code has `/branch` and `/rewind` but they act on the live session, not on captures; rewind "does not track files modified by bash commands" -- https://code.claude.com/docs/en/checkpointing; transcript format "is internal to Claude Code and changes between versions, so scripts that parse these files directly can break on any release" -- https://code.claude.com/docs/en/sessions. A proxy capture is version-stable because it is the API wire format.
- For non-Claude-Code agents, fork/replay is framework-locked: LangGraph time travel requires "A StateGraph compiled with a checkpointer" and is a Python/JS library feature -- https://docs.langchain.com/oss/python/langgraph/use-time-travel

**Closest existing attempts.**
- jhlee0409/claude-code-history-viewer -- 2,157 stars, Tauri; "Browse, search, and analyze conversations from Claude Code, Gemini CLI... 100% offline"; reads local logs; no proxy, no diff, no fork, no replay -- https://github.com/jhlee0409/claude-code-history-viewer
- d-kimuson/claude-code-viewer -- 1,282 stars, TS; daaain/claude-code-log -- 1,214 stars, Python, "converts Claude Code transcript JSONL files into readable HTML / Markdown"; delexw/claude-code-trace -- 364 stars, Tauri, "operates as read-only over local JSONL files... does not proxy API traffic"; HillviewCap/clog -- 24. All read Claude Code's private JSONL; none sees a Python agent, none diffs or forks.
- Hosted/heavy trace platforms: Langfuse (34,495 stars) self-host requires "Postgres... ClickHouse... Redis/Valkey... S3/Blob Storage" -- https://langfuse.com/self-hosting. LangSmith self-hosted "is an add-on to the Enterprise plan" needing PostgreSQL, ClickHouse, Redis -- https://docs.langchain.com/langsmith/self-hosted. Arize Phoenix (11,419 stars) does run locally with SQLite by default (`docker run -p 6006:6006 ... arizephoenix/phoenix:latest`), but ingestion is via `register(... auto_instrument=True)` and OpenInference instrumentors; no proxy mode found -- https://arize.com/docs/phoenix/tracing/llm-traces-1. Phoenix is the closest single-binary competitor for viewing; it does not fork or replay and needs code instrumentation.
- Why none satisfy: read-only viewers of one client's private log format; platforms needing 3-4 infra services or an Enterprise license; Phoenix needs SDK instrumentation and has no fork/replay.

**Demo in 30 seconds.** With the proxy running, run two `claude -p` prompts that differ by one word. Open the viewer, select both sessions, click Diff: it shows which step first diverged and the exact JSON delta (which also exposes cache-prefix breaks). Click step 3 of run A, edit the tool result, "Fork": the proxy re-issues step 3 upstream and the branch appears next to the original with its own usage and cost.

**Compose dashboard / control plane.** Tree of sessions and forks; step list with role, tool name, usage, cost, cache read/write, latency; body diff (structural JSON diff, not text); fork editor; "replay from here in strict mode" toggle. Control plane: choose upstream key/model for a fork, mark a branch as golden for #4.

**Solo MVP.** ~7 weeks on top of #2's capture layer (timeline 2, diff 2, fork 2, polish 1). Main risk: UI scope creep, and that Claude Code users already have `/branch`; the demo must lead with what Claude Code cannot do (cross-run diff, non-Claude-Code clients, fork without re-running tools).

**Vs. baseline.** Complementary: this is the visual layer any of these proxies needs. Weaker as a standalone bet because the crowded viewer space shows users accept read-only tools; stronger as the reason the baseline proxy has a desktop app at all.

---

## 4. Proxy-based regression assertions with CI thresholds

**Description.** A YAML spec evaluated against proxy captures (live or replayed): "tool X called with args matching Y", "no forbidden tool", "at most N model turns", "total cost under $Y" (computed from usage blocks and a price table), "stream never stalled > T s". Runs in replay mode for deterministic harness tests, or live with k repetitions and a pass-rate threshold. Emits JUnit XML so any CI consumes it; language-agnostic because it reads wire traffic.

**Evidence the problem is real.**
- pydantic-ai #3138 (open): "No semantic assertions: Can't easily assert 'tool X was called with args Y'"; proposes `expect_tool_called`, `expect_tools_called_in_order`, snapshot flows -- https://github.com/pydantic/pydantic-ai/issues/3138
- `claude plugin eval` shows Anthropic's own answer for one client: graders are `regex`, `tool_used`, `tool_order`, `file_exists`, `llm`, `baseline`; "There are no custom-code graders"; "each case runs three times by default"; a case passes when score meets `--threshold`; cost is reported as "a list-price estimate" but is not a grader; plugins only -- https://code.claude.com/docs/en/plugin-evals. No equivalent for a Python or TypeScript agent.
- Anthropic's general eval guide covers "code-based grading", "LLM-based grading" and embeddings, and says nothing about agent trajectories or tool-call assertions -- https://platform.claude.com/docs/en/test-and-evaluate/develop-tests. OpenAI trace grading needs traces "from an SDK-based app, or from an existing Agent Builder workflow" on the OpenAI platform -- https://developers.openai.com/api/docs/guides/agent-evals (local/non-OpenAI use not documented: **unverified**).

**Closest existing attempts.** promptfoo (25,039 stars, TS) -- evaluates prompts/providers it calls itself; its cache is internal. inspect_ai (2,754, Python) and deepeval (18,230, Python) -- in-process Python. jinngimk-lang/agentci (1 star, "Developer Preview") -- replay artifacts as evidence, Python. None observes an external agent's wire traffic or is language-agnostic.

**Demo in 30 seconds.** Record a `claude -p` run through the proxy, then `agentproxy assert spec.yaml --session <id>` where spec says `tool_called: Read, max_turns: 6, max_cost_usd: 0.05`. Output is a pass/fail table and a JUnit file. Re-run in replay mode in CI with no key.

**Compose dashboard / control plane.** Spec editor with autocomplete from observed tools; run history with pass-rate trend; threshold sliders; "promote this run to golden".

**Solo MVP.** ~5 weeks on top of #2. Main risk: replay-mode tests only verify harness logic (a point raised in #4795: "replay proves the same normalized call sequence happened rather than that the outcome was correct"); live-mode tests are flaky and cost money, so the spec language must support pass-rate thresholds from day one.

**Vs. baseline.** Weaker standalone; phase 2 of #2. Complementary to the scheduler (the cost and turn-count assertions read the same usage blocks the scheduler already parses).

---

## 5. Cache-break forensics module (reframed from "prompt-cache advisor")

**Description.** Not a standalone advisor: the proxy keeps every request body and usage block, applies the chain invariant `prev.cache_read + prev.cache_creation == next.cache_read`, and at each break diffs the two bodies to name the first divergent field, with the dollar cost of the break (5m vs 1h write price from usage.cache_creation buckets). Works offline, across sessions, and on gateways/Bedrock/Vertex where the vendor diagnostics do not.

**Evidence the problem is real** -- and that developers hand-roll exactly this.
- claude-code #81967 (open, 6 comments): "I ran a local mitmproxy reverse proxy in front of api.anthropic.com and captured 1,821 /v1/messages request bodies plus their usage blocks... Chain invariant used to detect a cache break: expected = prev.cache_read_input_tokens + prev.cache_creation_input_tokens; actual = next.cache_read_input_tokens"; found a 274,262-token break from a mid-session tools-array change -- https://github.com/anthropics/claude-code/issues/81967
- claude-code #84253 (open): scanned "236 primary session transcripts" with a "drop-detection script (`cache-forensics-scan.py`)"; "13.8% of total session cost across 2 weeks is attributable to the 1h->5m switch" -- https://github.com/anthropics/claude-code/issues/84253
- claude-code #49038 (closed not planned): "Intercepted two consecutive API calls... Diffed the request bodies field-by-field and found `tools[0]`... differed"; 56,296 cache-create tokens became 32 after sorting -- https://github.com/anthropics/claude-code/issues/49038

**Why it is only a feature now.**
- Anthropic shipped cache diagnostics (beta header `cache-diagnosis-2026-04-07`): returns `cache_miss_reason` of `model_changed | system_changed | tools_changed | messages_changed | previous_message_not_found | unavailable`. Limits: "Claude API only: Not available on Amazon Bedrock or Google Cloud"; "Limited retention: Fingerprints for `previous_message_id` lookup expire after a short period"; "Comparison horizon: For very long conversations... the response may be `unavailable`"; `unavailable` also when `thinking`, `context_management`, `output_config` or the beta-header set differs -- https://platform.claude.com/docs/en/build-with-claude/cache-diagnostics
- Claude Code v2.1.251+ prints a `Prompt cache (main)` line in `/usage` with misses and, from v2.1.260, "likely cause: tool definitions changed"; "It covers the main conversation only, not subagents" -- https://code.claude.com/docs/en/costs
- OpenAI ships `prompt_cache_options.comparison_response_id` returning `prompt_cache_diagnostics` with `reason` such as `tools_changed`, for "GPT-5.6 and later supported models" via the Responses API -- https://developers.openai.com/api/docs/guides/prompt-caching/diagnostics
- Mechanics to implement against (verified): "a cache entry only becomes available after the first response begins"; "The lookback window is 20 blocks"; hierarchy "`tools` -> `system` -> `messages`. Changes at each level invalidate that level and all subsequent levels"; "Verify that the keys in your `tool_use` content blocks have stable ordering" -- https://platform.claude.com/docs/en/build-with-claude/prompt-caching

**Closest existing attempts.** None as a product; the three issue authors used mitmproxy (45,020 stars, Python, generic) plus private scripts. Anthropic/OpenAI/Claude Code cover the online consecutive-request case.

**Demo in 30 seconds.** Run a Claude Code session through the proxy, toggle an MCP server mid-session (behind a custom base URL, tool search is off and MCP tools "load into the prefix", so this invalidates the cache -- https://code.claude.com/docs/en/prompt-caching); the dashboard flags the break, names `tools[]` as the first divergence, and prints the token and dollar cost.

**Compose dashboard / control plane.** Per-session cache-hit strip chart; break markers with cause and cost; "aggregate breaks by cause across all sessions this week". Control plane: none needed beyond retention.

**Solo MVP.** 2-3 weeks on top of any capture-capable proxy. Main risk: mostly redundant for direct-API users now that vendor diagnostics exist; residual audience is gateway/Bedrock/Vertex users and anyone wanting cross-session dollar attribution.

**Vs. baseline.** Weaker; but the baseline scheduler already parses usage and cache fields per request, so this is the cheapest add-on in the list. Complementary.

---

## 6. MCP toxics (extension of #1)

**Description.** Fault injection at the MCP layer: delayed or hung `tools/call`, tool results returning `isError`, malformed JSON-RPC frames, `notifications/tools/list_changed` with a drifted schema, server disconnect mid-call, elicitation timeouts. Only Streamable HTTP can be proxied transparently; stdio needs the proxy to wrap the server command.

**Evidence.** mcpsnoop's feature list shows these are observed failure modes: it "Flags hung calls, stream errors, malformed JSON-RPC frames" and "Detects tool definition drift after approval" -- https://github.com/kerlenton/mcpsnoop (347 stars, Go, terminal UI, last push 2026-09-11). Claude Code documents the client-side consequences: "a stdio server's process exits, an HTTP session expires, or a server reconnects automatically after a transient failure" invalidates the prompt cache when tools are in the prefix -- https://code.claude.com/docs/en/prompt-caching. **Unverified**: no issue with meaningful upvotes explicitly asks for MCP fault injection; the evidence is that the failure modes are real, not that developers want to inject them.

**Closest attempts.** mcpsnoop (record/replay/edit, no injection, no GUI); modelcontextprotocol/inspector (10,864 stars, TS, interactive testing, no injection); modelcontextprotocol/conformance (119 stars, "Conformance Tests for MCP"; spec conformance, not client resilience); craigm26/mcp-tape (2 stars). Nobody injects faults.

**Demo / dashboard / MVP.** Same as #1 with an MCP tab; ~2 weeks inside #1's budget. Risk: stdio wrapping is fiddly on Windows; audience (MCP host authors) is smaller than LLM-API agent authors.

**Vs. baseline.** Complementary; a tab in the chaos module.

---

## Investigated and dropped

- **Context-window budget middleware (brief #6).** Redundant and actively harmful behind Claude Code. Anthropic ships server-side tool-result clearing (`clear_tool_uses_20250919`, beta `context-management-2025-06-27`) and server-side compaction (`compact_20260112`, beta `compact-2026-01-12`, on Claude API, Bedrock, Google Cloud and Foundry) -- https://platform.claude.com/docs/en/build-with-claude/context-editing and https://platform.claude.com/docs/en/build-with-claude/compaction. Claude Code auto-compacts with a configurable window (`/autocompact 500k`, `CLAUDE_CODE_AUTO_COMPACT_WINDOW`) -- https://code.claude.com/docs/en/model-config. A proxy that edits bodies "breaks the pairing the same way stripping does, so inspect without modifying" -- https://code.claude.com/docs/en/llm-gateway-protocol -- and any edit to earlier messages is a `messages_changed` cache miss.
- **Standalone prompt-cache advisor (brief #5).** Vendor-covered on both sides since April 2026 (Anthropic `cache-diagnosis-2026-04-07`; OpenAI `prompt_cache_diagnostics`) plus Claude Code's `/usage` line with likely cause. Survives only as module #5.
- **Another Claude Code JSONL viewer.** Saturated: claude-code-history-viewer 2,157 stars, claude-code-viewer 1,282, claude-code-log 1,214, claude-code-trace 364, ccusage 18,505 (cost reports). And the format "is internal to Claude Code and changes between versions" -- https://code.claude.com/docs/en/sessions.
- **Hosted-trace-platform clone (Langfuse/LangSmith/Phoenix).** Langfuse 34,495 stars, Phoenix 11,419 already runs single-container with SQLite; competing on viewing alone loses. Only the proxy-capture + fork angle (#3) is unoccupied.
- **MCP inspector clone.** modelcontextprotocol/inspector 10,864 stars; mcp-use/inspector 26; mcpsnoop 347 covers the transparent-tap niche.
- **Client-side retry/queue library.** SDKs "automatically retry transient failures... twice by default" (https://platform.claude.com/docs/en/api/errors); Claude Code retries "Up to 10 times" (https://code.claude.com/docs/en/errors); openai-agents-python marked rate-limit support wontfix (#782). A proxy-side scheduler (the baseline) is the right layer, not a library.
- **Fork/branch for Claude Code sessions as a product.** Claude Code has `/branch`, `--fork-session`, `/rewind`; what is missing is diff and merge (#32631), and diff is folded into #3.

## Could not verify

- Bifrost fault-injection support: no primary source found; the dev.to article used a Toxiproxy sidecar.
- LiteLLM stripping `mock_testing_fallbacks` from proxy requests since v1.85.0: from a search summary only.
- Whether OpenAI trace grading works with non-OpenAI models or locally: docs do not say.
- Reaction counts on claude-code #35801 and #45674 (529 handling): titles seen in search only, not fetched.
- The "mockllm" provider in inspect_ai: not found on the providers or models pages.
- Whether Phoenix has any proxy ingestion mode: none found on the tracing page; treated as instrumentation-only.
- Demand for MCP fault injection specifically (candidate 6): failure modes verified, demand not.

## Source index

Docs: https://code.claude.com/docs/en/llm-gateway , https://code.claude.com/docs/en/llm-gateway-protocol , https://code.claude.com/docs/en/llm-gateway-connect , https://code.claude.com/docs/en/errors , https://code.claude.com/docs/en/costs , https://code.claude.com/docs/en/prompt-caching , https://code.claude.com/docs/en/model-config , https://code.claude.com/docs/en/plugin-evals , https://code.claude.com/docs/en/sessions , https://code.claude.com/docs/en/checkpointing , https://code.claude.com/docs/en/mcp , https://platform.claude.com/docs/en/build-with-claude/cache-diagnostics , https://platform.claude.com/docs/en/build-with-claude/prompt-caching , https://platform.claude.com/docs/en/build-with-claude/context-editing , https://platform.claude.com/docs/en/build-with-claude/compaction , https://platform.claude.com/docs/en/api/errors , https://platform.claude.com/docs/en/api/rate-limits , https://platform.claude.com/docs/en/build-with-claude/streaming , https://platform.claude.com/docs/en/test-and-evaluate/develop-tests , https://developers.openai.com/api/docs/guides/rate-limits , https://developers.openai.com/api/docs/guides/prompt-caching , https://developers.openai.com/api/docs/guides/prompt-caching/diagnostics , https://developers.openai.com/api/docs/guides/agent-evals , https://modelcontextprotocol.io/specification/2026-07-28 , https://inspect.aisi.org.uk/caching.html , https://www.promptfoo.dev/docs/configuration/caching/ , https://docs.litellm.ai/docs/completion/mock_requests , https://langfuse.com/self-hosting , https://docs.langchain.com/langsmith/self-hosted , https://arize.com/docs/phoenix/tracing/llm-traces-1 , https://docs.langchain.com/oss/python/langgraph/use-time-travel , https://arxiv.org/abs/2607.16200

Issues: anthropics/claude-code #63875 #75953 #60577 #81967 #84253 #49038 #32631 #39484 #12629 #1417 #10370 #323; anthropics/anthropic-sdk-python #1258; anthropics/anthropic-sdk-typescript #842; openai/openai-agents-python #4795 #2061 #325 #782; pydantic/pydantic-ai #3138.

Repos (stars 2026-09-12): Taiwrash/agrepl 30; leynos/spycatcher-harness 3; devhelmhq/mcp-recorder 9; amosjyng/vcr-langchain 82; sixty-north/langchain-replay 1; YuCPbit/loop-replay 0; theblixguy/llm-mock-server 3; Rhugved-Kale/ChaosLLM 0; goshipra/ai-agent-chaos 0; navyabijoy/chaosline 1; kerlenton/mcpsnoop 347; craigm26/mcp-tape 2; jinngimk-lang/agentci 1; jhlee0409/claude-code-history-viewer 2,157; d-kimuson/claude-code-viewer 1,282; daaain/claude-code-log 1,214; delexw/claude-code-trace 364; HillviewCap/clog 24; ryoppippi/ccusage 18,505; Shopify/toxiproxy 12,333; mitmproxy/mitmproxy 45,020; kevin1024/vcrpy 3,008; promptfoo/promptfoo 25,039; UKGovernmentBEIS/inspect_ai 2,754; confident-ai/deepeval 18,230; langfuse/langfuse 34,495; Arize-ai/phoenix 11,419; BerriAI/litellm 58,546; maximhq/bifrost 7,990; modelcontextprotocol/inspector 10,864; modelcontextprotocol/conformance 119; mcp-use/inspector 26.
