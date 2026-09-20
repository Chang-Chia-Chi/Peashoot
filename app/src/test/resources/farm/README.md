# Farm reducer fixtures

Event lines in the shape `proxy/src/main/kotlin/dev/peashoot/proxy/Deriver.kt` writes them: the same
field names in the same order, one JSON object per line, as `events.jsonl` and the control API's
feed carry them. The exceptions are deliberate: `odd-lines.jsonl` and the last line of
`paths.jsonl` are lines no Deriver writes, there to show the reducer survives one. There was no
capture on this machine to take them from, so these are hand-built against that code; sessions,
exchange ids, and paths are invented. `FarmTest` replays each file line by line and asserts the
state after every event.

| File | Covers |
|---|---|
| `one-turn.jsonl` | One session's two turns: started, completed with `Write`, `Edit`, `Read` on one path, a `Read` of an unseen path, a `Grep`, and a pathless `Bash`; then started and completed again. |
| `two-in-flight.jsonl` | Two exchanges of one session out at once: the first completion must not send the villager home while the second is still at the well. |
| `sub-agents.jsonl` | A main-thread turn, then helpers: one whose `parentAgent` is null (parent is the session's villager), one whose `parentAgent` names the first helper, and one whose `parentAgent` names a helper that is only heard on the line after, as a window connecting mid-run hears them. |
| `late-join.jsonl` | One completed line for a session never heard starting, as the app sees it when it connects mid-turn: the villager is made anyway and the turn's tools are applied. |
| `paths.jsonl` | Windows and POSIX spellings of one path meeting on one crop, growth capped at the last stage, an `Edit` to an unseen path planting it, and a last line with no `ts` at all, whose touches must not blank what the crops already had and whose one planting has no timestamp to show. |
| `odd-lines.jsonl` | Lines the reducer must survive: `event` as an object, an unknown event name, `exchange.client_gone`, `session` null, `tools` as a string with `usage` null, and a missing `exchangeId` with a tool whose `path` is an object. |
| `weather.jsonl` | The sky, one line at a time: a cache read is rain, a completion without one is clear again, an `exchange.client_gone` is lightning and a spilled bucket whose completion (`clientDisconnected`) keeps the lightning and carries no water home — that turn also read from the cache, so the line is one both the lightning and the rain rule would claim, which pins the precedence — and a 529 is a storm. |
| `rate-limit.jsonl` | Stamina and the well: rate-limit remaining kept per completion, a 429 resting the villager at the well without dropping produce, the retry walking it out again, then a `rateLimit` with null fields and one that is null, neither of which may forget what the villager had. |
| `bin.jsonl` | The shipping bin: produce only for a 2xx `end_turn`, a `tool_use` completion dropping none, a null `costUsd` counted as unpriced rather than as zero, a replay hit adding 0.0, and `mode` turning night on and off again. |
| `day.jsonl` | Two sessions for the idle sweep: one with a helper, tool calls in both path spellings, usage and cost to add up, and a `started` with no `completed` to strand it at the well; the other heard twenty minutes later, which the same tick must leave alone. |
| `odd-signals.jsonl` | Signal fields of the wrong shape: `costUsd` a string, `rateLimit` a string, no `status` at all, a `ts` that is not a time, and a `mode` no proxy of ours writes. |
| `touches.jsonl` | One file touched by two villagers of one session — the main thread plants it, a sub-agent reads it in the Windows spelling, the main thread edits it again — so that a crop's touch history (#22) has more than one agent in it, in order, with their times. A `Grep` naming a path touches nothing and leaves no touch. |
| `responses-poll.jsonl` | A Responses turn, and then the exchanges that are no turn at all (#81): a get-by-id, whose body repeats the create's own output and so names its tools again, a cancel, and a cancel for a session this window never heard. Each carries its own started and completed line and reports no usage, and each must leave the sky, the water, the crop and the bin exactly as the turn before it left them — while still bringing its villager home from the well its `started` sent it to. |
