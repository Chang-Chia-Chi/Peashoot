# Frames are text; non-text upstream bodies are refused

Status: accepted

A response passes through the proxy as frames, and a frame is text with an arrival offset: the client writer re-encodes it, the store and the cassette keep it as a JSON string, and every sink reads it as text. A provider body that is not text, such as the Files API content download answering with a PDF or a PNG, cannot travel that path byte for byte. We keep frames text and refuse a non-text body before the response starts, with a 502 Peashoot error, rather than give frames a byte form: no v1 surface returns a non-text body, Claude Code's gateway traffic never asks for one, and a second frame kind would be carried by every sink and every cassette for one endpoint nobody calls.

## Considered options

- **A byte path.** Frames carry bytes; the String is a view for SSE only. Byte-verbatim relay for any content type. Set aside because it changes the frame type every sink reads, the store's frame JSON, and the cassette shape, for one endpoint outside v1. It stays open as the ticket "Byte path for non-streaming upstream bodies". If it is ever taken, it is cheapest before cassettes are committed anywhere and before the deriver exists; after that it is a migration.
- **A denylist of binary types.** Set aside because every new binary type would be a new hole. The allowlist names what the frame path can actually decode.

## Consequences

- A text body is `text/*`, `application/json`, or `application/*+json`, with a declared charset of UTF-8 or none. A missing content-type passes; the strict decode stays the backstop for a body that lies about itself.
- `GET /v1/files/{id}/content` through the proxy answers 502 `unsupported_content_type`. The Files API is not a v1 surface, so no supported client meets it.
- The refusal takes the proxy-failure path: one WARN line, `onComplete` with the 502, nothing recorded, because the exchange never had a source.
- The request side is untouched. Request bodies are bytes end to end, so uploads and base64 content in requests already survive.
