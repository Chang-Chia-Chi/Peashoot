#!/usr/bin/env python3
"""SPIKE CODE for GitHub issue #2. Not part of the Peashoot product, not on any
build path, not imported by `core`, `proxy` or `app`. Python 3 stdlib only.

A throwaway TCP cutter that sits between a client and Peashoot:

    claude -p  ->  cutter (127.0.0.1:8796)  ->  Peashoot (127.0.0.1:8795)  ->  api.anthropic.com

It forwards bytes both ways untouched. For the FIRST response that is a
`text/event-stream` answering a `POST /v1/messages` whose request body carried
`"stream":true` and a model that is not a haiku/small-fast helper, it aborts the
client-side socket (RST via SO_LINGER 0) once roughly `--cut-after` bytes of
response body have been relayed, i.e. mid-stream after several
`content_block_delta` events. The upstream socket is closed at the same moment
so Peashoot sees its own client go. Every other connection passes untouched.

`--cut-at-request` cuts instead as soon as the matching request has been fully
relayed, before any response byte exists.

Privacy: this script never prints, logs or writes request bytes, header values
or bodies. It logs method, path, status, byte counts and the decision it made.

ponytail: one HTTP/1.1 parser good enough for this traffic. Content-Length
bodies are tracked so a keep-alive connection can carry several requests; a
chunked or unbounded response puts the connection into blind passthrough for
the rest of its life, which is fine because the SSE turn is the last thing that
matters on it.
"""

import argparse
import collections
import socket
import struct
import threading
import time

LOG_LOCK = threading.Lock()
CUT_LOCK = threading.Lock()
START = time.monotonic()
CUT_DONE = False


def log(message):
    with LOG_LOCK:
        print(f"[{time.monotonic() - START:8.3f}] {message}", flush=True)


def reset(sock):
    """Close with RST rather than FIN, so the peer sees an abrupt drop."""
    try:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
    except OSError:
        pass
    try:
        sock.close()
    except OSError:
        pass


def header_value(lines, name):
    prefix = name.encode() + b":"
    for line in lines:
        if line.lower().startswith(prefix):
            return line.split(b":", 1)[1].strip()
    return None


def model_is_small(body):
    """True for a haiku/small-fast helper call. The model name is never logged."""
    marker = b'"model":"'
    at = body.find(marker)
    if at < 0:
        return False
    end = body.find(b'"', at + len(marker))
    return b"haiku" in body[at + len(marker) : end].lower()


class Parser:
    """Incremental HTTP/1.1 message parser fed the bytes that are also forwarded."""

    def __init__(self):
        self.buf = bytearray()
        self.state = "head"
        self.remaining = 0
        self.body = bytearray()
        self.head_lines = []

    def feed(self, data):
        """Yields (head_lines, body) per complete message; stops at an unbounded body."""
        self.buf += data
        while True:
            if self.state == "passthrough":
                self.buf.clear()
                return
            if self.state == "head":
                at = self.buf.find(b"\r\n\r\n")
                if at < 0:
                    return
                self.head_lines = bytes(self.buf[:at]).split(b"\r\n")
                del self.buf[: at + 4]
                self.body = bytearray()
                length = header_value(self.head_lines, "content-length")
                if length is None:
                    yield (self.head_lines, self.body)
                    # No length: either a stream or a chunked body. Stop parsing.
                    self.state = "passthrough"
                    continue
                self.remaining = int(length)
                self.state = "body"
            if self.state == "body":
                take = min(self.remaining, len(self.buf))
                self.body += self.buf[:take]
                del self.buf[:take]
                self.remaining -= take
                if self.remaining:
                    return
                self.state = "head"
                yield (self.head_lines, self.body)


