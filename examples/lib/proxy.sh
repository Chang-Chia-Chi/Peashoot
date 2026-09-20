# Sourced by the scripts under examples/ that start a proxy and need the address it took. One
# loop, rather than a copy in each: the proxy is told to take a free port and logs which one.
#
# `examples/ci-replay/replay.sh` keeps its own copy on purpose. It is what the Replay demo
# workflow runs on every push, and #28 was the wrong change to touch a green check with.

# Echoes the address the proxy logged, or fails after a minute with the log. $1 log file, $2 pid.
proxy_url() {
  local log=$1 pid=$2 url=
  for _ in $(seq 60); do
    # The last one: a script that starts a proxy twice reads its own second line, not its first.
    url=$(grep -o 'listening on http://[^,]*' "$log" | tail -n 1 | cut -d' ' -f3 || true)
    [ -n "$url" ] && break
    # A proxy that has already exited will never log one.
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
  done
  if [ -z "$url" ]; then
    echo "the proxy never logged an address" >&2
    cat "$log" >&2
    return 1
  fi
  echo "$url"
}
