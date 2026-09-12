"""PostToolUse hook: format the Kotlin file an agent just edited. Never blocks."""

import json
import os
import subprocess
import sys

MODULES = ("core", "proxy", "app")

try:
    payload = json.load(sys.stdin)
except Exception:
    sys.exit(0)

path = (payload.get("tool_input") or {}).get("file_path") or ""
if not path.endswith(".kt"):
    sys.exit(0)

root = os.environ.get("CLAUDE_PROJECT_DIR") or os.getcwd()
rel = os.path.relpath(os.path.abspath(path), root).replace(os.sep, "/")
module, _, inside = rel.partition("/")
if module not in MODULES or not inside:
    sys.exit(0)

wrapper = os.path.join(root, "gradlew.bat" if os.name == "nt" else "gradlew")
task = "ktfmtFormatTest" if inside.startswith("src/test/") else "ktfmtFormatMain"
subprocess.run([wrapper, "-q", f":{module}:{task}", f"--include-only={inside}"], cwd=root)
sys.exit(0)
