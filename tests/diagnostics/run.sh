#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
javac -d "$out" app/src/main/java/com/limelight/diagnostics/*.java app/src/main/java/com/limelight/nvstream/input/HeldInputState.java tests/diagnostics/*.java
java -cp "$out" DiagnosticsTest
java -cp "$out" FrameTimingTest
java -cp "$out" HeldInputStateTest
