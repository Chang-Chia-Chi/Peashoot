# Peashoot

A language-agnostic record/replay and stream-resume proxy for LLM agent traffic, with a farm dashboard. Kotlin, Ktor, Compose for Desktop.

Status: pre-v1. The spec is `docs/spec.md`, the design `docs/design.md`, the plan is the issue tracker.

## Build

```
./gradlew build
```

JDK 21+. The build treats every compiler warning as an error, checks formatting with ktfmt (Kotlin language style, no options), and runs the tests under `check`.

## Hooks

Humans: activate the pre-commit hook once per clone. It formats staged Kotlin files and runs `gradlew check`.

```
git config core.hooksPath .githooks
```

Agents: `.claude/settings.json` formats each Kotlin file after every edit and runs `gradlew check` before a Claude Code session may stop.
