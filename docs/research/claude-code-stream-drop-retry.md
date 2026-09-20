# Claude Code's re-issued request after a stream drop: it is not the same request

Measured 2026-09-20 on this machine, against the real `api.anthropic.com` through Peashoot itself.
Spike 1 of `docs/spec.md` ("Milestones") and `docs/design.md` §13, GitHub issue #2. The question #26
turns on: **after a mid-stream drop, does Claude Code re-issue a request that fingerprints the same
as the original under `Rules.DEFAULT`?**

**Answer: no, and no rule in the current vocabulary can make it so.** Claude Code does retry, silently
and within ~30 ms, but the request it sends is not the original: it appends a newline to the user's
last text block and appends a *new* text block instructing the model to resume. The fingerprint
therefore differs, and the difference is in `/messages/*/content/*/text` — the one field the
fingerprint exists to tell requests apart by.

A drop **before the first response byte** is a different story: there the re-issued request is
byte-identical and fingerprints identically under the defaults. Resume as designed works for that
case and only that case.

## Setup

| | |
|---|---|
| Client | Claude Code **2.1.278**, `claude -p … --model sonnet --max-turns 1`, saved claude.ai login, run from an empty temp directory so no project `CLAUDE.md` entered the body |
| Model on the wire | `claude-sonnet-5`, `max_tokens` 64000, `stream: true`, 113 tools, 3 `system` blocks |
| Proxy | this worktree at `533c2d2`, `:proxy:installDist`, `PEASHOOT_MODE=record`, `PEASHOOT_PORT=8795`, fresh `PEASHOOT_HOME` in a temp directory (deleted afterwards) |
| OS | Windows 11, `Microsoft Windows NT 10.0.26200.0` |
| Date | 2026-09-20 |

Topology — Peashoot is the capturing proxy; it has no way to drop a stream, so a throwaway TCP cutter
sits in front of it:

```
claude -p  ->  cutter 127.0.0.1:8796  ->  Peashoot 127.0.0.1:8795  ->  https://api.anthropic.com
```

The cutter is `docs/research/spikes/stream-cutter.py` — spike code, stdlib only, on no build path. It
forwards bytes untouched and logs only method, path, status, byte counts and its decision; it never
prints or writes a header value or a body byte. Its rule: for the **first** response that is a
`text/event-stream` answering a `POST /v1/messages` whose request body carried `"stream":true` and a
model that is not a haiku helper, abort the client-side socket (RST, `SO_LINGER 0`) once ~3 KB of
response body has been relayed, and close Peashoot's socket at the same moment so Peashoot sees its
own client leave. Every other connection — the `HEAD /api/hello` probe, everything else — passes
untouched. `--cut-at-request` cuts instead the moment the request has been relayed in full, before
any response byte exists.

Four `claude -p` runs in total: two with the mid-stream cut, one discarded (below), one with the
pre-response cut.

## What happened on the wire

**Run 1, mid-stream cut.** Cutter clock, seconds:

| t | Event |
|---|---|
| 13.500 | `HEAD /api/hello` → 200, 62 B. Untouched. |
| 18.390 | `POST /v1/messages?beta=true`, request 283,426 B on the wire |
| 21.625 | 200 `text/event-stream` begins |
| 23.250 | ~3,146 B of response body relayed → **RST to Claude Code, socket to Peashoot closed**. Peashoot emitted `exchange.client_gone`, `bytesSoFar: 3060` |
| 23.281 | **+31 ms**: a new TCP connection, a second `POST /v1/messages?beta=true`, request **283,618 B (+192 B)** |
| 25.297 | 200 begins; stream completes at 31.234 after 15,325 B |

`claude -p` exited 0 in 18.3 s and printed a complete 1…300. **The user sees no error, no
truncation, no retry message.** Run 2 reproduced it exactly: cut after ~3,138 B, re-issue **+47 ms**
later, again **+192 B** on the wire, and the three changed strings hashed identically to run 1's.

**Both halves were billed.** Peashoot keeps consuming after the client leaves, so the event log has
the whole of both:

