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
