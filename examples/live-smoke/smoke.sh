#!/usr/bin/env bash
# One tiny prompt per provider, through the proxy, against the real APIs — and then the same
# requests again in replay mode with every upstream pointed at a port nothing listens on, which is
# the README's whole claim, proved against the providers rather than against a fixture. What it
# catches is grammar drift: a field the providers renamed, a usage object that moved.
#
# Not free, and not part of `check`: each run spends a fraction of a cent. `ANTHROPIC_API_KEY` and
# `OPENAI_API_KEY` come from the environment, and a key that is not set skips its provider with a
# notice rather than failing the run. Run from the repository root after `./gradlew :proxy:fatJar`:
#
#   ANTHROPIC_API_KEY=... OPENAI_API_KEY=... bash examples/live-smoke/smoke.sh
#
# Never run this under `set -x`: the keys are in the environment of every command below.
set -euo pipefail

jar=proxy/build/libs/peashoot.jar
home=$(mktemp -d)
# The keys live here and not in the proxy's data directory, so that whatever CI keeps of that
# directory afterwards cannot hold one.
conf=$(mktemp -d)
log=$home/proxy.log
events=$home/events.jsonl
body=$home/body
# Model ids are settable because a provider renaming one should not need a commit.
anthropic_model=${ANTHROPIC_MODEL:-claude-haiku-4-5}
openai_model=${OPENAI_MODEL:-gpt-4.1-nano}
# Small enough to be a rounding error, big enough that a provider still reports usage.
max_tokens=16
# Where to leave the event lines for a CI artifact. Nothing is copied when it is not set.
out=${PEASHOOT_SMOKE_EVENTS:-}
url=
proxy=

# Before anything can fail: two temp directories and a child process are already at stake.
trap cleanup EXIT

cleanup() {
  stop
  # Here and not at the end of a passing run: the lines worth reading are a failed run's.
  if [ -n "$out" ] && [ -f "$events" ]; then
    cp "$events" "$out"
  fi
  rm -rf "$home" "$conf"
}

stop() {
  if [ -n "$proxy" ]; then
    kill "$proxy" 2>/dev/null || true
    wait "$proxy" 2>/dev/null || true
    proxy=
  fi
}

# Port 0: the proxy takes a free port and logs it, so nothing races for one. The log is emptied
# first because this runs twice and the second start must not read the first one's line.
start() {
  : >"$log"
  PEASHOOT_HOME=$home PEASHOOT_PORT=0 PEASHOOT_MODE=$1 java -jar "$jar" >"$log" 2>&1 &
  proxy=$!
  url=
  for _ in $(seq 60); do
    url=$(grep -o 'listening on http://[^,]*' "$log" | tail -n 1 | cut -d' ' -f3 || true)
    [ -n "$url" ] && break
    kill -0 "$proxy" 2>/dev/null || {
      cat "$log"
      exit 1
    }
    sleep 1
  done
  if [ -z "$url" ]; then
    echo "the proxy did not start in $1 mode"
    cat "$log"
    exit 1
  fi
}

# A key belongs in a file curl reads, never in an argument list: `ps` shows the arguments of every
# process on the machine, and a failing command prints its own.
keyfile() {
  # A subshell, so the umask is this one file's business and not every write after it.
  (
    umask 077
    printf 'header = "%s: %s"\n' "$2" "$3" >"$conf/$1.conf"
  )
}

anthropic_call() {
  curl -sS --max-time 120 -o "$body" -w '%{http_code}' \
    -K "$conf/anthropic.conf" \
    -H 'anthropic-version: 2023-06-01' \
    -H 'content-type: application/json' \
    --data-binary @- "$url/v1/messages" <<JSON
{"model":"$anthropic_model","max_tokens":$max_tokens,
 "messages":[{"role":"user","content":"Reply with the single word: peashoot"}]}
JSON
}

openai_chat_call() {
  curl -sS --max-time 120 -o "$body" -w '%{http_code}' \
    -K "$conf/openai.conf" \
    -H 'content-type: application/json' \
    --data-binary @- "$url/v1/chat/completions" <<JSON
{"model":"$openai_model","max_tokens":$max_tokens,
 "messages":[{"role":"user","content":"Reply with the single word: peashoot"}]}
JSON
}

