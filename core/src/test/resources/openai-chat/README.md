# Chat Completions fixtures

Response bodies of the OpenAI Chat Completions API. **None of these was captured from a provider.**
Each is hand-built from the published wire format, in the shape a capture would have, and ids and
fingerprints read `REDACTED` where a real one would carry a value. Tests in `core` parse them and
prove the frames re-serialize to the same bytes; the proxy's fake upstream replays them.

They are good enough to hold the grammar honest and nothing more: only a real capture catches drift
between what OpenAI documents and what OpenAI sends. Replacing them with captures is still owed
(#24, acceptance criterion 1).

| File | Origin |
|---|---|
| `stream-with-tool-calls.sse` | Hand-built from the published streaming format: a turn with two `tool_calls`, each assembled from `function.arguments` fragments spread over three chunks under its own `index`, a `finish_reason` chunk, and the usage chunk `stream_options.include_usage` asks for. |
| `stream-without-usage.sse` | Hand-built: text deltas and `finish_reason: stop`, with no `stream_options`, so no usage chunk and no `usage` key at all. Absent usage must read as absent. |
| `non-streaming-tool-calls.json` | Hand-built: one `stream: false` body carrying `usage`, `choices[0].finish_reason`, and `choices[0].message.tool_calls`. |

## Capturing real ones

Owed to a human with an API key; nothing here may call a paid API. Run the proxy with the dump flag
and one turn through it, then copy the response section for `/v1/chat/completions` out of the dump
file and replace every `id`, `system_fingerprint`, and `call_*` with `REDACTED`:

```
PEASHOOT_PORT=8790 PEASHOOT_DUMP_FRAMES=dump.txt proxy/build/install/proxy/bin/proxy &
OPENAI_BASE_URL=http://127.0.0.1:8790/v1 OPENAI_API_KEY=$YOUR_KEY \
  python -c "$(cat <<'PY'
from openai import OpenAI
c = OpenAI()
s = c.chat.completions.create(
    model="gpt-4o-mini", stream=True, stream_options={"include_usage": True},
    messages=[{"role": "user", "content": "Run the Bash tool: echo peashoot"}],
    tools=[{"type": "function", "function": {"name": "Bash", "parameters": {
        "type": "object", "properties": {"command": {"type": "string"}}}}}])
for _ in s: pass
PY
)"
```

`Authorization` never reaches the dump, the store, or the event line: it is a secret header, dropped
at receipt and only ever sent upstream.

## Free local capture (Ollama)

No key and no spend, and the same grammar: Ollama serves `/v1/chat/completions`. Point the OpenAI
surface at it in `~/.peashoot/peashoot.toml`:

```
[surfaces.openai]
upstream = "http://127.0.0.1:11434"
```

```
ollama serve &
ollama pull qwen2.5:0.5b
PEASHOOT_PORT=8790 PEASHOOT_DUMP_FRAMES=dump.txt proxy/build/install/proxy/bin/proxy &
curl -s http://127.0.0.1:8790/v1/chat/completions -H 'content-type: application/json' \
  -d '{"model":"qwen2.5:0.5b","stream":true,"stream_options":{"include_usage":true},
       "messages":[{"role":"user","content":"say peashoot"}]}'
```

Then replay it with the upstream pointed at a dead port (`upstream = "http://127.0.0.1:1"`) and
`PEASHOOT_MODE=replay`: the same bytes come back and nothing is dialled.