| Run 1 | `usage` |
|---|---|
| original (dropped at 3,060 B) | `input 2, output 715, cacheRead 0, cacheWrite 106710`, `stop_reason end_turn`, 8.06 s |
| re-issue | `input 2, output 706, cacheRead 82570, cacheWrite 24189`, `stop_reason end_turn`, 6.65 s |

The re-issue read 82,570 tokens from cache and had to **write 24,189 again** — the appended block
falls inside the cached prefix, so the cache breaks at the point where the two requests diverge. 715
output tokens were generated, paid for, and thrown away; none of them travelled back to the provider
(see below).

**Run 3 is discarded.** The first pre-response cut RST'd Peashoot's socket too, which discarded
request bytes still in the kernel buffer, so Peashoot recorded a truncated body and the provider
answered 400. That is the cutter's fault, not Claude Code's; the script now closes that side
gracefully after a pause. Run 4 is the clean one. (Claude Code re-issued after the 400 as well.)

**Run 4, cut before the first response byte.** Request relayed in full at 10.203, client RST
immediately; re-issue **+625 ms** later at 10.828, request **307,548 B both times**. Billing is still
doubled — `output 712` then `output 743` — but the two requests are identical.

## The two requests, side by side

Run 1's pair, shown structurally: JSON pointer, type, string length, first 12 hex of SHA-256. **A** is
the original, **B** the re-issue. Nothing personal is reproduced; the long strings are the system
prompt, the user's global `CLAUDE.md` and memory, and the machine-context block, and they are shown
as hashes only.

Top level — ten keys, all present in both:

| Pointer | A | B | |
|---|---|---|---|
| `/model` | `string len=15 sha=4b07d9a517e3` | same | same |
| `/max_tokens` | `64000` | `64000` | same |
| `/stream` | `true` | `true` | same (ignored by default) |
| `/metadata` | `object(user_id)` | `object(user_id)`, equal | same (ignored by default) |
| `/thinking` | `object(display,type)` | same | same |
| `/output_config` | `object(effort)` | same | same |
| `/context_management` | `object(edits)` | same | same |
| `/tools` | `array[113]` | `array[113]` | same |
| `/system` | `array[3]` | `array[3]` | same |
| `/messages` | `array[2]` | `array[2]` | **contents differ** |

`/system`, all three blocks byte-equal: `len=74 sha=44ea4c75aec6`, `len=62 sha=0d7062851dd7`,
`len=27477 sha=0677784275bb`. `/messages/1` (a `system`-role message carrying a session-start
reminder, `len=57074 sha=e828adeace13`) is byte-equal too.

`/messages/0` (role `user`) is where the two part:

| Pointer | A | B |
|---|---|---|
| `/messages/0/content` | `array[3]` | **`array[4]`** |
| `/messages/0/content/0/text` | `len=1876 sha=13c5d083e85f` | same |
| `/messages/0/content/1/text` | `len=539 sha=7daba0e0903c` | same |
| `/messages/0/content/2/text` | `len=55 sha=87adf37aa02d` | **`len=56 sha=f5c8c96365e8`** |
| `/messages/0/content/3` | *(absent)* | **`{"type":"text","text":…}` , text `len=162 sha=c373e56926c4`** |

Five pointers differ and no others. Both differing strings are safe to quote in full — one is the
spike's own prompt, the other is a fixed client string with no path, name, address or token in it:

- `/messages/0/content/2/text`
  A: `"Count from 1 to 300, one number per line, nothing else."`
  B: `"Count from 1 to 300, one number per line, nothing else.\n"` — one appended `\n`.
- `/messages/0/content/3/text`, present only in B:
  `"Your response above was cut off mid-stream. Resume directly from where it stops — no apology, no recap. If none of it survived, answer the request from the start."`

Note what is *not* there: **no assistant turn, and no copy of the 3,060 bytes the client had already
received.** `/messages` is `array[2]` in both. Claude Code discards the partial output entirely and
asks the model to start again — which is exactly the waste `docs/spec.md` story 21 exists to stop,
now measured: 715 output tokens billed for nothing, plus 24,189 cache-write tokens billed a second
time because the appended block broke the prefix.

Stored headers, both requests (Peashoot strips secret headers at receipt, so what follows is the
whole of what reached the store):

