#!/bin/sh
# Stop hook: an agent may not declare itself done while the build's checks fail.
# Exit 2 sends the failure back to the agent as the reason it cannot stop. After three
# consecutive failures the session may end, so a stuck agent reports instead of looping.
# Escape hatch for humans driving a session: PEASHOOT_SKIP_CHECK=1
[ "$PEASHOOT_SKIP_CHECK" = "1" ] && exit 0
cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0
input=$(cat | tr -d ' ')
session=$(printf '%s' "$input" | grep -o '"session_id":"[^"]*"' | head -1 | cut -d'"' -f4)
counter="build/stop-bounces-${session:-unknown}"
# A stop that is not itself the result of a bounce starts a fresh count.
case "$input" in *'"stop_hook_active":true'*) ;; *) rm -f "$counter" ;; esac
./gradlew -q check 1>&2 && { rm -f "$counter"; exit 0; }
bounces=$(( $(cat "$counter" 2>/dev/null || echo 0) + 1 ))
mkdir -p build && echo "$bounces" > "$counter"
if [ "$bounces" -ge 3 ]; then
  echo "gradlew check still fails after $bounces attempts; letting the session end." 1>&2
  exit 0
fi
echo "gradlew check failed (attempt $bounces of 3): fix formatting, warnings, or tests before stopping." 1>&2
exit 2