def pump_up(conn_id, client, upstream, pending, args):
    """Client -> Peashoot. Classifies each request and queues its verdict."""
    parser = Parser()
    total = 0
    try:
        while True:
            chunk = client.recv(65536)
            if not chunk:
                break
            upstream.sendall(chunk)
            total += len(chunk)
            for head, body in parser.feed(chunk):
                try:
                    method, path = head[0].decode("latin-1").split(" ")[:2]
                except (UnicodeDecodeError, ValueError, IndexError):
                    method, path = "?", "?"
                main = (
                    method == "POST"
                    and path.split("?")[0] == "/v1/messages"
                    and b'"stream":true' in body.replace(b'"stream": true', b'"stream":true')
                    and not model_is_small(body)
                )
                pending.append(main)
                log(
                    f"conn {conn_id} request {method} {path} "
                    f"body={len(body)}B main_turn={main}"
                )
                if main and args.cut_at_request and take_cut():
                    log(f"conn {conn_id} CUT before any response byte")
                    reset(client)
                    # The upstream is closed gracefully, and only after a pause:
                    # an RST here would discard the request bytes still in the
                    # kernel buffer and Peashoot would record a truncated body.
                    time.sleep(0.5)
                    try:
                        upstream.shutdown(socket.SHUT_WR)
                    except OSError:
                        pass
                    return
    except OSError:
        pass
    finally:
        log(f"conn {conn_id} client->upstream ended after {total}B")
        try:
            upstream.shutdown(socket.SHUT_WR)
        except OSError:
            pass


def take_cut():
    """True for exactly one caller, ever."""
    global CUT_DONE
    with CUT_LOCK:
        if CUT_DONE:
            return False
        CUT_DONE = True
        return True


def pump_down(conn_id, upstream, client, pending, args):
    """Peashoot -> client. Cuts the first eligible SSE response mid-body."""
    parser = Parser()
    total = 0
    cutting = False
    body_bytes = 0
    try:
        while True:
            chunk = upstream.recv(65536)
            if not chunk:
                break
            client.sendall(chunk)
            total += len(chunk)
            if cutting:
                body_bytes += len(chunk)
                if body_bytes >= args.cut_after:
                    log(
                        f"conn {conn_id} CUT mid-stream after ~{body_bytes}B "
                        f"of response body (RST to client, close to upstream)"
                    )
                    reset(client)
                    reset(upstream)
                    return
                continue
            for head, _ in parser.feed(chunk):
                try:
                    status = head[0].decode("latin-1").split(" ")[1]
                except (UnicodeDecodeError, IndexError):
                    status = "?"
                ctype = header_value(head, "content-type") or b""
                main = pending.popleft() if pending else False
                sse = b"text/event-stream" in ctype.lower()
                eligible = main and sse and not args.cut_at_request
                log(
                    f"conn {conn_id} response status={status} sse={sse} "
                    f"main_turn={main} eligible={eligible}"
                )
                if eligible and take_cut():
                    cutting = True
                    body_bytes = len(parser.buf)
                    parser.buf.clear()
    except OSError:
        pass
    finally:
        log(f"conn {conn_id} upstream->client ended after {total}B")
        reset(client)
        reset(upstream)


def serve(args):
    listener = socket.socket()
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind((args.host, args.listen))
    listener.listen(64)
    log(f"listening on {args.host}:{args.listen} -> {args.host}:{args.target}")
    conn_id = 0
    while True:
        client, _ = listener.accept()
        conn_id += 1
        threading.Thread(
            target=handle, args=(conn_id, client, args), daemon=True
        ).start()


def handle(conn_id, client, args):
    log(f"conn {conn_id} accepted")
    try:
        upstream = socket.create_connection((args.host, args.target))
    except OSError as e:
        log(f"conn {conn_id} upstream connect failed: {type(e).__name__}")
        reset(client)
        return
    client.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    upstream.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    pending = collections.deque()
    threading.Thread(
        target=pump_up, args=(conn_id, client, upstream, pending, args), daemon=True
    ).start()
    pump_down(conn_id, upstream, client, pending, args)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--listen", type=int, default=8796)
    parser.add_argument("--target", type=int, default=8795)
    parser.add_argument("--cut-after", type=int, default=3072)
    parser.add_argument("--cut-at-request", action="store_true")
    serve(parser.parse_args())
