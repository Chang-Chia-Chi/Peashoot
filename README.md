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

The data directory holds `peashoot.db` (the store), `bodies/` (request bodies and frame lists over 64 KB, named by SHA-256), `gource.log` (written only when the Gource formatter is on), `rules.json` (what makes two requests the same request: a header allowlist, ignored JSON pointers, and regex replacements, written with defaults on first start and meant to be edited; reducing it to `{}` is exact matching), and `peashoot.toml`, written with defaults on first start: port, upstream, secret headers, the Gource flag, the keep-alive ping interval, and the mode of each route (`record`, `replay`, or `passthrough`). Environment variables override the file:

| Variable | Default | Meaning |
|---|---|---|
| `PEASHOOT_HOME` | `~/.peashoot` | The data directory |
| `PEASHOOT_PORT` | `8787` | Listen port, loopback only |
| `PEASHOOT_ANTHROPIC_UPSTREAM` | `https://api.anthropic.com` | Where Messages requests go |
| `PEASHOOT_DUMP_FRAMES` | unset | Append every raw upstream response to this file, for capturing fixtures |

## Keep-alive pings and a client that leaves

While a streaming response is silent, the proxy writes the SSE comment line `: keep-alive` followed by a blank line, at the interval set by `pingIntervalSeconds` in the `[resume]` table of `peashoot.toml` (default 15 seconds, fractions allowed). The comment never reaches the recording; SSE clients ignore comment lines by specification. Non-streaming responses never get one.

If a client disconnects mid-stream, the proxy keeps reading the upstream to the end, records the exchange complete with `clientDisconnected` set, and appends an `exchange.client_gone` line to `events.jsonl`. `exchange.completed` still follows, with `clientDisconnected: true`.

Manual check, with a real key. The seam test stalls a fake upstream for several intervals; the real API cannot be made to stall on demand, because it sends `event: ping` frames of its own, so what a human checks here is that Claude Code and its SDK take the proxy's lines in their stride. Set `pingIntervalSeconds = 0.2`, start the proxy, then send a streaming Messages request through it with a prompt that makes the model think for a while:

```
$ curl -N http://localhost:8787/v1/messages \
  -H "x-api-key: $ANTHROPIC_API_KEY" -H "anthropic-version: 2023-06-01" -H "content-type: application/json" \
  -d '{"model":"claude-sonnet-4-5","max_tokens":20000,"stream":true,"thinking":{"type":"enabled","budget_tokens":16000},"messages":[{"role":"user","content":"Solve a hard logic puzzle, showing your reasoning."}]}'
```

The pauses in a thinking stream are longer than that interval, so `: keep-alive` lines appear between the provider's frames. The provider's own `event: ping` frames are forwarded, not generated, so the proxy's lines are the ones that start with a colon. Then run Claude Code against the proxy with the same kind of prompt: the turn completes, and `events.jsonl` ends with an `exchange.completed` line for it, no `exchange.client_gone`.

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
