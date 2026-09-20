# Responses fixtures

Response bodies of the OpenAI Responses API. **None of these was captured from a provider.**
Each is hand-built from the published wire format, in the shape a capture would have, and ids read
`REDACTED` where a real one would carry a value. Tests in `core` parse them and prove the frames
re-serialize to the same bytes; the proxy's fake upstream replays them.

They are good enough to hold the grammar honest and nothing more: only a real capture catches drift
between what OpenAI documents and what OpenAI sends. Replacing them with captures is still owed
(#25, acceptance criterion 2), as is the Codex smoke (acceptance criterion 1, steps in the
repository README under "A Codex turn through the proxy").

| File | Origin |
|---|---|
| `stream-with-function-call.sse` | Hand-built from the published streaming format: two `function_call` items opened by `response.output_item.added`, their `arguments` assembled from `response.function_call_arguments.delta` fragments that interleave between the two items, closed by `response.function_call_arguments.done` and `response.output_item.done`, and a `response.completed` carrying the same two items whole in `output[]` plus the usage. Every event carries a `sequence_number`. |
| `stream-incomplete.sse` | Hand-built: an assistant message streamed as `response.output_text.delta`, ending in `response.incomplete` with `incomplete_details.reason` and `"usage": null`. Absent usage must read as absent, and a message item must not read as a call. |
| `non-streaming-function-call.json` | Hand-built: one `stream: false` body — the response object itself — carrying `status`, `usage`, and an `output[]` of a `reasoning` item and a `function_call`. |

`GET /v1/responses/{id}` and `POST /v1/responses/{id}/cancel` have no fixture here: their bodies are
the same response object as the non-streaming one, and what the proxy must get right about them is
routing, fingerprinting, and replay, which `ResponsesSeamTest` exercises at the HTTP boundary with
inline bodies.

## Capturing real ones

Owed to a human with an API key; nothing here may call a paid API. Run the proxy with the dump flag
and one turn through it, then copy the response section for `/v1/responses` out of the dump file and
replace every `id`, `call_id`, and `item_id` with `REDACTED`:

```
./gradlew :proxy:installDist
PEASHOOT_PORT=8790 PEASHOOT_DUMP_FRAMES=dump.txt proxy/build/install/proxy/bin/proxy &
OPENAI_BASE_URL=http://127.0.0.1:8790/v1 OPENAI_API_KEY=$YOUR_KEY \
  python -c "$(cat <<'PY'
from openai import OpenAI
c = OpenAI()
s = c.responses.create(
    model="gpt-5", stream=True,
    input=[{"role": "user", "content": "Run the shell tool: echo peashoot"}],
    tools=[{"type": "function", "name": "shell", "parameters": {
        "type": "object", "properties": {"command": {"type": "string"}}}}])
for _ in s: pass
PY
)"
```

A chained pair is the other capture worth having: take the `id` off the first response and send a
second request with `previous_response_id` set to it. That is the shape acceptance criterion 3
replays, and it needs no id rewriting because the recorded id is served back verbatim.

`Authorization` never reaches the dump, the store, or the event line: it is a secret header, dropped
at receipt and only ever sent upstream. `OpenAI-Organization` and `OpenAI-Project` are stored today
(#77) — do not put a real one through this recipe.

## Free local capture

Unlike Chat Completions, there is no free local server for this surface to record against: Ollama
serves `/v1/chat/completions` and not `/v1/responses`. Point `[surfaces.openai] upstream` at any
server that does speak Responses, or capture with a key as above.
