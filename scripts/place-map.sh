#!/usr/bin/env bash
# Usage: bash scripts/place-map.sh worlds.map DEST_DIR
# Copies after/<source> to DEST_DIR/<target> for every line of the map file and sets LevelName.
# An existing DEST_DIR/<target> is renamed to <target>.old-<timestamp>, never deleted.
# The server must be stopped before DEST_DIR is the live worlds folder.
set -euo pipefail
cd "$(dirname "$0")/.."
map="${1:?map file}"; dest="${2:?destination}"
[[ -d "$dest" ]] || { echo "Missing destination: $dest" >&2; exit 1; }
stamp=$(date +%Y%m%d-%H%M%S)
while IFS=$'\t' read -r source target extra; do
    [[ -z "${source// }" || "$source" == \#* ]] && continue
    [[ -d "after/$source/db" ]] || { echo "Not converted: $source" >&2; exit 1; }
    [[ ! -e "$dest/$target.new-$stamp" ]] || exit 1
    cp -a "after/$source" "$dest/$target.new-$stamp"
    java -Xmx128m -cp "tools:runtime/batch/target.jar" SetLevelName "$dest/$target.new-$stamp/level.dat" "$target"
    [[ ! -e "$dest/$target" ]] || mv "$dest/$target" "$dest/$target.old-$stamp"
    mv "$dest/$target.new-$stamp" "$dest/$target"
    echo "PLACED $source -> $dest/$target"
done < "$map"
