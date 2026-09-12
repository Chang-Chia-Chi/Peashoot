#!/bin/sh
# Stop hook: an agent may not declare itself done while the build's checks fail.
# Exit 2 sends the failure back to the agent as the reason it cannot stop.
# Escape hatch for humans driving a session: PEASHOOT_SKIP_CHECK=1
[ "$PEASHOOT_SKIP_CHECK" = "1" ] && exit 0
cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0
./gradlew -q check 1>&2 || {
  echo "gradlew check failed: fix formatting, warnings, or tests before stopping." 1>&2
  exit 2
}
exit 0
