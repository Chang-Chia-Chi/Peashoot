# Lateral directions (non-proxy) for local AI/LLM developer infrastructure on the JVM

Research date: 2026-09-12. Star counts are from the GitHub API on that date. Primary sources only (official docs, repos, issues, arXiv). Anything I could not confirm from a primary source is marked **unverified**.

Baseline for comparison: "a token-aware, provider-faithful fair scheduler proxy for LLM API traffic (models Anthropic's cache-aware ITPM and OpenAI's max_tokens-inclusive TPM exactly, learns capacity from rate-limit headers, weighted fair queuing across tenants/agents), with a desktop control plane."

Hard constraints applied: Kotlin/JVM single JAR, embedded state, existing protocol (MCP 2026-07-28 / OTLP / OpenAI-compatible), Compose Desktop dashboard + control plane, vendor-neutral with Claude Code as first demo, solo dev at ~10 h/week with a useful v1 in 3 months, no GPU assumption.

---

## Ranked surviving candidates

### 1. JVM Runtime Cockpit: one MCP server for JFR, heap/thread/coroutine dumps, GC and JMX, with a Compose control plane

**Description.** A single Kotlin JAR that discovers local JVMs (attach API / `jps`), and exposes MCP tools that return *compact, token-budgeted* diagnostics instead of raw artifacts: start/stop/stream JFR and summarize hot paths, allocation, GC pauses, lock contention and virtual-thread pinning; take thread dumps with deadlock/lock-graph detection; take Kotlin coroutine dumps (via `kotlinx-coroutines-debug` `DebugProbes`) and merge them with thread dumps; heap histogram and leak-suspect summary (delegating full dominator-tree analysis to an external engine); read/write JMX MBeans. The Compose app shows live JFR streams and lets the developer arm/limit what the agent may do (which PIDs, max dump size, sampling rate, kill switch). Claude Code connects via stdio or Streamable HTTP and diagnoses a slow or leaking JVM without the user pasting logs.

**Evidence the problem is real.**
- Claude Code issue #13865 "[FEATURE] Interactive Debug Mode with Runtime Log Instrumentation" (opened 2025-12-13, open, 17 thumbs-up / 22 reactions, 4 comments): "When dealing with complex bugs—specifically race conditions, state management issues, or problems that are difficult to reproduce via static analysis alone—Claude Code currently relies heavily on the user manually providing context (pasting error logs, describing behavior) or running existing tests." https://github.com/anthropics/claude-code/issues/13865
- JetBrains/mcp-jetbrains issue #52 (opened 2025-06-09, open, 6 thumbs-up, 4 comments): "AI can set breakpoints... but AI cannot see what happened at the breakpoint." https://github.com/JetBrains/mcp-jetbrains/issues/52
- JetBrains' IntelliJ IDEA 2026.2 MCP Server tool list now contains 13 `xdebug_*` debugger tools plus `build_project`, `analyze_calls`, `get_project_dependencies`, but lists no profiler, JFR, heap-dump, GC or thread-dump tool (stated as absence in the docs' tool list). https://www.jetbrains.com/help/idea/mcp-server.html
- arXiv 2604.24212 "Empowering Autonomous Debugging Agents with Efficient Dynamic Analysis" (2026-04-27): "their effectiveness is often hindered by reliance on post-mortem, coarse-grained execution feedback"; integrating its debugging interface into existing agents "produced gains between 6.2% to 18.5%" on SWE-bench Verified. https://arxiv.org/abs/2604.24212
- arXiv 2503.21557 "debug-gym" (Microsoft): agents given interactive debugger tools show "promising improvements in agents' performance … especially on complex, real-world coding tasks." https://arxiv.org/abs/2503.21557
- Token budget forces summarization, which is the product's core: Claude Code MCP docs: "Claude Code displays a warning when any MCP tool output exceeds 10,000 tokens" and "the default maximum is 25,000 tokens." https://code.claude.com/docs/en/mcp . jvmlens README quantifies why: "A 2.7 MB raw JFR dump (~684K tokens) compresses to ~1 KB (~250 tokens)." https://github.com/alexmond/jvmlens
- Revealed demand: at least 10 independent single-artifact attempts appeared between 2025-04 and 2026-09 (listed below), including SAP's Johannes Bechberger adding an MCP server to hprof-analyzer ("Designed for use with LLM tooling"). None unifies the artifacts or has a control plane.
- Kotlin-specific gap: thread dumps do not show suspended coroutines; `kotlinx-coroutines-debug` provides "dumpCoroutines" with "creation and suspension stacktraces", installable via `-javaagent:kotlinx-coroutines-debug-<ver>.jar` or `DebugProbes.install()`. https://github.com/Kotlin/kotlinx.coroutines/blob/master/kotlinx-coroutines-debug/README.md . Open issue #4299 "withContext calls are not reflected in coroutine debug dumps" (2024-12-16, 2 comments). https://github.com/Kotlin/kotlinx.coroutines/issues/4299
- Mechanisms are standard JDK: JEP 349 (JDK 14) "Provide an API for the continuous consumption of JFR data on disk, both for in-process and out-of-process applications." https://openjdk.org/jeps/349 . `jcmd` `Thread.print` ("Prints all threads with stacktraces"), `GC.heap_dump`, `GC.class_histogram`, `JFR.start`/`JFR.dump`, `VM.native_memory`; "must be used on the same machine on which the JVM is running, and have the same effective user and group identifiers". https://docs.oracle.com/en/java/javase/21/docs/specs/man/jcmd.html

