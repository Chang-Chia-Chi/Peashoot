# Peashoot

A language-agnostic record/replay and stream-resume proxy for LLM agent traffic (Kotlin, Ktor) with a Compose for Desktop farm dashboard. Read `docs/spec.md` for what v1 does, `docs/design.md` for how, `docs/brief.md` for why, and `docs/research/` for the cited evidence.

## Agent skills

### Issue tracker

Issues live in this repo's GitHub Issues, managed with the `gh` CLI; blocking edges are native issue dependencies. See `docs/agents/issue-tracker.md`.

### Triage labels

The five default labels, each string equal to its role name (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `CONTEXT.md` and `docs/adr/` at the repo root, created lazily by the domain-modeling skill. See `docs/agents/domain.md`.