| Header | Equal A vs B? | In `keepHeaders`? |
|---|---|---|
| `content-type`, `anthropic-version`, `anthropic-beta` | yes | **yes** |
| `accept`, `accept-encoding`, `host`, `user-agent`, `x-app`, `anthropic-dangerous-direct-browser-access` | yes | no |
| `x-claude-code-session-id` | yes — `<redacted:session-id>`, the same value in both | no |
| `x-stainless-arch`, `-lang`, `-os`, `-package-version`, `-runtime`, `-runtime-version`, `-timeout` | yes | no |
| `x-stainless-retry-count` | yes — **`0` on both** | no |
| `content-length` | no (`283426` vs `283618`) | no |
| `connection` | run 4 only: present on the first, absent on the re-issue | no |

**No `authorization` and no `x-api-key` is in the store**, on any of the eight exchanges, although
every call authenticated successfully upstream: the secret-header strip in `docs/design.md` §4 step 1
does what it says. `/metadata/user_id` is `<redacted:account-id>`, equal between A and B, and is
dropped by `ignorePointers` before the hash anyway.

`x-stainless-retry-count: 0` on the re-issue is the important one: this is **not** an SDK-level retry
of the same HTTP request. It is a fresh turn issued by Claude Code above the SDK, on a new TCP
connection, which is why it is free to change the body.

## Fingerprints under `Rules.DEFAULT` (criterion 2)

`Rules.DEFAULT` is `keepHeaders = {content-type, anthropic-version, anthropic-beta, openai-beta}`,
`ignorePointers = [/metadata, /stream]`, and three `replace` rules (the billing line and the
environment block out of `/system/*/text`, the environment block out of `/messages/*/content/*/text`).

| Run | Drop | fingerprint A | fingerprint B | Equal? |
|---|---|---|---|---|
| 1 | mid-stream | `1c5133bb0da8c1d8…` | `3334d01bf99aae4c…` | **no** |
| 2 | mid-stream | `6b1344c200cea088…` | `a9beb49563f697fa…` | **no** |
| 4 | before first byte | `22a67bc98ff54f08…` | `22a67bc98ff54f08…` | **yes** |

Every default rule was re-applied to the captured bodies independently and none of them touches the
five differing pointers: the environment-block pattern does not match the appended text, and
`/metadata` and `/stream` were equal to begin with. **The defaults do not make a mid-stream
re-issue's fingerprint equal to the original's, and a drop before the first byte needs no new rule.**

## The differing field, and what a rule could and could not do (criterion 3)

The differing field is **`/messages/*/content/*/text`, and the length of `/messages/*/content`**, in
the last `user` message. Two of the three differences are string-level and are expressible:

```json
{"pointer": "/messages/*/content/*/text",
 "pattern": "(?s)\\AYour response above was cut off mid-stream\\..*\\z",
 "replacement": ""},
{"pointer": "/messages/*/content/*/text",
 "pattern": "\n\\z",
 "replacement": ""}
```

The third is not. The re-issue's content array has **one element more** than the original's, and
`keepHeaders`, `ignorePointers` and `replace` cannot express "drop an array element whose text is
empty". Blanking the nudge's text leaves `{"type":"text","text":""}` in the array, and a canonical
hash over an array of three objects is not a hash over an array of four.

The only pointer in today's vocabulary that removes it is an index — and it removes it
unconditionally:

```json
"ignorePointers": ["/metadata", "/stream", "/messages/*/content/3"]
```

That was tested, through the proxy's own `POST /_peashoot/v1/rules/test`, over all eight recorded
exchanges. With the two `replace` rules and that pointer added to the defaults, the answer is

```json
{"tested": 8,
 "collisions": [{"fingerprint": "6909c35b…", "exchangeIds": ["…4JZ4…", "…4E2W…"]},
                {"fingerprint": "2ba21370…", "exchangeIds": ["…0N7P…", "…0GF4…"]}],
 "splits": []}
```

— exactly the two mid-stream pairs collapse into one fingerprint each, nothing splits. That is a
useful negative result twice over: it confirms the five pointers are the *only* differences, and it
shows that the only rule that closes the gap does so by **deleting the fourth content block of every
message of every conversation**, which would silently merge genuinely different requests as soon as a
real turn has four content blocks. It must not go into the defaults.

