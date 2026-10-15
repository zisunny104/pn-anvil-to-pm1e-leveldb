#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p reports after intermediate backups
for tool in java javac; do command -v "$tool" >/dev/null; done
for file in runtime/batch/source.jar runtime/batch/target.jar; do
    [[ -f "$file" ]] || { echo "Missing runtime: $file" >&2; exit 1; }
done
sourcecp='tools:runtime/batch/source.jar'
targetcp='tools:runtime/batch/target.jar:runtime/batch/libs/*'
javac -proc:none -cp "$sourcecp" tools/CompactExport.java \
    tools/WorldInventory.java tools/SourcePropertyTypes.java tools/SourcePaletteVersion.java
javac -proc:none -cp "$targetcp" tools/SetLevelName.java tools/OfflineNbt.java tools/LegacyBiomes.java \
    tools/RoundTripTarget.java tools/CompactTarget.java tools/SemanticPolicyTest.java tools/PackWorld.java \
    tools/AuditWorlds.java tools/AuditEntityPositions.java tools/FixEntityPositions.java
java -Xmx192m -cp "$sourcecp" SourcePropertyTypes
java -Xmx192m -cp "$sourcecp" SourcePaletteVersion
java -Xmx128m -cp "$targetcp" SemanticPolicyTest
echo BUILD_PASS
