#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
javac -d "$out" app/src/main/java/com/limelight/diagnostics/*.java tests/diagnostics/*.java
java -cp "$out" DiagnosticsTest
java -cp "$out" FrameTimingTest
