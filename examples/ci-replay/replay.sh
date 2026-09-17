#!/usr/bin/env bash
# Replays the committed cassette through the built proxy with no API key and an upstream nothing
# listens on: the committed request is answered from the cassette, and an unrecorded one is refused
# with 409. Run from the repository root after `./gradlew :proxy:installDist`.
#
# tool-use.jsonl was recorded through the proxy from core's stream-with-tool-use.sse fixture and
# written by `proxy export tool-use`; CiReplayTest fails first if a rule change would make it miss.
set -euo pipefail

dir=examples/ci-replay
home=$(mktemp -d)
log=$home/proxy.log

# Port 0: the proxy takes a free port and logs it, so nothing races for one.
PEASHOOT_HOME=$home \
  PEASHOOT_PORT=0 \
  PEASHOOT_MODE=replay \
  PEASHOOT_STRICT=true \
  PEASHOOT_CASSETTE=$dir/tool-use.jsonl \
  PEASHOOT_ANTHROPIC_UPSTREAM=http://127.0.0.1:9 \
  proxy/build/install/proxy/bin/proxy >"$log" 2>&1 &
proxy=$!
trap 'kill "$proxy" 2>/dev/null || true' EXIT

url=
for _ in $(seq 60); do
  url=$(grep -o 'listening on http://[^,]*' "$log" | cut -d' ' -f3 || true)
  [ -n "$url" ] && break
  kill -0 "$proxy" 2>/dev/null || { cat "$log"; exit 1; }
  sleep 1
done
[ -n "$url" ] || { echo "the proxy did not start"; cat "$log"; exit 1; }

post() {
  curl -sS -o "$home/body" -w '%{http_code}' \
    -H 'content-type: application/json' \
    -H 'anthropic-version: 2023-06-01' \
    --data-binary "$1" "$url/v1/messages"
}

expect() {
  local status=$1 want=$2 pattern=$3
  if [ "$status" != "$want" ] || ! grep -qF "$pattern" "$home/body"; then
    echo "expected $want with $pattern, got $status:"
    cat "$home/body" "$log"
    exit 1
  fi
}

expect "$(post "@$dir/request.json")" 200 '"stop_reason":"tool_use"'
echo "replayed the committed request from the cassette"
expect "$(post '{"model":"unrecorded"}')" 409 '"error":"replay_miss"'
echo "refused an unrecorded request with 409"