**Closest existing attempts (all fail the "unified + control plane + single JAR" bar).**
| Repo | Stars | Lang | Why it does not satisfy the constraints |
|---|---|---|---|
| https://github.com/theSharque/mcp-jperf (registry: `io.github.theSharque/javaperf` v1.4.1, published 2026-05-23) | 10 | TypeScript | Shells out to `jps`/`jcmd`/`jfr`; "Local only"; no UI; no heap-dump analysis beyond histogram; no coroutines. |
| https://github.com/alexmond/jvmlens | 3 | Java | JFR only: "Does not handle: Heap dumps, thread dumps, or JMX"; no UI. |
| https://github.com/Djaler/jvm-heap-dump-mcp | 9 | Kotlin | Heap dumps only via Eclipse MAT ("~28 MB" download on first use, "Default is -Xmx4g"); "No user interface". |
| https://github.com/parttimenerd/hprof-analyzer | 4 | Rust | Heap dumps only; has browser UI + MCP; not JVM/Kotlin; no JFR/threads. |
| https://github.com/jolokia/jolokia-mcp-server | 17 | Java | JMX only. |
| https://github.com/itz4blitz/JMX-MCP | 10 | Java | JMX only. |
| https://github.com/FgForrest/mcp-jdwp-java | 25 | Java (MIT) | Debugger only (47 tools); "no daemon, no extra port, no GUI"; "Only synchronized-monitor contention shows; Object.wait() and java.util.concurrent locks are out of scope by design"; no JFR/heap/coroutines. |
| https://github.com/mahaat/jfr-analyzer-mcp | 1 | Java | JFR file only. |
| https://github.com/SnipeFactory/lumen-mcp | 0 | JavaScript | JFR only. |
| https://github.com/ylw-a/java-debug-mcp, https://github.com/Acendas/android-debugger | 2, 2 | Java, Kotlin | Debugger only. |
| JetBrains IntelliJ 2026.2 MCP server | n/a | IDE plugin | IDE-bound; debugger yes, no profiler/JFR/heap/GC tools. |
| rnett/gradle-mcp https://github.com/rnett/gradle-mcp | 60 | Kotlin | Build/REPL focus; no diagnostics artifacts. |

**Demo in 30 seconds.** `java -jar jvmcockpit.jar` → the Compose window lists running JVMs. In Claude Code: `claude mcp add jvm --transport http http://localhost:7777/mcp`, then prompt "my Spring Boot app on PID 4242 is slow — profile it for 20 s and tell me why." The agent calls `jfr.profile(pid, 20s)` and gets a ~300-token hot-path/allocation/GC summary with source attribution; the dashboard shows the recording live and the tool call in the audit pane.

