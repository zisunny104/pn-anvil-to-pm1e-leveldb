#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
[[ $# -gt 0 ]] || { echo 'Usage: bash scripts/verify.sh World [World ...]' >&2; exit 1; }
for world in "$@"; do
    [[ "$world" =~ ^[A-Za-z0-9_-]+$ ]] || { echo 'Invalid world name' >&2; exit 1; }
done
targetcp='tools:runtime/batch/target.jar:runtime/batch/libs/*'
java -Xmx192m -cp "$targetcp" AuditWorlds "$@"
paths=()
for world in "$@"; do paths+=("after/$world"); done
java -Xmx128m -cp "$targetcp" AuditEntityPositions "${paths[@]}"
echo 'Coordinate audit complete; this does not replace independent server-load testing.'
