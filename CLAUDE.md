# Peashoot

A language-agnostic record/replay and stream-resume proxy for LLM agent traffic (Kotlin, Ktor) with a Compose for Desktop farm dashboard. Read `docs/spec.md` for what v1 does, `docs/design.md` for how, `docs/brief.md` for why, and `docs/research/` for the cited evidence.

## Build and gates

`./gradlew build` is the whole gate: ktfmt (Kotlin language style, never argue about layout), every compiler warning is an error with extra warnings on, tests on JUnit 5 under `check`. Versions live only in `gradle/libs.versions.toml`. The Stop hook runs `gradlew check` and refuses to let a session end while it fails. Tests sit at the seams named in `docs/spec.md`: the proxy's HTTP boundary against the fake upstream, and the pure farm reducer. Do not add an interface with one implementation, a config value that never changes, or a dependency for something a few lines can do. Why and what was skipped: `docs/research/code-quality-environment.md`.

## Agent skills

### Issue tracker

Issues live in this repo's GitHub Issues, managed with the `gh` CLI; blocking edges are native issue dependencies. See `docs/agents/issue-tracker.md`.

### Triage labels

The five default labels, each string equal to its role name (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `CONTEXT.md` and `docs/adr/` at the repo root, created lazily by the domain-modeling skill. See `docs/agents/domain.md`.
