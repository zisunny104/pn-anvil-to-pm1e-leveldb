#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p reports after intermediate backups
[[ $# -gt 0 ]] || { echo "Usage: bash scripts/convert.sh World [World ...]" >&2; exit 1; }
for world in "$@"; do
    [[ "$world" =~ ^[A-Za-z0-9_-]+$ ]] || { echo "Invalid world name" >&2; exit 1; }
    [[ -d "before/$world/region" && -f "before/$world/level.dat" ]] || { echo "Missing source: $world" >&2; exit 1; }
done
exec 9>reports/conversion.lock
flock -n 9 || { echo '已有轉換工作執行中。'; exit 1; }

fingerprint=$(find tools scripts runtime/batch -type f \( -name '*.java' -o -name '*.sh' -o -name '*.py' -o -name '*.jar' \) -print0 | sort -z | xargs -0 sha256sum | sha256sum | awk '{print $1}')
if [[ -f reports/runtime-fingerprint && "$(cat reports/runtime-fingerprint)" != "$fingerprint" ]]; then
    echo 'Runtime or code changed. Use a fresh workspace; existing output was not modified.' >&2
    exit 1
fi
printf '%s\n' "$fingerprint" > reports/runtime-fingerprint
bash scripts/build.sh
java -Xmx192m -cp tools:runtime/batch/source.jar WorldInventory "$@" | tee reports/inventory.log
targetcp='tools:runtime/batch/target.jar:runtime/batch/libs/*'
mkdir -p reports/completed intermediate/batch
rm -f reports/conversion.ok

for world in "$@"; do
    mkdir -p "reports/completed/$world" "after/$world"
    while IFS= read -r region; do
        name=$(basename "$region")
        marker="reports/completed/$world/$name.sha256"
        signature=$(sha256sum "$region" | awk '{print $1}')
        if [ -f "$marker" ]; then
            [ "$(cat "$marker")" = "$signature" ] ||
                { echo "來源已變更：$region"; exit 1; }
            echo "RESUME_SKIP $world $name"
            continue
        fi

        echo "REGION_START $world $name $(date -Is)"
        sourcefile="intermediate/batch/source.gz"
        targetfile="intermediate/batch/target.gz"
        rm -f "$sourcefile" "$targetfile"

        nice -n 10 java -Xmx192m -cp tools:runtime/batch/source.jar \
            CompactExport "$region" "$sourcefile"
        nice -n 10 java -Xmx192m -cp "$targetcp" \
            CompactTarget "$sourcefile" "$targetfile"
        nice -n 10 java -Xmx192m -cp "$targetcp" \
            PackWorld "$targetfile" "after/$world" "before/$world/level.dat"

        python3 - "$sourcefile" "$world" "$name" <<'PY'
import gzip,json,sys
source,world,region=sys.argv[1:]
actual=set()
with gzip.open(source,'rt') as stream:
    for line in stream:
        row=json.loads(line)
        coord=(row['chunkX'],row['chunkZ'])
        if coord in actual:
            raise RuntimeError('重複區塊')
        actual.add(coord)
expected=set()
with open('reports/chunks.tsv') as stream:
    for line in stream:
        fields=line.rstrip('\n').split('\t')
        if len(fields)>=5 and fields[0]==world and \
                fields[1].split('/')[-1]==region:
            expected.add((int(fields[3]),int(fields[4])))
if actual != expected:
    raise RuntimeError(
        f'盤點不符 {world}/{region}: actual={len(actual)} expected={len(expected)}')
print(f'INVENTORY_MATCH {world} {region} Chunks={len(actual)}',flush=True)
PY

        printf '%s\n' "$signature" > "$marker.pending"
        mv "$marker.pending" "$marker"
        rm -f "$sourcefile" "$targetfile"
        echo "REGION_PASS $world $name $(date -Is)"
    done < <(find "before/$world/region" -maxdepth 1 -type f \
        -name 'r.*.*.mca' | sort)
done

paths=()
for world in "$@"; do paths+=("after/$world"); done
backup="backups/entity-rehome-$(date +%Y%m%d-%H%M%S)-$$"
java -Xmx192m -cp "$targetcp" FixEntityPositions "$backup" "${paths[@]}"
bash scripts/verify.sh "$@"
echo 'CONVERSION_PASS：資料庫寫入及讀回完成，正式核心載入驗證及部署尚未進行。'
date -Is > reports/conversion.ok