openai_responses_call() {
  curl -sS --max-time 120 -o "$body" -w '%{http_code}' \
    -K "$conf/openai.conf" \
    -H 'content-type: application/json' \
    --data-binary @- "$url/v1/responses" <<JSON
{"model":"$openai_model","max_output_tokens":$max_tokens,
 "input":"Reply with the single word: peashoot"}
JSON
}

call() {
  case $1 in
    anthropic) anthropic_call ;;
    openai-chat) openai_chat_call ;;
    openai-responses) openai_responses_call ;;
    *)
      echo "no such provider: $1"
      exit 1
      ;;
  esac
}

# What the event line calls this provider's surface.
surface() {
  case $1 in
    anthropic) echo anthropic-messages ;;
    *) echo "$1" ;;
  esac
}

# Completed exchanges on one surface that carry a usage object. `"usage":null` does not match, so
# a turn whose usage the parser missed fails here rather than passing quietly.
completed() {
  grep '"event":"exchange.completed"' "$events" 2>/dev/null |
    grep "\"surface\":\"$(surface "$1")\"" |
    grep -c "$2" || true
}

expect2xx() {
  case $2 in
    2??) ;;
    *)
      echo "$1: expected 2xx, got $2"
      cat "$body" "$log"
      exit 1
      ;;
  esac
}

# Waits for one more matching line than there was. The proxy ends the client's response before its
# sinks run, so the line is written after curl has already returned, and a count read on the spot
# can miss one that is on its way. Ten seconds is many times what an insert and an append take.
gained() {
  local tries=0
  while [ "$tries" -lt 20 ]; do
    if [ "$(completed "$2" "$3")" -gt "$4" ]; then
      return 0
    fi
    sleep 0.5
    tries=$((tries + 1))
  done
  echo "$1: no new event line after 10s (there were $4)"
  tail -n 3 "$events" 2>/dev/null || true
  cat "$log"
  exit 1
}

# A word list and not an array: the names hold no spaces, and `${#array[@]}` on an empty array is
# an unbound variable under `set -u` in the bash macOS still ships.
providers=
if [ -n "${ANTHROPIC_API_KEY:-}" ]; then
  keyfile anthropic x-api-key "$ANTHROPIC_API_KEY"
  providers="$providers anthropic"
else
  echo "::notice::ANTHROPIC_API_KEY is not set; skipping the Anthropic Messages surface"
fi
if [ -n "${OPENAI_API_KEY:-}" ]; then
  keyfile openai authorization "Bearer $OPENAI_API_KEY"
  providers="$providers openai-chat openai-responses"
else
  echo "::notice::OPENAI_API_KEY is not set; skipping both OpenAI surfaces"
fi
if [ -z "$providers" ]; then
  echo "::notice::no provider key is set, so there is nothing to smoke"
  exit 0
fi

echo "== recording against the real providers =="
start record
for provider in $providers; do
  before=$(completed "$provider" '"usage":{')
  status=$(call "$provider")
  expect2xx "$provider (record)" "$status"
  gained "$provider (record)" "$provider" '"usage":{' "$before"
  echo "$provider: $status, and one more exchange.completed with usage"
done
stop

# Replay with both upstreams on a port nothing listens on: an answer now can only have come from
# the store. Strict, so that a miss is a 409 naming the fingerprint rather than a call to a
# provider nobody meant to make — which with these upstreams would fail anyway.
cat >"$home/peashoot.toml" <<'TOML'
[surfaces.anthropic]
upstream = "http://127.0.0.1:9"

[surfaces.openai]
upstream = "http://127.0.0.1:9"

[routes.default]
mode = "replay"
strict = true
TOML

echo "== replaying with the providers unreachable =="
start replay
for provider in $providers; do
  before=$(completed "$provider" '"replayHit":true')
  status=$(call "$provider")
  expect2xx "$provider (replay)" "$status"
  gained "$provider (replay)" "$provider" '"replayHit":true' "$before"
  echo "$provider: $status from the store, with nothing listening upstream"
done
stop

echo "live smoke passed for:$providers"
