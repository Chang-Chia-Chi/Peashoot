# Peashoot

A local proxy that sits between an agent client and a model provider, recording every call, replaying recordings, and finishing a stream the client abandoned. One context: the proxy, its store, and the farm that watches them share this language.

## Language

**Exchange**:
One request through the proxy, from receipt to completion, with the response it drew and the flags the sinks set on it.
_Avoid_: call, transaction, request/response pair

**Frame**:
One unit of a response as it arrives: text plus its arrival offset. A streamed response is many frames; a non-streamed response is one. A frame is always text, never bytes.
_Avoid_: chunk, event, packet

**Source**:
Where an exchange's frames come from: the upstream provider, a cassette, or a resume buffer. An exchange has exactly one source.
_Avoid_: origin, backend, producer

**Sink**:
A consumer of an exchange's frames: the client writer, the recorder, the deriver. Every sink sees every frame once.
_Avoid_: consumer, subscriber, listener

**Surface**:
One provider API the proxy understands: its request shape, frame grammar, and resume story. Anthropic Messages, OpenAI Responses, OpenAI Chat Completions.
_Avoid_: endpoint, API, provider

**Text body**:
An upstream response body the frame path can carry: declared as text or JSON, in UTF-8 or with no charset declared. Any other body is a non-text body and is refused with a Peashoot error.
_Avoid_: binary body, byte body, blob, non-text content

**Event**:
One line the proxy emits about an exchange for any tool to consume: `exchange.started` when the request is heard, `exchange.completed` when the response ends. Written to the events file and the event table by the deriver, the sink that reads every frame for it.
_Avoid_: log line, record, notification

**Client**:
Who sent an exchange, detected from its headers in a fixed order: a type (claude-code, codex, an SDK, or unknown), a session, and for sub-agents an agent and a parent agent.
_Avoid_: user, caller, consumer

**Session**:
The conversation an exchange belongs to: the client's own session header when it sends one, otherwise the client type plus a hash of the conversation's first user message, so a client with no header still groups its turns.
_Avoid_: conversation id, thread

**Peashoot error**:
The proxy's own failure answer, typed so that no client can mistake it for a provider's error. Never imitates a provider's error shape.
_Avoid_: proxy error, gateway error, 502

## Flagged ambiguities

- **"Forwarded unmodified"** applies to bodies the proxy carries. A non-text body is not carried; it is refused before any of it reaches the client. The rule is about never rewriting what passes, not a promise to pass everything.

## Example dialogue

**Dev:** A client asked the proxy for a file's content and the provider answered with a PNG. What happens?

**Domain expert:** That is a non-text body. The proxy never opens a frame for it. The client gets a Peashoot error, and the exchange never had a source, so nothing is recorded.

**Dev:** So the frame path is text only. What about the PDF the client uploaded in the request?

**Domain expert:** The request side is not frames. Whatever the client sends goes to the surface as it was sent. Frames are only the response.

**Dev:** And a JSON error body from the provider, a 429?

**Domain expert:** A text body. One frame, forwarded unmodified, recorded like any exchange.
