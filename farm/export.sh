#!/usr/bin/env bash
# Exports the farm as one self-contained executable, for the app to carry (farm/README.md).
#   bash farm/export.sh Linux|Windows <out-dir>
# Runs on Linux and in Git Bash on Windows, in CI's release legs and on a developer's machine alike.
# The Godot version is gradle/libs.versions.toml's `godot`; the editor and the one export template
# needed are downloaded once into build/godot (or $PEASHOOT_GODOT_CACHE) and reused.
set -euo pipefail

platform=${1:?"usage: export.sh Linux|Windows <out-dir>"}
out=${2:?"usage: export.sh Linux|Windows <out-dir>"}
root=$(cd "$(dirname "$0")/.." && pwd)
version=$(sed -n 's/^godot = "\(.*\)"$/\1/p' "$root/gradle/libs.versions.toml")
[ -n "$version" ] || { echo "no godot version in gradle/libs.versions.toml"; exit 1; }
base="https://github.com/godotengine/godot/releases/download/$version"
cache=${PEASHOOT_GODOT_CACHE:-$root/build/godot}
mkdir -p "$cache" "$out"

case $platform in
  Linux) template=linux_release.x86_64 name=peashoot-farm.x86_64 ;;
  Windows) template=windows_release_x86_64.exe name=peashoot-farm.exe ;;
  *) echo "export the farm for Linux or Windows, not $platform"; exit 1 ;;
esac

# Godot looks for export templates in one fixed place per OS, named for the version with a dot.
case $(uname -s) in
  Linux*)
    archive="Godot_v${version}_linux.x86_64.zip" editor="Godot_v${version}_linux.x86_64"
    templates="${XDG_DATA_HOME:-$HOME/.local/share}/godot/export_templates/${version/-/.}" ;;
  MINGW* | MSYS* | CYGWIN*)
    archive="Godot_v${version}_win64.exe.zip" editor="Godot_v${version}_win64_console.exe"
    templates="$APPDATA/Godot/export_templates/${version/-/.}" ;;
  *) echo "export the farm from Linux or Windows"; exit 1 ;;
esac

if [ ! -f "$cache/$editor" ]; then
  curl -fsSL -o "$cache/$archive" "$base/$archive"
  unzip -o -q "$cache/$archive" -d "$cache"
  chmod +x "$cache/$editor"
fi
if [ ! -f "$templates/$template" ]; then
  [ -f "$cache/templates.tpz" ] ||
    curl -fsSL -o "$cache/templates.tpz" "$base/Godot_v${version}_export_templates.tpz"
  mkdir -p "$templates"
  unzip -o -q -j "$cache/templates.tpz" "templates/$template" "templates/version.txt" -d "$templates"
fi

# Godot on Windows wants Windows paths; Git Bash hands out /c/... ones.
native() { if command -v cygpath >/dev/null; then cygpath -w "$1"; else echo "$1"; fi; }
project=$(native "$root/farm")
target=$(native "$(cd "$out" && pwd)/$name")

# Import first: an export reads the imported copies, and a fresh checkout has none.
"$cache/$editor" --headless --path "$project" --import
"$cache/$editor" --headless --path "$project" --export-release "$platform" "$target"
[ -s "$out/$name" ] || { echo "Godot wrote no $out/$name"; exit 1; }
echo "exported $out/$name"
