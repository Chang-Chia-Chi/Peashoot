# AI/LLM tooling landscape — saturation map and gap candidates (Sept 2026)

Researched 2026-09-12 against primary sources only (official specs/docs, source repos, GitHub issues, arXiv). Star counts are from the GitHub API on 2026-09-12 unless noted. Spec/doc revision dates are given inline. Anything I could not confirm from a primary source is marked **unverified**.

Target reader: a Kotlin/JVM backend engineer with distributed-systems projects (Dynamo-style cache with consistent hashing / SWIM / DVVs / Merkle anti-entropy / W-TinyLFU, a Zanzibar-style authz engine, NATS+MinIO pipelines with retry pools, MapReduce, TrackNet CV).

---

## PART A — Saturation map (what is already commodity)

| Category | Verdict | Evidence (repo, stars) |
|---|---|---|
| RAG frameworks | Saturated | [langchain-ai/langchain](https://github.com/langchain-ai/langchain) 146.2k · [run-llama/llama_index](https://github.com/run-llama/llama_index) 52.1k · [deepset-ai/haystack](https://github.com/deepset-ai/haystack) 26.5k |
| Chat UIs | Saturated | [open-webui/open-webui](https://github.com/open-webui/open-webui) 151.7k · [danny-avila/LibreChat](https://github.com/danny-avila/LibreChat) 43.1k · LobeChat (star count not fetched; rate-limited) |
| Generic agent frameworks | Saturated | [microsoft/autogen](https://github.com/microsoft/autogen) 60.9k · [crewAIInc/crewAI](https://github.com/crewAIInc/crewAI) 58.4k · [langchain-ai/langgraph](https://github.com/langchain-ai/langgraph) 41.5k · [openai/openai-agents-python](https://github.com/openai/openai-agents-python) 29.4k · [langchain-ai/deepagents](https://github.com/langchain-ai/deepagents) 29.3k |
| MCP servers | Saturated | [modelcontextprotocol/servers](https://github.com/modelcontextprotocol/servers) 90.3k, README lists 7 reference servers and points to the registry for everything else. Official registry [modelcontextprotocol/registry](https://github.com/modelcontextprotocol/registry) 7.2k stars; its `/v0/servers` API returns only per-page `count` + `nextCursor`, no global total (**total count unverified**). Third-party directory PulseMCP shows "Last Update: 21,936 Servers" ([pulsemcp.com/servers](https://www.pulsemcp.com/servers)). |
| LLM gateways / routers | Saturated | [BerriAI/litellm](https://github.com/BerriAI/litellm) 58.5k ("Rust core with Python SDK") · [Portkey-AI/gateway](https://github.com/Portkey-AI/gateway) 13.0k · [maximhq/bifrost](https://github.com/maximhq/bifrost) ~8k (Go, from repo page) · [agentgateway/agentgateway](https://github.com/agentgateway/agentgateway) 4.8k (Rust) · [IBM/mcp-context-forge](https://github.com/IBM/mcp-context-forge) 4.5k (Python) · [microsoft/mcp-gateway](https://github.com/microsoft/mcp-gateway) 827 (C#) |
| Eval frameworks | Saturated (Python/TS) | [promptfoo/promptfoo](https://github.com/promptfoo/promptfoo) 25.0k (TS) · [confident-ai/deepeval](https://github.com/confident-ai/deepeval) 18.2k · [UKGovernmentBEIS/inspect_ai](https://github.com/UKGovernmentBEIS/inspect_ai) 2.75k · Ragas (not fetched; rate-limited) |
| Prompt management | Saturated | [langfuse/langfuse](https://github.com/langfuse/langfuse) 34.5k · [Agenta-AI/agenta](https://github.com/Agenta-AI/agenta) 4.7k |
| Vector DBs | Saturated | [milvus-io/milvus](https://github.com/milvus-io/milvus) 46.1k · [qdrant/qdrant](https://github.com/qdrant/qdrant) 34.5k · [weaviate/weaviate](https://github.com/weaviate/weaviate) 16.8k |
| Local inference runtimes | Saturated | [ollama/ollama](https://github.com/ollama/ollama) 180.7k · [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp) 127.9k · [vllm-project/vllm](https://github.com/vllm-project/vllm) 91.5k · [sgl-project/sglang](https://github.com/sgl-project/sglang) 35.8k |
| Agent memory | Saturated at the product level | [mem0ai/mem0](https://github.com/mem0ai/mem0) 65.2k · [getzep/graphiti](https://github.com/getzep/graphiti) 30.8k · [letta-ai/letta](https://github.com/letta-ai/letta) 24.7k (all Python; see Gap 5 for what is *not* solved) |
| Observability | Saturated (platforms) | Langfuse 34.5k · [Arize-ai/phoenix](https://github.com/Arize-ai/phoenix) 11.4k. Conventions are NOT stable: see Gap 10 |
| Durable execution engines | Saturated (engines) | [restatedev/restate](https://github.com/restatedev/restate) 4.4k · [inngest/inngest](https://github.com/inngest/inngest) 5.8k · [temporalio/sdk-java](https://github.com/temporalio/sdk-java) 431 · DBOS ([py](https://github.com/dbos-inc/dbos-transact-py) 1.6k, [ts](https://github.com/dbos-inc/dbos-transact-ts) 1.35k, [go](https://github.com/dbos-inc/dbos-transact-golang) 830, [java](https://github.com/dbos-inc/dbos-transact-java) 238) |
| Constrained decoding | Saturated (Python/C++/Rust) | [dottxt-ai/outlines](https://github.com/dottxt-ai/outlines) 15.8k · [mlc-ai/xgrammar](https://github.com/mlc-ai/xgrammar) 1.9k · [guidance-ai/llguidance](https://github.com/guidance-ai/llguidance) 863 |
| Distributed KV-cache | Saturated by vendors | [LMCache/LMCache](https://github.com/LMCache/LMCache) 11.8k · [ai-dynamo/dynamo](https://github.com/ai-dynamo/dynamo) 8.0k (Rust, NVIDIA) · [kvcache-ai/Mooncake](https://github.com/kvcache-ai/Mooncake) 6.6k (C++) · [llm-d/llm-d](https://github.com/llm-d/llm-d) 4.5k (CNCF sandbox) |
| Code sandboxes | Saturated | [e2b-dev/E2B](https://github.com/e2b-dev/E2B) 13.8k · Modal Sandboxes ("secure containers for executing untrusted user or agent code", default 5-min lifetime, [docs](https://modal.com/docs/guide/sandbox)) · Codex sandboxing (macOS Seatbelt, Linux bubblewrap; [docs](https://learn.chatgpt.com/codex/sandboxing)) |

### JVM/Kotlin side vs Python — honest read

| Project | Version / date | Stars | Notes |
|---|---|---|---|
| [langchain4j/langchain4j](https://github.com/langchain4j/langchain4j) | active | 13.1k, 875 open issues | JSON-schema structured output "does not work in the streaming mode for OpenAI yet" ([docs](https://docs.langchain4j.dev/tutorials/structured-outputs)); AI-service observability "is an experimental feature" ([docs](https://docs.langchain4j.dev/tutorials/observability)) |
| [spring-projects/spring-ai](https://github.com/spring-projects/spring-ai) | 2.0.1 stable | 9.4k, 1,497 open issues | Feature list marks "MCP Security (WIP)" ([ref docs](https://docs.spring.io/spring-ai/reference/index.html)); evaluation = 2 evaluators only ([testing docs](https://docs.spring.io/spring-ai/reference/api/testing.html)) |
| [JetBrains/koog](https://github.com/JetBrains/koog) | 1.2.0 (additions module 1.2.0-beta) | 4.6k | Kotlin-first agent framework; persistence, MCP, A2A, W&B/Langfuse |
| [modelcontextprotocol/java-sdk](https://github.com/modelcontextprotocol/java-sdk) | v2.0.1, 2026-08-19 | 3.7k, 271 open issues | Release notes: "Upgrade conformance suite to 0.1.16"; [issue #668 "SEP-1686: Tasks Support"](https://github.com/modelcontextprotocol/java-sdk/issues/668) open since 2025-11-15 |
| [modelcontextprotocol/kotlin-sdk](https://github.com/modelcontextprotocol/kotlin-sdk) | 0.15.0, 2026-07-28 (pre-1.0) | 1.45k, 76 open issues | [Tracking issue #842 "tracking issue for 2026-07-28 Spec implementation"](https://github.com/modelcontextprotocol/kotlin-sdk/issues/842): body "No description provided", GitHub sub-issue progress shows 0 of 19 completed (as of 2026-09-12). Per the [SDK tier rules](https://modelcontextprotocol.io/community/sdk-tiers), Tier 1/2 require a "Stable Release … version `1.0.0` or higher"; 0.15.0 does not qualify. |
| Python/TS MCP SDKs (for contrast) | TS v2 "First beta release of SDK v2 with support for the MCP 2026-07-28 specification revision" ([release 2026-07-27](https://github.com/modelcontextprotocol/typescript-sdk/releases/tag/%40modelcontextprotocol/fastify%402.0.0)) | python-sdk 24.3k · typescript-sdk 13.4k · go 5.1k · csharp 4.5k · rust 3.9k | The [conformance repo](https://github.com/modelcontextprotocol/conformance) README lists known SDKs as TypeScript, Go, C#, Rust, Python (Kotlin/Java not named in the fetched README extract) |
| [a2aproject/a2a-java](https://github.com/a2aproject/a2a-java) | 1.3.0_Final | 490 (vs a2a-python 2.1k) | JSON-RPC, gRPC, REST; no Kotlin A2A SDK exists (GitHub search `a2a agent2agent language:Kotlin` → 0 results) |
| OTel Java auto-instrumentation | — | — | `supported-libraries.md` lists exactly one GenAI library: "OpenAI Java SDK 1.1+" ([source](https://raw.githubusercontent.com/open-telemetry/opentelemetry-java-instrumentation/main/docs/supported-libraries.md)) |
| JVM LLM eval frameworks | — | — | GitHub search `LLM evaluation framework language:Java OR language:Kotlin stars:>20` → 0 results |
| JVM agent-memory stores | — | — | GitHub search `agent memory language:Kotlin OR language:Java llm long-term memory stars:>30` → 0 results |
| JVM MCP gateways | — | — | GitHub search `mcp gateway proxy language:Kotlin` → 0 results; the only JVM hit for `mcp gateway proxy stars:>500` is [aklivity/zilla](https://github.com/aklivity/zilla) 1.7k (Java, general multi-protocol gateway) |

Bottom line: the JVM has one framework per niche (LangChain4j, Spring AI, Koog) but the official Kotlin MCP SDK is a full spec revision behind, and there is essentially nothing JVM-native for gateways, eval, memory, or MCP-tasks infrastructure.

---

## PART B — Gap candidates

Ordered by (strength of primary-source evidence × fit for this engineer). Each has: description, primary-source evidence, closest existing attempts, why a distsys engineer fits, and MVP scope.

### Gap 1 — Token-aware, provider-faithful admission control and fair scheduling for LLM API traffic (JVM library + sidecar)

**Description.** An org-wide admission controller that models provider rate limits exactly as providers enforce them (token buckets on *tokens*, per model class, cache-aware), learns remaining capacity from provider response headers, and schedules competing tenants/agents with weighted fair queuing and priority — as a standalone library/sidecar rather than a feature buried in a full gateway.

**Evidence it is a gap.**
- Anthropic rate-limit docs ([platform.claude.com/docs/en/api/rate-limits](https://platform.claude.com/docs/en/api/rate-limits), fetched 2026-09-12): "The API uses the token bucket algorithm to do rate limiting"; "For most Claude models, only uncached input tokens count toward your ITPM rate limits"; "OTPM rate limits are evaluated in real time as output tokens are produced … The `max_tokens` parameter does not factor into OTPM"; per-limit headers `anthropic-ratelimit-input-tokens-remaining`, `-output-tokens-remaining`, `-reset`; workspace limits exist but "You can't set limits on the default Workspace" and "Organization-wide limits always apply, even if Workspace limits add up to more." Spend-cap 429s carry "no `retry-after` header".
- OpenAI rate-limit docs ([developers.openai.com/api/docs/guides/rate-limits](https://developers.openai.com/api/docs/guides/rate-limits)): "Your rate limit is calculated as the maximum of `max_tokens` and the estimated number of tokens based on the character count of your request"; "unsuccessful requests contribute to your per-minute limit"; "Rate limits are defined at the organization level and at the project level, not user level."
  These two accounting models differ (cache-aware ITPM vs `max_tokens`-inclusive TPM); no fetched gateway doc models the difference.
- LiteLLM's own docs on the closest features: request prioritization is "Beta feature. Use for testing only" ([scheduler docs](https://docs.litellm.ai/docs/scheduler)); dynamic rate limiting notes "Actual tokens served in a window can exceed the configured TPM by roughly one request's tokens per concurrently sending key" and multi-node saturation detection is delayed by local caching "up to 60 seconds", with priority reservation "[BETA]" and enterprise-gated ([dynamic_rate_limit](https://docs.litellm.ai/docs/proxy/dynamic_rate_limit)); multi-instance limits drift "at most 10 requests at high-traffic (100 RPS across 3 instances)" ([users docs](https://docs.litellm.ai/docs/proxy/users)). [Issue #20996 "Team-Level Dynamic Fair-Share Rate Limiting"](https://github.com/BerriAI/litellm/issues/20996) was "Closed as not planned".

**Closest attempts and why they fall short.** LiteLLM (58.5k), Portkey (13.0k), Bifrost (~8k) all bundle per-key TPM/RPM and budgets inside a full proxy; none of the fetched docs describe cache-aware ITPM accounting, header-driven adaptive limits, or weighted fair queuing across tenants as a reusable component. **Unverified:** I did not find any OSS implementation of Anthropic's uncached-only ITPM semantics; absence in the docs fetched is not proof.

**Fit.** This *is* the Dynamo-cache skill set: token buckets, W-TinyLFU-style admission, consistent hashing to shard buckets across replicas, gossip for approximate global counters.

**Scope.** MVP in 4–8 weeks: Kotlin library (`suspend fun acquire(model, estTokens, tenant, priority)`), Redis/in-memory shard backends, provider-header feedback loop, WFQ scheduler, a load-test harness that replays real `usage` payloads. Sidecar/Envoy-ext-proc can come later.

### Gap 2 — Durable MCP Tasks + event-delivery backend (JVM)

**Description.** A server-side runtime for the MCP Tasks extension and `subscriptions/listen`: a persistent task store (Postgres/Redis), replicated fan-out of `notifications/tasks` across horizontally scaled stateless MCP servers, TTL/expiry management, and (ahead of the spec) ordered webhook delivery — packaged for Ktor/Spring.

**Evidence it is a gap.**
- MCP spec revision **2026-07-28** ([changelog](https://modelcontextprotocol.io/specification/latest/changelog)): removed sessions and `Mcp-Session-Id`, made MCP stateless, removed SSE resumability ("A broken response stream loses the in-flight request"), and moved Tasks to an extension with "polling via `tasks/get`".
- Tasks extension spec ([ext-tasks, 2026-07-28](https://raw.githubusercontent.com/modelcontextprotocol/ext-tasks/main/specification/2026-07-28/tasks.md)): "A server **MUST NOT** return `CreateTaskResult` until the task is durably created — that is, until a `tasks/get` for the returned `taskId` would resolve"; in eventually-consistent environments "the server **MUST** wait for consistency before responding"; "Servers **MAY** mark a task as `failed` at any point after the TTL elapses". The spec does not prescribe storage or delivery guarantees.
- Tasks overview ([modelcontextprotocol.io/extensions/tasks/overview](https://modelcontextprotocol.io/extensions/tasks/overview)): clients must "Persist task IDs … so polling can resume after a client crash or restart"; "Polling is the default."
- Triggers & Events WG charter ([2026-03-24](https://modelcontextprotocol.io/community/working-groups/triggers-events)): "Today, clients learn about server-side updates by polling or holding an SSE connection open"; deliverable "SEP: Events in MCP v1 RFC — Ideating". Roadmap ([2026-08-22](https://modelcontextprotocol.io/development/roadmap)): "we need extensions that let servers tell clients when work has finished, without relying purely on expensive client-side polling."
- SDK reality: TypeScript SDK ships only `InMemoryTaskStore` (issues [#2032](https://github.com/modelcontextprotocol/typescript-sdk/issues/2032), [#2020](https://github.com/modelcontextprotocol/typescript-sdk/issues/2020)); Java SDK [#668 Tasks support](https://github.com/modelcontextprotocol/java-sdk/issues/668) open since 2025-11-15; Kotlin SDK tracking issue #842 at 0/19.

**Closest attempts.** [microsoft/mcp-gateway](https://github.com/microsoft/mcp-gateway) (827, C#) advertises "session-aware stateful routing" — built for the pre-2026-07-28 session model the spec just removed. Durable-execution engines (Restate, Temporal, DBOS) could host this but none ship an MCP Tasks binding (Restate has [ai-examples](https://github.com/restatedev/ai-examples), 87 stars, Python).

**Fit.** Exactly-once-ish task state machines, TTL GC, fan-out with ordering across replicas, at-least-once webhooks with retry pools (the NATS pipeline project).

**Scope.** MVP 4–8 weeks: Kotlin `TaskStore` (Postgres + Redis), `tasks/get|update|cancel` handlers, `subscriptions/listen` fan-out via Redis streams/NATS, conformance-test run. Bonus: an MRTR `requestState` helper, since the spec requires servers to "protect its integrity (e.g. HMAC or AEAD)" and include principal, TTL and request digest ([MRTR](https://modelcontextprotocol.io/specification/2026-07-28/basic/patterns/mrtr)).

### Gap 3 — Delegation-aware, relationship-based authorization for agent tool calls (Zanzibar/macaroons for MCP)

**Description.** A policy decision point + MCP-proxy enforcement point where each tool call is checked against (a) relationship tuples (who owns/can act on which resource), (b) a delegation chain from user → agent → sub-agent with monotonic attenuation of capabilities, and (c) optional temporal constraints. Today's engines are point-in-time and identity-flat.

**Evidence it is a gap.**
- MCP roadmap ([2026-08-22](https://modelcontextprotocol.io/development/roadmap), Priority 3): "MCP authorization assumes a person with a browser at consent time. Increasingly the caller is an agent: a cloud workload with its own identity, acting for a user who isn't present, or spawning sub-agents that should get narrower authority than their parent. Existing MCP servers lean on pasted API keys and long-lived refresh tokens. We need a standardized way for MCP servers to handle agent identities" — Agent Identity WG is "forming during this roadmap period".
- [SEP-1933 Workload Identity Federation](https://github.com/modelcontextprotocol/modelcontextprotocol/pull/1933): status Draft; single-hop token exchange, nothing on sub-agent delegation/attenuation.
- AWS Dogwood ([AWS blog, 2026-08-06](https://aws.amazon.com/blogs/opensource/introducing-dogwood-runtime-verification-for-ai-agents/)): extends Cedar with temporal conditions; authors note "temporal conditions do not currently support the powerful automated reasoning analysis tools that Cedar provides" and list "Orchestration for multi-agent systems" as future work. Repo [dogwood-policy/dogwood](https://github.com/dogwood-policy/dogwood): 384 stars, Rust, created 2026-07-27.
- Claude Code permissions ([code.claude.com/docs/en/permissions](https://code.claude.com/docs/en/permissions)): "A Bash rule … isn't a security boundary around the program"; "You can't match a tool's primary content field this way"; recommends sandboxing/hooks for real enforcement — i.e., the hosted agent's own rules are prefix matchers, not a policy engine.

**Closest attempts.** OPA/Cedar (point-in-time, no delegation semantics), Dogwood (temporal sequences, single agent), [agentgateway](https://github.com/agentgateway/agentgateway) 4.8k (Rust, RBAC), [hoophq/hoop](https://github.com/hoophq/hoop) 810, [apache/casbin-gateway](https://github.com/apache/casbin-gateway) 623. None fetched describe attenuated delegation chains or ReBAC tuples for agents.

**Fit.** Directly extends the existing Zanzibar-style engine: add a `delegation` relation type, macaroon/biscuit-style caveats for attenuation, and an enforcement proxy that uses the new `Mcp-Method`/`Mcp-Name` headers (spec: intermediaries can "route and inspect requests without parsing the body").

**Scope.** 2–3 months. MVP: tuple store + check API + Kotlin MCP proxy that rewrites/denies `tools/call`; demo with a parent agent spawning a sub-agent that receives a strictly narrower token.

### Gap 4 — Smarter KV/prefix-cache retention and eviction policies (W-TinyLFU / ARC for KV blocks)

**Description.** vLLM and LMCache evict KV blocks with plain LRU; multiple open RFCs ask for frequency-, priority-, session- or queue-aware retention. A pluggable eviction policy + trace-driven simulator (and an upstream PR) is a concrete, well-scoped contribution.

**Evidence it is a gap.**
- vLLM design doc ([prefix_caching](https://docs.vllm.ai/en/latest/design/prefix_caching.html)): "Pop the block from the head of the free queue. This is the LRU block to be evicted."
- [vLLM RFC #37003 "Context-Aware KV-Cache Retention API (Prioritized Evictions)"](https://github.com/vllm-project/vllm/issues/37003) (open, 7 👍): agent turns pause on tool calls "40–60% of session time", blocks get "evicted via LRU by competing agents", "LRU sees only recency".
- [#40268 "KV Cache Eviction support ARC"](https://github.com/vllm-project/vllm/issues/40268) (open, 3 👍); [#47802 "Priority scheduling is not reflected in KV/prefix cache retention under cache pressure"](https://github.com/vllm-project/vllm/issues/47802); [#48485 "Waiting-Queue-Informed LRU for Prefix Cache Eviction"](https://github.com/vllm-project/vllm/issues/48485); [#45405 session-aware KV eviction](https://github.com/vllm-project/vllm/issues/45405) — 51 matching open issues in total.

**Closest attempts.** LMCache 11.8k / Mooncake 6.6k / Dynamo 8.0k / llm-d 4.5k solve *placement and transfer*; the eviction policy inside the engine is still LRU per the design doc. The RFC authors report "a working implementation" but it is not merged.

**Fit.** The W-TinyLFU implementer's home turf: frequency sketches, admission filters, segmented LRU, and a replayable simulator (block-hash traces are already what vLLM computes: "Parent hash value" + "Block tokens").

**Scope.** Simulator + policy library in 3–6 weeks (Kotlin or Python; the simulator can be JVM). Upstream PR to vLLM/LMCache is Python and has an uncertain review timeline — treat the simulator + benchmark writeup as the portfolio artifact.

### Gap 5 — Agent memory store with real concurrency and conflict semantics (JVM)

**Description.** A multi-tenant memory service where "add fact" is idempotent, updates use CAS/versions, contradictory facts are tracked as versioned siblings (DVV-style) and resolved explicitly, and dedup is race-free — the properties the leading Python stores are currently missing.

**Evidence it is a gap (all open Mem0 issues).**
- [#6515](https://github.com/mem0ai/mem0/issues/6515) "hash-dedup TOCTOU race in add() creates permanent duplicate memories under concurrency": "can create a permanent duplicate memory when two concurrent calls extract the identical fact for the same scope"; same for the TS port [#6531](https://github.com/mem0ai/mem0/issues/6531).
- [#6243](https://github.com/mem0ai/mem0/issues/6243) entity-store TOCTOU race; [#4892](https://github.com/mem0ai/mem0/issues/4892) "concurrent AsyncMemory writes corrupt Qdrant HNSW index" (13 comments).
- [#5867](https://github.com/mem0ai/mem0/issues/5867) "ADD-only memory extraction can create conflicting memories when a user updates or replaces a previously stored preference or fact" (P2, open).

**Closest attempts.** Mem0 65.2k, Graphiti 30.8k, Letta 24.7k — all Python; no JVM memory store with >30 stars found (GitHub search, 2026-09-12).

**Fit.** DVVs and anti-entropy were built for exactly "two writers added the same/conflicting fact". Vector search can be delegated (pgvector/Qdrant); the value is the write path and consistency model.

**Scope.** 6–8 weeks: Kotlin service with Postgres+pgvector, fact versioning with dotted version vectors, idempotency keys, an LLM-driven reconcile step that runs *after* a CAS, and a concurrency test suite that reproduces Mem0's #6515 scenario.

### Gap 6 — MCP-aware caching/routing reverse proxy for the stateless-HTTP era (Kotlin)

**Description.** A thin L7 proxy that understands 2026-07-28 MCP: honors `ttlMs`/`cacheScope` on list results and resource reads, serves `server/discover` from cache, routes/limits on `Mcp-Method`/`Mcp-Name`/`Mcp-Param-*` headers, validates header–body consistency, normalizes `tools/list` ordering for prompt-cache hits, and (anticipating the roadmap) adds ETag-style versioning.

**Evidence it is a gap.**
- Spec ([Streamable HTTP, 2026-07-28](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http)): "The Streamable HTTP transport mirrors selected JSON-RPC body fields into HTTP headers so that intermediaries (load balancers, gateways, observability tooling) can route and inspect requests without parsing the body"; "Intermediaries that enforce policy based on mirrored headers (e.g., routing or rate-limiting by tenant) **SHOULD** verify …".
- Changelog: "Require `ttlMs` and `cacheScope` fields on results returned by `tools/list`, `prompts/list`, `resources/list` … `cacheScope` (`"public"` or `"private"`) controls whether shared intermediaries may cache the response" (SEP-2549); "Servers **SHOULD** return tools from `tools/list` in a deterministic order to enable client-side caching and improve LLM prompt cache hit rates."
- Roadmap: "we want to extend our caching approach to support ETags, which should allow versioning the results of primitives, in particular tool calls."
- Existing gateways: agentgateway issue search for `ttlMs cacheScope` finds nothing relevant (only [#2215 "Explore X-Models-Etag"](https://github.com/agentgateway/agentgateway/issues/2215)); microsoft/mcp-gateway is session-based (obsolete model); no Kotlin MCP gateway exists (search → 0).

**Fit.** HTTP caching semantics + consistent-hash routing + rate limiting are the cache project reapplied at L7.

**Scope.** 4–8 weeks for a Ktor/Netty proxy with a pluggable cache (Caffeine/Redis) and a conformance-test pass-through mode. Pairs naturally with Gap 1 and Gap 3.

### Gap 7 — A2A push-notification delivery broker (ordered, retried, multi-agent) + Kotlin A2A

**Description.** A2A v1.0.0 mandates ordering and leaves reliability to implementers; the Java SDK's push notifications are known-broken on ordering and timing. A durable, ordered, at-least-once webhook broker for A2A task events (plus a coroutine-native Kotlin A2A client/server) fills a visible hole in the JVM ecosystem.

**Evidence it is a gap.**
- A2A spec 1.0.0 ([a2a-protocol.org/latest/specification](https://a2a-protocol.org/latest/specification/)): "All implementations MUST deliver events in the order they were generated. Events MUST NOT be reordered during transmission, regardless of protocol binding." Push notifications are "delivered via HTTP POST to client-registered webhook endpoints"; the fetched extract says retry policy/queue size/timeouts are implementation-defined (**verbatim wording of §4.3 unverified** — the fetch truncated).
- [a2a-java #775 "pushnotification is not support order-preserving of update event"](https://github.com/a2aproject/a2a-java/issues/775) (open since 2026-03-28): notifications sent via `CompletableFuture.runAsync()` so "the request agent may not receive those event in the order it produce".
- [a2a-java #952 "push notification is not implemented exactly according to the A2A protocol"](https://github.com/a2aproject/a2a-java/issues/952) (open): code "only send notification after initial return".
- [a2a-java #898 "How to scale a A2A production deployment to 100 A2A Server Agents"](https://github.com/a2aproject/a2a-java/issues/898) (10 comments).
- No Kotlin A2A SDK: GitHub search `a2a agent2agent language:Kotlin` → 0 results (a2aproject has python/js/java/go/dotnet/rs only).

**Closest attempts.** a2a-java 490 stars (Quarkus/Jakarta), a2a-python 2.1k. The [a2a-tck](https://github.com/a2aproject/a2a-tck) exists (50 stars, 60 open issues) to validate against.

**Fit.** The NATS + retry-pool pipeline project is the same shape: per-task ordered streams, outbox pattern, retry with backoff, idempotent receivers.

**Scope.** 4–6 weeks for the broker as a library + reference server; a Kotlin SDK is a larger 2–3 month effort, so start with the broker and contribute the ordering fix upstream (#775).

### Gap 8 — Verified/adaptive semantic cache as production middleware

**Description.** Semantic caches ship with a single global cosine threshold and return wrong answers at unpredictable rates; ICLR 2026 work shows per-entry learned thresholds with error-rate guarantees, but only as research code. A production-grade cache (tenant-scoped, TTL, invalidation, exact-prefix + semantic tiers, observable hit/false-hit rates) in Kotlin or Go does not exist.

**Evidence it is a gap.**
- [vCache: Verified Semantic Prompt Caching, arXiv 2502.03771 (ICLR 2026)](https://arxiv.org/abs/2502.03771): "static thresholds do not give formal correctness guarantees, result in unexpected error rates, and lead to suboptimal cache hit rates"; optimal threshold "ranging from 0.71 to 1.0" across embeddings.
- [vcache-project/vCache](https://github.com/vcache-project/vCache): 79 stars, Python research code.
- [zilliztech/GPTCache](https://github.com/zilliztech/GPTCache): 8.2k stars but latest release 0.1.44 on **2024-08-01**.
- LiteLLM caching docs: semantic caching "goes badly wrong on agentic traffic" and is for "single-shot prompts" ([docs](https://docs.litellm.ai/docs/proxy/caching)).

**Closest attempts.** GPTCache (stale), [codefuse-ai/ModelCache](https://github.com/codefuse-ai/ModelCache) 938, [messkan/prompt-cache](https://github.com/messkan/prompt-cache) 406 (Go), [upstash/semantic-cache](https://github.com/upstash/semantic-cache) 300 — all threshold-based.

**Fit.** Cache admission/eviction, tenancy, and consistency are the strengths; the online-threshold estimator from the paper is ~a few hundred lines.

**Scope.** 4–6 weeks. Risk: value is workload-dependent and the LiteLLM warning is real — position it as a gateway plugin with a measurable false-hit budget rather than a default-on cache.

### Gap 9 — Bring the Kotlin MCP SDK to the 2026-07-28 spec (contribution track)

**Description.** Implement stateless lifecycle, `server/discover`, MRTR/`InputRequiredResult`, `subscriptions/listen`, `Mcp-Method`/`Mcp-Name` headers, `ttlMs`/`cacheScope`, OTel `_meta` propagation, and the Tasks extension in the official Kotlin SDK, then run the conformance suite.

**Evidence it is a gap.** Kotlin SDK 0.15.0 (released 2026-07-28) is pre-1.0; [#842](https://github.com/modelcontextprotocol/kotlin-sdk/issues/842) 0/19 sub-issues; [#800 "Implement SEP-414: OpenTelemetry Trace Context Propagation"](https://github.com/modelcontextprotocol/kotlin-sdk/issues/800) open; [#812 SEP-2468 iss parameter](https://github.com/modelcontextprotocol/kotlin-sdk/issues/812) open. TS v2 already ships 2026-07-28 support; tier rules require Tier 1 SDKs to implement new features "Before new spec version release".

**Fit.** Coroutines/Ktor transport work; conformance-driven. Less "novel" than Gaps 1–8 but highest visibility (official repo, JetBrains co-maintained).

**Scope.** Individual PRs are 1–2 weeks each; a complete revision is a multi-month team effort. Good complement to Gap 2/6 (build them against your own SDK branch).

### Gap 10 — OTel GenAI + MCP instrumentation for JVM clients

**Description.** Auto-instrumentation (Kotlin MCP SDK client/server spans with `traceparent` in `_meta`; Anthropic Java SDK inference spans/metrics) following the GenAI semantic conventions, contributed to opentelemetry-java-instrumentation or shipped as a Kotlin library.

**Evidence it is a gap.** GenAI conventions moved to [open-telemetry/semantic-conventions-genai](https://github.com/open-telemetry/semantic-conventions-genai) (350 stars, "Schema URL: TODO"); every document — spans, agent spans, events, metrics, and `mcp.md` — is marked "Development" ([gen-ai README](https://raw.githubusercontent.com/open-telemetry/semantic-conventions-genai/main/docs/gen-ai/README.md), [mcp.md](https://raw.githubusercontent.com/open-telemetry/semantic-conventions-genai/main/docs/gen-ai/mcp.md)). Java auto-instrumentation covers only "OpenAI Java SDK 1.1+". MCP spec 2026-07-28 now documents "OpenTelemetry trace context propagation conventions for `_meta` keys (`traceparent`, `tracestate`, `baggage`)" (SEP-414) and the Kotlin SDK has not implemented it (#800).

**Fit.** Medium — plumbing more than distributed systems, but it is what makes Gaps 2/6 observable.

**Scope.** 2–4 weeks per instrumentation. Risk: conventions are unstable; expect attribute renames.

### Gap 11 — Typed streaming structured output on the JVM (partial objects, repair, schema-aware)

**Description.** An Instructor/BAML-style library for Kotlin: incremental JSON parsing of a token stream into partially-filled typed objects, repair of truncated JSON, and schema validation against provider constraints.

**Evidence it is a gap.** LangChain4j: JSON schema "does not work in the streaming mode for OpenAI yet" ([docs](https://docs.langchain4j.dev/tutorials/structured-outputs)). Anthropic structured outputs list unsupported schema features ("Recursive schemas", "Numerical constraints", "String constraints", `additionalProperties` other than `false`) that a client-side validator must pre-check ([docs](https://platform.claude.com/docs/en/build-with-claude/structured-outputs)). Instructor lists official ports for Python, TypeScript, Go, Ruby, Elixir — no Java/Kotlin ([python.useinstructor.com](https://python.useinstructor.com/)). Only JVM attempt found: [FunnySaltyFish/partial-json-parser-kmp](https://github.com/FunnySaltyFish/partial-json-parser-kmp), 7 stars.

**Fit.** Low on distsys, but small and immediately useful; Jackson's non-blocking parser gives a head start.

**Scope.** 3–6 weeks.

### Gap 12 — JVM eval harness (JUnit 5 extension + promptfoo-compatible runner)

**Description.** LLM-as-judge and deterministic metrics that run inside JUnit/Gradle with dataset fixtures, caching of judge calls, and CI-friendly thresholds.

**Evidence it is a gap.** Spring AI documents exactly two evaluators (`RelevancyEvaluator`, `FactCheckingEvaluator`) ([testing docs](https://docs.spring.io/spring-ai/reference/api/testing.html)). LangChain4j: issue search for an evaluation framework returned nothing (**unverified** that none is planned). GitHub search for Java/Kotlin LLM eval frameworks with >20 stars → 0 results. Python/TS side is saturated (promptfoo 25.0k, DeepEval 18.2k).

**Fit.** Low-medium; commodity logic, JVM packaging is the only novelty.

**Scope.** 3–4 weeks. Consider only as a supporting piece for another gap.

### Gap 13 (brief) — Tracking-model output → LLM tactical commentary for racket sports

**Evidence.** Academic assets exist but no open end-to-end pipeline: [BFMD (CVPRW 2026, arXiv 2603.25533)](https://arxiv.org/abs/2603.25533) releases 19 badminton matches, 16,751 hit events with shuttle trajectories and shot captions; its repo [Ning-D/BFMD](https://github.com/Ning-D/BFMD) (7 stars) is a VideoMAE captioner with, per the README, no LLM commentary layer. Benchmarks [SportR (ICLR 2026)](https://arxiv.org/abs/2511.06499) and [DeepSport](https://arxiv.org/abs/2511.12908) evaluate MLLMs on sports reasoning. **Unverified:** no OSS project fusing TrackNet-style trajectories with an LLM for tactical commentary was found; a targeted search may turn one up.

**Fit.** Uses the TrackNet background, not the distsys background; treat as a demo, not the systems flagship. Scope: 4–8 weeks for a pipeline (TrackNet → rally segmentation → structured events → LLM commentary with citations to frames).

---

## Areas investigated and dropped as well-served

- **Durable/resumable agent runtimes.** Restate (Java/Kotlin SDK with Virtual Objects and Workflows, [docs](https://docs.restate.dev/develop/java/overview)), Temporal, Inngest, DBOS (Python/TS/Go/Java) already provide language-agnostic durable execution; DBOS has a "Build Durable AI Agents" section ([docs](https://docs.dbos.dev/)). Koog ships agent persistence with pluggable `PersistenceStorageProvider` (**details unverified**: the docs page 404'd; README states "Restore the agent state at specific points during execution"). Claude Agent SDK sessions are local JSONL files ("Session files are local to the machine that created them") with a `SessionStore` adapter for shared storage ([sessions docs](https://code.claude.com/docs/en/agent-sdk/sessions)); OpenAI Agents SDK sessions are "conversation history managers" with SQLite/Redis/SQLAlchemy/Mongo/Dapr backends ([docs](https://openai.github.io/openai-agents-python/sessions/)). Managed Agents is beta and "not currently eligible for Zero Data Retention … or HIPAA" ([overview](https://platform.claude.com/docs/en/managed-agents/overview)). Verdict: the engine layer is commodity; the narrow, unbuilt piece is Gap 2.
- **Distributed KV-cache transfer/placement.** LMCache, Mooncake, Dynamo, llm-d are vendor-scale; a solo dev should not compete on transfer engines. The open seam is eviction policy (Gap 4).
- **Code sandboxes.** E2B, Modal, Codex (Seatbelt/bubblewrap), Claude Code sandboxing — commodity. The unbuilt seam is the *policy* layer (Gap 3).
- **Anthropic docs limitations that are third-party-fillable** (all fetched 2026-09-12): MCP connector — "only tool calls are currently supported" and "Local STDIO servers cannot be connected directly" ([mcp-connector](https://platform.claude.com/docs/en/agents-and-tools/mcp-connector)) → an MCP bridge/proxy (Gap 6 territory). Prompt caching — "Caches are isolated per workspace", "a cache entry only becomes available after the first response begins", 20-block lookback window ([prompt-caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching)) → cache-aware request coalescing/warm-up belongs in Gap 1's scheduler. Structured outputs — no recursive schemas / numeric constraints ([structured-outputs](https://platform.claude.com/docs/en/build-with-claude/structured-outputs)) → Gap 11.

## Recommended picks (ranked by evidence × fit)

1. **Gap 1** token-aware admission control / fair scheduler — strongest primary-source evidence (both providers' docs + LiteLLM's own "beta"/drift caveats + a closed-as-not-planned fair-share issue), perfect fit.
2. **Gap 2** durable MCP Tasks + events backend — spec MUSTs on durability, only in-memory stores in SDKs, WG still "Ideating", nothing on the JVM.
3. **Gap 3** delegation-aware authz for tool calls — the MCP roadmap names the problem verbatim; Zanzibar engine is the natural base.
4. **Gap 4** KV-cache eviction policy — vLLM's own design doc says LRU, four open RFCs ask for better; W-TinyLFU author fit is unusual.
5. **Gap 5** consistent agent memory store — five open Mem0 race/conflict issues; DVVs solve the stated problem.

Gaps 6 and 7 are strong secondary picks that compose with 1–3. Gaps 9–12 are contribution/support tracks. Gap 13 is a demo, not a flagship.

## Could not verify

- Official MCP registry total server count (API exposes no total).
- Exact §4.3 wording of A2A push-notification reliability (fetch truncated).
- Whether any OSS gateway implements Anthropic's cache-aware ITPM accounting (none seen in fetched docs).
- Koog persistence storage providers (docs page 404).
- Star counts for LobeChat, Ragas, Chroma (GitHub API rate limit during the batch).
- Whether LangChain4j has a planned evaluation module (issue search returned nothing).
- Whether the Kotlin/Java SDKs are wired into the conformance runner's `known-sdks.ts` (README extract named TS/Go/C#/Rust/Python only).
