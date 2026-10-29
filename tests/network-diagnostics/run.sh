#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
cc -std=c11 -Wall -Wextra -Werror -pthread -I app/src/main/jni/moonlight-core \
  app/src/main/jni/moonlight-core/network_diagnostics.c tests/network-diagnostics/test.c -lm -o "$out/test"
"$out/test"
