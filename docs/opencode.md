# OpenCode through the proxy

OpenCode reaches a provider through a Vercel AI SDK package chosen per provider, and every one of
them takes a `baseURL`. So pointing it at Peashoot is a config change and nothing more, and the one
thing config cannot do — say which OpenCode *session* a request belongs to — is what the plugin
below is for.

**Nobody has run this yet.** Everything here is read from OpenCode's own documentation and source
(links and dates at the bottom); the proxy's half is covered by tests at its HTTP boundary. No
OpenCode binary has been pointed at Peashoot, and the smoke run in the last section is owed to a
human with a key (#27, acceptance criteria 3 and 4).

## 1. Point a provider at the proxy

Peashoot listens on `8787` by default and routes by path, so one base URL serves every surface:
`http://localhost:8787/v1`. Each AI SDK package appends its own path to it — `/messages`,
`/responses`, `/chat/completions` — which is the same thing it would append to the provider's own
base URL.

Put this in `opencode.json` in the project root, or in `~/.config/opencode/opencode.json` for every
project. OpenCode "first looks for a config file in the current directory, then traverses up to the
nearest Git directory".

### The Anthropic provider (Peashoot's `anthropic-messages` surface)

```json
{
  "$schema": "https://opencode.ai/config.json",
  "provider": {
    "anthropic": {
      "options": {
        "baseURL": "http://localhost:8787/v1"
      }
    }
  }
}
```

### An OpenAI-compatible provider (Peashoot's `openai-chat` surface)

`@ai-sdk/openai-compatible` is the package for `/v1/chat/completions` endpoints. Declaring a
provider of your own is what the docs show for any custom endpoint:

```json
{
  "$schema": "https://opencode.ai/config.json",
  "provider": {
    "peashoot": {
      "npm": "@ai-sdk/openai-compatible",
      "name": "OpenAI through Peashoot",
      "options": {
        "baseURL": "http://localhost:8787/v1",
        "apiKey": "{env:OPENAI_API_KEY}"
      },
      "models": { "gpt-5": { "name": "GPT-5 through Peashoot" } }
    }
  }
}
```

Overriding the built-in `openai` provider's `baseURL` instead sends the same traffic to Peashoot's
`openai-responses` surface, because OpenCode asks that provider for `sdk.responses(modelID)`. Either
is supported; the `openai-compatible` package is the one whose path is documented as
`/v1/chat/completions`, so it is the less surprising of the two to smoke first.

The API key still travels: Peashoot strips `authorization` and `x-api-key` from everything it
stores and logs, and forwards them upstream unchanged. It never stores one.

## 2. Tag the session — the three-line plugin

OpenCode sends no session identifier that Peashoot recognises, so without this every turn is
grouped by the conversation's first user message instead: workable, but it splits a session in two
whenever OpenCode rewrites that message. Peashoot honours an `x-peashoot-session` header outright,
from any client, over every other signal — and OpenCode's `chat.headers` hook is handed the session
id directly.

Drop this in `.opencode/plugins/peashoot.ts` (project) or `~/.config/opencode/plugins/peashoot.ts`
(global). It is auto-loaded; no `plugin` entry in `opencode.json` is needed.

```ts
import type { Plugin } from "@opencode-ai/plugin"

export const Peashoot: Plugin = async () => ({
  "chat.headers": async (input, output) => {
    output.headers["x-peashoot-session"] = input.sessionID
  },
})
```

**The hook is three lines; the file around it is four more.** The spec calls this "a three-line
plugin", and the hook body really is three lines — but a module needs its import and its export, so
this is the honest shape rather than a contrived one. The hook's signature is
`(input: { sessionID, agent, model, provider, message }, output: { headers }) => Promise<void>`, and
what a plugin puts in `output.headers` is merged last into the request's headers, so it wins over
anything the provider config set. OpenCode's own built-in GitHub Copilot plugin sets
`X-Interaction-Id` from `incoming.sessionID` in exactly this way.

### If you would rather not run a plugin

`provider.<id>.options.headers` sends a fixed set of headers with every request:

```json
"options": {
  "baseURL": "http://localhost:8787/v1",
  "headers": { "x-peashoot-session": "opencode-local" }
}
```

That is one session id for every OpenCode session that ever uses this config, so the farm draws one
villager doing everything and the ledger sums them all together. It is enough to tell OpenCode's
traffic apart from Claude Code's and no more. Use the plugin if you want sessions.

### What the header is and is not

- It is **stripped before the request goes upstream**. It is Peashoot's own; it means nothing to
  Anthropic or OpenAI, and forwarding it would hand them a stable identifier tying your turns
  together that they would not otherwise have.
- It is **no part of the fingerprint**, so a recording made in one session replays in another and a
  cassette does not go stale when you start a new session.
- Its value is **bounded where it enters**: control characters are replaced and it is capped at 128
  characters, because it is a string you chose and it ends up in `events.jsonl`, in the store, in
  the Gource log, and on the farm's labels.

## 3. What grouping looks like

One line per request in `~/.peashoot/events.jsonl`:

```
grep '"exchange.completed"' ~/.peashoot/events.jsonl | tail -n 5
```

With the plugin, every line of one OpenCode session carries the same `"session"` — OpenCode's own
session id — whichever surface the turn went to:

```json
{"event":"exchange.completed","session":"ses_8Fq2xKp1","client":"opencode","surface":"anthropic-messages", ...}
{"event":"exchange.completed","session":"ses_8Fq2xKp1","client":"opencode","surface":"openai-chat", ...}
```

`"client"` is the user-agent's product token, so it says `opencode` whether or not the plugin is
installed; `"session"` is what the plugin changes. The proxy's own view of the same thing:

```
curl -H "Authorization: Bearer $(cat ~/.peashoot/token)" http://localhost:8787/sessions
```

In the farm, one session is one villager, walking to the well for each turn and carrying its tokens
home. Without a session header the fallback groups turns by the SHA-256 of the conversation's first
user message, which is stable while that message is, so you may see a session split where OpenCode
rewrote it. With the header you will not.

## 4. The smoke run — owed to a human

Two runs, one per provider. Nothing in this repository may call a paid API, so these steps have
never been executed; run them and say what happened on #27.

1. Build and start the proxy:

   ```
   ./gradlew :proxy:installDist
   proxy/build/install/proxy/bin/proxy
   ```

2. Write the `anthropic` config from §1 and the plugin from §2, then run one turn that touches a
   file, so the tool grammar is exercised and the farm has a crop to grow:

   ```
   opencode run "Read README.md and name the three modules."
   ```

3. Check the event lines. What should be there: `"surface":"anthropic-messages"`,
   `"client":"opencode"`, a `"session"` that is OpenCode's own session id and not a
   `opencode:<hex>` fallback, and a `"tools"` array naming the read with its path. If `session`
   reads `opencode:` and then hex, the plugin did not load or the hook did not fire — check that
   the directory is `plugins/` and not `plugin/`.

4. Swap to the OpenAI-compatible provider from §1 and run the same prompt. The lines should say
   `"surface":"openai-chat"` and, if both runs are in one OpenCode session, carry the same
   `"session"` as step 3. That is criterion 3 end to end: two surfaces, one session, grouped by
   nothing but the injected header.

5. Confirm the header reached no provider. There is no way to see this from outside, so run the
   proxy with `PEASHOOT_DUMP_FRAMES=dump.txt` — the dump holds the response side only, so the
   honest check is the seam test `SessionHeaderSeamTest` rather than this step. Recorded here so
   that whoever runs the smoke knows it is already covered and need not look.

6. Replay and spend nothing. Stop the proxy, start it again with `PEASHOOT_MODE=replay`, and run
   the identical prompt. Every line the second run adds says `"replayHit":true` and
   `"costUsd":0.0`.

## Sources

Read 2026-09-20. **`github.com/sst/opencode` now redirects to `github.com/anomalyco/opencode`** — the
organisation was renamed, and both paths currently serve the same `dev` branch. Links below name the
current one.

| What | Where |
|---|---|
| Config file locations, `$schema`, custom provider example | [opencode.ai/docs/config](https://opencode.ai/docs/config/) |
| `provider.<id>.options.baseURL`, `options.headers`, `npm`, which package serves which endpoint | [opencode.ai/docs/providers](https://opencode.ai/docs/providers/) |
| Plugin directories, module shape, `@opencode-ai/plugin` | [opencode.ai/docs/plugins](https://opencode.ai/docs/plugins/) |
| The `chat.headers` hook's exact signature | [packages/plugin/src/index.ts](https://github.com/anomalyco/opencode/blob/dev/packages/plugin/src/index.ts) |
| That a plugin's headers are merged last into the request | [packages/opencode/src/session/llm/request.ts](https://github.com/anomalyco/opencode/blob/dev/packages/opencode/src/session/llm/request.ts) |
| OpenCode's own plugin setting a per-session header | [packages/opencode/src/plugin/github-copilot/copilot.ts](https://github.com/anomalyco/opencode/blob/dev/packages/opencode/src/plugin/github-copilot/copilot.ts) |
| Which provider maps to `sdk.responses` | [packages/opencode/src/provider/provider.ts](https://github.com/anomalyco/opencode/blob/dev/packages/opencode/src/provider/provider.ts), and `docs/research/client-compat-matrix.md` §2.3 |

Not verified, and worth checking during the smoke: whether OpenCode now sends a session header of
its own. `docs/research/client-compat-matrix.md` §2.3(d) recorded "no session header by default",
while the current `request.ts` appears to set one before a plugin's headers are merged. Nothing here
depends on the answer — Peashoot reads no OpenCode header but the one the plugin injects — but if
OpenCode has grown a stable one, `Client.detect` could read it and the plugin could go.
