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

The data directory holds `peashoot.db` (the store), `bodies/` (request bodies and frame lists over 64 KB, named by SHA-256), `gource.log` (written only when the Gource formatter is on), `rules.json` (what makes two requests the same request: a header allowlist, ignored JSON pointers, and regex replacements, written with defaults on first start and meant to be edited; reducing it to `{}` is exact matching), `redact.json` (what a cassette export strips, see below), `cassettes/` (exported cassettes), `token` (the control API's bearer token, created on first start, readable by its owner only), and `peashoot.toml`, written with defaults on first start: port, host, upstream, secret headers, the Gource flag, the keep-alive ping interval, the mode of each route (`record`, `replay`, or `passthrough`), whether a replay miss on it is refused (`strict`), and the cassette it replays from (`cassette`), and the replay `cadence` and `repeatPolicy`. Environment variables override the file:

| Variable | Default | Meaning |
|---|---|---|
| `PEASHOOT_HOME` | `~/.peashoot` | The data directory |
| `PEASHOOT_PORT` | `8787` | Listen port, loopback only; `0` takes a free one and logs it |
| `PEASHOOT_HOST` | `127.0.0.1` | Listen address; anything that is not loopback is refused |
| `PEASHOOT_ANTHROPIC_UPSTREAM` | `https://api.anthropic.com` | Where Messages requests go |
| `PEASHOOT_MODE` | `record` | The default route's mode: `record`, `replay`, or `passthrough` |
| `PEASHOOT_STRICT` | `false` | `true` refuses a replay miss with 409 |
| `PEASHOOT_CASSETTE` | unset | A cassette file, imported on start under its base name, which the default route then replays from |
| `PEASHOOT_DUMP_FRAMES` | unset | Append every raw upstream response to this file, for capturing fixtures |

An invalid value stops the proxy with a message naming the variable.

The database schema is created if absent and never altered before v1. A `peashoot.db` made before cassettes (#13) lacks the `cassette` column, and the proxy refuses to open it: move it aside and a fresh one is created.

## Control API

The proxy's own port serves a control API under `/_peashoot/v1/`; nothing under `/_peashoot/` is ever relayed. Every call but `GET /health` needs the token from the data directory:

```
TOKEN=$(cat ~/.peashoot/token)
curl http://localhost:8787/_peashoot/v1/health
curl -N -H "Authorization: Bearer $TOKEN" "http://localhost:8787/_peashoot/v1/events?since=0"
curl -H "Authorization: Bearer $TOKEN" "http://localhost:8787/_peashoot/v1/exchanges?client=claude-code&limit=10"
curl -X PUT -H "Authorization: Bearer $TOKEN" -d '{"mode":"replay","strict":true}' \
  http://localhost:8787/_peashoot/v1/routes/default
```

`/events` is a server-sent event feed of the event lines, each with its id; `Last-Event-ID`, or `since` when the header is absent, backfills what came after that id. A reader that falls far behind is cut off and picks up where it left off when it reconnects. `/exchanges` pages newest first with `cursor` and filters by `session` and `client`; `/exchanges/{id}?frames=true` adds the frames. `/sessions` is the spend per session and agent. `/routes` shows each route, and a `PUT` changes one for the next request without a restart; the change is held in memory, so a restart goes back to `peashoot.toml` and the environment. Errors are `application/problem+json` objects with `type`, `title`, `detail`, and `status`.

## Replay: the second run costs nothing

A route in `replay` mode answers every request it has a recording for straight from the store, with the recorded status, headers, and frames, byte for byte, and never calls the provider. Two requests are the same request when `rules.json` gives them the same fingerprint. In `peashoot.toml`:

```
[routes.default]
mode = "replay"
strict = true      # a miss is a 409 naming the fingerprint; false asks the provider and records the answer

[replay]
cadence = "instant"        # or "recorded": each frame waits for the offset it arrived at
repeatPolicy = "inOrder"   # or "latest"
```

With `inOrder`, a request recorded three times replays its three responses in the order they happened, then repeats the last; the count starts over when the proxy restarts. `latest` always serves the newest. A strict miss answers `409` with `{"type":"peashoot_error","error":"replay_miss","fingerprint":"...","route":"default"}`. A lenient miss goes to the provider, and the answer is recorded, so the next identical request hits.

The demo: one Claude Code prompt, run twice. The first run records and is billed; the second replays and is not. Pick a prompt that uses no tools, so the second run sends exactly the request the first one did.

1. Start the proxy in record mode (the default) and run the prompt:

   ```
   proxy/build/install/proxy/bin/proxy
   ANTHROPIC_BASE_URL=http://localhost:8787 claude -p "Name three vegetables that grow well in shade."
   ```

2. Stop the proxy, set `mode = "replay"` and `strict = true` under `[routes.default]` in `~/.peashoot/peashoot.toml`, and start it again with the provider pointed at a port nothing listens on, so any request that got past the replay would fail instead of being billed:

   ```
   PEASHOOT_ANTHROPIC_UPSTREAM=http://127.0.0.1:9 proxy/build/install/proxy/bin/proxy
   ANTHROPIC_BASE_URL=http://localhost:8787 claude -p "Name three vegetables that grow well in shade."
   ```

   The same answer comes back. Every `exchange.completed` line this run added to `~/.peashoot/events.jsonl` says `"replayHit":true` and `"costUsd":0.0`:

   ```
   grep '"exchange.completed"' ~/.peashoot/events.jsonl | tail -n 5
   ```

   Claude Code can send more than one request for one prompt; each was recorded in step 1 and replays the same way. A request that differs between the runs gets the 409 instead, and its fingerprint is in the body, so the rule that should have ignored the difference can be found in `rules.json`.

## Cassettes: replay in CI without a key

A cassette is a JSONL file with one recorded exchange per line, meant to be committed. Export what the store holds, and import it anywhere:

```
proxy/build/install/proxy/bin/proxy export nightly --dry-run    # list what redaction would strip; writes nothing
proxy/build/install/proxy/bin/proxy export nightly              # writes ~/.peashoot/cassettes/nightly.jsonl
proxy/build/install/proxy/bin/proxy export nightly --session 3f9c...   # only one session's exchanges
proxy/build/install/proxy/bin/proxy import nightly.jsonl        # tagged "nightly", replacing what that name held
```

Export applies `redact.json`, a list of `{"pointer", "pattern", "replacement"}` rules in the shape of `rules.json`'s `replace`. A pointer names strings in the request body; the empty pointer names every string in the request body and the response text too. The default replaces anything shaped like an API key (`sk-ant-…`, `sk-…`) with `[REDACTED]`. The dry run prints one line per hit: the exchange id, where, what matched (masked to its first characters and length, since the output can land in CI logs), and what it becomes. Export takes live recordings only, never a cassette the home imported. Import drops secret headers too, and needs a file whose base name is a usable cassette name. The request's fingerprint is exported as recorded, so a redacted request still replays. Authorization headers and API keys are never in the store, and export drops the secret headers again by name.

`[routes.default]` with `cassette = "nightly"` replays only what that cassette holds. A CI job needs no config file:

```
PEASHOOT_MODE=replay PEASHOOT_STRICT=true PEASHOOT_CASSETTE=cassettes/nightly.jsonl proxy/build/install/proxy/bin/proxy
```

`examples/ci-replay/` is a working example that the `Replay demo` workflow runs on every push: `replay.sh` starts the proxy that way, with an upstream nothing listens on, sends `request.json`, checks the replayed stream, and checks that an unrecorded request gets 409. Run it locally after `./gradlew :proxy:installDist`:

```
bash examples/ci-replay/replay.sh
```

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
