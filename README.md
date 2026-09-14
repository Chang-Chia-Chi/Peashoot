# Peashoot

A language-agnostic record/replay and stream-resume proxy for LLM agent traffic, with a farm dashboard. Kotlin, Ktor, Compose for Desktop.

Status: pre-v1. The spec is `docs/spec.md`, the design `docs/design.md`, the plan is the issue tracker.

## Build

```
./gradlew build
```

JDK 21+. The build treats every compiler warning as an error, checks formatting with ktfmt (Kotlin language style, no options), runs detekt's default rules and an ArchUnit boundary test, and runs the tests under `check`. `./gradlew koverHtmlReport` writes a coverage report to `build/kover/html`.

## Quickstart

Build the proxy and start it:

```
./gradlew :proxy:installDist
proxy/build/install/proxy/bin/proxy
```

Then point a client at it. Claude Code needs one variable and keeps its saved login:

```
ANTHROPIC_BASE_URL=http://localhost:8787 claude
```

## Configuration

The data directory holds `peashoot.db` (the store), `bodies/` (request bodies and frame lists over 64 KB, named by SHA-256), `gource.log` (written only when the Gource formatter is on), and `peashoot.toml`, written with defaults on first start: port, upstream, secret headers, the Gource flag, and the mode of each route (`record`, `replay`, or `passthrough`). Environment variables override the file:

| Variable | Default | Meaning |
|---|---|---|
| `PEASHOOT_HOME` | `~/.peashoot` | The data directory |
| `PEASHOOT_PORT` | `8787` | Listen port, loopback only |
| `PEASHOOT_ANTHROPIC_UPSTREAM` | `https://api.anthropic.com` | Where Messages requests go |
| `PEASHOOT_DUMP_FRAMES` | unset | Append every raw upstream response to this file, for capturing fixtures |

## Watch agents move through the repository

![Agents moving through a repository](docs/gource.gif)

One Claude Code session reading and editing this repository through the proxy, played back by Gource from the log below.

Turn the Gource formatter on in `peashoot.toml`:

```
[gource]
enabled = true
```

The proxy then appends one line to `gource.log` for every file tool a turn used. A line carries the timestamp, the session short id as the user, `A` for a read and `M` for an edit, the file, and a colour per tool. A tool that names no file, like `Bash`, is skipped. Play the log back, or watch a run live:

```
gource --log-format custom ~/.peashoot/gource.log
tail -f ~/.peashoot/gource.log | gource --log-format custom --realtime -
```

Claude Code names files by their absolute path, so the tree starts at the drive root. Trim the repository's prefix to start it at the repository instead:

```
sed 's#/home/me/myrepo/##' ~/.peashoot/gource.log | gource --log-format custom -
```

## Hooks

Humans: activate the pre-commit hook once per clone. It formats staged Kotlin files and runs `gradlew check`.

```
git config core.hooksPath .githooks
```

Agents: `.claude/settings.json` formats each Kotlin file after every edit and runs `gradlew check` before a Claude Code session may stop.
