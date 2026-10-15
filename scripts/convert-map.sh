#!/usr/bin/env bash
# Usage: bash scripts/convert-map.sh worlds.map
# Converts only the worlds listed in the map file (source<TAB>target). Output stays under
# after/<source>; scripts/place-map.sh puts it under the target name.
set -euo pipefail
cd "$(dirname "$0")/.."
map="${1:?Usage: bash scripts/convert-map.sh worlds.map}"
[[ -f "$map" ]] || { echo "Missing map file: $map" >&2; exit 1; }
sources=()
while IFS=$'\t' read -r source target extra; do
    [[ -z "${source// }" || "$source" == \#* ]] && continue
    [[ -n "${target:-}" && -z "${extra:-}" ]] || { echo "Bad line (need source<TAB>target): $source" >&2; exit 1; }
    [[ "$source" =~ ^[A-Za-z0-9_-]+$ && "$target" =~ ^[A-Za-z0-9_-]+$ ]] || { echo "Invalid world name: $source/$target" >&2; exit 1; }
    sources+=("$source")
done < "$map"
[[ ${#sources[@]} -gt 0 ]] || { echo "The map file lists no worlds; nothing to convert." >&2; exit 1; }
echo "Converting only: ${sources[*]}"
exec bash scripts/convert.sh "${sources[@]}"
