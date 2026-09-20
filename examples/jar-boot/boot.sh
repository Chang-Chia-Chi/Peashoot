#!/usr/bin/env bash
# Boots the fat jar — the one the installers carry and the app starts — and asks it who it is.
# Nothing else in the gate ever runs `java -jar peashoot.jar`: `build` proves a zip was written,
# not that it starts, so a jar that lost its main class, a service file, or its version would
# have sailed through every check and failed on a user's machine.
#
# Run from the repository root after `./gradlew build` (or `:proxy:fatJar`):
#
#   bash examples/jar-boot/boot.sh              # any version that is not the manifest-less "dev"
#   bash examples/jar-boot/boot.sh 1.0.0-dev    # exactly this one
set -euo pipefail

. examples/lib/proxy.sh

jar=proxy/build/libs/peashoot.jar
expected=${1:-}
home=$(mktemp -d)
log=$home/proxy.log
proxy=

cleanup() {
  if [ -n "$proxy" ]; then
    kill "$proxy" 2>/dev/null || true
    wait "$proxy" 2>/dev/null || true
  fi
  rm -rf "$home"
}
trap cleanup EXIT

if [ ! -f "$jar" ]; then
  echo "no $jar: run ./gradlew :proxy:fatJar"
  exit 1
fi

# Port 0, so two jobs on one runner cannot collide over a number either of them picked.
PEASHOOT_HOME=$home PEASHOOT_PORT=0 java -jar "$jar" >"$log" 2>&1 &
proxy=$!
url=$(proxy_url "$log" "$proxy")

health=
for _ in $(seq 30); do
  health=$(curl -sS --max-time 5 "$url/_peashoot/v1/health" 2>/dev/null) && [ -n "$health" ] && break
  sleep 1
done

if [ -z "$health" ]; then
  echo "the jar started but never answered on $url"
  cat "$log"
  exit 1
fi

case $health in
  *'"status":"ok"'*) ;;
  *)
    echo "health did not say ok: $health"
    exit 1
    ;;
esac

# The version comes from the jar's own manifest, so "dev" means `fatJar` stopped writing
# Implementation-Version and every release would ship a jar that cannot name itself.
version=$(printf '%s' "$health" | sed -n 's/.*"version":"\([^"]*\)".*/\1/p')
case $version in
  '' | dev)
    echo "health says version '$version': the jar's manifest has no Implementation-Version"
    echo "$health"
    exit 1
    ;;
esac
if [ -n "$expected" ] && [ "$version" != "$expected" ]; then
  echo "health says version '$version', but this build should be '$expected'"
  echo "$health"
  exit 1
fi

echo "the jar booted on $url and answered ok as version $version"