**Compose dashboard / control plane.** Dashboard: JVM list; live JFR stream charts (CPU samples, allocation, GC pauses, lock contention); thread/coroutine state table; last heap histogram; MCP tool-call log with token cost. Control plane: allow-list of PIDs the agent may touch; per-tool caps (max recording seconds, max dump MB, max output tokens); enable/disable coroutine agent injection; "arm heap dump" confirmation; kill switch; choose output verbosity profile.

**MVP scope.** ~10–12 weeks at 10 h/week: (1) attach + `jcmd` wrappers over MCP (thread dump, histogram, JFR start/dump) with Kotlin MCP SDK stdio + Streamable HTTP; (2) `jdk.jfr.consumer` parsing → compact summaries; (3) coroutine dump via DebugProbes agent merged with thread dump; (4) heap "leak suspects" via histogram deltas, delegating full dominator trees to an external engine (MAT/hprof-analyzer) if present; (5) Compose dashboard + caps. **Main risk:** heap-dump analysis at scale (dominator tree needs memory ∝ dump size — Djaler's server defaults to `-Xmx4g`); mitigate by scoping v1 to histogram/leak heuristics and JFR `OldObjectSample`, not full HPROF graphs.

**Versus baseline.** Complementary, not competing: baseline governs *API traffic*; this gives agents *runtime evidence about the user's own JVM*. Stronger on defensibility (deep JVM domain, fragmented 0–25★ competition, JetBrains explicitly leaves profiling uncovered); weaker on audience breadth (JVM developers only vs. every LLM app).

---

### 2. PIT Mutation Kill-Loop: an MCP server that runs PIT, hands the agent surviving mutants with source context, and re-verifies only those mutants

**Description.** Kotlin JAR that runs PIT (through the Gradle/Maven plugin or PIT's CLI), parses the XML report into an embedded store, and exposes MCP tools: `mutation.audit(class|package, budget)`, `mutation.survivors(file)` (each with mutator, line, method, description and a source window), `mutation.verify(runId, mutantIds)` (re-run PIT scoped with `targetClasses`/`targetTests` + history so only the named mutants are re-executed), and `mutation.explain(mutantId)` (mutated bytecode diff via PIT's `EXPORT` feature). The Compose app shows mutation score over time, a per-file survivor heatmap, and lets you set mutators, time budget, thresholds and exclusions. Claude Code's loop: audit → write/strengthen tests → verify → repeat until survivors are killed or judged equivalent.

**Evidence the problem is real.**
- arXiv 2602.08146 "Test vs Mutant: Adversarial LLM Agents for Robust Unit Test Generation" (2026-02-08; v3 2026-08-31): "Experimental results in the Defects4J dataset show that our approach improves fault detection rates by 8.56% over the best existing LLM-based methods and by 63.30% over EvoSuite, while also improving line and branch coverage." (Java.) https://arxiv.org/abs/2602.08146
- arXiv 2605.19265 "MuMuTestUp: Mutation-based Multi-Agent Test Case Update" (2026-05-19): "generates repair instructions for each surviving mutant"; benchmark "PRBENCH, comprising 571 samples from 10 open-source Java projects". https://arxiv.org/abs/2605.19265
- arXiv 2501.12862 "Mutation-Guided LLM-based Test Generation at Meta" (2025-01-22): deployed on "Android Kotlin", "10,795 classes", "9,095" mutants, "571" tests generated, "Engineers accepted 73% of generated tests". https://arxiv.org/abs/2501.12862
- PIT is the JVM standard: https://github.com/hcoles/pitest 1,865★ (318 open issues); Gradle plugin https://github.com/szpak/gradle-pitest-plugin 249★.
- PIT's data is agent-unfriendly today: XML per-mutant fields are `detected`, `status`, `numberOfTestsRun`, `sourceFile`, `mutatedClass`, `mutatedMethod`, `methodDescription`, `lineNumber`, `mutator`, `indexes`, `blocks`, `killingTest(s)`, `description` (XMLReportListener.java) — no source diff. https://raw.githubusercontent.com/hcoles/pitest/master/pitest-entry/src/main/java/org/pitest/mutationtest/report/xml/XMLReportListener.java . FAQ: "The mutations that PIT generates are held in memory and never written to disk, except if explicitly enabled using the `EXPORT` feature." https://pitest.org/faq/ . PIT issue #644 "Please add some possibility to dump the mutants" (open since 2019-08-27). https://github.com/hcoles/pitest/issues/644
- Kotlin is a paid add-on today: PIT FAQ lists "Kotlin (via the Arcmutate kotlin plugin)" and warns that for unsupported JVM languages "the results are not generally useful"; Arcmutate docs describe a commercial "Licence" and "Licence Management". https://pitest.org/faq/ , https://docs.arcmutate.com/

**Closest existing attempts.**
| Repo | Stars | Lang | Why it does not satisfy |
|---|---|---|---|
| https://github.com/AraneaDev/Chaos-MCP | 3 | TypeScript (MIT) | Wraps StrykerJS, cosmic-ray, cargo-mutants, Infection — "PIT/pitest for Java/Kotlin is not included". (Its `runId` re-verify design — `nowKilled` / `stillSurviving` / `newSurvivors` — is the right shape to copy.) |
| https://github.com/NaelDj/PIT-MCP-Server | 0 | Python | Read-only "querying PIT mutation testing results"; no run/verify loop, no UI. |
| https://github.com/leelakrishna288/mutagent | 0 | Java | Created 2026-09-08; own mutator via JDK Compiler API, not PIT; no UI. |
| https://github.com/sumithr/sumo-qa | 6 | Python | Generic QA prompts; not a PIT runner. |
| Arcmutate (commercial) | n/a | Java | Kotlin/git/incremental plugins, licensed; no MCP/agent loop documented. |

**Demo in 30 seconds.** `java -jar mutloop.jar --project .` → dashboard shows the last PIT score (or runs a 60 s budgeted audit on one package). In Claude Code: "harden tests for `PaymentService` until mutation score ≥ 90%". Agent calls `mutation.survivors`, writes two assertions, calls `mutation.verify(runId, [...])`; the dashboard's heatmap cells turn from red to green as mutants die.

**Compose dashboard / control plane.** Dashboard: mutation score trend, per-file survivor heatmap, mutator breakdown, PIT wall-clock per run, agent kill-loop timeline. Control plane: mutator set, `targetClasses`/`targetTests` scopes, time budget and thread count, `mutationThreshold`, exclusions, "mark as equivalent" list that the server hides from the agent.

**MVP scope.** ~8–10 weeks: (1) run PIT via Gradle/Maven with history enabled, parse XML into an embedded store; (2) MCP tools audit/survivors/verify with source windows; (3) `EXPORT`-based mutant bytecode diff (javap text diff) for `explain`; (4) Compose dashboard + controls. **Main risk:** PIT wall-clock on large modules and Kotlin bytecode producing junk mutants without Arcmutate; mitigate with scoped `targetClasses`, history, and Kotlin-noise filters (skip `$default`, intrinsics null checks).

**Versus baseline.** Complementary. Stronger evidence base (three 2025–2026 papers on Java/Kotlin plus a Meta deployment) and a clear "before/after number" demo; weaker audience (teams that already tolerate mutation-testing runtimes) and more runtime risk than a proxy.

---

### 3. Affected-Test Selector: "which tests should I run for this diff" as an MCP tool for Gradle/Maven projects

**Description.** Kotlin JAR that builds a class→test dependency map for a JVM project (static: bytecode references via ASM/`jdeps`-style analysis, STARTS-style; optional dynamic: JaCoCo per-test coverage sessions), watches the working tree, and exposes MCP tools `tests.affected(diff|paths)` → ranked test classes/methods with reasons, `tests.run_affected()` → runs only those via `gradle test --tests` / `mvn -Dtest`, and `tests.explain(test)` → why it was selected. Always includes recently changed/failed tests (the Develocity heuristic). The Compose app shows the impact graph and lets you tune inclusion rules.

**Evidence the problem is real.**
- Claude Code issue #90487 (2026-08-29, open): the agent "started the **entire** test suite (`yarn test`) unprompted", "~291s of wall-clock blocked on poll loop #1", and the expected behaviour is stated as "Run targeted specs covering the change plus lint, then stop". https://github.com/anthropics/claude-code/issues/90487
- arXiv 2603.17973 "TDAD: Test-Driven Agentic Development" (2026-03-18): a tool that "performs pre-change impact analysis for AI coding agents by building a dependency map between source code and tests so agents know exactly which tests to verify"; regression rate "reduced from 6.08% to 1.82%", issue resolution "24% to 32%", and notably "TDD instructions without targeted test context increased regressions to 9.94% (worse than no intervention)". Evaluated on SWE-bench Verified (Python). https://arxiv.org/abs/2603.17973
- Commercial validation: Develocity Predictive Test Selection — "identifies the tests relevant to a code change and runs only those tests, reducing feedback time"; example output "Predictive Test Selection: 5 of 22 test classes selected...saving 23m 45s serial time"; requires "Develocity 2022.2 or later" server plus the Develocity Gradle plugin/Maven extension. https://docs.develocity.ai/predictive-test-selection/
- Gradle issue #12143 "Run deterministic subset of tests" (2020-02-06, open, 10 thumbs-up) — adjacent (partitioning, not impact), shows appetite for subset execution. https://github.com/gradle/gradle/issues/12143

**Closest existing attempts.**
| Repo | Stars | Lang | Why it does not satisfy |
|---|---|---|---|
| https://github.com/TestingResearchIllinois/starts | 34 | Java | Maven-only Surefire plugin, "tested on Java 8 through Java 15"; no Gradle, no MCP, no UI. |
| https://github.com/gliga/ekstazi | 42 | Java | Maven 3.5.2, JUnit 3.8.2/4.x; needs `-Djdk.attach.allowAttachSelf=true`; no Gradle/JUnit 5/MCP. |
| https://github.com/dropbox/AffectedModuleDetector | 655 | Kotlin | Module-level only ("determine[s] which modules were affected"); archived 2026-06-16 (fork at flo-health); no test-level selection, no MCP. |
| Develocity PTS (commercial) | n/a | — | Requires a Develocity server. |
| TDAD (paper artifact) | **unverified** — repo not located | Python | SWE-bench/Python; not JVM. |
| JetBrains MCP `analyze_calls` / rnett gradle-mcp test execution | 60 (rnett) | Kotlin | Can run filtered tests, but nothing computes the affected set. |

**Demo in 30 seconds.** `java -jar affected.jar --project .` indexes compiled classes (~seconds for a mid-size repo). In Claude Code, after editing `OrderPricer.kt`: "run the tests that matter". Agent calls `tests.affected()` → 4 test classes with reasons ("references OrderPricer#price via PricingServiceTest"), runs them in 12 s instead of the full 6-minute suite; the dashboard highlights the impact subgraph.

**Compose dashboard / control plane.** Dashboard: class→test graph around the current diff, selection size vs. full suite, historical time saved, false-negative log (tests that failed in a full run but were not selected). Control plane: always-include rules (recently failed/changed, tagged tests), reflection/DI hints (Spring component scan packages), dynamic-map toggle (JaCoCo `output=tcpserver`/`jmx=true`, `sessionid` per test), safety mode (fall back to full suite before commit).

**MVP scope.** ~8–10 weeks: (1) static class-level dependency map from compiled classes + git diff → test set; (2) MCP tools + Gradle/Maven runners; (3) optional JaCoCo per-test dynamic map; (4) Compose graph + rules. **Main risk:** precision on reflection-heavy code (Spring, Jackson, Kotlin `Class.forName`) — static maps miss edges; mitigate with the dynamic JaCoCo map and the "always include recently failed/changed" rule that Develocity documents as its own safety net.

**Versus baseline.** Complementary. Problem is well evidenced from both the agent side (#90487, TDAD) and the market side (Develocity PTS), and the open-source JVM options are old and Maven-only; weaker than the baseline on determinism of value — a wrong selection costs trust, whereas a scheduler's wins are measurable per request.

---

### 4. Local JVM Evidence Store: single-JAR OTLP (traces/logs/metrics) receiver with an MCP query surface — best as the ingestion half of candidate 1, weak standalone

**Description.** One JAR that listens on OTLP/HTTP+gRPC, stores spans/logs/metrics in an embedded store, and exposes read-only MCP tools (`find_slow_spans`, `errors_since`, `logs_for_trace`, `n_plus_one`), so a Spring Boot / Ktor app configured with `management.opentelemetry.tracing.export.otlp.*` and `spring-boot-starter-opentelemetry` (Spring Boot docs) becomes queryable by Claude Code. Differentiator only if correlated with candidate 1 (spans ↔ JFR events ↔ coroutine dumps by thread/trace id).

**Evidence the problem is real.**
- Demand for local viewers: https://github.com/CtrlSpice/otel-desktop-viewer 1,254★ (Go; traces, metrics, logs; DuckDB optional) — but "No MCP or AI agent integration is mentioned".
- Someone already sees the agent gap: https://github.com/nishantmodak/ltrace (1★, Rust, 2026-09-06) — "Local OpenTelemetry trace viewer for developers and AI coding agents", yet "Trace ingestion only: no OTLP gRPC, metrics, standalone log ingestion, or MCP server".
- Backend-attached MCP is the served path: https://github.com/traceloop/opentelemetry-mcp-server 199★ (Python) "requires an existing backend—it does not ingest OTLP directly".
- Claude Code #13865 (above) asks for the instrument → reproduce → "analyzes the captured runtime logs" loop.

**Closest existing attempts — and why this is only rank 4.**
| Repo | Stars | Lang | Note |
|---|---|---|---|
| https://github.com/danielloader/waggle | 15 | Go | Already does the standalone version: "Single static binary — pure Go, no CGO", SQLite + FTS5, and "a read-only Model Context Protocol endpoint at `/mcp`". A Kotlin clone adds nothing unless JVM-correlated. |
| https://github.com/mashiro/otelop | 16 | Go | Single binary, DuckDB; mentions AI coding agents as *exporters*, no MCP. |
| https://github.com/metafab/otel-gui | 179 | TypeScript | Viewer only. |

**Demo in 30 seconds.** Point `OTEL_EXPORTER_OTLP_ENDPOINT` at the JAR, hit an endpoint, ask Claude Code "why was `/checkout` slow?" → it returns the slowest span chain and the log lines inside that trace.

**Dashboard / control plane.** Waterfall, log search, retention/size caps, PII redaction rules before the agent can read logs, per-tool result caps.

**MVP scope.** ~6–8 weeks standalone, but low differentiation. **Main risk:** being a Kotlin re-implementation of waggle.

**Versus baseline.** Weaker standalone (prior art exists at the exact shape); complementary as the OTel input of candidate 1.

---

## Investigated and dropped

- **Gradle/Maven build-failure MCP (direction 2).** Served: https://github.com/rnett/gradle-mcp 60★ Kotlin/Apache-2.0 (project structure, tasks, dependency graph, "Filtered test suite execution with full logs and stack traces", persistent Kotlin REPL, Build Scan publishing, Gradle docs search); https://github.com/IlyaGulya/gradle-mcp-server 47★ Kotlin (Tooling API); JetBrains 2026.2 `build_project`, `get_project_dependencies`, `lint_files`; official Develocity MCP for failure grouping/flaky tests (requires "Develocity 2025.3 or higher", cannot function without a Develocity instance — https://docs.develocity.ai/current/integrations/mcp/); https://github.com/arvindand/maven-tools-mcp 32★ for dependency intelligence. Only the Kotlin-compiler-diagnostics slice is thin, and JetBrains' `lint_files`/`build_project` plus the official Kotlin LSP cover it.
- **Agent-readable JVM code index (direction 3).** Saturated: https://github.com/oraios/serena 29,201★ (LSP-based incl. Kotlin via kotlin-lsp; churn but not a gap — issue #2008 "Kotlin LSP (intellij-server 262.9593.0) build has expired", 2026-09-09); https://github.com/pzalutski-pixel/javalens-mcp 38★ (Eclipse JDT, "63 semantic Java analysis tools" per registry); JetBrains `analyze_calls`/`search_symbol`/`get_symbol_info`; Anthropic-verified Kotlin LSP plugin for Claude Code ("optimized for JVM-only Kotlin Gradle projects") https://claude.com/plugins/kotlin-lsp ; registry "Java All Call Graph Server"; https://github.com/Lincoln-cn/JCodeIndexer 2★ shows the remaining niche is tiny.
- **Claude Code session → fine-tuning dataset (direction 6).** Blocked by terms and served: Anthropic help center: "Our Terms do not allow the use of Outputs to train models that are competitive with Anthropic's own." https://support.claude.com/en/articles/12326764-can-i-use-my-outputs-to-train-an-ai-model ; transcript tooling exists (https://github.com/simonw/claude-code-transcripts 1,687★); the one fine-tune tool (https://github.com/NodeNestor/claude-code-finetune 2★) targets "16GB GPUs".
- **Standalone JDWP debugger MCP (direction 8).** IntelliJ 2026.2 ships 13 `xdebug_*` tools (set/remove breakpoints, step, evaluate, frames, threads, set variable); for non-IDE users https://github.com/FgForrest/mcp-jdwp-java 25★ already exists. Folded into candidate 1 as an optional module, not a product.
- **Local single-binary OTel MCP (direction 4) as a standalone.** https://github.com/danielloader/waggle already ships "a read-only Model Context Protocol endpoint at `/mcp`" in a "Single static binary". Kept only as candidate 4 / input to candidate 1.
- **Agent-trace UI for JVM agent frameworks.** Koog #159 "UI to test or check the agents" (2 thumbs-up, 6 comments, open since 2025-05-24) https://github.com/JetBrains/koog/issues/159 ; langchain4j #4442 "Native UI for Agent Execution Trace Visualization" (closed) https://github.com/langchain4j/langchain4j/issues/4442 ; Spring AI #3230 "Tool Observability" (closed). Trace viewers were covered in the earlier devex pass; no new standalone-tool evidence.
- **JMX-only MCP.** https://github.com/jolokia/jolokia-mcp-server 17★ and https://github.com/itz4blitz/JMX-MCP 10★ exist; JMX becomes one tool group in candidate 1.
- **Agent-driven JMH performance loop.** arXiv 2607.07744 PERFOPT-Bench (2026-07-08) frames performance optimization as "a distinct agentic task: agents must profile executions, diagnose cross-layer bottlenecks, edit code without breaking correctness, and verify that gains are reproducible", but gives no JVM-specific evidence; jvmlens already ships a JMH plugin. Possible later feature of candidate 1.
- **Kotlin compiler-diagnostics MCP.** Official https://github.com/Kotlin/kotlin-lsp plus the Anthropic-verified Claude Code plugin and JetBrains `lint_files` cover diagnostics; Serena wraps the LSP.
- **MCP registry gaps as a signal.** Registry search "java" returns 9 entries (5 Java-focused: JavaLens, Java All Call Graph, javaperf, ReadyOrAI, javaBin archive); "kotlin" returns only `io.github.aoreshkov/kotlin-lib-mcp`; "gradle" returns one ("Gradle dependency count"). Sparse, but registry sparsity alone is not demand evidence, so it is used only as supporting context.

## What I could not verify

- Whether the Kotlin MCP SDK (https://github.com/modelcontextprotocol/kotlin-sdk, ~1.5k★) implements the 2026-07-28 protocol revision — the README lists transports (stdio, Streamable HTTP, WebSocket, SSE) but no revision date. **Unverified.**
- TDAD's released repository (language, stars) — the arXiv page says a repo exists; I did not locate it. **Unverified.**
- Develocity MCP licensing/access process ("contact the Develocity support team" appears only in a search snippet, not on the docs page I fetched). **Unverified.**
- Whether the IntelliJ MCP server's debugger tools require an AI Assistant licence (docs say the MCP Server plugin is bundled since 2025.2; licence terms not checked). **Unverified.**
- Reaction counts on https://github.com/anthropics/claude-code/issues/90487 and https://github.com/gradle/gradle/issues/12143 beyond what the API returned (0 and 10 thumbs-up respectively) — no maintainer responses visible.
- Exact behaviour of NaelDj/PIT-MCP-Server (read-only inferred from its description only).
- No GitHub issue with high upvotes explicitly asks for "JFR/heap dump via MCP"; candidate 1's demand evidence is the debugger/runtime-feedback issues, the research results, and the count of independent small attempts — not a single high-vote request.