**Recommendation: add nothing to `Rules.DEFAULT` for this.** The two `replace` rules above are
harmless but insufficient alone, and shipping half a fix would hide the problem rather than solve it.
What is actually needed is a decision, which belongs to the repository's owner and not to this note
(`CLAUDE.md`: a new rule, threshold or mechanism is raised, not added):

1. **Resume keyed on a prefix, not on equality.** A re-issue is the original plus appended blocks in
   the last user message, with `system`, `tools`, `model` and every earlier message byte-equal. A
   resume key computed over everything *except* the tail of the last user message's content would
   match both drop kinds, and would stay separate from the replay fingerprint, which must keep
   telling those two requests apart.
2. **A fourth rule type** — drop an array element matching a predicate — which would let the
   defaults express this. It is a real widening of the rule language for one client's one string.
3. **Ship resume for the pre-first-byte drop only**, and document that a mid-stream drop is not
   resumable by fingerprint. This is the smallest honest option and costs nothing now.

## What this means for #26

`docs/design.md` §9 says resume on Messages is "the buffered completion, matched by fingerprint
within the window", and §13 says "Spike 1 decides whether a re-issued request differs in any field
the default rules do not already ignore; if it does, the default rule set gains a rule for that
field. The design does not change." **The second half of that sentence does not survive this
measurement.** The differing field is user-message content, so the design does change, or resume on
Messages never fires for the case it was written for.

Concretely, for #26 as it stands:

- Resume **will** hit when the stream drops before the first response byte, and there it is worth
  having: run 4 re-billed 712 output tokens for nothing.
- Resume **will never** hit on a mid-stream drop, which is the flaky-Wi-Fi case in story 21 and the
  more expensive one, since the partial output is further along.
- Serving the buffered completion to a re-issue that carries the resume nudge is, on the merits, the
  *right* answer — the buffer holds the whole original answer, which is more than the client is
  asking for and exactly what the user wants — but it is a deliberate decision to answer a
  continuation request with a restart, not something a matching rule should sneak in.
- The window is not the binding constraint. The re-issue arrives **31–47 ms** after the drop
  mid-stream and **625 ms** after a pre-response drop, against a default `resume.windowSeconds` of
  300. Nothing here argues for changing it.

Neither `docs/spec.md` nor `docs/design.md` was edited; where this note contradicts §13 it says so
here and the reconciliation is the spec owner's.

## Not established

- **One client version, one OS, one model, one kind of prompt.** Claude Code 2.1.278 on Windows 11,
  `claude-sonnet-5`, a single-turn `-p` run with no tool use. The nudge string is a client constant
  and will move with a client release; a rule quoting it is a rule with a shelf life, which is the
  fourth argument against putting one in the defaults.
- **One kind of drop each.** An abrupt RST at ~3 KB of body, and a clean close after the request.
  Not tested: a drop after the *first* content block but before any delta, a drop during
  `message_delta`, a half-open connection that goes silent rather than closing (which is what a
  laptop sleep actually looks like, and which the 300-second byte watchdog governs), or a drop on a
  turn that had already produced a `tool_use` block.
- **Whether Claude Code ever re-issues more than once.** `docs/research/client-compat-matrix.md`
  §2.1 quotes the docs as "up to two times in quick succession" before
  `Connection lost before a response was produced`. One re-issue was observed each time, because one
  cut was made each time; the cutter cuts once by construction.
- **Whether the partial output is used locally.** The re-issued *request* provably does not carry it
  — `/messages` is `array[2]`, with no assistant turn — so nothing partial went back to the
  provider. Whether Claude Code concatenates its local partial with the new answer before printing
  was not determined; the printed result was a clean 1…300 either way.
- **Subscription traffic only.** Every run used the saved claude.ai login, so `costUsd` is null on
  all eight event lines and the waste is quoted in tokens, as story 30 requires.
- **Nothing here was replayed.** The proxy ran in `record` mode throughout; that a cassette of these
  exchanges replays is `ReplaySeamTest`'s business, not this note's.
