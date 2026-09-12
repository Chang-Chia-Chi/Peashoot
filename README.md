# Peashoot

A language-agnostic record/replay and stream-resume proxy for LLM agent traffic, with a farm dashboard. Kotlin, Ktor, Compose for Desktop.

Status: pre-v1. The spec is `docs/spec.md`, the design `docs/design.md`, the plan is the issue tracker.

## Build

```
./gradlew build
```

JDK 21+. The build treats every compiler warning as an error, checks formatting with ktfmt (Kotlin language style, no options), runs detekt's default rules and an ArchUnit boundary test, and runs the tests under `check`. `./gradlew koverHtmlReport` writes a coverage report to `build/kover/html`.

## Run the proxy

```
./gradlew :proxy:installDist
proxy/build/install/proxy/bin/proxy
```

Then point a client at it. Claude Code needs one variable and keeps its saved login:

```
ANTHROPIC_BASE_URL=http://localhost:8787 claude
```

Environment (a config file arrives with the data directory):

| Variable | Default | Meaning |
|---|---|---|
| `PEASHOOT_PORT` | `8787` | Listen port, loopback only |
| `PEASHOOT_ANTHROPIC_UPSTREAM` | `https://api.anthropic.com` | Where Messages requests go |
| `PEASHOOT_DUMP_FRAMES` | unset | Append every raw upstream response to this file, for capturing fixtures |

## Hooks

Humans: activate the pre-commit hook once per clone. It formats staged Kotlin files and runs `gradlew check`.

```
git config core.hooksPath .githooks
```

Agents: `.claude/settings.json` formats each Kotlin file after every edit and runs `gradlew check` before a Claude Code session may stop.
