# Codex's Responses transport: headers, the WebSocket attempt, and what makes it fall back

Read 2026-09-20 from `github.com/openai/codex`, branch `main` at commit
**`5c5308fc9a9ee789049d646ef11e5400384b9c6f`**, via `raw.githubusercontent.com`. Nothing here was
learned by running Codex or by calling a provider: every claim is a quotation from that source tree.

`docs/research/client-compat-matrix.md` §2.2 already covers Codex's wire API, base-URL knobs, and
timeouts, read 2026-09-12. This note exists for the three facts #25 turns on and that one does not
settle: the exact name of the sticky turn-state header, the code that reads and re-sends it, and
**which refusal status makes the WebSocket attempt fall back cleanly instead of retrying**. Where the
two notes overlap they agree; the file layout in the older note is now stale (`client.rs` and friends
have moved between crates).

## Headers on a Responses request

| Header | Value | Source |
|---|---|---|
| `originator` | `codex_cli_rs`, overridable by `CODEX_INTERNAL_ORIGINATOR_OVERRIDE` | `codex-rs/login/src/auth/default_client.rs`: `pub const DEFAULT_ORIGINATOR: &str = "codex_cli_rs";`, inserted by `default_headers()` as `headers.insert("originator", …)` |
| `session-id` | opaque string (a UUID, or the prompt-cache key for a root session) | `codex-rs/codex-api/src/requests/headers.rs`: `build_session_headers` does `insert_header(&mut headers, "session-id", &id)`. `codex-rs/core/src/client.rs`'s `responses_session_id` may substitute `prompt_cache_key`, so it is not always the raw session id |
| `thread-id` | UUID of the thread this turn runs on | same `build_session_headers`: `insert_header(&mut headers, "thread-id", &id)` |
| `x-codex-parent-thread-id` | the thread a spawned thread came from | `codex-rs/core/src/client.rs` |
| `x-openai-subagent` | `review`, `compact`, or a task name | `codex-rs/core/src/client.rs` |
| `x-codex-turn-state` | server-issued sticky token, echoed back | see below |
| `User-Agent` | `{originator}/{version} ({os} {os_version}; {arch}) {terminal}` | `get_codex_user_agent` in `default_client.rs` |
| also seen | `x-client-request-id` (set to the *thread* id), `x-codex-window-id`, `x-codex-turn-metadata`, `x-codex-beta-features`, `x-codex-installation-id`, `accept: text/event-stream`, `Authorization: Bearer …`, `ChatGPT-Account-ID` | `codex-rs/codex-api/src/endpoint/responses.rs`, `codex-rs/model-provider/src/auth.rs` |

`OpenAI-Beta: responses_websockets=2026-02-06` is sent **only on the WebSocket handshake**
(`build_websocket_headers`), not on HTTP requests. A plain `version` header was searched for and not
found.

## The sticky turn-state header is `x-codex-turn-state`

Declared identically in `codex-rs/core/src/client.rs`,
`codex-rs/codex-api/src/sse/responses.rs`, and `codex-rs/codex-api/src/endpoint/responses_websocket.rs`:

```rust
pub const X_CODEX_TURN_STATE_HEADER: &str = "x-codex-turn-state";
```

Read off the response, in `spawn_response_stream` (`codex-api/src/sse/responses.rs`):

```rust
if let Some(turn_state) = turn_state.as_ref()
    && let Some(header_value) = stream_response.headers
        .get(X_CODEX_TURN_STATE_HEADER).and_then(|value| value.to_str().ok())
{ let _ = turn_state.set(header_value.to_string()); }
```

Sent back on the next request of the same turn, in `build_responses_headers` (`core/src/client.rs`):

```rust
if let Some(turn_state) = turn_state
    && let Some(state) = turn_state.get()
    && let Ok(header_value) = HeaderValue::from_str(state)
{ headers.insert(X_CODEX_TURN_STATE_HEADER, header_value); }
```

It is an `Arc<OnceLock<String>>` captured once per turn and reset per turn (`turn_state:
Arc::new(OnceLock::new())` in `new_session`). Over WebSocket it travels in the JSON `client_metadata`
map instead of a header.

