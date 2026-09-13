# Messages fixtures

Raw response bodies of the Anthropic Messages API, byte for byte. Message, tool, and request ids are replaced with `REDACTED`; nothing else is edited. Tests in `core` parse them and prove the frames re-serialize to the same bytes; the proxy's fake upstream replays them.

| File | Origin |
|---|---|
| `non-streaming-401.json` | Captured 2026-09-12 from `api.anthropic.com` through the proxy's `PEASHOOT_DUMP_FRAMES` flag: a non-streaming error body. |
| `stream-with-tool-use.sse` | Transcribed from the documented streaming grammar: a turn with a text block and a `tool_use` block, every event type except `error`. Replace with a capture (below). |
| `stream-ending-in-error.sse` | Transcribed from the documented grammar: a stream that ends in an `error` event. The API only sends one under load, so it cannot be captured on demand. |

To capture a real stream, run the proxy with the dump flag and one Claude Code turn through it, then copy the response section for `/v1/messages` out of the dump file and redact the ids:

```
PEASHOOT_PORT=8790 PEASHOOT_DUMP_FRAMES=dump.txt proxy/build/install/proxy/bin/proxy &
ANTHROPIC_BASE_URL=http://127.0.0.1:8790 claude -p "Use the Bash tool to run: echo peashoot. Then reply: done" --allowedTools "Bash(echo:*)"
```
