# Peashoot

A language-agnostic record/replay and stream-resume proxy for LLM agent traffic (Kotlin, Ktor) with a Compose for Desktop farm dashboard. Read `docs/spec.md` for what v1 does, `docs/design.md` for how, `docs/brief.md` for why, and `docs/research/` for the cited evidence.

## Build and gates

`./gradlew build` is the whole gate: ktfmt (Kotlin language style, never argue about layout), every compiler warning is an error with extra warnings on, detekt's default rules with no baseline, an ArchUnit test for module boundaries, tests on JUnit 5 under `check`. `./gradlew koverHtmlReport` writes coverage to `build/kover/html`. Versions live only in `gradle/libs.versions.toml`. The Stop hook runs `gradlew check` and refuses to let a session end while it fails. Tests sit at the seams named in `docs/spec.md`: the proxy's HTTP boundary against the fake upstream, and the pure farm reducer. Coroutine tests: `runTest` for suspend logic in `core`; the seam tests run real servers and stay on `runBlocking`, where a virtual clock would fire timeouts early. Do not add an interface with one implementation, a config value that never changes, or a dependency for something a few lines can do. Why and what was skipped: `docs/research/code-quality-environment.md`; the one-page sheet of every gate: `docs/gates.md`. Edits made through Bash skip the format hook, so run `./gradlew ktfmtFormat` before finishing. When the Stop hook bounces you, fix the failure; after three consecutive failures it lets the session end, so say in your final message what still fails. GitHub enforces no merge gate on this private repo: merge a PR only after `gh pr checks <n>` is green. The gates themselves are fixed: never change detekt, ktfmt, the compiler flags, Kover, the ArchUnit rules, or the hooks, and never add `@Suppress` or a baseline entry, unless the user approves it explicitly in the conversation. Fix the code instead. When a new constraint seems needed, a rule, a tool, a threshold, or a hook, do not add it: raise it with the user and decide together.

## Model choice

Implement on Opus unless the work is genuinely complex; a trivial task can be implemented on Sonnet and reviewed on Opus. Pass the model to sub-agents explicitly.

## Agent skills

### Issue tracker

Issues live in this repo's GitHub Issues, managed with the `gh` CLI; blocking edges are native issue dependencies. See `docs/agents/issue-tracker.md`.

### Triage labels

The five default labels, each string equal to its role name (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `CONTEXT.md` and `docs/adr/` at the repo root, created lazily by the domain-modeling skill. See `docs/agents/domain.md`.

### Kotlin Skills & Idiomatic Standards
Proactive Skills: Invoke Kotlin idiomatic refactoring and review skills before finalizing implementation.