**What this means for the proxy.** The token is server state that changes between turns, so it must
not be in the fingerprint: if it were, the second turn of a recorded session could never replay,
because the client would be sending a token the recording has never seen. It is not in
`Rules.DEFAULT.keepHeaders` (`content-type`, `anthropic-version`, `anthropic-beta`, `openai-beta`),
so it is already excluded, and `ResponsesSeamTest` pins that: a replay whose request carries a
*different* turn-state token still hits. It reaches the upstream on the way in because request
headers are forwarded minus hop-by-hop, and reaches the client on the way out for the same reason,
and it is stored with the rest of the response headers, so a replay serves it verbatim.

## The WebSocket attempt, and why the refusal must be 426

`codex-rs/core/src/client.rs`'s `stream()` tries the WebSocket first whenever
`responses_websocket_enabled()`, and only falls through to `stream_responses_api` on
`FallbackToHttp`. The URL is the provider's base URL with the scheme swapped to `wss` and `/responses`
appended (`websocket_url_for_path`, `codex-api/src/provider.rs`), so pointing `openai_base_url` at
Peashoot makes Codex open `ws://127.0.0.1:8787/v1/responses` before it ever sends an HTTP request.

Exactly one status produces a clean fallback:

```rust
Err(ApiError::Transport(TransportError::Http { status, .. }))
    if status == StatusCode::UPGRADE_REQUIRED =>
{ return Ok(WebsocketStreamOutcome::FallbackToHttp); }
```

Any other handshake failure becomes an ordinary stream error and is retried up to
`stream_max_retries` (default 5) before `try_switch_fallback_transport` gives up and pins the session
to HTTP (`core/src/responses_retry.rs`). A connection reset or a 5xx therefore costs five slow
retries per session; a 426 costs nothing.

This is what `docs/spec.md` already decided ("Upgrade requests are refused with a plain 426 so Codex
falls back to HTTP") and what `ProxyServer.kt`'s `relayModule` already does. This note is the
evidence for it, which the spec asserted without one.

## Retry behaviour, for picking any other refusal status

`codex-rs/codex-client/src/retry.rs` with `model-provider-info/src/lib.rs`:

```rust
retry_429: false,
retry_5xx: true,
retry_transport: true,
```

`should_retry` is `(retry_429 && status == 429) || (retry_5xx && status.is_server_error())`, with
`DEFAULT_REQUEST_MAX_RETRIES = 4`, 200 ms base, doubling, 0.9–1.1 jitter. `Retry-After` is **not**
honoured on this path — `codex-client/src/retry.rs` carries
`// TODO(anp): Respect Retry-After from HTTP responses before retrying the request.` and
`core/tests/suite/retry_after.rs` asserts the TODO. So a 4xx that is not 429 is the only refusal the
proxy can make that Codex will not hammer.

## Codex does not use the Responses resume cursor

Searched the tree for `starting_after` (one unrelated TUI local variable), for `stream=true`, and for
`"/responses/"`: **no GET-by-id resume path exists in Codex**. It resumes a cut turn by reopening the
WebSocket and sending an incremental `response.create` that omits the input items already sent
(`get_incremental_items` / `prepare_websocket_request` in `core/src/client.rs`), keyed by
`x-codex-turn-state`.

`docs/spec.md` story 23 and `docs/design.md` §9 both describe `GET /v1/responses/{id}?stream=true&
starting_after=N` as the mechanism "Codex" resumes with. Against this source tree that is not so —
it is a real Responses API feature, and other clients may use it, but Codex is not one of them. The
spec is left as it stands; this paragraph is the correction, and #27, which owns serving
`starting_after` from the proxy's own buffer, is where the question of who actually asks for it
belongs. #25 relays, records and replays those requests like any other exchange and serves nothing
from a buffer.

## Not verified

- No Codex binary was run and no provider was called, here or anywhere in this repository. That the
  fallback is clean *in practice*, and that a real turn completes through the proxy, is the manual
  smoke in the repository README ("A Codex turn through the proxy"), owed to a human.
- Whether `session-id` is stable across a whole CLI session when `responses_session_id` substitutes
  the prompt-cache key. The proxy treats it as opaque, so nothing here depends on the answer.
